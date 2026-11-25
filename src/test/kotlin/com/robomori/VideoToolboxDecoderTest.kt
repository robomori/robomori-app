package com.robomori

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.test.*

class VideoToolboxDecoderTest {
    private val stream = checkNotNull(javaClass.getResource("/video/red-blue.h264")).readBytes()
    private fun annexB(units: List<NalUnit>) = units.fold(byteArrayOf()) { packet, unit -> packet + byteArrayOf(0, 0, 0, 1) + unit.bytes() }

    @Test fun `Annex B packets become length-prefixed NAL units`() {
        val packet = byteArrayOf(0, 0, 0, 1, 0x67, 1, 2, 0, 0, 1, 0x68, 3)
        assertEquals(listOf(7, 8), nalUnits(packet).map { it.type })
        assertContentEquals(byteArrayOf(0, 0, 0, 3, 0x67, 1, 2, 0, 0, 0, 2, 0x68, 3), lengthPrefixed(packet))
    }

    @Test fun `decodes H264 to the pixels it shows`() {
        assumeTrue(VideoToolboxDecoder.isAvailable)
        val units = nalUnits(stream)
        val decoder = assertNotNull(VideoToolboxDecoder.create(annexB(units.filter { it.type == 7 || it.type == 8 })))
        decoder.use {
            // scrcpy sends each picture as its own packet, after the stream's parameter sets.
            val frames = units.filter { it.type == 1 || it.type == 5 }.map { slice ->
                it.decode(annexB(listOf(slice))) { width, height, rowBytes ->
                    org.jetbrains.skia.Bitmap().apply { allocPixels(org.jetbrains.skia.ImageInfo(width, height,
                        org.jetbrains.skia.ColorType.BGRA_8888, org.jetbrains.skia.ColorAlphaType.OPAQUE), rowBytes) }
                }
            }
            assertEquals(3, frames.size)
            val frame = assertNotNull(frames.last())
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
