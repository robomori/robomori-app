package com.robomori

import java.io.File
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class DeviceCaptureTest {
    @TempDir lateinit var desktop: File
    private val settings = AppSettings("/unused/sdk", "/unused/avds")

    @Test fun `bundled scrcpy is executable and includes its Android server`() {
        // Platforms Robomori doesn't ship on, such as Linux on ARM in the test VM, build without scrcpy.
        assumeTrue(javaClass.getResource("/scrcpy/scrcpy-bundle.zip") != null)
        val files = BundledScrcpy.files
        assertTrue(files.executable.canExecute())
        assertTrue(files.server.isFile)
        val process = ProcessBuilder(files.executable.absolutePath, "--version")
            .redirectErrorStream(true).start()
        assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        assertEquals(0, process.exitValue(), process.inputStream.bufferedReader().readText())
    }

    @Test fun `bundled scrcpy records an MP4 on a connected emulator`() {
        val serial = System.getenv("ROBOMORI_TEST_RECORDING_SERIAL")
        assumeTrue(!serial.isNullOrBlank())
        val adb = System.getenv("ROBOMORI_TEST_RECORDING_ADB") ?: "adb"
        val capture = AdbDeviceCapture(desktop)
        val recording = capture.startRecording(settings.copy(adb = adb), serial, "Recording Test", DeviceType.PHONE)
        Thread.sleep(3_000)
        recording.stop()
        val file = recording.save()
        assertTrue(file.length() > 1_000, "Expected a finalized MP4 recording")
        assertEquals("mp4", file.extension)
    }

    private fun licensed(scope: CoroutineScope) = licensedForTest(scope, File(desktop, "license"))

    @Test fun `unsupported fast capture falls back to Android PNG and removes failed captures`() {
        assumeTrue(HostOs.detect() != HostOs.WINDOWS)
        val commands = mutableListOf<List<String>>()
        var fail = false
        val capture = AdbDeviceCapture(desktop, processBuilder = { command ->
            commands += command
            ProcessBuilder("sh", "-c", if (fail) "exit 1" else "printf 'PNG bytes'")
        })
        val file = capture.screenshot(settings, "emulator-5554", "Pixel 10", DeviceType.PHONE)
        assertEquals(desktop, file.parentFile)
        assertTrue(file.name.endsWith(".png"))
        assertEquals("PNG bytes", file.readText())
        assertEquals("exec-out", commands.last().dropLast(1).last())
        assertTrue(commands.last().last().lines().contains("screencap -p"))

        fail = true
        assertFailsWith<IllegalStateException> { capture.screenshot(settings, "emulator-5554", "Pixel 10", DeviceType.PHONE) }
        assertEquals(listOf(file), desktop.listFiles()?.toList())
    }

    @Test fun `foldable screenshots capture the screen that's on`() {
        assumeTrue(HostOs.detect() != HostOs.WINDOWS)
        val commands = mutableListOf<List<String>>()
        val capture = AdbDeviceCapture(desktop, processBuilder = { command ->
            commands += command
            when {
                "dumpsys" in command -> ProcessBuilder("printf", "%s\n%s\n",
                    foldableScreen("4619827259835644672", "OFF"), foldableScreen("4619827551948147201", "ON"))
                command.last().contains("gzip") -> ProcessBuilder("sh", "-c", "exit 1")
                else -> ProcessBuilder("printf", "PNG bytes")
            }
        })
        val file = capture.screenshot(settings, "emulator-5556", "Pixel 10 Pro Fold", DeviceType.FOLDABLE)
        assertEquals("PNG bytes", file.readText())
        assertTrue("dumpsys" in commands.first())
        val captures = commands.filter { "exec-out" in it }.map { it.last().lines().single { line -> "screencap" in line } }
        assertEquals(listOf("screencap -d 4619827551948147201 | gzip -1", "screencap -p -d 4619827551948147201"), captures)

        commands.clear()
        capture.screenshot(settings, "emulator-5556", File(desktop, "phone.png"))
        val phone = commands.filter { "exec-out" in it }.map { it.last() }
        assertEquals(listOf("screencap | gzip -1", "screencap -d 4619827551948147201 | gzip -1", "screencap -p -d 4619827551948147201"), phone)
    }

    @Test fun `single-screen devices are captured without a display ID`() {
        assertNull(activeDisplayId(foldableScreen("4619827259835644672", "ON")))
        assertNull(activeDisplayId(""))
        val unfolded = listOf(foldableScreen("1", "ON"), foldableScreen("2", "OFF"), foldableScreen("1", "ON"))
        assertEquals("1", activeDisplayId(unfolded.joinToString("\n")))
    }

    @Test fun `phone screenshots clean the status bar in the same command that captures`() {
        assumeTrue(HostOs.detect() != HostOs.WINDOWS)
        val commands = mutableListOf<List<String>>()
        val capture = AdbDeviceCapture(desktop, processBuilder = { command ->
            commands += command
            ProcessBuilder("printf", "PNG bytes")
        })
        capture.screenshot(settings, "emulator-5554", "Pixel 10", DeviceType.PHONE)
        assertTrue(commands.filter { "exec-out" in it }.all { it.last().contains("demo enter") })

        commands.clear()
        capture.screenshot(settings, "emulator-5554", "Pixel Watch", DeviceType.WEAR)
        assertTrue(commands.none { it.last().contains("demo") })
    }

    @Test fun `clean status bar capture prints only the image and restores the device settings it changed`() {
        assumeTrue(HostOs.detect() != HostOs.WINDOWS)
        fun run(demoAllowed: String, animations: String): Pair<String, List<String>> {
            val log = File.createTempFile("adb-shell-", ".log", desktop)
            val stubs = """
                settings() { echo "settings $*" >> "${'$'}LOG"; case "$1 $3" in "get sysui_demo_allowed") echo $demoAllowed;; "get animator_duration_scale") echo $animations;; "delete "*) echo "Deleted 1 rows";; esac; }
                am() { echo "am $*" >> "${'$'}LOG"; echo "Broadcast completed"; }
                cmd() { echo "cmd $*" >> "${'$'}LOG"; }
                dumpsys() { printf 'Current Battery Service state:\n  AC powered: false\n  USB powered: true\n  level: 23\n'; }
                sleep() { :; }
            """.trimIndent()
            val process = ProcessBuilder("sh", "-c", stubs + "\n" + withCleanStatusBar("printf IMAGE"))
                .apply { environment()["LOG"] = log.path; environment()["TMPDIR"] = desktop.path }.start()
            val image = process.inputStream.bufferedReader().readText()
            assertTrue(process.waitFor(5, TimeUnit.SECONDS))
            return image to log.readLines()
        }

        val (image, steps) = run(demoAllowed = "0", animations = "null")
        assertEquals("IMAGE", image)
        val firstDemoCommand = steps.indexOfFirst { it.startsWith("am broadcast") }
        // The icons may only change once demo mode is allowed and animations are off.
        assertTrue(steps.indexOf("settings put global animator_duration_scale 0") in 0 until firstDemoCommand)
        assertTrue(steps.indexOf("settings put global sysui_demo_allowed 1") in 0 until firstDemoCommand)
        assertTrue(steps.indexOf("cmd statusbar send-disable-flag notification-icons") < steps.indexOf("cmd statusbar send-disable-flag none"))
        // The real battery is the last one demo mode shows, or its full demo battery stays on screen after it exits.
        val realBattery = steps.indexOf("am broadcast -a com.android.systemui.demo -e command battery -e level 23 -e plugged true")
        assertTrue(realBattery in 0 until steps.indexOf("am broadcast -a com.android.systemui.demo -e command exit"))
        assertTrue("settings delete global animator_duration_scale" in steps)
        assertTrue(desktop.listFiles().orEmpty().none { it.name.startsWith(".robomori") })

        // Demo mode stays allowed, so the next capture doesn't wait for the status bar to notice it again.
        val (_, next) = run(demoAllowed = "1", animations = "0.5")
        assertTrue(next.none { it.startsWith("settings put global sysui_demo_allowed") })
        assertTrue("settings put global animator_duration_scale 0.5" in next)
    }


    private fun foldableScreen(id: String, state: String) =
        """  DisplayDeviceInfo{"Built-in Screen": uniqueId="local:$id", 2076 x 2152, modeId 1, type INTERNAL, """ +
            """address StablePhysical{id=$id, port=0}, state $state, committedState $state, frameRateOverride }"""

    @Test fun `recording uses bundled scrcpy to save an MP4 without a time limit`() {
        assumeTrue(HostOs.detect() != HostOs.WINDOWS)
        val commands = mutableListOf<List<String>>()
        var psCalls = 0
        val bundled = File(desktop, "scrcpy").apply { mkdirs() }
        val capture = AdbDeviceCapture(desktop, { command ->
            commands += command
            when {
                "ps" in command -> ProcessBuilder("sh", "-c", if (psCalls++ == 0) "printf 'PID NAME ARGS\\n'"
                    else "printf 'PID NAME ARGS\\n1234 app_process app_process / com.genymobile.scrcpy.Server 5.0.1\\n'")
                "kill" in command -> ProcessBuilder("sh", "-c", "exit 0")
                else -> ProcessBuilder("sh", "-c", "printf 'MP4 bytes' > \"\$1\"; sleep 1", "sh",
                    command.first { it.startsWith("--record=") }.removePrefix("--record="))
            }
        }, { ScrcpyFiles(File(bundled, "scrcpy"), File(bundled, "scrcpy-server")) })
        val recording = capture.startRecording(settings, "emulator-5554", "Pixel 10", DeviceType.PHONE)
        recording.stop()
        val file = recording.save()
        assertEquals(desktop, file.parentFile)
        assertTrue(file.name.endsWith(".mp4"))
        assertEquals("MP4 bytes", file.readText())
        assertTrue(commands.any { "--no-window" in it && "--no-audio" in it && "--no-control" in it })
        assertTrue(commands.none { it.any { arg -> arg.contains("time-limit") || arg == "screenrecord" } })
        assertTrue(commands.any { it.takeLast(2) == listOf("-2", "1234") })
    }

    @Test fun `Wear recording uses the same unlimited MP4 recorder`() {
        assumeTrue(HostOs.detect() != HostOs.WINDOWS)
        val commands = mutableListOf<List<String>>()
        var psCalls = 0
        val bundled = File(desktop, "scrcpy").apply { mkdirs() }
        val capture = AdbDeviceCapture(desktop, { command ->
            commands += command
            when {
                "ps" in command -> ProcessBuilder("sh", "-c", if (psCalls++ == 0) "printf 'PID NAME ARGS\\n'"
                    else "printf '2345 app_process app_process / com.genymobile.scrcpy.Server 5.0.1\\n'")
                "kill" in command -> ProcessBuilder("sh", "-c", "exit 0")
                else -> ProcessBuilder("sh", "-c", "printf 'MP4 bytes' > \"\$1\"; sleep 1", "sh",
                    command.first { it.startsWith("--record=") }.removePrefix("--record="))
            }
        }, { ScrcpyFiles(File(bundled, "scrcpy"), File(bundled, "scrcpy-server")) })
        val recording = capture.startRecording(settings, "emulator-5554", "Pixel Watch", DeviceType.WEAR)
        recording.stop()
        val file = recording.save()
        assertEquals("MP4 bytes", file.readText())
        assertEquals("mp4", file.extension)
        assertTrue(commands.any { it.first() == File(bundled, "scrcpy").absolutePath })
    }

    @Test fun `capture controls show their result after screenshot and recording stop`() = runBlocking {
        withTimeout(5_000) {
            val fake = FakeAndroid(desktop)
            val device = fake.device("Pixel_10")
            fake.active[device.name] = "emulator-5554"
            val stopped = CountDownLatch(1)
            val screenshot = File(desktop, "capture.png").apply { writeText("png") }
            val video = File(desktop, "recording.mp4").apply { writeText("mp4") }
            val capture = object : DeviceCapture {
                override fun screenshot(settings: AppSettings, serial: String, deviceName: String, deviceType: DeviceType, clean: Boolean) = screenshot
                override fun startRecording(settings: AppSettings, serial: String, deviceName: String, deviceType: DeviceType, mirror: DeviceMirror?, clean: Boolean) = object : Recording {
                    override fun stop() { stopped.countDown() }
                    override fun save(): File {
                        check(stopped.await(3, TimeUnit.SECONDS))
                        return video
                    }
                }
            }
            val state = AppState(this, initial = fake.settings, executor = fake, capture = capture, license = licensed(this))
            state.refresh()
            state.captureScreenshot(device)
            while (state.captureNotices[device.name] == null) yield()
            assertEquals(CaptureNotice.Saved(CaptureKind.Screenshot, screenshot), state.captureNotices[device.name])

            state.toggleRecording(device)
            while (state.recordingDevices[device.name] != true) yield()
            assertTrue(state.recordingStartedAt.containsKey(device.name))
            assertFalse(state.showDemoDialog)
            state.toggleRecording(device)
            // Starting the recording replaced the screenshot result; saving it shows the recording's.
            while (state.captureNotices[device.name] != CaptureNotice.Saved(CaptureKind.Recording, video)) yield()
            assertFalse(state.recordingDevices.containsKey(device.name))
            assertFalse(state.recordingStartedAt.containsKey(device.name))
        }
    }

    @Test fun `recording shows at once and a stop while the recorder starts stops it when ready`() = runBlocking {
        withTimeout(5_000) {
            val fake = FakeAndroid(desktop)
            val device = fake.device("Pixel_10")
            fake.active[device.name] = "emulator-5554"
            val recorderReady = CountDownLatch(1)
            val stopped = CountDownLatch(1)
            val video = File(desktop, "recording.mp4").apply { writeText("mp4") }
            val capture = object : DeviceCapture {
                override fun screenshot(settings: AppSettings, serial: String, deviceName: String, deviceType: DeviceType, clean: Boolean) = video
                override fun startRecording(settings: AppSettings, serial: String, deviceName: String, deviceType: DeviceType, mirror: DeviceMirror?, clean: Boolean): Recording {
                    check(recorderReady.await(3, TimeUnit.SECONDS))
                    return object : Recording {
                        override fun stop() { stopped.countDown() }
                        override fun save(): File { check(stopped.await(3, TimeUnit.SECONDS)); return video }
                    }
                }
            }
            val state = AppState(this, initial = fake.settings, executor = fake, capture = capture, license = licensed(this))
            state.refresh()

            state.toggleRecording(device)
            assertEquals(true, state.recordingDevices[device.name])
            assertTrue(state.recordingStartedAt.containsKey(device.name))
            assertFalse(state.savingCaptures.containsKey(device.name))

            state.toggleRecording(device)
            assertEquals(true, state.savingCaptures[device.name])
            recorderReady.countDown()
            while (state.captureNotices[device.name] != CaptureNotice.Saved(CaptureKind.Recording, video)) yield()
            assertFalse(state.recordingDevices.containsKey(device.name))
        }
    }

    @Test fun `a recording that fails to start goes back to the record button`() = runBlocking {
        withTimeout(5_000) {
            val fake = FakeAndroid(desktop)
            val device = fake.device("Pixel_10")
            fake.active[device.name] = "emulator-5554"
            val capture = object : DeviceCapture {
                override fun screenshot(settings: AppSettings, serial: String, deviceName: String, deviceType: DeviceType, clean: Boolean) = File(desktop, "s.png")
                override fun startRecording(settings: AppSettings, serial: String, deviceName: String, deviceType: DeviceType, mirror: DeviceMirror?, clean: Boolean): Recording =
                    error("Could not start recording. Try again.")
            }
            val state = AppState(this, initial = fake.settings, executor = fake, capture = capture, license = licensed(this))
            state.refresh()

            state.toggleRecording(device)
            assertEquals(true, state.recordingDevices[device.name])
            while (state.captureNotices[device.name] == null) yield()
            assertEquals(CaptureStep.StartRecording, (state.captureNotices[device.name] as CaptureNotice.Failed).step)
            assertFalse(state.recordingDevices.containsKey(device.name))
        }
    }

    @Test fun `pairing over Wi-Fi is free`() = runBlocking {
        val fake = FakeAndroid(desktop)
        val state = AppState(this, initial = fake.settings, executor = fake, license = expiredTrialForTest(this, File(desktop, "no-license")))

        state.pairDeviceWirelessly()

        assertFalse(state.showDemoDialog)
        assertTrue(state.connected.showConnect)
    }

    @Test fun `licensed pairing opens the pairing dialog`() = runBlocking {
        val fake = FakeAndroid(desktop)
        val state = AppState(this, initial = fake.settings, executor = fake, license = licensed(this))

        state.pairDeviceWirelessly()

        assertTrue(state.connected.showConnect)
        assertFalse(state.showDemoDialog)
    }

    @Test fun `without a license, recordings and screenshots are free but keep the real status bar`() = runBlocking {
        val fake = FakeAndroid(desktop)
        val device = fake.device("Pixel_10")
        fake.active[device.name] = "emulator-5554"
        val cleaned = mutableListOf<Boolean>()
        val started = CompletableDeferred<Unit>()
        val capture = object : DeviceCapture {
            override fun screenshot(settings: AppSettings, serial: String, deviceName: String, deviceType: DeviceType, clean: Boolean) =
                File(desktop, "screenshot.png").also { cleaned += clean }
            override fun startRecording(settings: AppSettings, serial: String, deviceName: String, deviceType: DeviceType, mirror: DeviceMirror?, clean: Boolean): Recording {
                cleaned += clean
                started.complete(Unit)
                return object : Recording { override fun stop() {}; override fun save() = File(desktop, "recording.mp4") }
            }
        }
        val state = AppState(this, initial = fake.settings, executor = fake, capture = capture,
            license = expiredTrialForTest(this, File(desktop, "no-license")))
        state.refresh()

        state.toggleRecording(device)
        withTimeout(5_000) { started.await() }
        assertFalse(state.showDemoDialog, "Recording is free")
        assertEquals(listOf(false), cleaned, "A clean status bar needs a license")
    }
}
