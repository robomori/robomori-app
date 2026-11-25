package com.robomori

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.Flow
import org.jcodec.codecs.h264.H264Decoder
import org.jcodec.common.model.ColorSpace
import org.jcodec.common.model.Picture
import org.jcodec.scale.ColorUtil
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.io.DataInputStream
import java.io.File
import java.io.BufferedInputStream
import java.net.Socket
import java.lang.foreign.MemorySegment
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.random.Random

interface DeviceMirror {
    fun frames(settings: AppSettings, serial: String): Flow<ImageBitmap>
    fun touch(action: Int, x: Int, y: Int, width: Int, height: Int)
    fun key(code: Int)
    fun keyboardKey(usage: Int, down: Boolean)
    fun releaseKeyboard()
    /** Records the stream already showing into an MP4 at [file]; null when this mirror can't. */
    fun record(file: File): Recording? = null
}

/** Whether this computer decodes the device's whole screen at 60 fps: VideoToolbox on a Mac, libavcodec elsewhere. */
private val fullScreenDecoding: Boolean get() = VideoToolboxDecoder.isAvailable || FfmpegDecoder.isAvailable

internal class ScrcpyMirror(private val onFrameTiming: (Long, Long) -> Unit = { _, _ -> }) : DeviceMirror {
    private val control = AtomicReference<ScrcpyControl?>()
    private val recorder = AtomicReference<H264Mp4Writer?>()
    /** The stream's latest SPS and PPS and its size, which a recording starts from. */
    @Volatile private var format: Triple<ByteArray, Int, Int>? = null

    // Only a decoder that gets the device's whole screen records from the stream; the Java decoder's smaller stream would
    // make a worse recording than a recorder of its own.
    override fun record(file: File): Recording? {
        if (!fullScreenDecoding) return null
        val control = control.get() ?: return null
        val (config, width, height) = format ?: return null
        val writer = H264Mp4Writer(file, config, width, height)
        if (!recorder.compareAndSet(null, writer)) { writer.finish(); return null }
        // A new key frame now, instead of at the encoder's next one, seconds away.
        control.resetVideo()
        return object : Recording by writer {
            override fun stop() {
                recorder.compareAndSet(writer, null)
                writer.finish()
            }
        }
    }
    override fun touch(action: Int, x: Int, y: Int, width: Int, height: Int) { control.get()?.touch(action, x, y, width, height) }
    override fun key(code: Int) { control.get()?.key(code) }
    override fun keyboardKey(usage: Int, down: Boolean) { control.get()?.keyboardKey(usage, down) }
    override fun releaseKeyboard() { control.get()?.releaseKeyboard() }

    override fun frames(settings: AppSettings, serial: String) = callbackFlow<ImageBitmap> {
        val socket = AtomicReference<Socket?>()
        val controlSocket = AtomicReference<Socket?>()
        val sessionControl = AtomicReference<ScrcpyControl?>()
        fun closeControl() {
            sessionControl.getAndSet(null)?.let {
                control.compareAndSet(it, null)
                it.close()
            }
        }
        val server = AtomicReference<Process?>()
        val adb = Toolchain(settings).adb.path
        val id = Random.nextInt(1, Int.MAX_VALUE).toString(16).padStart(8, '0')
        // One copy per scrcpy version, shared by every session, so a session that ends abruptly leaves nothing behind.
        val remote = "/data/local/tmp/robomori-mirror-$ScrcpyServerVersion.jar"
        var port: String? = null
        fun command(vararg args: String): String {
            val process = ProcessBuilder(listOf(adb, "-s", serial) + args).redirectErrorStream(true).start()
            val result = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) { "Could not connect to your device. $result" }
            return result.trim()
        }
        // The Mac's video hardware, and libavcodec on Windows and Linux, keep up with the device's whole screen at 60 fps.
        // The Java decoder reads only the simplest H.264 and falls behind on more, so the device sends it less.
        val hardware = VideoToolboxDecoder.isAvailable
        val native = !hardware && FfmpegDecoder.isAvailable
        val video = if (hardware || native) listOf("max_size=0", "max_fps=60")
            else listOf("max_size=1280", "max_fps=30", "video_codec_options=profile=1,level=512")
        val worker = thread(isDaemon = true, name = "device-mirror-$id") {
            var accelerated: VideoToolboxDecoder? = null
            var ffmpeg: FfmpegDecoder? = null
            val ring = FrameRing()
            try {
                val serverJar = BundledScrcpy.files.server
                if (command("shell", deviceFileMatches(remote, serverJar.length())) != "ROBOMORI_PRESENT") {
                    // Earlier versions' copies, and the one-per-session copies older builds left, are of no further use.
                    command("shell", "rm -f /data/local/tmp/robomori-mirror-*.jar")
                    command("push", serverJar.path, remote)
                }
                port = command("forward", "tcp:0", "localabstract:scrcpy_$id")
                val process = ProcessBuilder(adb, "-s", serial, "shell", "CLASSPATH=$remote", "app_process", "/",
                    "com.genymobile.scrcpy.Server", ScrcpyServerVersion, "scid=$id", "tunnel_forward=true", "audio=false",
                    "control=true", "clipboard_autosync=false", "send_device_meta=false", "send_dummy_byte=false", *video.toTypedArray(),
                    "cleanup=false")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
                server.set(process)
                var stream: DataInputStream? = null
                repeat(50) {
                    if (stream == null && !isClosedForSend) {
                        try {
                            val candidate = Socket("127.0.0.1", port!!.toInt())
                            candidate.soTimeout = 15000
                            candidate.tcpNoDelay = true
                            socket.set(candidate)
                            val inputSocket = Socket("127.0.0.1", port!!.toInt()).apply { tcpNoDelay = true }
                            controlSocket.set(inputSocket)
                            val input = DataInputStream(BufferedInputStream(candidate.getInputStream(), 64 * 1024))
                            check(input.readInt() == 0x68323634) { "Your device could not start H.264 mirroring." }
                            candidate.soTimeout = 0 // A static screen legitimately produces no new video packets.
                            stream = input
                            val writer = ScrcpyControl(inputSocket.getOutputStream()) { close(it) }
                            writer.connectKeyboard()
                            sessionControl.set(writer)
                            if (isClosedForSend) writer.close() else control.set(writer)
                        } catch (_: Exception) {
                            runCatching { socket.getAndSet(null)?.close() }
                            runCatching { controlSocket.getAndSet(null)?.close() }
                            Thread.sleep(100)
                        }
                    }
                }
                val input = stream ?: error("Could not start device mirroring.")
                var width = 0
                var height = 0
                var decoder = H264Decoder()
                var pixels: Array<ByteArray>? = null
                var rgb: Picture? = null
                var rgba: ByteArray? = null
                while (!isClosedForSend) {
                    val first = input.readInt()
                    val second = input.readInt()
                    val third = input.readInt()
                    if (first < 0) {
                        width = second
                        height = third
                        check(width in 1..8192 && height in 1..8192) { "Invalid device screen size." }
                        if (hardware || native) continue
                        pixels = Picture.create((width + 15) / 16 * 16, (height + 15) / 16 * 16, ColorSpace.YUV420).data
                        decoder = H264Decoder()
                        rgb = null
                        rgba = null
                        continue
                    }
                    check(third in 1..16_777_216) { "Invalid device video packet." }
                    val bytes = ByteArray(third).also(input::readFully)
                    if (first and 0x40000000 != 0) {
                        format = Triple(bytes, width, height)
                        recorder.get()?.config(bytes)
                        if (hardware) {
                            accelerated?.close()
                            accelerated = VideoToolboxDecoder.create(bytes) ?: error("Your Mac could not decode your device's screen.")
                        } else if (native) {
                            // A new configuration, such as after rotating, starts a fresh decoder with its parameter sets.
                            ffmpeg?.close()
                            ffmpeg = (FfmpegDecoder.create() ?: error("Could not decode your device's screen.")).also { next ->
                                next.decode(bytes) { w, h, row -> ring.next(w, h, row, ColorType.BGRA_8888) }
                            }
                        } else decoder = H264Decoder.createH264DecoderFromCodecPrivate(ByteBuffer.wrap(bytes))
                        continue
                    }
                    // The header's low 61 bits are the frame's time in microseconds; bit 61 marks a key frame.
                    recorder.get()?.frame(bytes, (first.toLong() and 0x1FFFFFFFL shl 32) or (second.toLong() and 0xFFFFFFFF),
                        key = first and 0x20000000 != 0)
                    val decodeStarted = System.nanoTime()
                    if (hardware || native) {
                        val bitmap = (if (hardware) accelerated?.decode(bytes) { w, h, row -> ring.next(w, h, row, ColorType.BGRA_8888) }
                            else ffmpeg?.decode(bytes) { w, h, row -> ring.next(w, h, row, ColorType.BGRA_8888) }) ?: continue
                        val decodedAt = System.nanoTime()
                        trySend(bitmap.asComposeImageBitmap())
                        onFrameTiming(decodedAt - decodeStarted, System.nanoTime() - decodedAt)
                        continue
                    }
                    val decoded = decoder.decodeFrame(ByteBuffer.wrap(bytes), pixels ?: continue) ?: continue
                    val decodedAt = System.nanoTime()
                    val color = rgb ?: Picture.create(decoded.width, decoded.height, ColorSpace.RGB).also { rgb = it }
                    ColorUtil.getTransform(decoded.color, ColorSpace.RGB).transform(decoded, color)
                    val source = color.getPlaneData(0)
                    val output = rgba ?: ByteArray(width * height * 4).also { rgba = it }
                    for (y in 0 until height) for (x in 0 until width) {
                        val from = (y * color.width + x) * 3
                        val to = (y * width + x) * 4
                        output[to] = (source[from] + 128).toByte()
                        output[to + 1] = (source[from + 1] + 128).toByte()
                        output[to + 2] = (source[from + 2] + 128).toByte()
                        output[to + 3] = 255.toByte()
                    }
                    val bitmap = ring.next(width, height, width * 4, ColorType.RGBA_8888)
                    checkNotNull(bitmap.peekPixels()).use { MemorySegment.ofAddress(it.addr).reinterpret(output.size.toLong()).copyFrom(MemorySegment.ofArray(output)) }
                    bitmap.notifyPixelsChanged()
                    trySend(bitmap.asComposeImageBitmap())
                    onFrameTiming(decodedAt - decodeStarted, System.nanoTime() - decodedAt)
                }
            } catch (failure: Exception) { if (!isClosedForSend) close(failure) }
            finally {
                recorder.getAndSet(null)?.finish()
                format = null
                accelerated?.close()
                ffmpeg?.close()
                closeControl()
                runCatching { controlSocket.getAndSet(null)?.close() }
                runCatching { socket.getAndSet(null)?.close() }
                server.getAndSet(null)?.destroyForcibly()
                port?.let { runCatching { command("forward", "--remove", "tcp:$it") } }
            }
        }
        awaitClose {
            closeControl()
            runCatching { controlSocket.getAndSet(null)?.close() }
            runCatching { socket.getAndSet(null)?.close() }
            server.getAndSet(null)?.destroyForcibly()
            worker.interrupt()
        }
    }.buffer(Channel.CONFLATED)
}

/**
 * The bitmaps frames take turns in. A new native bitmap for each frame would only be freed when the garbage collector
 * happened to run, and at 60 frames of 8 MB a second the app grew by gigabytes in seconds. The screen shows the latest
 * frame, a bitmap or two behind the one being written, so with four the worst a slow screen shows is a newer frame.
 */
internal class FrameRing(size: Int = 4) {
    private val bitmaps = arrayOfNulls<Bitmap>(size)
    private var next = 0

    fun next(width: Int, height: Int, rowBytes: Int, colorType: ColorType): Bitmap {
        val index = next
        next = (next + 1) % bitmaps.size
        bitmaps[index]?.takeIf { it.width == width && it.height == height && it.rowBytes == rowBytes && it.colorType == colorType }
            ?.let { return it }
        // A bitmap from before a rotation may still be on screen, so it's left for the garbage collector, not closed.
        return Bitmap().also {
            check(it.allocPixels(ImageInfo(width, height, colorType, ColorAlphaType.OPAQUE), rowBytes)) { "Could not show the device's screen." }
            bitmaps[index] = it
        }
    }
}

/** The version of the scrcpy server Robomori bundles, which the server checks it was started with. */
private const val ScrcpyServerVersion = "5.0.1"
