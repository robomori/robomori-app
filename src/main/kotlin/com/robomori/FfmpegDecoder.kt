package com.robomori

import org.jetbrains.skia.Bitmap
import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.invoke.MethodHandle
import java.util.stream.IntStream

/**
 * Decodes an H.264 stream with FFmpeg's libavcodec, the decoder scrcpy's own client uses, so Windows and Linux show a
 * phone's whole screen at 60 fps like the Mac does with VideoToolbox. [create] returns null when libavcodec can't be
 * loaded, and the mirror then falls back to its pure Java decoder.
 *
 * Only libavcodec's public functions and the leading fields of `AVPacket` and `AVFrame` are used. Those fields have
 * kept their places for many FFmpeg versions, so the system's libavcodec on Linux works as well as the one Robomori ships.
 */
internal class FfmpegDecoder private constructor(
    private var context: MemorySegment,
    private val packet: MemorySegment,
    private val frame: MemorySegment,
) : AutoCloseable {
    private val arena = Arena.ofShared()
    private var input = MemorySegment.NULL

    /**
     * Decodes one Annex B [packet] into the BGRA bitmap [into] gives for the picture's width, height and row size.
     * Null when the packet completed no picture, such as a packet holding only the stream's SPS and PPS.
     */
    fun decode(bytes: ByteArray, into: (width: Int, height: Int, rowBytes: Int) -> Bitmap): Bitmap? {
        if (bytes.isEmpty()) return null
        // libavcodec reads up to 64 bytes past a packet's end, which must be zero.
        if (input.byteSize() < bytes.size + PADDING) input = arena.allocate((bytes.size + PADDING) * 2L)
        input.fill(0)
        MemorySegment.copy(bytes, 0, input, JAVA_BYTE, 0, bytes.size)
        packet.set(ADDRESS, PACKET_DATA, input)
        packet.set(JAVA_INT, PACKET_SIZE, bytes.size)
        // A corrupt packet is skipped rather than ending the mirror: the next key frame repairs the picture.
        if ((sendPacket.invoke(context, packet) as Int) < 0) return null
        var picture: Bitmap? = null
        // Drain every picture the packet completed; the stream's low delay means there is at most one.
        while ((receiveFrame.invoke(context, frame) as Int) >= 0) {
            picture = try { convert(into) } finally { frameUnref.invoke(frame) }
        }
        return picture
    }

    private fun convert(into: (Int, Int, Int) -> Bitmap): Bitmap? {
        val f = frame.reinterpret(FRAME_FORMAT + 4L)
        val width = f.get(JAVA_INT, FRAME_WIDTH)
        val height = f.get(JAVA_INT, FRAME_HEIGHT)
        val format = f.get(JAVA_INT, FRAME_FORMAT)
        if (width <= 0 || height <= 0 || (format != PIX_FMT_YUV420P && format != PIX_FMT_YUVJ420P)) return null
        val planes = (0..2).map { f.get(ADDRESS, it * ADDRESS.byteSize()) }
        val strides = (0..2).map { f.get(JAVA_INT, FRAME_LINESIZE + it * 4L) }
        val bitmap = into(width, height, width * 4)
        val pixels = checkNotNull(bitmap.peekPixels()) { "Could not show the device's screen." }
        pixels.use {
            val out = MemorySegment.ofAddress(it.addr).reinterpret(width.toLong() * height * 4)
            val y = planes[0].reinterpret(strides[0].toLong() * height)
            val u = planes[1].reinterpret(strides[1].toLong() * ((height + 1) / 2))
            val v = planes[2].reinterpret(strides[2].toLong() * ((height + 1) / 2))
            yuv420ToBgra(y, strides[0], u, strides[1], v, strides[2], width, height, out,
                fullRange = format == PIX_FMT_YUVJ420P)
        }
        bitmap.notifyPixelsChanged()
        return bitmap
    }

    override fun close() {
        Arena.ofConfined().use { call ->
            listOf(contextFree to context, frameFree to frame, packetFree to packet).forEach { (free, pointer) ->
                free.invoke(call.allocate(ADDRESS).also { it.set(ADDRESS, 0, pointer) })
            }
        }
        context = MemorySegment.NULL
        arena.close()
    }

    companion object {
        private const val PADDING = 64
        private const val CODEC_ID_H264 = 27
        private const val PIX_FMT_YUV420P = 0
        private const val PIX_FMT_YUVJ420P = 12
        private const val PACKET_DATA = 24L
        private const val PACKET_SIZE = 32L
        private const val FRAME_LINESIZE = 64L
        private const val FRAME_WIDTH = 104L
        private const val FRAME_HEIGHT = 108L
        private const val FRAME_FORMAT = 116L

        /** A folder holding libavcodec and libavutil, which wins over every other place; for development and tests. */
        const val LIBRARY_PROPERTY = "robomori.ffmpeg.dir"

        private val linker = Linker.nativeLinker()

        private val libraries: SymbolLookup? by lazy {
            val folders = listOfNotNull(
                System.getProperty(LIBRARY_PROPERTY)?.let(::File),
                // scrcpy's Windows release ships FFmpeg's DLLs next to scrcpy.exe.
                runCatching { BundledScrcpy.files.executable.parentFile }.getOrNull(),
            )
            folders.firstNotNullOfOrNull(::loadFrom) ?: if (HostOs.detect() == HostOs.LINUX) loadSystem() else null
        }

        /** Loads libavutil and libavcodec from [folder], dependencies first, as Windows only finds them that way. */
        private fun loadFrom(folder: File): SymbolLookup? = runCatching {
            fun library(name: String) = folder.listFiles().orEmpty().filter {
                it.isFile && (it.name.startsWith("$name-") && it.name.endsWith(".dll") ||
                    it.name.startsWith("lib$name.") && (it.name.contains(".so") || it.name.endsWith(".dylib")))
            }.minByOrNull { it.name.length }
            val avutil = library("avutil") ?: return null
            val avcodec = library("avcodec") ?: return null
            val lookups = listOfNotNull(avutil, library("swresample"), avcodec)
                .map { SymbolLookup.libraryLookup(it.toPath(), Arena.global()) }
            lookups.reduce(SymbolLookup::or).takeIf { it.find("avcodec_send_packet").isPresent }
        }.getOrNull()

        /** The newest libavcodec the Linux system has; its own dependencies bring libavutil along. */
        private fun loadSystem(): SymbolLookup? = (62 downTo 58).firstNotNullOfOrNull { version ->
            runCatching { SymbolLookup.libraryLookup("libavcodec.so.$version", Arena.global()) }.getOrNull()
        }?.takeIf { it.find("av_frame_alloc").isPresent }

        private fun function(name: String, descriptor: FunctionDescriptor): MethodHandle =
            linker.downcallHandle(libraries!!.find(name).orElseThrow(), descriptor)

        private val findDecoder by lazy { function("avcodec_find_decoder", FunctionDescriptor.of(ADDRESS, JAVA_INT)) }
        private val allocContext by lazy { function("avcodec_alloc_context3", FunctionDescriptor.of(ADDRESS, ADDRESS)) }
        private val openCodec by lazy { function("avcodec_open2", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS)) }
        private val optSet by lazy { function("av_opt_set", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT)) }
        private val packetAlloc by lazy { function("av_packet_alloc", FunctionDescriptor.of(ADDRESS)) }
        private val frameAlloc by lazy { function("av_frame_alloc", FunctionDescriptor.of(ADDRESS)) }
        private val sendPacket by lazy { function("avcodec_send_packet", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)) }
        private val receiveFrame by lazy { function("avcodec_receive_frame", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)) }
        private val frameUnref by lazy { function("av_frame_unref", FunctionDescriptor.ofVoid(ADDRESS)) }
        private val contextFree by lazy { function("avcodec_free_context", FunctionDescriptor.ofVoid(ADDRESS)) }
        private val frameFree by lazy { function("av_frame_free", FunctionDescriptor.ofVoid(ADDRESS)) }
        private val packetFree by lazy { function("av_packet_free", FunctionDescriptor.ofVoid(ADDRESS)) }

        /** Whether libavcodec, with its H.264 decoder, could be loaded on this computer. */
        val isAvailable: Boolean by lazy {
            libraries != null && runCatching { findDecoder.invoke(CODEC_ID_H264) as MemorySegment != MemorySegment.NULL }.getOrDefault(false)
        }

        /** A decoder ready for an H.264 stream, or null when libavcodec isn't available. */
        fun create(): FfmpegDecoder? {
            if (!isAvailable) return null
            return runCatching {
                val codec = findDecoder.invoke(CODEC_ID_H264) as MemorySegment
                val context = allocContext.invoke(codec) as MemorySegment
                Arena.ofConfined().use { call ->
                    // Slice threads decode one picture on several cores without frame threading's extra delay.
                    mapOf("threads" to "0", "thread_type" to "slice", "flags" to "+low_delay").forEach { (name, value) ->
                        optSet.invoke(context, call.allocateFrom(name), call.allocateFrom(value), 0)
                    }
                }
                check((openCodec.invoke(context, codec, MemorySegment.NULL) as Int) >= 0) { "Could not open the H.264 decoder." }
                FfmpegDecoder(context, (packetAlloc.invoke() as MemorySegment).reinterpret(PACKET_SIZE + 4), frameAlloc.invoke() as MemorySegment)
            }.getOrNull()
        }
    }
}

/**
 * Converts a YUV 4:2:0 picture to BGRA pixels at [out], [width] by [height], using BT.601 colours in video range,
 * or in full range when [fullRange]. Bands of rows convert in parallel, so a phone's whole screen keeps up with 60 fps.
 */
internal fun yuv420ToBgra(
    y: MemorySegment, yStride: Int, u: MemorySegment, uStride: Int, v: MemorySegment, vStride: Int,
    width: Int, height: Int, out: MemorySegment, fullRange: Boolean = false,
) {
    val bands = minOf(height / 2, Runtime.getRuntime().availableProcessors() * 2).coerceAtLeast(1)
    IntStream.range(0, bands).parallel().forEach { band ->
        // Bands start on even rows, so each pair of rows shares one row of chroma.
        val first = (height / 2 * band / bands) * 2
        val last = if (band == bands - 1) height else (height / 2 * (band + 1) / bands) * 2
        val luma = ByteArray(width)
        val chromaWidth = (width + 1) / 2
        val cb = ByteArray(chromaWidth)
        val cr = ByteArray(chromaWidth)
        val row = IntArray(width)
        for (line in first until last) {
            MemorySegment.copy(y, JAVA_BYTE, line.toLong() * yStride, luma, 0, width)
            if (line % 2 == 0 || line == first) {
                MemorySegment.copy(u, JAVA_BYTE, (line / 2).toLong() * uStride, cb, 0, chromaWidth)
                MemorySegment.copy(v, JAVA_BYTE, (line / 2).toLong() * vStride, cr, 0, chromaWidth)
            }
            for (x in 0 until width) {
                val d = (cb[x shr 1].toInt() and 0xFF) - 128
                val e = (cr[x shr 1].toInt() and 0xFF) - 128
                val c: Int
                val r: Int
                val g: Int
                val b: Int
                if (fullRange) {
                    c = (luma[x].toInt() and 0xFF) shl 8
                    r = (c + 359 * e + 128) shr 8
                    g = (c - 88 * d - 183 * e + 128) shr 8
                    b = (c + 454 * d + 128) shr 8
                } else {
                    c = 298 * ((luma[x].toInt() and 0xFF) - 16)
                    r = (c + 409 * e + 128) shr 8
                    g = (c - 100 * d - 208 * e + 128) shr 8
                    b = (c + 516 * d + 128) shr 8
                }
                // Little-endian ints land in memory as B, G, R, A.
                row[x] = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
            }
            MemorySegment.copy(row, 0, out, JAVA_INT, line.toLong() * width * 4, width)
        }
    }
}
