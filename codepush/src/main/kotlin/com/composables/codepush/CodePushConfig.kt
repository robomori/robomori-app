package com.composables.codepush

import java.io.File
import java.util.Properties

/**
 * What codepush needs to know about an app, read from a `codepush.properties` resource:
 * ```
 * appId=com.example.app
 * mainClass=com.example.MainKt
 * updatesUrl=https://downloads.example.com/code/
 * publicKey=<Base64 Ed25519 public key>
 * toolCommand=cli
 * ```
 * [base] is the installed version, from the packaged app (`jpackage.app-version`) unless the file sets `base`.
 * The `codepush.updatesUrl` system property overrides the URL, to try updates from a local server.
 * Updates are kept in a `codepush` folder inside the app's data folder, named after `dataFolder` (default: `appId`).
 * Launches whose first argument is [toolCommand] are a command-line tool's: they run the newest confirmed update and
 * leave the launch record alone, since they never report healthy. Without it, every launch tries new updates.
 */
data class CodePushConfig(
    val appId: String,
    val mainClass: String,
    val updatesUrl: String,
    val publicKey: String,
    val base: String,
    val dataDirectory: File,
    val toolCommand: String? = null,
) {
    /** Where an app's updates live on the server: `<updatesUrl>/<appId>/`. */
    val appUrl: String get() = updatesUrl.trimEnd('/') + "/" + appId + "/"

    companion object {
        const val RESOURCE = "codepush.properties"

        /** The config packaged with the app, or null when there is none or it isn't a packaged build. */
        fun load(loader: ClassLoader = CodePushConfig::class.java.classLoader): CodePushConfig? {
            val properties = properties(loader) ?: return null
            fun value(name: String) = properties.getProperty(name)?.trim()?.takeIf { it.isNotEmpty() }
            val appId = value("appId") ?: return null
            return CodePushConfig(
                appId = appId,
                mainClass = value("mainClass") ?: return null,
                updatesUrl = System.getProperty("codepush.updatesUrl") ?: value("updatesUrl") ?: return null,
                publicKey = value("publicKey") ?: return null,
                base = value("base") ?: System.getProperty("jpackage.app-version") ?: return null,
                dataDirectory = File(appDataDirectory(value("dataFolder") ?: appId), "codepush"),
                toolCommand = value("toolCommand"),
            )
        }

        /** The app's real main class, which runs even when there are no code updates, such as while developing. */
        fun mainClass(loader: ClassLoader = CodePushConfig::class.java.classLoader): String? =
            properties(loader)?.getProperty("mainClass")?.trim()?.takeIf { it.isNotEmpty() }

        private fun properties(loader: ClassLoader) = loader.getResourceAsStream(RESOURCE)?.use { Properties().apply { load(it) } }

        /** The per-user folder apps keep their data in on this computer. */
        fun appDataDirectory(folder: String): File {
            val os = System.getProperty("os.name").lowercase()
            val home = System.getProperty("user.home")
            return when {
                "mac" in os -> File(home, "Library/Application Support/$folder")
                "win" in os -> File(System.getenv("APPDATA") ?: "$home/AppData/Roaming", folder)
                else -> File(System.getenv("XDG_DATA_HOME") ?: "$home/.local/share", folder)
            }
        }
    }
}
