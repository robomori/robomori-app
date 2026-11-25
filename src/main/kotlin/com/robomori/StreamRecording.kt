package com.robomori

import org.jcodec.common.Codec
import org.jcodec.common.MuxerTrack
import org.jcodec.common.VideoCodecMeta
import org.jcodec.common.io.NIOUtils
import org.jcodec.common.model.ColorSpace
import org.jcodec.common.model.Packet
import org.jcodec.common.model.Size
import org.jcodec.containers.mp4.muxer.MP4Muxer
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch

/**
 * Writes an H.264 stream that's already encoded, such as the device's live screen, into an MP4 at [file], so a
 * recording starts without starting another encoder. It begins at the next key frame; [config] holds the stream's
 * SPS and PPS, which the MP4 needs ahead of that frame.
 */
internal class H264Mp4Writer(private val file: File, private var config: ByteArray, width: Int, height: Int) : Recording {
    private val channel = NIOUtils.writableChannel(file)
    private val muxer = MP4Muxer.createMP4MuxerToChannel(channel)
    private val track: MuxerTrack = muxer.addVideoTrack(Codec.H264,
        VideoCodecMeta.createSimpleVideoCodecMeta(Size(width, height), ColorSpace.YUV420))
    private val finished = CountDownLatch(1)
    private var start = -1L
    /** When the first frame was written, by the computer's clock, which the last frame is held until stop by. */
    private var startedAt = 0L
    private var frames = 0L
    // Each frame lasts until the next one arrives, so it's written one frame late.
    private var pending: Packet? = null
    private var failure: Exception? = null

    @Synchronized fun config(bytes: ByteArray) { config = bytes }

    /** Adds one Annex B frame captured at [timeMicros]. */
    @Synchronized fun frame(bytes: ByteArray, timeMicros: Long, key: Boolean) {
        if (finished.count == 0L) return
        if (start < 0) { if (!key) return; start = timeMicros; startedAt = System.nanoTime() }
        try {
            val time = timeMicros - start
            pending?.let { write(it, time - it.pts) }
            pending = Packet.createPacket(ByteBuffer.wrap(if (key) config + bytes else bytes), time, 1_000_000, 0, frames++,
                if (key) Packet.FrameType.KEY else Packet.FrameType.INTER, null)
        } catch (e: Exception) {
            failure = e
            finish()
        }
    }

    private fun write(packet: Packet, duration: Long) {
        packet.duration = duration.coerceAtLeast(1)
        track.addFrame(packet)
    }

    /** Stops taking frames and completes the file. */
    @Synchronized fun finish() {
        if (finished.count == 0L) return
        try {
            // A screen that stops changing sends no more frames, so the last one lasts until the recording stopped.
            pending?.let { write(it, ((System.nanoTime() - startedAt) / 1000 - it.pts).coerceAtLeast(1_000_000L / 60)) }
            if (frames > 0) muxer.finish()
        } catch (e: Exception) { failure = failure ?: e }
        finally {
            runCatching { channel.close() }
            finished.countDown()
        }
    }

    override fun stop() = finish()

    override fun save(): File {
        finished.await()
        if (failure != null || frames == 0L) {
            file.delete()
            error("Could not save the recording. Try again.")
        }
        return file
    }
}

/**
 * Makes the MP4 at [file] last [durationMicros] by showing its last frame longer. Android's encoder stops sending frames
 * a second after the screen last changed, so a recording of a still screen would otherwise end there, however long it
 * ran. Changes nothing when the video is already that long, or isn't laid out as recorders write it, with its index
 * last. Only the index is read and rewritten, so it's quick for long recordings too.
 */
internal fun holdLastFrame(file: File, durationMicros: Long) {
    RandomAccessFile(file, "rw").use { access -> holdLastFrame(access, durationMicros) }
}

private fun holdLastFrame(access: RandomAccessFile, durationMicros: Long) {
    // Finds the index among the file's top-level boxes; a size of 1 means the real size follows as 64 bits.
    var at = 0L
    var moov = -1L
    while (at + 8 <= access.length()) {
        access.seek(at)
        val short = access.readInt().toLong() and 0xFFFFFFFFL
        val type = ByteArray(4).also(access::readFully).toString(Charsets.US_ASCII)
        val size = if (short == 1L) access.readLong() else short
        if (size < 8) return
        if (type == "moov") moov = at
        at += size
    }
    if (moov < 0 || at != access.length()) return
    val index = ByteArray((at - moov).toInt()).also { access.seek(moov); access.readFully(it) }
    val held = holdLastFrame(index, durationMicros) ?: return
    access.seek(moov)
    access.write(held)
}

/** [moov], an MP4's index, with its last frame lasting until [durationMicros]; null when there's nothing to change. */
private fun holdLastFrame(moov: ByteArray, durationMicros: Long): ByteArray? {
    val buffer = ByteBuffer.wrap(moov)
    fun end(box: Int) = box + buffer.getInt(box)
    fun children(box: Int): Map<String, Int> {
        val found = linkedMapOf<String, Int>()
        var at = box + 8
        while (at + 8 <= end(box)) {
            val size = buffer.getInt(at)
            if (size < 8 || at + size > end(box)) break
            found.putIfAbsent(String(moov, at + 4, 4, Charsets.US_ASCII), at)
            at += size
        }
        return found
    }
    val mvhd = children(0)["mvhd"] ?: return null
    // The first track; recordings have only video.
    val trak = children(0)["trak"] ?: return null
    val tkhd = children(trak)["tkhd"] ?: return null
    val mdia = children(trak)["mdia"] ?: return null
    val mdhd = children(mdia)["mdhd"] ?: return null
    val minf = children(mdia)["minf"] ?: return null
    val stbl = children(minf)["stbl"] ?: return null
    val stts = children(stbl)["stts"] ?: return null
    // Version 0 boxes only, as recorders write them, with 32-bit times.
    if (listOf(mvhd, tkhd, mdhd).any { moov[it + 8].toInt() != 0 }) return null
    val mediaScale = buffer.getInt(mdhd + 20).toLong()
    val movieScale = buffer.getInt(mvhd + 20).toLong()
    if (mediaScale <= 0 || movieScale <= 0) return null
    val extra = durationMicros * mediaScale / 1_000_000 - (buffer.getInt(mdhd + 24).toLong() and 0xFFFFFFFFL)
    val entries = buffer.getInt(stts + 12)
    if (extra <= 0 || entries < 1) return null
    val extraMovie = extra * movieScale / mediaScale
    fun add(at: Int, amount: Long) { buffer.putInt(at, (buffer.getInt(at) + amount).toInt()) }
    add(mdhd + 24, extra)
    add(mvhd + 24, extraMovie)
    add(tkhd + 28, extraMovie)
    children(trak)["edts"]?.let { children(it)["elst"] }?.takeIf { moov[it + 8].toInt() == 0 && buffer.getInt(it + 12) >= 1 }
        ?.let { elst -> add(elst + 16 + (buffer.getInt(elst + 12) - 1) * 12, extraMovie) }
    val last = stts + 16 + (entries - 1) * 8
    val count = buffer.getInt(last)
    if (count == 1) {
        add(last + 4, extra)
        return moov
    }
    // Splits the last run of frames so only the last frame lasts longer, which adds an entry to the table.
    val delta = buffer.getInt(last + 4).toLong()
    buffer.putInt(last, count - 1)
    buffer.putInt(stts + 12, entries + 1)
    for (box in listOf(0, trak, mdia, minf, stbl, stts)) add(box, 8)
    val entry = ByteBuffer.allocate(8).putInt(1).putInt((delta + extra).toInt()).array()
    return moov.copyOfRange(0, last + 8) + entry + moov.copyOfRange(last + 8, moov.size)
}
