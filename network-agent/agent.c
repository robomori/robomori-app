// Robomori's agent for debuggable apps, loaded into the app with `am start --attach-agent` or `cmd activity
// attach-agent`. JVMTI hands out field and method IDs without Android's hidden API checks, so it needs neither root nor
// a device setting. Loading it again runs it again, so each load does one job, chosen by what it's given:
//
// - A folder in the app's own storage, holding the system's certificates plus Robomori's: makes the app trust Robomori's
//   certificate, so the Network page reads its HTTPS with no change to the app. The agent points the app's
//   SystemCertificateSource at that folder. Loading it again only points the source at the folder again.
// - `call:<jar>:<request>`: loads the device helper's AppCall from the jar into the app and hands it the request, so a
//   change made in Robomori reaches what the app holds in memory. AppCall finds objects by walking the app's heap,
//   which the agent does for it.
//
// Built by build.sh, which writes the libraries Robomori bundles.
#include <jvmti.h>
#include <android/log.h>
#include <stdlib.h>
#include <string.h>

#define TAG "Robomori"
#define LOG(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

// ART's JVMTI version for apps that aren't debuggable; debuggable ones also accept the standard version.
#define ART_TI_VERSION 0x70010200

static void release(jvmtiEnv *ti, char *a, char *b) {
    (*ti)->Deallocate(ti, (unsigned char *) a);
    (*ti)->Deallocate(ti, (unsigned char *) b);
}

static jmethodID find_method(jvmtiEnv *ti, jclass type, const char *name, const char *signature) {
    jint count; jmethodID *methods; jmethodID found = NULL;
    if ((*ti)->GetClassMethods(ti, type, &count, &methods) != JVMTI_ERROR_NONE) return NULL;
    for (jint i = 0; i < count && !found; i++) {
        char *n, *s;
        if ((*ti)->GetMethodName(ti, methods[i], &n, &s, NULL) != JVMTI_ERROR_NONE) continue;
        if (!strcmp(n, name) && !strcmp(s, signature)) found = methods[i];
        release(ti, n, s);
    }
    (*ti)->Deallocate(ti, (unsigned char *) methods);
    return found;
}

static jfieldID find_field(jvmtiEnv *ti, jclass type, const char *name, const char *signature) {
    jint count; jfieldID *fields; jfieldID found = NULL;
    if ((*ti)->GetClassFields(ti, type, &count, &fields) != JVMTI_ERROR_NONE) return NULL;
    for (jint i = 0; i < count && !found; i++) {
        char *n, *s;
        if ((*ti)->GetFieldName(ti, type, fields[i], &n, &s, NULL) != JVMTI_ERROR_NONE) continue;
        if (!strcmp(n, name) && !strcmp(s, signature)) found = fields[i];
        release(ti, n, s);
    }
    (*ti)->Deallocate(ti, (unsigned char *) fields);
    return found;
}

static const char *failed(JNIEnv *env, const char *reason) {
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    return reason;
}

/** Points the app's system certificates at [folder]; returns why it couldn't, or NULL. */
static const char *trust(JavaVM *vm, const char *folder) {
    jvmtiEnv *ti; JNIEnv *env;
    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) return "no JNI";
    if ((*vm)->GetEnv(vm, (void **) &ti, JVMTI_VERSION_1_2) != JNI_OK &&
        (*vm)->GetEnv(vm, (void **) &ti, ART_TI_VERSION) != JNI_OK) return "no JVMTI";
    jclass source = (*env)->FindClass(env, "android/security/net/config/SystemCertificateSource");
    if (!source) return failed(env, "no SystemCertificateSource");
    jmethodID getInstance = find_method(ti, source, "getInstance", "()Landroid/security/net/config/SystemCertificateSource;");
    if (!getInstance) return "no SystemCertificateSource.getInstance";
    jobject instance = (*env)->CallStaticObjectMethod(env, source, getInstance);
    if ((*env)->ExceptionCheck(env) || !instance) return failed(env, "SystemCertificateSource.getInstance failed");
    jclass directory = (*env)->GetSuperclass(env, source);
    jfieldID dir = find_field(ti, directory, "mDir", "Ljava/io/File;");
    if (!dir) return "no DirectoryCertificateSource.mDir";
    jclass file = (*env)->FindClass(env, "java/io/File");
    jmethodID init = (*env)->GetMethodID(env, file, "<init>", "(Ljava/lang/String;)V");
    jstring path = (*env)->NewStringUTF(env, folder);
    if (!file || !init || !path) return failed(env, "no java.io.File");
    jobject replacement = (*env)->NewObject(env, file, init, path);
    if (!replacement) return failed(env, "new File failed");
    (*env)->SetObjectField(env, instance, dir, replacement);
    // The certificates read so far, which would otherwise still come from the system's folder.
    jfieldID cache = find_field(ti, directory, "mCertificates", "Ljava/util/Set;");
    if (cache) (*env)->SetObjectField(env, instance, cache, NULL);
    if ((*env)->ExceptionCheck(env)) return failed(env, "setting the folder failed");
    return NULL;
}

// The JVMTI environment of the call under way, for the heap walk AppCall asks for while it runs.
static jvmtiEnv *calling;

#define CLASS_TAG 0x524f424f
#define FOUND_TAG 0x524f424e

static jint JNICALL tag_found(jlong class_tag, jlong size, jlong *tag, jint length, void *data) {
    if (class_tag == CLASS_TAG) *tag = FOUND_TAG;
    return JVMTI_VISIT_OBJECTS;
}

/** Tags with [tag] each loaded class that is [type] or extends it. */
static void tag_classes(JNIEnv *env, jclass type, jlong tag) {
    jint count = 0; jclass *classes = NULL;
    if ((*calling)->GetLoadedClasses(calling, &count, &classes) != JVMTI_ERROR_NONE) return;
    for (jint i = 0; i < count; i++) {
        if ((*env)->IsAssignableFrom(env, classes[i], type)) (*calling)->SetTag(calling, classes[i], tag);
        (*env)->DeleteLocalRef(env, classes[i]);
    }
    (*calling)->Deallocate(calling, (unsigned char *) classes);
}

/**
 * AppCall.instances: every live object that is a [type], subclasses included. The heap walk can only pick objects of
 * one exact class, so it picks them by their class's tag instead, given to [type] and every class extending it.
 */
static jobjectArray JNICALL instances(JNIEnv *env, jclass caller, jclass type) {
    jclass object = (*env)->FindClass(env, "java/lang/Object");
    if (!calling) return (*env)->NewObjectArray(env, 0, object, NULL);
    jvmtiHeapCallbacks callbacks;
    memset(&callbacks, 0, sizeof(callbacks));
    callbacks.heap_iteration_callback = tag_found;
    jlong tag = FOUND_TAG; jint count = 0; jobject *found = NULL;
    tag_classes(env, type, CLASS_TAG);
    jvmtiError walked = (*calling)->IterateThroughHeap(calling, 0, NULL, &callbacks, NULL);
    tag_classes(env, type, 0);
    if (walked != JVMTI_ERROR_NONE ||
        (*calling)->GetObjectsWithTags(calling, 1, &tag, &count, &found, NULL) != JVMTI_ERROR_NONE) {
        return (*env)->NewObjectArray(env, 0, object, NULL);
    }
    jobjectArray result = (*env)->NewObjectArray(env, count, object, NULL);
    for (jint i = 0; i < count; i++) {
        (*calling)->SetTag(calling, found[i], 0);
        if (result) (*env)->SetObjectArrayElement(env, result, i, found[i]);
        (*env)->DeleteLocalRef(env, found[i]);
    }
    (*calling)->Deallocate(calling, (unsigned char *) found);
    return result;
}

static jmethodID method(JNIEnv *env, jclass type, const char *name, const char *signature) {
    return type ? (*env)->GetMethodID(env, type, name, signature) : NULL;
}

/** Loads AppCall from [jar] into the app and runs [request] with it, in [ti]; returns why it couldn't, or NULL. */
static const char *run_call(JNIEnv *env, jvmtiEnv *ti, const char *jar, const char *request) {
    jclass thread = (*env)->FindClass(env, "android/app/ActivityThread");
    if (!thread) return failed(env, "no ActivityThread");
    jmethodID current = find_method(ti, thread, "currentApplication", "()Landroid/app/Application;");
    jobject application = current ? (*env)->CallStaticObjectMethod(env, thread, current) : NULL;
    if ((*env)->ExceptionCheck(env) || !application) return failed(env, "no application");
    jmethodID getClassLoader = method(env, (*env)->FindClass(env, "android/content/Context"), "getClassLoader", "()Ljava/lang/ClassLoader;");
    jclass paths = (*env)->FindClass(env, "dalvik/system/PathClassLoader");
    jmethodID newPaths = method(env, paths, "<init>", "(Ljava/lang/String;Ljava/lang/ClassLoader;)V");
    jmethodID loadClass = method(env, (*env)->FindClass(env, "java/lang/ClassLoader"), "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    if (!getClassLoader || !newPaths || !loadClass) return failed(env, "no class loaders");
    // AppCall's loader sits under the app's, as the app's own code does.
    jobject parent = (*env)->CallObjectMethod(env, application, getClassLoader);
    jobject loader = (*env)->NewObject(env, paths, newPaths, (*env)->NewStringUTF(env, jar), parent);
    jclass type = loader ? (jclass) (*env)->CallObjectMethod(env, loader, loadClass, (*env)->NewStringUTF(env, "com.robomori.device.AppCall")) : NULL;
    if ((*env)->ExceptionCheck(env) || !type) return failed(env, "no AppCall");
    JNINativeMethod natives[] = {{"instances", "(Ljava/lang/Class;)[Ljava/lang/Object;", (void *) instances}};
    if ((*env)->RegisterNatives(env, type, natives, 1) != JNI_OK) return failed(env, "no AppCall.instances");
    jmethodID run = (*env)->GetStaticMethodID(env, type, "run", "(Landroid/content/Context;Ljava/lang/String;)V");
    if (!run) return failed(env, "no AppCall.run");
    calling = ti;
    (*env)->CallStaticVoidMethod(env, type, run, application, (*env)->NewStringUTF(env, request));
    calling = NULL;
    if ((*env)->ExceptionCheck(env)) return failed(env, "AppCall.run failed");
    return NULL;
}

/** Runs `call:<jar>:<request>` in an environment of its own; returns why it couldn't, or NULL. */
static const char *call(JavaVM *vm, const char *options) {
    jvmtiEnv *ti; JNIEnv *env;
    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) return "no JNI";
    if ((*vm)->GetEnv(vm, (void **) &ti, JVMTI_VERSION_1_2) != JNI_OK &&
        (*vm)->GetEnv(vm, (void **) &ti, ART_TI_VERSION) != JNI_OK) return "no JVMTI";
    jvmtiCapabilities capabilities;
    memset(&capabilities, 0, sizeof(capabilities));
    capabilities.can_tag_objects = 1;
    // Without it AppCall finds no objects on the heap, and changes only what it reaches otherwise.
    (*ti)->AddCapabilities(ti, &capabilities);
    const char *jar = options + strlen("call:");
    const char *end = strchr(jar, ':');
    char *path = end ? strndup(jar, end - jar) : NULL;
    // Its own frame, so the references it makes go once it's done rather than staying for the app's lifetime.
    (*env)->PushLocalFrame(env, 64);
    const char *problem = path ? run_call(env, ti, path, end + 1) : "no request";
    (*env)->PopLocalFrame(env, NULL);
    free(path);
    (*ti)->DisposeEnvironment(ti);
    return problem;
}

JNIEXPORT jint JNICALL Agent_OnAttach(JavaVM *vm, char *options, void *reserved) {
    const char *given = options ? options : "";
    if (!strncmp(given, "call:", strlen("call:"))) {
        const char *problem = call(vm, given);
        if (problem) LOG("Couldn't reach the app's data: %s", problem);
        return JNI_OK;
    }
    const char *problem = trust(vm, given);
    if (problem) LOG("Couldn't trust Robomori's certificate: %s", problem);
    else LOG("Trusting Robomori's certificate from %s", given);
    return JNI_OK;  // Never stops the app, even when what it was loaded for didn't work.
}

JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM *vm, char *options, void *reserved) {
    return Agent_OnAttach(vm, options, reserved);
}
