package com.robomori

import org.jcodec.common.io.NIOUtils
import org.jcodec.containers.mp4.demuxer.MP4Demuxer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class StreamRecordingTest {
    @TempDir lateinit var directory: File
    private val units = nalUnits(checkNotNull(javaClass.getResource("/video/red-blue.h264")).readBytes())
    private fun annexB(units: List<NalUnit>) = units.fold(byteArrayOf()) { packet, unit -> packet + byteArrayOf(0, 0, 0, 1) + unit.bytes() }
    private val config = annexB(units.filter { it.type == 7 || it.type == 8 })
    private val frames = units.filter { it.type == 1 || it.type == 5 }.map { annexB(listOf(it)) }

    @Test fun `writes a stream that's already encoded into a playable MP4 from its first key frame`() {
        val file = File(directory, "recording.mp4")
        val writer = H264Mp4Writer(file, config, 64, 32)
        frames.forEachIndexed { i, frame -> writer.frame(frame, 1_000_000L + i * 33_333, key = true) }
        writer.stop()
        assertEquals(file, writer.save())

        NIOUtils.readableChannel(file).use { channel ->
            val track = MP4Demuxer.createMP4Demuxer(channel).videoTracks.single()
            assertEquals(3, track.meta.totalFrames)
            assertEquals(64, track.meta.videoCodecMeta.size.width)
            assertEquals(0L, track.nextFrame().pts)
        }
    }

    @Test fun `a still screen's last frame stays on until the recording stopped`() {
        val file = File(directory, "recording.mp4")
        val writer = H264Mp4Writer(file, config, 64, 32)
        frames.forEachIndexed { i, frame -> writer.frame(frame, i * 33_333L, key = true) }
        Thread.sleep(300)
        writer.stop()
        writer.save()

        NIOUtils.readableChannel(file).use { channel ->
            val seconds = MP4Demuxer.createMP4Demuxer(channel).videoTracks.single().meta.totalDuration
            assertTrue(seconds >= 0.3, "Lasted $seconds s")
        }
    }

    @Test fun `holding the last frame lengthens a recorder's MP4 to when it stopped`() {
        val file = File(directory, "recording.mp4")
        val writer = H264Mp4Writer(file, config, 64, 32)
        frames.forEachIndexed { i, frame -> writer.frame(frame, i * 100_000L, key = true) }
        writer.stop()
        writer.save()

        holdLastFrame(file, 5_000_000)
        holdLastFrame(file, 2_000_000) // Never shortens it.

        NIOUtils.readableChannel(file).use { channel ->
            val track = MP4Demuxer.createMP4Demuxer(channel).videoTracks.single()
            assertEquals(5.0, track.meta.totalDuration, 0.01)
            assertEquals(3, track.meta.totalFrames)
            assertEquals(listOf(0.0, 0.1, 0.2), List(3) { Math.round(track.nextFrame().ptsD * 10) / 10.0 })
        }
    }

    @Test fun `a recording without a key frame saves nothing`() {
        val file = File(directory, "recording.mp4")
        val writer = H264Mp4Writer(file, config, 64, 32)
        writer.frame(frames.first(), 0, key = false)
        writer.stop()
        assertFailsWith<IllegalStateException> { writer.save() }
        assertFalse(file.exists())
    }

    @Test fun `recording uses the stream already showing the device`() {
        var commands = 0
        val mirror = object : DeviceMirror {
            override fun frames(settings: AppSettings, serial: String) = error("Unused")
            override fun touch(action: Int, x: Int, y: Int, width: Int, height: Int) = Unit
            override fun key(code: Int) = Unit
            override fun keyboardKey(usage: Int, down: Boolean) = Unit
            override fun releaseKeyboard() = Unit
            override fun record(file: File) = object : Recording {
                override fun stop() = Unit
                override fun save() = file
            }
        }
        val capture = AdbDeviceCapture(directory, processBuilder = { commands++; ProcessBuilder("true") },
            scrcpy = { error("A second recorder should not start") })
        val recording = capture.startRecording(AppSettings("/unused/sdk", "/unused/avds"), "phone", "Pixel", DeviceType.WEAR, mirror)
        assertEquals("mp4", recording.save().extension)
        assertEquals(0, commands)
    }
}
