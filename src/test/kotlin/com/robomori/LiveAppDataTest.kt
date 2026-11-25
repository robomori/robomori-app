package com.robomori

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Records what the running app is told, and answers preference edits with [answer]. */
internal class FakeLiveAppData(var answer: () -> LiveEdit = { LiveEdit.Applied }) : LiveAppData {
    val edits = mutableListOf<List<PreferenceEdit>>()
    val tables = mutableListOf<List<String>>()

    override fun editPreferences(serial: String, app: InstalledApp, file: PreferenceFile, edits: List<PreferenceEdit>): LiveEdit {
        this.edits += edits
        return answer()
    }

    override fun tablesChanged(serial: String, app: InstalledApp, tables: List<String>): LiveEdit {
        this.tables += tables
        return answer()
    }
}

class LiveAppDataTest {
    @TempDir lateinit var directory: File
    private val pantry = InstalledApp("com.example.pantry", "Pantry", "1.0", "1", null)
    private val settings = PreferenceFile(DeviceFile("settings.xml", "./shared_prefs/settings.xml", DeviceFileKind.File, 0, Instant.EPOCH),
        PreferenceKind.SharedPreferences)

    @Test fun `only the preferences that changed are edited`() {
        val before = listOf(
            Preference("name", PreferenceType.String, "Ada"),
            Preference("launches", PreferenceType.Int, "3"),
            Preference("old", PreferenceType.Boolean, "true"),
        )
        val after = listOf(Preference("name", PreferenceType.String, "Grace"), Preference("launches", PreferenceType.Int, "3"))
        assertEquals(listOf(PreferenceEdit.Remove("old"), PreferenceEdit.Put(Preference("name", PreferenceType.String, "Grace"))),
            preferenceEdits(before, after))
    }

    @Test fun `edits preferences inside the running app, putting the agent and helper in its folder first`() {
        val device = AppDevice(pid = "4242")
        val live = AgentLiveAppData(device, { DeviceHelperJar(File(directory, "h.jar").apply { writeText("jar") }, "t") }, { "agent".toByteArray() })
        val edits = listOf(PreferenceEdit.Put(Preference("name", PreferenceType.String, "Ada")), PreferenceEdit.Remove("old"),
            PreferenceEdit.Put(Preference("token", PreferenceType.String, null)))

        assertEquals(LiveEdit.Applied, live.editPreferences("s", pantry, settings, edits))
        assertEquals(2, device.pushes.size, "The agent and the helper go into the app's folder.")
        val attach = device.commands.single { it.startsWith("cmd activity attach-agent") }
        val home = "/data/user/0/com.example.pantry/code_cache"
        val (agent, options) = attach.removePrefix("cmd activity attach-agent 4242 ").split('=', limit = 2)
        assertTrue(Regex("$home/robomori-agent-[0-9a-f]{16}\\.so").matches(agent), agent)
        assertTrue(options.startsWith("call:$home/robomori-database-t.jar:"), options)
        val fields = options.substringAfterLast(':').split('.').map { String(Base64.getDecoder().decode(it)) }
        assertEquals(listOf("prefs", "settings", "set", "name", "String", "Ada", "remove", "old", "set", "token", "Null", ""), fields.drop(1))

        device.ready = true
        live.tablesChanged("s", pantry, listOf("items"))
        assertEquals(2, device.pushes.size, "What's in the app's folder already stays.")
        assertEquals(listOf("room", "items"), device.commands.last { it.startsWith("cmd activity attach-agent") }
            .substringAfterLast(':').split('.').drop(1).map { String(Base64.getDecoder().decode(it)) })
    }

    @Test fun `leaves an app that isn't running to read its files when it starts`() {
        val device = AppDevice(pid = "")
        val live = AgentLiveAppData(device, { DeviceHelperJar(File(directory, "h.jar"), "t") }, { "agent".toByteArray() })
        assertEquals(LiveEdit.NotRunning, live.editPreferences("s", pantry, settings, emptyList()))
        live.tablesChanged("s", pantry, listOf("items"))
        assertTrue(device.commands.none { it.startsWith("cmd activity attach-agent") })
    }

    @Test fun `a release build is never attached to, and needs a restart while it runs`() {
        val release = InstalledApp("com.example.release", "Release", "1.0", "1", null, debuggable = false, uid = 10233)
        val running = AppDevice(pid = "4242")
        val live = AgentLiveAppData(running, { DeviceHelperJar(File(directory, "h.jar"), "t") }, { "agent".toByteArray() })
        assertEquals(LiveEdit.NeedsRestart, live.editPreferences("s", release, settings, listOf(PreferenceEdit.Remove("old"))))
        assertEquals(LiveEdit.NeedsRestart, live.tablesChanged("s", release, listOf("items")))
        assertEquals(List(2) { "pidof com.example.release || true" }, running.commands, "Nothing goes into its folder or its process.")
        assertEquals(emptyList(), running.pushes)

        val stopped = AgentLiveAppData(AppDevice(pid = ""), { DeviceHelperJar(File(directory, "h.jar"), "t") }, { "agent".toByteArray() })
        assertEquals(LiveEdit.NotRunning, stopped.tablesChanged("s", release, listOf("items")))
    }

    @Test fun `DataStore needs the app to restart, and a failure inside the app says why`() {
        val device = AppDevice(pid = "4242", outcome = "error\tThe app couldn't save its preferences.").apply { ready = true }
        val live = AgentLiveAppData(device, { DeviceHelperJar(File(directory, "h.jar"), "t") }, { "agent".toByteArray() })
        val dataStore = settings.copy(kind = PreferenceKind.DataStore)
        assertEquals(LiveEdit.NeedsRestart, live.editPreferences("s", pantry, dataStore, emptyList()))
        assertEquals("The app couldn't save its preferences.",
            assertFailsWith<IllegalStateException> { live.editPreferences("s", pantry, settings, emptyList()) }.message)
    }

    @Test fun `a preference edit goes into the running app, or to its file with a restart when it can't`() = runBlocking {
        val xml = writeSharedPreferences(listOf(Preference("name", PreferenceType.String, "Ada"), Preference("old", PreferenceType.Int, "1")))
        val pushes = mutableListOf<String>()
        val device = object : DeviceFileTransport {
            override fun shell(serial: String, command: String): String = when {
                "stat -L -c %s" in command -> "${xml.size}"
                "./databases/" in command -> "ROBOMORI_DONE"
                "./shared_prefs/" in command -> "regular file|${xml.size}|0|./settings.xml\nROBOMORI_DONE"
                else -> ""
            }
            override fun pull(serial: String, command: String, destination: File) = destination.writeBytes(xml)
            override fun push(serial: String, command: String, source: File) { pushes += command }
        }
        val live = FakeLiveAppData()
        val files = DeviceFilesService(device)
        val databases = AppDatabasesState(AppDatabasesService(files), InstalledAppsService(FakeAndroid(directory).settings), "s", this,
            AppPreferencesService(files), live = live)
        databases.chooseApp(pantry)
        withTimeout(5000) { while (databases.preferences !is PreferencesLoad.Ready) delay(10) }
        suspend fun edited(expected: LiveEdit) = withTimeout(5000) {
            while (databases.preferencesEdit != expected || databases.action != DataAction.None) delay(10)
        }

        databases.editPreference("name", "Grace")
        edited(LiveEdit.Applied)
        assertEquals(listOf(PreferenceEdit.Put(Preference("name", PreferenceType.String, "Grace"))), live.edits.single())
        assertEquals(emptyList(), pushes, "The app saves what it took in itself.")

        live.answer = { error("The app didn't answer.") }
        databases.deletePreferences(listOf("old"))
        edited(LiveEdit.NeedsRestart)
        assertEquals(1, pushes.size, "The file is written instead, for the app to read when it starts.")

        live.answer = { LiveEdit.NotRunning }
        databases.editPreference("name", "Ada")
        edited(LiveEdit.NotRunning)
        assertEquals(2, pushes.size)
        databases.close()
    }

    /**
     * A device with the app running as [pid], or not when it's empty, answering each call with [outcome]. [ready] says
     * whether the agent and helper are in the app's folder.
     */
    private class AppDevice(private val pid: String, private val outcome: String = "ok\tdone") : DeviceFileTransport {
        val commands = mutableListOf<String>()
        val pushes = mutableListOf<String>()
        var ready = false

        override fun shell(serial: String, command: String): String {
            commands += command
            return when {
                "dumpsys package" in command -> "    primaryCpuAbi=arm64-v8a"
                "pidof" in command -> pid + if (ready) "\nROBOMORI_READY" else ""
                "robomori-call-" in command -> outcome
                else -> ""
            }
        }
        override fun pull(serial: String, command: String, destination: File) {}
        override fun push(serial: String, command: String, source: File) { pushes += command; ready = true }
    }
}
