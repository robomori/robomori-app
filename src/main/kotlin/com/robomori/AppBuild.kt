package com.robomori

import com.composables.codepush.CodePushClient
import com.composables.codepush.CodePushConfig
import java.util.Properties

const val APP_NAME = "Robomori"
const val FEEDBACK_EMAIL = "support@robomori.com"
val APP_VERSION: String by lazy {
    val properties = Properties().apply {
        checkNotNull(object {}.javaClass.getResourceAsStream("/robomori-build.properties")) {
            "Missing app version."
        }.use(::load)
    }
    checkNotNull(properties.getProperty("version")) { "Missing app version." }
}

/** Code updates for the packaged app: none while developing or testing. */
internal val codePush: CodePushClient? by lazy { CodePushConfig.load()?.let(::CodePushClient) }

/** The version people see: the installed version, and the code update it runs, as in 1.4.0+3. */
val APP_DISPLAY_VERSION: String get() = APP_VERSION + (codePush?.runningPatch?.let { "+$it" } ?: "")

/**
 * Whether a code update published at [published] is covered by the license, by the same rule as full updates:
 * it came out on or before the license's last day of updates. Without a license every update is allowed,
 * as during the unrestricted launch phase.
 */
internal fun codeUpdateAllowed(published: java.time.Instant, updatesUntil: java.time.Instant?): Boolean =
    updatesUntil == null || !published.isAfter(updatesUntil)

/**
 * Set when running emulators should outlive the app: before quitting to relaunch, after a crash, and in development runs,
 * so they keep running through the restart.
 */
internal val keepEmulatorsOnExit = java.util.concurrent.atomic.AtomicBoolean(false)

/**
 * The command that waits for process [pid] to end, then opens the installed app again: the .app [bundle] on macOS,
 * the [appImage] on Linux, the launcher [executable] on Windows. Null when this isn't an installed app.
 */
internal fun relaunchCommand(os: HostOs, pid: Long, bundle: java.io.File?, appImage: String?, executable: String?): List<String>? {
    fun quoted(path: String) = "'" + path.replace("'", "'\\''") + "'"
    val waitThen = "while kill -0 $pid 2>/dev/null; do sleep 0.2; done; "
    return when (os) {
        HostOs.MAC -> bundle?.let { listOf("/bin/sh", "-c", waitThen + "open -n " + quoted(it.path)) }
        HostOs.LINUX -> appImage?.takeIf { it.isNotBlank() }?.let { listOf("/bin/sh", "-c", waitThen + "exec " + quoted(it)) }
        HostOs.WINDOWS -> executable?.takeIf { it.endsWith(".exe", true) }?.let {
            listOf("powershell", "-NoProfile", "-Command", "Wait-Process -Id $pid -ErrorAction SilentlyContinue; Start-Process '${it.replace("'", "''")}'")
        }
    }
}

/** Quits and reopens Robomori, leaving running emulators alone, so a downloaded code update takes effect. */
internal fun restartToUpdate() {
    val command = relaunchCommand(HostOs.detect(), ProcessHandle.current().pid(), appBundle(), System.getenv("APPIMAGE"),
        System.getProperty("jpackage.app-path")) ?: return
    ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    keepEmulatorsOnExit.set(true)
    kotlin.system.exitProcess(0)
}
