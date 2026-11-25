package com.composables.codepush

import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.util.Collections
import java.util.Enumeration

/**
 * The packaged app's entry point. Runs the app's main class from the newest healthy code update for the
 * installed version, or from the installed code when there is none or anything about the update is off.
 *
 * Only the update's own jars load in a separate class loader, which looks in them first. Libraries and the runtime
 * keep loading from the installed app, so they keep using its startup (AOT) cache: an update costs tens of
 * milliseconds at launch instead of hundreds. The running update's number is the `codepush.patch` system property.
 */
object CodePushLauncher {
    const val PATCH_PROPERTY = "codepush.patch"

    @JvmStatic
    fun main(args: Array<String>) {
        val config = CodePushConfig.load()
        if (config == null) {
            // Not a packaged build, such as a run from the IDE: there are no code updates to pick from.
            run(Class.forName(CodePushConfig.mainClass() ?: error("Missing mainClass in ${CodePushConfig.RESOURCE}")), args)
            return
        }
        val update = runCatching { pick(config, tool = config.toolCommand != null && args.firstOrNull() == config.toolCommand) }
            .onFailure { System.err.println("codepush: running the installed code, the update could not be used: $it") }
            .getOrNull()
        if (update == null) {
            run(Class.forName(config.mainClass), args)
            return
        }
        System.setProperty(PATCH_PROPERTY, update.manifest.patch.toString())
        val loader = UpdateClassLoader(update.manifest.patch, update.jars, CodePushLauncher::class.java.classLoader)
        Thread.currentThread().contextClassLoader = loader
        run(Class.forName(config.mainClass, true, loader), args)
    }

    internal class Update(val manifest: Manifest, val jars: List<File>)

    /** A [tool] launch runs the newest confirmed update instead of trying a new one. */
    internal fun pick(config: CodePushConfig, installedClasspath: List<File> = installedClasspath(), tool: Boolean = false): Update? {
        val store = PatchStore(config.dataDirectory)
        val manifest = (if (tool) store.confirmed(config.appId, config.base, config.publicKey)
            else store.beginLaunch(config.appId, config.base, config.publicKey)) ?: return null
        val missing = manifest.replacements.map { it.module }.filter { module -> installedClasspath.none { isModuleJar(it.name, module) } }
        check(missing.isEmpty()) { "The installed app has no jar for $missing" }
        return Update(manifest, manifest.replacements.map { store.file(it.sha256) })
    }

    /** Whether [fileName] is the packaged jar of [module], named like `module-1.0.0-3a64.jar` or `module.jar`. */
    internal fun isModuleJar(fileName: String, module: String) =
        fileName == "$module.jar" || (fileName.startsWith("$module-") && fileName.endsWith(".jar") &&
            fileName.removePrefix("$module-").firstOrNull()?.isDigit() == true)

    private fun installedClasspath(): List<File> =
        System.getProperty("java.class.path").split(File.pathSeparatorChar).filter { it.isNotBlank() }.map(::File)

    private fun run(mainClass: Class<*>, args: Array<String>) {
        try {
            mainClass.getMethod("main", Array<String>::class.java).invoke(null, args)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException
        }
    }
}

/**
 * Loads classes and resources from an update's jars before the installed app's, so the update's copies of the app's
 * own modules win, and falls back to the installed app for everything else.
 */
internal class UpdateClassLoader(patch: Int, jars: List<File>, installed: ClassLoader) :
    URLClassLoader("codepush-$patch", jars.map { it.toURI().toURL() }.toTypedArray(), installed) {

    override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(getClassLoadingLock(name)) {
        val loaded = findLoadedClass(name) ?: fromUpdate(name) ?: return super.loadClass(name, resolve)
        if (resolve) resolveClass(loaded)
        loaded
    }

    private fun fromUpdate(name: String): Class<*>? =
        if (name.startsWith("java.")) null else try { findClass(name) } catch (_: ClassNotFoundException) { null }

    override fun getResource(name: String): URL? = findResource(name) ?: super.getResource(name)

    override fun getResources(name: String): Enumeration<URL> =
        Collections.enumeration(Collections.list(findResources(name)) + Collections.list(parent.getResources(name)))
}
