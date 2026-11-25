package com.robomori

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPOutputStream
import javax.imageio.ImageIO
import kotlin.test.*

class RawScreenshotTest {
    @TempDir lateinit var desktop: File
    private fun pixels() = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(2).putInt(1).putInt(1).putInt(1)
        .put(byteArrayOf(-1, 0, 0, -1, 0, 0, -1, -1)).array()

    @Test fun `local encoding preserves native dimensions and exact pixels`() {
        val png = encodeRawScreenshot(pixels())
        val image = ImageIO.read(png.inputStream())
        assertEquals(2, image.width)
        assertEquals(1, image.height)
        assertEquals(0xFFFF0000.toInt(), image.getRGB(0, 0))
        assertEquals(0xFF0000FF.toInt(), image.getRGB(1, 0))
        assertFailsWith<IllegalArgumentException> { encodeRawScreenshot(pixels().copyOf(23)) }
    }

    @Test fun `encoding keeps every row's pixels`() {
        val colors = listOf(0xFF102030, 0xFFFFFFFF, 0xFF000000, 0xFF0A0B0C, 0xFF80FF01, 0xFF102030).map { it.toInt() }
        val raw = ByteBuffer.allocate(12 + colors.size * 4).order(ByteOrder.LITTLE_ENDIAN).putInt(2).putInt(3).putInt(1)
        colors.forEach { raw.put((it shr 16).toByte()).put((it shr 8).toByte()).put(it.toByte()).put(-1) }
        val image = ImageIO.read(encodeRawScreenshot(raw.array()).inputStream())
        assertEquals(colors, (0 until 3).flatMap { y -> (0 until 2).map { x -> image.getRGB(x, y) } })
    }

    @Test fun `fast screenshots save PNG using a single transfer`() {
        assumeTrue(HostOs.detect() != HostOs.WINDOWS)
        val fixture = File.createTempFile("raw-screen-", ".gz")
        try {
            GZIPOutputStream(fixture.outputStream()).use { it.write(pixels()) }
            val commands = mutableListOf<List<String>>()
            val capture = AdbDeviceCapture(desktop, processBuilder = { args ->
                commands += args
                ProcessBuilder("cat", fixture.path)
            })
            val saved = capture.screenshot(AppSettings("/unused", "/unused"), "phone", "Pixel", DeviceType.PHONE)
            assertEquals(2, ImageIO.read(saved).width)
            assertTrue(commands.single().last().lines().contains("screencap | gzip -1"))
        } finally { fixture.delete() }
    }

    @Test fun `full resolution screenshot from connected device`() {
        val serial = System.getenv("ROBOMORI_TEST_SCREENSHOT_SERIAL")
        assumeTrue(!serial.isNullOrBlank())
        val start = System.nanoTime()
        val file = AdbDeviceCapture(desktop).screenshot(SettingsStore().load(), serial!!, "Screenshot test", DeviceType.PHONE)
        val image = ImageIO.read(file)
        println("Full-resolution screenshot: ${(System.nanoTime() - start) / 1_000_000} ms; ${image.width} x ${image.height}")
        assertTrue(image.width > 100)
        assertTrue(image.height > 100)
    }
}
