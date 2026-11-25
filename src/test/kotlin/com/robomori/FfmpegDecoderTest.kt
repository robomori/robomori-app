package com.robomori

import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout.JAVA_BYTE
import kotlin.test.*

class FfmpegDecoderTest {
    private val stream = checkNotNull(javaClass.getResource("/video/red-blue.h264")).readBytes()
    private fun annexB(units: List<NalUnit>) = units.fold(byteArrayOf()) { packet, unit -> packet + byteArrayOf(0, 0, 0, 1) + unit.bytes() }
    private fun bitmap(width: Int, height: Int, rowBytes: Int) = Bitmap().apply {
        allocPixels(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE), rowBytes)
    }

    @Test fun `video range colours convert to the RGB they stand for`() = Arena.ofConfined().use { arena ->
        // A 2x2 picture of pure red in BT.601 video range: Y 81, U 90, V 240.
        val y = arena.allocate(4).also { it.fill(81) }
        val u = arena.allocate(1).also { it.set(JAVA_BYTE, 0, 90) }
        val v = arena.allocate(1).also { it.set(JAVA_BYTE, 0, 240.toByte()) }
        val out = arena.allocate(16)
        yuv420ToBgra(y, 2, u, 1, v, 1, 2, 2, out)
        val (blue, green, red, alpha) = (0..3).map { out.get(JAVA_BYTE, it.toLong()).toInt() and 0xFF }
        assertTrue(red > 245 && green < 10 && blue < 10 && alpha == 255, "Expected red, got B$blue G$green R$red A$alpha")
    }

    @Test fun `decodes H264 to the pixels it shows`() {
        // Homebrew's FFmpeg stands in for the libraries Windows and Linux load.
        listOf("/opt/homebrew/lib", "/usr/local/lib").firstOrNull { File(it, "libavcodec.dylib").isFile }
            ?.let { System.setProperty(FfmpegDecoder.LIBRARY_PROPERTY, it) }
        assumeTrue(FfmpegDecoder.isAvailable, "libavcodec isn't installed")
        val units = nalUnits(stream)
        assertNotNull(FfmpegDecoder.create()).use { decoder ->
            // scrcpy sends the stream's parameter sets first, then each picture as its own packet.
            assertNull(decoder.decode(annexB(units.filter { it.type == 7 || it.type == 8 }), ::bitmap))
            val frames = units.filter { it.type == 1 || it.type == 5 }.mapNotNull { decoder.decode(annexB(listOf(it)), ::bitmap) }
            assertEquals(3, frames.size)
            val frame = frames.last()
            assertEquals(64 to 32, frame.width to frame.height)
            val pixels = assertNotNull(frame.readPixels())
            fun bgr(x: Int, y: Int) = (0..2).map { pixels[y * frame.rowBytes + x * 4 + it].toInt() and 0xFF }
            val (blue, green, red) = bgr(8, 16)
            assertTrue(red > 200 && green < 60 && blue < 60, "Left half should be red: ${bgr(8, 16)}")
            val (rightBlue, _, rightRed) = bgr(56, 16)
            assertTrue(rightBlue > 200 && rightRed < 60, "Right half should be blue: ${bgr(56, 16)}")
        }
    }
}
