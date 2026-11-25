package com.robomori

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import sun.misc.Signal

internal fun Cli.screenshot(options: Options) {
    options.expect(values = setOf("device", "output", "size"), flags = setOf("clean"))
    val output = options.value("output")
    val size = options.value("size")?.let { it.toIntOrNull()?.takeIf { size -> size in 100..4000 } ?: throw UsageException("Give --size in pixels, from 100 to 4000.") }
    if (output == "-" && jsonOutput) throw UsageException("Choose -o - or --json: both write to standard output.")
    val clean = options.flag("clean")
    val device = target(options)
    val serial = device.serial!!
    val shot = Files.createTempFile("robomori-screenshot-", ".png").toFile()
    val png = try {
        capture.screenshot(settings, serial, shot, cleanStatusBar = clean && device.type.cleansStatusBar)
        shot.readBytes().let { if (size != null) scaledDown(it, size) else it }
    } finally { shot.delete() }
    if (output == "-") { out.write(png); out.flush(); return }
    val file = save(png, output, device, "png")
    if (jsonOutput) printJson(buildJsonObject { put("device", device.name); put("path", file.path) }) else out.println(file.path)
}

internal fun Cli.record(options: Options) {
    options.expect(values = setOf("device", "output", "duration"), flags = setOf("clean"))
    val duration = options.value("duration")?.let { parseTime(it, "--duration", max = 24 * 3_600_000L) }
    val clean = options.flag("clean")
    val device = target(options)
    val file = options.value("output")?.let { File(it).absoluteFile } ?: capture.newCaptureFile(device.displayName, "mp4")
    file.parentFile?.mkdirs()
    val stop = CountDownLatch(1)
    // Ctrl+C, or the process that ran this command stopping it, ends the recording and keeps the video.
    val previous = listOf("INT", "TERM").associateWith { Signal.handle(Signal(it)) { stop.countDown() } }
    try {
        val recording = capture.recordWithCleanStatusBar(settings, device.serial!!, clean && device.type.cleansStatusBar) {
            capture.startRecording(settings, device.serial!!, file)
        }
        progress(if (duration != null) "Recording ${device.name} for ${formatTime(duration)}…" else "Recording ${device.name}. Press Ctrl+C to stop.")
        if (duration != null) stop.await(duration, TimeUnit.MILLISECONDS) else stop.await()
        recording.stop()
        recording.save()
    } finally {
        previous.forEach { (name, handler) -> Signal.handle(Signal(name), handler) }
    }
    if (jsonOutput) printJson(buildJsonObject { put("device", device.name); put("path", file.path) }) else out.println(file.path)
}

/**
 * The PNG [png] scaled down so its shorter side is [size] pixels, keeping its shape, or as it is when it's no bigger.
 * The shorter side, so a screen turned sideways shrinks as much as one upright. A smaller image costs a coding agent
 * fewer tokens to look at and still shows it what's on the screen.
 */
internal fun scaledDown(png: ByteArray, size: Int): ByteArray {
    val image = ImageIO.read(ByteArrayInputStream(png)) ?: return png
    if (minOf(image.width, image.height) <= size) return png
    val scale = size.toDouble() / minOf(image.width, image.height)
    val width = (image.width * scale).toInt().coerceAtLeast(1)
    val height = (image.height * scale).toInt().coerceAtLeast(1)
    val scaled = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val graphics = scaled.createGraphics()
    try {
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        graphics.drawImage(image, 0, 0, width, height, null)
    } finally { graphics.dispose() }
    return ByteArrayOutputStream().also { ImageIO.write(scaled, "png", it) }.toByteArray()
}

/** Writes [bytes] to [output], or to a new file on the Desktop, where the app saves captures. */
private fun Cli.save(bytes: ByteArray, output: String?, device: CliDevice, extension: String): File {
    val file = output?.let { File(it).absoluteFile } ?: capture.newCaptureFile(device.displayName, extension)
    file.parentFile?.mkdirs()
    file.writeBytes(bytes)
    return file
}
