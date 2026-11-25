package com.composables.codepush

import java.io.File

/**
 * Lays out a signed code update in a folder, ready to upload as-is to the app's updates URL.
 *
 * ```
 * codepush keygen                       prints a new private key, then the public key for codepush.properties
 * codepush publish --out DIR --app ID --base VERSION --patch N MODULE=JAR...
 *                                       signs with the private key in CODEPUSH_PRIVATE_KEY
 * ```
 * The folder mirrors the server: `<app>/<base>/latest`, `<app>/<base>/<patch>/manifest(.sig)`, `<app>/files/<sha256>`.
 * Upload the jars and manifest before `latest`, which is what makes the update visible.
 */
object CodePushPublisher {
    @JvmStatic
    fun main(args: Array<String>) {
        when (args.firstOrNull()) {
            "keygen" -> Signing.newKeyPair().let { (privateKey, publicKey) -> println(privateKey); println(publicKey) }
            "publish" -> {
                val options = args.drop(1)
                fun option(name: String) = options.getOrNull(options.indexOf("--$name") + 1)?.takeIf { "--$name" in options }
                    ?: error("Missing --$name")
                val modules = options.filter { '=' in it && !it.startsWith("--") }.associate { it.substringBefore('=') to File(it.substringAfter('=')) }
                val privateKey = System.getenv("CODEPUSH_PRIVATE_KEY") ?: error("Set CODEPUSH_PRIVATE_KEY")
                val out = publish(File(option("out")), option("app"), option("base"), option("patch").toInt(), modules, privateKey)
                println("Code update ${option("base")}+${option("patch")} laid out in $out")
            }
            else -> {
                System.err.println("Usage: codepush keygen | codepush publish --out DIR --app ID --base VERSION --patch N MODULE=JAR...")
                kotlin.system.exitProcess(2)
            }
        }
    }

    fun publish(out: File, app: String, base: String, patch: Int, modules: Map<String, File>, privateKey: String,
                published: java.time.Instant = java.time.Instant.now()): File {
        require(modules.isNotEmpty()) { "Name at least one MODULE=JAR" }
        val appDirectory = File(out, app)
        val files = File(appDirectory, "files").apply { mkdirs() }
        val replacements = modules.map { (module, jar) ->
            val bytes = jar.readBytes()
            val sha256 = Signing.sha256(bytes)
            File(files, sha256).writeBytes(bytes)
            Manifest.Replacement(module, sha256, bytes.size.toLong())
        }
        val manifest = Manifest(app, base, patch, published.epochSecond, replacements).text()
        val patchDirectory = File(File(appDirectory, base), patch.toString()).apply { mkdirs() }
        File(patchDirectory, "manifest").writeText(manifest)
        File(patchDirectory, "manifest.sig").writeText(Signing.sign(manifest.toByteArray(), privateKey))
        File(File(appDirectory, base), "latest").writeText("$patch\n")
        return appDirectory
    }
}
