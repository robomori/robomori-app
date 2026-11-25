package com.robomori

import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Where a change to an app's data took effect. */
internal enum class LiveEdit {
    /** Inside the running app too, which sees it at once. */
    Applied,
    /** In the app's files only, as the app isn't running: it reads them when it starts. */
    NotRunning,
    /** In the app's files only, while the app runs on with what it read before, until it restarts. */
    NeedsRestart,
}

/** One change to a set of preferences: a key set to a value of its type, or removed. */
internal sealed interface PreferenceEdit {
    data class Put(val preference: Preference) : PreferenceEdit
    data class Remove(val key: String) : PreferenceEdit
}

/** The edits that turn the preferences [before] into [after]. */
internal fun preferenceEdits(before: List<Preference>, after: List<Preference>): List<PreferenceEdit> {
    val kept = after.mapTo(mutableSetOf()) { it.key }
    return before.filter { it.key !in kept }.map { PreferenceEdit.Remove(it.key) } +
        after.filter { it !in before }.map { PreferenceEdit.Put(it) }
}

/**
 * Reaches into an app while it runs, so a change made to its data from Robomori shows in the app at once. An app keeps
 * its preferences in memory and Room watches only its own writes, so a change to their files alone goes unseen.
 */
internal interface LiveAppData {
    /**
     * Applies [edits] to the preferences [file] inside [app] when it runs, and says where they took effect. Unless it's
     * [LiveEdit.Applied], the caller writes the file.
     */
    fun editPreferences(serial: String, app: InstalledApp, file: PreferenceFile, edits: List<PreferenceEdit>): LiveEdit

    /**
     * Tells [app]'s Room databases, when it runs, that [tables] changed, so what it shows from them updates, and says
     * where the change took effect.
     */
    fun tablesChanged(serial: String, app: InstalledApp, tables: List<String>): LiveEdit
}

/**
 * [LiveAppData] through Robomori's agent: it loads into the app's process with `attach-agent`, as for the Network page,
 * and runs the device helper's AppCall there, which writes its outcome to a file in the app's code cache. Both go into
 * the app's own folder once per build, as only files the app owns load into it.
 */
internal class AgentLiveAppData(
    private val transport: DeviceFileTransport,
    private val helper: () -> DeviceHelperJar = { DeviceHelperJar.bundled },
    private val agent: (abi: String) -> ByteArray = NetworkAgent::library,
    /** How long to wait for the app to answer; its main thread runs the call, so a busy app answers late. */
    private val patience: Duration = 3.seconds,
) : LiveAppData {
    private val files = DeviceFilesService(transport, helper)
    // Each app's ABI, which doesn't change while it stays installed.
    private val abis = ConcurrentHashMap<String, String>()

    override fun editPreferences(serial: String, app: InstalledApp, file: PreferenceFile, edits: List<PreferenceEdit>): LiveEdit {
        if (!app.debuggable) return outOfReach(serial, app)
        val running = prepare(serial, app) ?: return LiveEdit.NotRunning
        // DataStore keeps its data behind its own coroutines and cache, which nothing outside it can safely change.
        if (file.kind != PreferenceKind.SharedPreferences) return LiveEdit.NeedsRestart
        val fields = edits.flatMap { edit ->
            when (edit) {
                is PreferenceEdit.Remove -> listOf("remove", edit.key)
                is PreferenceEdit.Put -> edit.preference.let {
                    listOf("set", it.key, if (it.value == null) "Null" else it.type.name, it.value.orEmpty())
                }
            }
        }
        call(serial, app, running, listOf("prefs", file.name) + fields)
        return LiveEdit.Applied
    }

    override fun tablesChanged(serial: String, app: InstalledApp, tables: List<String>): LiveEdit {
        // Nothing the app shows could have changed.
        if (tables.isEmpty()) return LiveEdit.Applied
        if (!app.debuggable) return outOfReach(serial, app)
        val running = prepare(serial, app) ?: return LiveEdit.NotRunning
        call(serial, app, running, listOf("room") + tables)
        return LiveEdit.Applied
    }

    /**
     * Where a change to a release build's files took effect: Android loads agents only into debuggable apps, so one that
     * runs keeps what it read before until it restarts.
     */
    private fun outOfReach(serial: String, app: InstalledApp): LiveEdit {
        require(app.packageName.matches(Regex("[A-Za-z0-9_.]+"))) { "Choose a valid app." }
        val running = transport.shell(serial, "pidof ${app.packageName} || true").trim().split(' ').any { it.toIntOrNull() != null }
        return if (running) LiveEdit.NeedsRestart else LiveEdit.NotRunning
    }

    /** The agent and helper for [app] in its folder, while it runs. */
    private class Running(val pid: Int, val agent: String, val jar: String)

    /** Finds [app]'s process, and puts the agent and helper in its folder unless they're there; null when it isn't running. */
    private fun prepare(serial: String, app: InstalledApp): Running? {
        val packageName = app.packageName
        require(packageName.matches(Regex("[A-Za-z0-9_.]+"))) { "Choose a valid app." }
        val abi = abis.getOrPut("$serial/$packageName") {
            parsePrimaryAbi(transport.shell(serial, "dumpsys package $packageName | grep primaryCpuAbi="))
                ?: transport.shell(serial, "getprop ro.product.cpu.abi").trim()
        }
        check(abi in NetworkAgent.abis) { "Robomori can't reach apps on $abi." }
        val library = agent(abi)
        val jar = helper()
        val agentPath = "code_cache/robomori-agent-${fingerprint(library)}.so"
        // The app's process is named after its package; one in a process of its own, such as `:remote`, isn't reached.
        val output = files.shellAsApp(serial, app,
            "pidof $packageName || true; [ -f $agentPath ] && [ -f ${jar.inApp} ] && echo ROBOMORI_READY; true")
        val pid = output.lineSequence().firstNotNullOfOrNull { line -> line.trim().split(' ').firstOrNull()?.toIntOrNull() }
            ?: return null
        if (output.lineSequence().none { it.trim() == "ROBOMORI_READY" }) {
            files.shellAsApp(serial, app, "mkdir -p code_cache && rm -f code_cache/robomori-agent-*.so")
            val local = Files.createTempFile("robomori-agent", ".so").toFile()
            try {
                local.writeBytes(library)
                files.installAsApp(serial, app, local, agentPath)
            } finally { local.delete() }
            files.installAsApp(serial, app, jar.file, jar.inApp)
        }
        val home = "/data/user/0/$packageName"
        return Running(pid, "$home/$agentPath", "$home/${jar.inApp}")
    }

    /** Runs AppCall with [fields] inside the app and returns what it did; throws with its reason when it couldn't. */
    private fun call(serial: String, app: InstalledApp, running: Running, fields: List<String>): String {
        val id = "${System.currentTimeMillis()}${(0..999).random()}"
        val encoder = Base64.getEncoder()
        val request = (listOf(id) + fields).joinToString(".") { encoder.encodeToString(it.toByteArray()) }
        val attached = transport.shell(serial, "cmd activity attach-agent ${running.pid} ${running.agent}=call:${running.jar}:$request")
        check(attached.isBlank()) { attached.trim() }
        val outcome = "code_cache/robomori-call-$id"
        val started = TimeSource.Monotonic.markNow()
        while (true) {
            val answer = files.shellAsApp(serial, app, "cat $outcome 2>/dev/null && rm -f $outcome; true").trim()
            if (answer.isNotEmpty()) {
                check(answer.startsWith("ok\t")) { answer.substringAfter('\t').ifBlank { "The app couldn't take the change." } }
                return answer.substringAfter('\t')
            }
            check(started.elapsedNow() < patience) { "The app didn't answer." }
            Thread.sleep(50)
        }
    }

    private fun fingerprint(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).take(8).joinToString("") { "%02x".format(it) }
}
