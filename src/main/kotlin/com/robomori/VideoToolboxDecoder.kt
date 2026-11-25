package com.robomori

import org.jetbrains.skia.Bitmap
import java.io.ByteArrayOutputStream
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

/**
 * Decodes an H.264 stream with the Mac's video hardware through VideoToolbox, so full-resolution 60 fps video costs
 * the computer almost nothing. [create] returns null on other systems, or when the Mac can't decode the stream.
 */
internal class VideoToolboxDecoder private constructor(private val format: MemorySegment) : AutoCloseable {
    private val arena = Arena.ofShared()
    private var session = MemorySegment.NULL
    @Volatile private var decoded: Bitmap? = null
    /** Gives the bitmap a picture of this width, height and row size goes into. */
    @Volatile private var target: (Int, Int, Int) -> Bitmap = { _, _, _ -> error("Not decoding") }
    @Volatile private var failure: Int = 0

    private fun open(): Boolean {
        val callback = linker.upcallStub(onFrame.bindTo(this), outputCallback, arena)
        val record = arena.allocate(16).also { it.set(ADDRESS, 0, callback); it.set(ADDRESS, 8, MemorySegment.NULL) }
        val out = arena.allocate(ADDRESS)
        val status = vtSessionCreate.invoke(MemorySegment.NULL, format, MemorySegment.NULL, bgraAttributes, record, out) as Int
        if (status != 0) return false
        session = out.get(ADDRESS, 0)
        return true
    }

    /**
     * Decodes one Annex B [packet] into the BGRA bitmap [into] gives for the picture's width, height and row size, which
     * Skia draws as is. Null when the packet completed no picture.
     */
    fun decode(packet: ByteArray, into: (width: Int, height: Int, rowBytes: Int) -> Bitmap): Bitmap? {
        target = into
        val sample = lengthPrefixed(packet)
        if (sample.isEmpty()) return null
        Arena.ofConfined().use { call ->
            val data = call.allocate(sample.size.toLong()).copyFrom(MemorySegment.ofArray(sample))
            val out = call.allocate(ADDRESS)
            check(blockBufferCreate.invoke(MemorySegment.NULL, data, sample.size.toLong(), allocatorNull, MemorySegment.NULL,
                0L, sample.size.toLong(), 0, out) as Int == 0) { "Could not read a video packet." }
            val block = out.get(ADDRESS, 0)
            try {
                val size = call.allocate(JAVA_LONG).also { it.set(JAVA_LONG, 0, sample.size.toLong()) }
                check(sampleBufferCreate.invoke(MemorySegment.NULL, block, format, 1L, 0L, MemorySegment.NULL, 1L, size, out) as Int == 0) {
                    "Could not read a video packet."
                }
                val buffer = out.get(ADDRESS, 0)
                try {
                    decoded = null
                    failure = 0
                    val status = decodeFrame.invoke(session, buffer, 0, MemorySegment.NULL, MemorySegment.NULL) as Int
                    waitForFrames.invoke(session) as Int
                    if (status != 0 || failure != 0) {
                        decoded = null
                        error("The video decoder stopped (${if (status != 0) status else failure}).")
                    }
                    return decoded.also { decoded = null }
                } finally { cfRelease.invoke(buffer) }
            } finally { cfRelease.invoke(block) }
        }
    }

    @Suppress("unused") // Called by VideoToolbox through [onFrame].
    private fun frameDecoded(refCon: MemorySegment, frameRefCon: MemorySegment, status: Int, flags: Int, image: MemorySegment,
                             time: MemorySegment, duration: MemorySegment) {
        // An exception thrown back into VideoToolbox would crash the app.
        try {
            if (status != 0 || image == MemorySegment.NULL) { if (status != 0) failure = status; return }
            check(pixelBufferLock.invoke(image, 1L) as Int == 0)
            try {
                val width = (pixelBufferWidth.invoke(image) as Long).toInt()
                val height = (pixelBufferHeight.invoke(image) as Long).toInt()
                val rowBytes = (pixelBufferRowBytes.invoke(image) as Long).toInt()
                val size = rowBytes.toLong() * height
                val base = (pixelBufferBase.invoke(image) as MemorySegment).reinterpret(size)
                // Straight into the bitmap's own memory: one copy per frame, and none on the Java heap.
                val bitmap = target(width, height, rowBytes)
                checkNotNull(bitmap.peekPixels()).use { MemorySegment.ofAddress(it.addr).reinterpret(size).copyFrom(base) }
                bitmap.notifyPixelsChanged()
                decoded = bitmap
            } finally { pixelBufferUnlock.invoke(image, 1L) as Int }
        } catch (_: Throwable) { failure = -1 }
    }

    override fun close() {
        if (session != MemorySegment.NULL) {
            sessionInvalidate.invoke(session)
            cfRelease.invoke(session)
            session = MemorySegment.NULL
        }
        cfRelease.invoke(format)
        arena.close()
    }

    companion object {
        private val linker = Linker.nativeLinker()
        private val cmTime = MemoryLayout.structLayout(JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_LONG)
        private val outputCallback = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, cmTime, cmTime)
        private val onFrame = MethodHandles.privateLookupIn(VideoToolboxDecoder::class.java, MethodHandles.lookup())
            .findVirtual(VideoToolboxDecoder::class.java, "frameDecoded", MethodType.methodType(Void.TYPE,
                MemorySegment::class.java, MemorySegment::class.java, Int::class.java, Int::class.java,
                MemorySegment::class.java, MemorySegment::class.java, MemorySegment::class.java))

        private val frameworks: SymbolLookup? by lazy {
            if (HostOs.detect() != HostOs.MAC) return@lazy null
            runCatching {
                listOf("CoreFoundation", "CoreMedia", "CoreVideo", "VideoToolbox")
                    .map { SymbolLookup.libraryLookup("/System/Library/Frameworks/$it.framework/$it", Arena.global()) }
                    .reduce(SymbolLookup::or)
            }.getOrNull()
        }

        /** Whether this computer decodes with VideoToolbox; elsewhere the mirror falls back to a software decoder. */
        val isAvailable: Boolean get() = frameworks != null

        private fun function(name: String, descriptor: FunctionDescriptor): MethodHandle =
            linker.downcallHandle(frameworks!!.find(name).orElseThrow(), descriptor)

        private fun constant(name: String): MemorySegment =
            frameworks!!.find(name).orElseThrow().reinterpret(ADDRESS.byteSize()).get(ADDRESS, 0)

        private val cfRelease by lazy { function("CFRelease", FunctionDescriptor.ofVoid(ADDRESS)) }
        private val formatCreate by lazy { function("CMVideoFormatDescriptionCreateFromH264ParameterSets",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS, JAVA_INT, ADDRESS)) }
        private val vtSessionCreate by lazy { function("VTDecompressionSessionCreate",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS)) }
        private val blockBufferCreate by lazy { function("CMBlockBufferCreateWithMemoryBlock",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_INT, ADDRESS)) }
        private val sampleBufferCreate by lazy { function("CMSampleBufferCreateReady",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS)) }
        private val decodeFrame by lazy { function("VTDecompressionSessionDecodeFrame",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS)) }
        private val waitForFrames by lazy { function("VTDecompressionSessionWaitForAsynchronousFrames", FunctionDescriptor.of(JAVA_INT, ADDRESS)) }
        private val sessionInvalidate by lazy { function("VTDecompressionSessionInvalidate", FunctionDescriptor.ofVoid(ADDRESS)) }
        private val pixelBufferLock by lazy { function("CVPixelBufferLockBaseAddress", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG)) }
        private val pixelBufferUnlock by lazy { function("CVPixelBufferUnlockBaseAddress", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG)) }
        private val pixelBufferBase by lazy { function("CVPixelBufferGetBaseAddress", FunctionDescriptor.of(ADDRESS, ADDRESS)) }
        private val pixelBufferRowBytes by lazy { function("CVPixelBufferGetBytesPerRow", FunctionDescriptor.of(JAVA_LONG, ADDRESS)) }
        private val pixelBufferWidth by lazy { function("CVPixelBufferGetWidth", FunctionDescriptor.of(JAVA_LONG, ADDRESS)) }
        private val pixelBufferHeight by lazy { function("CVPixelBufferGetHeight", FunctionDescriptor.of(JAVA_LONG, ADDRESS)) }
        private val allocatorNull by lazy { constant("kCFAllocatorNull") }

        /** Asks VideoToolbox for BGRA pictures, which Skia draws without converting. */
        private val bgraAttributes: MemorySegment by lazy {
            val numberCreate = function("CFNumberCreate", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG, ADDRESS))
            val dictionaryCreate = function("CFDictionaryCreate",
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS))
            val arena = Arena.global()
            val bgra = arena.allocate(JAVA_INT).also { it.set(JAVA_INT, 0, 0x42475241) } // kCVPixelFormatType_32BGRA
            val number = numberCreate.invoke(MemorySegment.NULL, 3L /* kCFNumberSInt32Type */, bgra) as MemorySegment
            val keys = arena.allocate(ADDRESS).also { it.set(ADDRESS, 0, constant("kCVPixelBufferPixelFormatTypeKey")) }
            val values = arena.allocate(ADDRESS).also { it.set(ADDRESS, 0, number) }
            dictionaryCreate.invoke(MemorySegment.NULL, keys, values, 1L,
                frameworks!!.find("kCFTypeDictionaryKeyCallBacks").orElseThrow(),
                frameworks!!.find("kCFTypeDictionaryValueCallBacks").orElseThrow()) as MemorySegment
        }

        /** A decoder for the stream that the Annex B [config] packet, holding its SPS and PPS, describes. */
        fun create(config: ByteArray): VideoToolboxDecoder? {
            if (!isAvailable) return null
            return runCatching {
                val units = nalUnits(config)
                val sps = units.firstOrNull { it.type == 7 } ?: return null
                val pps = units.firstOrNull { it.type == 8 } ?: return null
                Arena.ofConfined().use { call ->
                    val sets = listOf(sps, pps).map { call.allocate(it.size.toLong()).copyFrom(MemorySegment.ofArray(it.bytes())) }
                    val pointers = call.allocate(ADDRESS, 2).also { sets.forEachIndexed { i, set -> it.setAtIndex(ADDRESS, i.toLong(), set) } }
                    val sizes = call.allocate(JAVA_LONG, 2).also { sets.forEachIndexed { i, set -> it.setAtIndex(JAVA_LONG, i.toLong(), set.byteSize()) } }
                    val out = call.allocate(ADDRESS)
                    if (formatCreate.invoke(MemorySegment.NULL, 2L, pointers, sizes, 4, out) as Int != 0) return null
                    VideoToolboxDecoder(out.get(ADDRESS, 0)).takeIf { decoder -> decoder.open().also { if (!it) decoder.close() } }
                }
            }.getOrNull()
        }
    }
}

/** One NAL unit of an Annex B stream: [size] bytes of [stream] from [start]. */
internal class NalUnit(private val stream: ByteArray, val start: Int, val size: Int) {
    val type get() = stream[start].toInt() and 0x1F
    fun bytes(): ByteArray = stream.copyOfRange(start, start + size)
}

/** Splits an Annex B stream, where each NAL unit follows a 00 00 01 start code, into its units. */
internal fun nalUnits(stream: ByteArray): List<NalUnit> {
    val starts = mutableListOf<Int>()
    var i = 0
    while (i + 2 < stream.size) {
        if (stream[i].toInt() == 0 && stream[i + 1].toInt() == 0 && stream[i + 2].toInt() == 1) { starts += i + 3; i += 3 } else i++
    }
    return starts.mapIndexed { index, start ->
        var end = if (index + 1 < starts.size) starts[index + 1] - 3 else stream.size
        // A four-byte start code leaves one more zero before the next unit.
        if (index + 1 < starts.size && end > start && stream[end - 1].toInt() == 0) end--
        NalUnit(stream, start, end - start)
    }.filter { it.size > 0 }
}

/** Rewrites an Annex B packet as VideoToolbox reads it: each NAL unit after its length in four big-endian bytes. */
internal fun lengthPrefixed(packet: ByteArray): ByteArray {
    val out = ByteArrayOutputStream(packet.size + 16)
    for (unit in nalUnits(packet)) {
        val size = unit.size
        out.write(size ushr 24); out.write(size ushr 16); out.write(size ushr 8); out.write(size)
        out.write(packet, unit.start, size)
    }
    return out.toByteArray()
}

