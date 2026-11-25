/* The helper's calls into hev-socks5-tunnel, for com.robomori.helper.Tunnel. */
#include <jni.h>
#include <string.h>

#include "hev-main.h"

JNIEXPORT jint JNICALL
Java_com_robomori_helper_Tunnel_run (JNIEnv *env, jclass type, jstring config, jint tun_fd)
{
    const char *text = (*env)->GetStringUTFChars (env, config, NULL);
    if (!text)
        return -1;
    int result = hev_socks5_tunnel_main_from_str ((const unsigned char *)text, strlen (text), tun_fd);
    (*env)->ReleaseStringUTFChars (env, config, text);
    return result;
}

JNIEXPORT void JNICALL
Java_com_robomori_helper_Tunnel_quit (JNIEnv *env, jclass type)
{
    hev_socks5_tunnel_quit ();
}
