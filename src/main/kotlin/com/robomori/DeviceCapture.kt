package com.robomori

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal fun encodeRawScreenshot(raw: ByteArray): ByteArray {
    require(raw.size >= 12) { "Incomplete screenshot." }
    val header = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
    val width = header.int
    val height = header.int
    val format = header.int
    require(width in 1..8192 && height in 1..8192 && format in 1..2) { "Unsupported screenshot format." }
    val pixels = width.toLong() * height * 4
    val offset = raw.size.toLong() - pixels
    require(offset == 12L || offset == 16L) { "Incomplete screenshot pixels." }
    // Preserve wide-gamut colors through Android's PNG encoder when necessary.
    require(offset != 16L || header.int == 1) { "Screenshot uses a non-sRGB color space." }
    return encodePng(width, height, raw, offset.toInt())
}

/**
 * Encodes RGBA [pixels] starting at [offset] as an opaque RGB PNG. Screens are mostly flat color, so each row stored as
 * its difference from the row above at zlib's fastest level stays small, and encodes several times faster than Skia.
 */
private fun encodePng(width: Int, height: Int, pixels: ByteArray, offset: Int): ByteArray {
    val stride = width * 3 + 1
    val rows = ByteArray(stride * height)
    for (y in 0 until height) {
        val row = y * stride
        rows[row] = 2 // The "Up" filter.
        val source = offset + y * width * 4
        for (x in 0 until width) {
            val pixel = source + x * 4
            val target = row + 1 + x * 3
            for (channel in 0..2) {
                val above = if (y == 0) 0 else pixels[pixel + channel - width * 4].toInt()
                rows[target + channel] = (pixels[pixel + channel] - above).toByte()
            }
        }
    }
    val compressed = ByteArrayOutputStream(rows.size / 4)
    val deflater = Deflater(Deflater.BEST_SPEED)
    try { DeflaterOutputStream(compressed, deflater, 1 shl 16).use { it.write(rows) } } finally { deflater.end() }
    val bytes = ByteArrayOutputStream(compressed.size() + 64)
    val png = DataOutputStream(bytes)
    png.write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
    fun chunk(type: String, data: ByteArray) {
        val crc = CRC32().apply { update(type.toByteArray()); update(data) }
        png.writeInt(data.size); png.writeBytes(type); png.write(data); png.writeInt(crc.value.toInt())
    }
    chunk("IHDR", ByteBuffer.allocate(13).putInt(width).putInt(height).put(byteArrayOf(8, 2, 0, 0, 0)).array())
    chunk("IDAT", compressed.toByteArray())
    chunk("IEND", ByteArray(0))
    return bytes.toByteArray()
}

private val displayDevice = Regex("""DisplayDeviceInfo\{.*?uniqueId="local:(\d+)".*?, state (\w+),""")

/**
 * The ID screencap takes for the built-in screen that's on, from `dumpsys display`; null when the device has a single
 * screen, which screencap captures without one.
 */
internal fun activeDisplayId(dumpsysDisplay: String): String? {
    val screens = displayDevice.findAll(dumpsysDisplay).map { it.groupValues[1] to it.groupValues[2] }.distinctBy { it.first }.toList()
    if (screens.size < 2) return null
    return screens.firstOrNull { (_, state) -> state == "ON" }?.first
}

/** Phones, tablets and foldables share a status bar that demo mode can clean; watches and TVs don't. */
internal val DeviceType.cleansStatusBar get() = this == DeviceType.PHONE || this == DeviceType.FOLDABLE || this == DeviceType.TABLET

/** Where a capture keeps the animation speed it found, until it puts it back. */
private const val savedAnimations = "\${TMPDIR:-/data/local/tmp}/.robomori-animations"

/** Restores global [setting] to the shell variable [variable] holds, deleting it when it was never set. */
private fun restoreSetting(setting: String, variable: String) =
    "if [ \"$$variable\" = null ]; then settings delete global $setting >/dev/null; else settings put global $setting \"$$variable\"; fi"

/**
 * Android's status bar demo mode: 9:41, full Wi-Fi and battery, no other icons. Hiding notification icons through
 * the status bar's disable flags covers the ones already showing, without touching the notifications themselves.
 * Each command costs tens of milliseconds on a phone, so independent ones run side by side. With [animationsOff], the
 * icons switch within a frame instead of fading, and the previous animation speed is kept in `$animations`.
 */
private fun cleanStatusBarOn(animationsOff: Boolean) = listOfNotNull(
    // Errors would land in the screenshot, which shares the output.
    "exec 2>/dev/null",
    // One file for every capture, kept until the speed is restored: a capture that overlaps another, or is cut off,
    // finds the speed from before either, instead of the 0 the other set.
    "saved=$savedAnimations",
    """demo() { am broadcast -a com.android.systemui.demo -e command "$@" >/dev/null 2>&1; }""",
    // Animations go off before any icon changes, or the old icons fade out over several frames.
    "{ [ -s \"\$saved\" ] || settings get global animator_duration_scale > \"\$saved\"; settings put global animator_duration_scale 0; } &"
        .takeIf { animationsOff },
    // Newer status bars notice that demo mode is allowed a moment after the setting changes, and ignore demo commands
    // until then. Allowing it only lets the shell drive the status bar, so it stays on and later captures skip the wait.
    """[ "$(settings get global sysui_demo_allowed)" = 1 ] || { settings put global sysui_demo_allowed 1; sleep 0.5; } &""",
    "wait",
    "changed=$(date +%s%N 2>/dev/null)",
    "cmd statusbar send-disable-flag notification-icons >/dev/null 2>&1 &",
    // Newer Android versions ignore demo commands until demo mode is entered.
    "demo enter",
    "demo clock -e hhmm 0941 & demo battery -e level 100 -e plugged false & " +
        "demo network -e wifi show -e level 4 -e fully true & demo network -e mobile hide & wait",
    "animations=$(cat \"\$saved\")".takeIf { animationsOff },
).joinToString("\n")

/**
 * Leaving demo mode keeps the demo battery on screen until the real one next changes, which on an emulator may be
 * never. Showing the real level and charging state as the last demo battery leaves the icon right.
 */
private const val restoreBattery = "b=\$(dumpsys battery 2>/dev/null); " +
    "level=\$(echo \"\$b\" | sed -n 's/^ *level: //p'); " +
    "case \"\$b\" in *'powered: true'*) plugged=true;; *) plugged=false;; esac; " +
    "[ -n \"\$level\" ] && am broadcast -a com.android.systemui.demo -e command battery -e level \"\$level\" -e plugged \"\$plugged\" >/dev/null 2>&1"

private fun cleanStatusBarOff(animationsOff: Boolean) = listOfNotNull(
    "cmd statusbar send-disable-flag none >/dev/null 2>&1 &",
    "{ $restoreBattery; am broadcast -a com.android.systemui.demo -e command exit >/dev/null 2>&1; } &",
    "{ ${restoreSetting("animator_duration_scale", "animations")}; rm -f \"$savedAnimations\"; } &".takeIf { animationsOff },
    "wait",
).joinToString("\n")

/**
 * Cleans the status bar for a recording and waits for it to draw, so the video starts clean. Animations are off only
 * while the icons switch.
 */
internal val enterCleanStatusBar by lazy {
    listOf(cleanStatusBarOn(animationsOff = true), waitForStatusBar, restoreSetting("animator_duration_scale", "animations"),
        "rm -f \"$savedAnimations\"").joinToString("\n")
}

internal val exitCleanStatusBar = cleanStatusBarOff(animationsOff = false)

/**
 * Waits until the status bar has had time since its first change to draw the clean icons: 120 ms, or 300 ms on
 * Android 13 and later, whose status bar takes longer to apply demo mode. Phones spend much of it sending commands.
 */
private val waitForStatusBar = """
    [ "$(getprop ro.build.version.sdk)" -ge 33 ] 2>/dev/null && budget=300 || budget=120
    now=$(date +%s%N 2>/dev/null)
    case "${'$'}changed${'$'}now" in
        *[!0-9]*|"") sleep 0.3 ;;
        *) left=$(( budget - (now - changed) / 1000000 )); [ "${'$'}left" -gt 0 ] && sleep "0.$(printf %03d "${'$'}left")" ;;
    esac
""".trimIndent()

/** Runs [screencap] with a clean status bar, then restores it, in a single shell so it costs one round trip. */
internal fun withCleanStatusBar(screencap: String) = listOf(
    cleanStatusBarOn(animationsOff = true),
    waitForStatusBar,
    screencap,
    cleanStatusBarOff(animationsOff = true),
).joinToString("\n")

interface DeviceCapture {
    /** Saves the device's screen; with [clean], a clean status bar on devices that have one. */
    fun screenshot(settings: AppSettings, serial: String, deviceName: String, deviceType: DeviceType, clean: Boolean = true): File
    /**
     * Records the device's screen, from [mirror]'s stream when it's showing the device and can; with [clean], a clean
     * status bar on devices that have one.
     */
    fun startRecording(settings: AppSettings, serial: String, deviceName: String, deviceType: DeviceType, mirror: DeviceMirror? = null,
                       clean: Boolean = true): Recording
}

interface Recording {
    fun stop()
    fun save(): File
}

class AdbDeviceCapture(
    private val desktop: File = File(userHome, "Desktop"),
    private val processBuilder: (List<String>) -> ProcessBuilder = ::ProcessBuilder,
    private val scrcpy: () -> ScrcpyFiles = { BundledScrcpy.files },
) : DeviceCapture {
    override fun screenshot(settings: AppSettings, serial: String, deviceName: String, deviceType: DeviceType, clean: Boolean): File =
        screenshot(settings, serial, newCaptureFile(deviceName, "png"), cleanStatusBar = clean && deviceType.cleansStatusBar,
            severalScreens = deviceType == DeviceType.FOLDABLE)

    /** Cleans the status bar for a recording; false when the device refused. */
    private fun cleanStatusBar(settings: AppSettings, serial: String): Boolean =
        runCatching { commandOutput(adb(settings, serial) + listOf("shell", enterCleanStatusBar)) }.isSuccess

    private fun restoreStatusBar(settings: AppSettings, serial: String) {
        runCatching { commandOutput(adb(settings, serial) + listOf("shell", exitCleanStatusBar)) }
    }

    /**
     * Saves the device's screen as a PNG at [file], with a clean status bar when [cleanStatusBar]. Devices with
     * [severalScreens], such as foldables, need the screen named; the lookup costs a transfer, so other devices only
     * look it up when capturing without it fails.
     */
    fun screenshot(settings: AppSettings, serial: String, file: File, cleanStatusBar: Boolean = false,
                   severalScreens: Boolean = false): File {
        val shell = { screencap: String -> if (cleanStatusBar) withCleanStatusBar(screencap) else screencap }
        try {
            val known = if (severalScreens) displayArgument(settings, serial) else null
            if (fastScreenshot(settings, serial, shell("screencap${known.orEmpty()} | gzip -1"), file)) return file
            val display = known ?: displayArgument(settings, serial)
            if (known == null && display.isNotEmpty() && fastScreenshot(settings, serial, shell("screencap$display | gzip -1"), file)) return file
            val process = processBuilder(adb(settings, serial) + listOf("exec-out", shell("screencap -p$display")))
                .redirectOutput(file).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            try {
                check(process.waitFor(20, TimeUnit.SECONDS) && process.exitValue() == 0 && file.length() > 0) {
                    "Could not capture the screenshot. Try again."
                }
            } finally { if (process.isAlive) process.destroyForcibly() }
            return file
        } catch (failure: Exception) {
            file.delete()
            throw failure
        }
    }

    /**
     * `-d <id>` for the screen that's on, on devices with several, such as foldables. Without it, screencap writes a
     * warning into the image and may capture the screen that's off.
     */
    private fun displayArgument(settings: AppSettings, serial: String): String =
        runCatching { activeDisplayId(commandOutput(adb(settings, serial) + listOf("shell", "dumpsys", "display"))) }
            .getOrNull()?.let { " -d $it" } ?: ""

    /** Runs [shell], which writes the raw screen gzipped, and saves it as a PNG at [destination]. */
    private fun fastScreenshot(settings: AppSettings, serial: String, shell: String, destination: File): Boolean {
        val compressed = Files.createTempFile("robomori-screen-", ".gz").toFile()
        try {
            val process = processBuilder(adb(settings, serial) + listOf("exec-out", shell))
                .redirectOutput(compressed).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            try {
                if (!process.waitFor(20, TimeUnit.SECONDS) || process.exitValue() != 0) return false
            } finally { if (process.isAlive) process.destroyForcibly() }
            val raw = GZIPInputStream(compressed.inputStream()).use { it.readNBytes(256 * 1024 * 1024 + 17) }
            destination.writeBytes(encodeRawScreenshot(raw))
            return true
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted
        } catch (_: Exception) { return false }
        finally { compressed.delete() }
    }

    override fun startRecording(settings: AppSettings, serial: String, deviceName: String, deviceType: DeviceType, mirror: DeviceMirror?,
                                clean: Boolean): Recording {
        val file = newCaptureFile(deviceName, "mp4")
        // Recording the stream already showing starts at once; a recorder of its own takes a second to start.
        return recordWithCleanStatusBar(settings, serial, clean && deviceType.cleansStatusBar) { mirror?.record(file) ?: startRecording(settings, serial, file) }
    }

    /**
     * Starts a recording with [start], with a clean status bar when [clean] and the device allows it, and puts the
     * status bar back once the recording is saved.
     */
    fun recordWithCleanStatusBar(settings: AppSettings, serial: String, clean: Boolean, start: () -> Recording): Recording {
        val cleaned = clean && cleanStatusBar(settings, serial)
        val recording = try { start() } catch (failure: Exception) {
            if (cleaned) restoreStatusBar(settings, serial)
            throw failure
        }
        if (!cleaned) return recording
        return object : Recording by recording {
            override fun save(): File = try { recording.save() } finally { restoreStatusBar(settings, serial) }
        }
    }

    /** Records the device's screen as an MP4 at [file] until the recording is stopped. */
    fun startRecording(settings: AppSettings, serial: String, file: File): Recording {
        val before = serverPids(settings, serial)
        val files = scrcpy()
        val process = try {
            processBuilder(listOf(files.executable.absolutePath, "--serial=$serial", "--no-audio",
                "--no-window", "--no-control", "--record=${file.absolutePath}"))
                .apply {
                    directory(files.executable.parentFile)
                    environment()["ADB"] = Toolchain(settings).adb.path
                    environment()["SCRCPY_SERVER_PATH"] = files.server.absolutePath
                }
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        } catch (failure: Exception) {
            file.delete()
            throw failure
        }
        val pid = try { waitForServer(settings, serial, before, process) }
        catch (failure: Exception) {
            process.destroyForcibly()
            file.delete()
            throw failure
        }
        val startedAt = System.nanoTime()
        return object : Recording {
            @Volatile private var stoppedAt = 0L

            override fun stop() {
                if (stoppedAt == 0L) stoppedAt = System.nanoTime()
                if (process.isAlive) runCommand(adb(settings, serial) + listOf("shell", "kill", "-2", pid.toString()))
            }

            override fun save(): File {
                process.waitFor()
                if (file.length() == 0L) {
                    file.delete()
                    error("Could not save the recording. Try again.")
                }
                // The video is complete either way; this only keeps a still screen on until the recording stopped.
                if (stoppedAt != 0L) runCatching { holdLastFrame(file, (stoppedAt - startedAt) / 1000) }
                return file
            }
        }
    }

    private fun waitForServer(settings: AppSettings, serial: String, before: Set<Int>, process: Process): Int {
        repeat(50) {
            check(process.isAlive) { "Could not start recording. Try again." }
            (serverPids(settings, serial) - before).singleOrNull()?.let { return it }
            Thread.sleep(100)
        }
        error("Could not start recording. Try again.")
    }

    private fun serverPids(settings: AppSettings, serial: String): Set<Int> =
        commandOutput(adb(settings, serial) + listOf("shell", "ps", "-A", "-o", "PID,NAME,ARGS"))
            .lineSequence().mapNotNull { line ->
                val fields = line.trim().split(Regex("\\s+"), limit = 3)
                fields.getOrNull(0)?.toIntOrNull()?.takeIf {
                    fields.getOrNull(1)?.startsWith("app_process") == true &&
                        fields.getOrNull(2)?.contains("com.genymobile.scrcpy.Server") == true
                }
            }.toSet()

    /** A new file on the Desktop named after the device and the time, where the app saves captures. */
    fun newCaptureFile(deviceName: String, extension: String): File {
        check(desktop.isDirectory) { "Desktop is unavailable. Connect it and try again." }
        val safeName = deviceName.replace(Regex("[^A-Za-z0-9_-]+"), "-").trim('-').ifEmpty { "Device" }
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        return Files.createTempFile(desktop.toPath(), "Robomori-$safeName-$timestamp-", ".$extension").toFile()
    }

    private fun commandOutput(command: List<String>): String {
        val process = processBuilder(command).redirectErrorStream(true).start()
        try {
            check(process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0) {
                "Could not communicate with the device. Try again."
            }
            return process.inputStream.bufferedReader().readText()
        } finally { if (process.isAlive) process.destroyForcibly() }
    }

    private fun runCommand(command: List<String>) {
        val process = processBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        try { check(process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0) { "Could not stop recording. Try again." } }
        finally { if (process.isAlive) process.destroyForcibly() }
    }

    private fun adb(settings: AppSettings, serial: String) = listOf(Toolchain(settings).adb.path, "-s", serial)
}
