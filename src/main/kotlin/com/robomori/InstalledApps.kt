package com.robomori

import java.io.File

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import java.util.Base64

/**
 * An app installed on a device, such as a debug build run from Android Studio or Gradle. [icon] is its launcher icon as
 * a PNG, or null when the device couldn't draw it. A [debuggable] one opens its own files to `run-as` and Robomori's
 * agent; others are reached only through root, as the Linux user [uid] that owns their files. A [system] app came with
 * the device rather than being installed on it.
 */
internal class InstalledApp(val packageName: String, val name: String, val versionName: String?, val versionCode: String?,
                            val icon: ByteArray?, val debuggable: Boolean = true, val system: Boolean = false,
                            val uid: Int? = null) {
    override fun equals(other: Any?) = other is InstalledApp && packageName == other.packageName && name == other.name &&
        versionName == other.versionName && versionCode == other.versionCode && icon.contentEquals(other.icon) &&
        debuggable == other.debuggable && system == other.system && uid == other.uid
    override fun hashCode() = packageName.hashCode()
    override fun toString() = "InstalledApp($packageName, $name, $versionName, $versionCode, icon=${icon?.size}, " +
        "debuggable=$debuggable, system=$system, uid=$uid)"
}

internal class InstalledAppsService(
    settings: AppSettings,
    executor: CommandExecutor = ProcessExecutor(),
    private val helper: () -> DeviceHelperJar = { DeviceHelperJar.bundled },
) {
    private val android = AndroidService(settings, executor)

    /** The debuggable apps the user installed on [serial], by name. */
    fun debuggableApps(serial: String): List<InstalledApp> = listApps(serial, everyApp = false)

    /**
     * The apps whose own files Robomori can open on [serial]: with [everyApp], as on a device that allows root, every
     * app that has code, release builds and the system's own apps too, the user's first; otherwise the debuggable ones.
     */
    fun appsWithData(serial: String, everyApp: Boolean): List<InstalledApp> = listApps(serial, everyApp)

    private fun listApps(serial: String, everyApp: Boolean): List<InstalledApp> {
        require(serial.isNotBlank()) { "Select a running device." }
        val jar = helper()
        // Named by version, so the helper is pushed once per device and build rather than on every refresh.
        val remote = "/data/local/tmp/robomori-apps-${jar.version}.jar"
        val run = "[ -f $remote ] && CLASSPATH=$remote app_process / com.robomori.device.AppsHelper${if (everyApp) " all" else ""} || echo ROBOMORI_MISSING"
        var output = shell(serial, run)
        if (output.lineSequence().any { it.trim() == "ROBOMORI_MISSING" }) {
            // Earlier builds' helpers are of no further use, nor are the one-per-session mirror and language helpers that
            // builds before 1.10 named at random and could leave behind; today's are named by version.
            shell(serial, "rm -f /data/local/tmp/robomori-apps-*.jar /data/local/tmp/robomori-mirror-????????.jar " +
                "/data/local/tmp/robomori-locale-*-*-*-*-*.jar")
            android.command(android.toolchain.adb, listOf("-s", serial, "push", jar.file.absolutePath, remote))
            output = shell(serial, run)
        }
        check(!output.contains("ROBOMORI_ERROR\tDENIED")) { "This device does not allow listing its apps." }
        check(output.lineSequence().any { it.trim() == "ROBOMORI_DONE" }) {
            "Could not read the apps on this device. Try again."
        }
        return parseInstalledApps(output)
    }

    /** Installs [apk] on [serial], replacing any version installed and keeping its data. */
    fun install(serial: String, apk: File) {
        val output = try {
            // -t also takes test-only builds, as Android Studio's run does.
            android.command(android.toolchain.adb, listOf("-s", serial, "install", "-r", "-t", apk.absolutePath))
        } catch (failure: AndroidCommandException) {
            failure.output
        }
        if (output.lineSequence().none { it.trim() == "Success" }) throw ApkInstallException(installFailure(output))
    }

    /** Runs [action] on [packageName]; a launch with [agent] loads Robomori's network agent as the app starts. */
    fun run(serial: String, packageName: String, action: AppAction, agent: String? = null) {
        require(packageName.matches(Regex("[A-Za-z0-9_.]+"))) { "Choose a valid app." }
        if (action == AppAction.Launch && agent != null) return launchWithAgent(serial, packageName, agent)
        val output = shell(serial, when (action) {
            // Opens the app's launcher screen, as tapping its icon does.
            AppAction.Launch -> "monkey -p $packageName -c android.intent.category.LAUNCHER 1"
            AppAction.ForceStop -> "am force-stop $packageName"
            // run-as reaches the app's own files, which every debuggable app allows; the app recreates these folders.
            AppAction.ClearCache -> "run-as $packageName rm -rf cache code_cache"
            AppAction.ClearData -> "pm clear $packageName"
            AppAction.Uninstall -> "pm uninstall $packageName"
        })
        when (action) {
            AppAction.Launch -> check("Events injected: 1" in output) { "This app has no screen to launch." }
            AppAction.ClearData, AppAction.Uninstall -> check(output.lineSequence().any { it.trim() == "Success" }) {
                output.lineSequence().map(String::trim).lastOrNull(String::isNotEmpty) ?: "The device did not respond. Try again."
            }
            AppAction.ForceStop, AppAction.ClearCache -> check(output.isBlank()) { output.trim() }
        }
    }

    private fun launchWithAgent(serial: String, packageName: String, agent: String) {
        val activity = shell(serial, "cmd package resolve-activity --brief -a android.intent.action.MAIN " +
            "-c android.intent.category.LAUNCHER $packageName").lineSequence().map(String::trim).lastOrNull { "/" in it }
        checkNotNull(activity) { "This app has no screen to launch." }
        // Only a process that starts now takes the agent; one already running got it from the Network page.
        val output = shell(serial, "am start --attach-agent '$agent' -n '$activity'")
        check(!output.contains("Error")) { output.lineSequence().map(String::trim).lastOrNull(String::isNotEmpty).orEmpty() }
    }

    private fun shell(serial: String, script: String) =
        android.command(android.toolchain.adb, listOf("-s", serial, "shell", script))
}

/**
 * Reads the helper's `ROBOMORI_APP` lines: package, name, version name, version code, base64 PNG icon, user id, and
 * whether it's debuggable and a system app. The user's apps come first, then the system's, each by name.
 */
internal fun parseInstalledApps(output: String): List<InstalledApp> = output.lineSequence()
    .filter { it.startsWith("ROBOMORI_APP\t") }
    .mapNotNull { line ->
        val fields = line.trimEnd('\r').split('\t')
        val packageName = fields.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        InstalledApp(
            packageName = packageName,
            name = fields.getOrNull(2)?.takeIf { it.isNotBlank() } ?: packageName,
            versionName = fields.getOrNull(3)?.takeIf { it.isNotBlank() },
            versionCode = fields.getOrNull(4)?.takeIf { it.isNotBlank() },
            icon = fields.getOrNull(5)?.takeIf { it.isNotBlank() }?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() },
            uid = fields.getOrNull(6)?.trim()?.toIntOrNull(),
            // A helper from before these fields listed only debuggable apps the user installed.
            debuggable = fields.getOrNull(7)?.trim() != "0",
            system = fields.getOrNull(8)?.trim() == "1",
        )
    }
    .sortedWith(compareBy<InstalledApp> { it.system }.thenBy(String.CASE_INSENSITIVE_ORDER, InstalledApp::name).thenBy(InstalledApp::packageName))
    .toList()

internal sealed interface InstalledAppsSnapshot {
    data object Loading : InstalledAppsSnapshot
    data class Loaded(val apps: List<InstalledApp>) : InstalledAppsSnapshot
    data class Failed(val cause: Throwable) : InstalledAppsSnapshot
}

internal enum class AppAction(val needsConfirmation: Boolean) {
    Launch(false), ForceStop(false), ClearCache(false), ClearData(true), Uninstall(true)
}

/** An action and the apps it applies to. */
internal data class AppActionRequest(val action: AppAction, val apps: List<InstalledApp>)

internal sealed interface AppActionState {
    data object Idle : AppActionState
    data class Running(val request: AppActionRequest) : AppActionState
    data class Done(val request: AppActionRequest) : AppActionState
    /** Stopped at [app], so the apps after it were left as they were. */
    data class Failed(val request: AppActionRequest, val app: InstalledApp, val cause: Throwable) : AppActionState
}

/** The debuggable apps on one device, kept up to date while they are on screen, and the ones selected in the list. */
internal class InstalledAppsState(
    private val service: InstalledAppsService,
    val serial: String,
    private val scope: CoroutineScope,
    /** What loads Robomori's network agent into an app launched now, when the Network page needs it there. */
    private val networkAgent: (packageName: String) -> String? = { null },
) {
    var snapshot by mutableStateOf<InstalledAppsSnapshot>(InstalledAppsSnapshot.Loading); private set
    /** The selected apps' packages. */
    var selection by mutableStateOf<Set<String>>(emptySet()); private set
    private var anchor: String? = null
    /** The action waiting for the user to confirm it. */
    var confirming by mutableStateOf<AppActionRequest?>(null); private set
    var action by mutableStateOf<AppActionState>(AppActionState.Idle); private set

    private val apps get() = (snapshot as? InstalledAppsSnapshot.Loaded)?.apps.orEmpty()

    /** Selects [app] alone, or adds or removes it with [toggle], or the apps from the last one clicked with [extend]. */
    fun select(app: InstalledApp, toggle: Boolean = false, extend: Boolean = false) {
        val order = apps.map { it.packageName }
        val from = order.indexOf(anchor)
        val to = order.indexOf(app.packageName)
        selection = when {
            extend && from >= 0 && to >= 0 -> {
                val range = order.subList(minOf(from, to), maxOf(from, to) + 1).toSet()
                if (toggle) selection + range else range
            }
            toggle -> if (app.packageName in selection) selection - app.packageName else selection + app.packageName
            else -> setOf(app.packageName)
        }
        if (!extend || from < 0) anchor = app.packageName
    }

    /** The apps a context menu opened on [app] acts on: the selection when [app] is part of it, otherwise [app] alone. */
    fun contextTargets(app: InstalledApp): List<InstalledApp> {
        if (app.packageName !in selection) select(app)
        return apps.filter { it.packageName in selection }
    }

    /** Runs [action] on [targets], or first asks to confirm one that loses the apps' data. */
    fun request(action: AppAction, targets: List<InstalledApp>) {
        if (targets.isEmpty() || this.action is AppActionState.Running) return
        val request = AppActionRequest(action, targets)
        if (action.needsConfirmation) confirming = request else run(request)
    }

    fun confirm() {
        val pending = confirming ?: return
        confirming = null
        run(pending)
    }

    fun dismissConfirmation() { confirming = null }

    fun dismissFailure() {
        if (action is AppActionState.Failed) action = AppActionState.Idle
    }

    private fun run(request: AppActionRequest) {
        if (action is AppActionState.Running) return
        action = AppActionState.Running(request)
        scope.launch {
            var outcome: AppActionState = AppActionState.Done(request)
            for (app in request.apps) {
                try {
                    runInterruptible(Dispatchers.IO) {
                        val agent = if (request.action == AppAction.Launch) runCatching { networkAgent(app.packageName) }.getOrNull() else null
                        service.run(serial, app.packageName, request.action, agent)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    outcome = AppActionState.Failed(request, app, failure)
                    break
                }
            }
            action = outcome
            if (request.action == AppAction.Uninstall) refresh()
        }
    }

    /** Reads the apps now, then again every few seconds while [visible], so newly installed apps show up. */
    suspend fun observe(visible: Boolean) {
        refresh()
        while (visible && currentCoroutineContext().isActive) {
            delay(5000)
            refresh()
        }
    }

    suspend fun refresh() {
        snapshot = try {
            InstalledAppsSnapshot.Loaded(runInterruptible(Dispatchers.IO) { service.debuggableApps(serial) })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // Keep showing the apps already read; a failed refresh only matters when there is nothing to show.
            snapshot as? InstalledAppsSnapshot.Loaded ?: InstalledAppsSnapshot.Failed(failure)
        }
        // Apps that are gone can't stay selected.
        val present = apps.mapTo(mutableSetOf()) { it.packageName }
        if (!present.containsAll(selection)) selection = selection intersect present
    }
}
