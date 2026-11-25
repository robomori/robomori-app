package com.robomori

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CliAgentHelpersTest {
    private fun emulator(name: String, type: DeviceType, api: String, hardware: String, running: Boolean = false) = CliDevice(
        name = name, displayName = name, physical = false, type = type, api = api, android = "Android $api",
        serial = if (running) "emulator-$name" else null,
        state = if (running) CliDevice.State.RUNNING else CliDevice.State.NOT_RUNNING,
        emulator = Device(name, File(name), mapOf("hw.device.name" to hardware), 0),
    )

    @Test
    fun `suggests the stopped emulator most like the running ones, not the first`() {
        val tv = emulator("Android_TV", DeviceType.TV, "36", "tv_1080p")
        val watch = emulator("Watch", DeviceType.WEAR, "37", "pixel_watch")
        val olderPhone = emulator("Pixel_10_Android_16", DeviceType.PHONE, "36", "pixel_10")
        val samePhone = emulator("Pixel_10", DeviceType.PHONE, "37", "pixel_10")
        val running = listOf(emulator("Agent_A", DeviceType.PHONE, "37", "pixel_10", running = true))
        assertSame(samePhone, mostAlike(listOf(tv, watch, olderPhone, samePhone), running))
        assertSame(olderPhone, mostAlike(listOf(tv, watch, olderPhone), running))
    }

    @Test
    fun `suggests a phone when nothing runs`() {
        val tv = emulator("Android_TV", DeviceType.TV, "36", "tv_1080p")
        val phone = emulator("Pixel_10", DeviceType.PHONE, "37", "pixel_10")
        assertSame(phone, mostAlike(listOf(tv, phone), emptyList()))
    }

    private fun png(width: Int, height: Int) =
        ByteArrayOutputStream().also { ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()

    @Test
    fun `scales a screenshot's shorter side to the size asked for, keeping its shape`() {
        val image = ImageIO.read(ByteArrayInputStream(scaledDown(png(1080, 2424), 540)))
        assertEquals(540, image.width)
        assertEquals(1212, image.height)
    }

    @Test
    fun `scales a sideways screenshot by its shorter side too`() {
        val image = ImageIO.read(ByteArrayInputStream(scaledDown(png(2424, 1080), 540)))
        assertEquals(1212, image.width)
        assertEquals(540, image.height)
    }

    @Test
    fun `leaves a screenshot that's already narrow enough as it is`() {
        val original = png(400, 800)
        assertSame(original, scaledDown(original, 540))
    }

    @Test
    fun `names tools instead of commands in what the MCP server says`() {
        assertEquals("crash com.example: boom\nThe logs tool, with app com.example, shows the whole stack trace.",
            McpServerText.forTools("crash com.example: boom\nrobomori app logs com.example shows the whole stack trace."))
        assertEquals("Nothing matches \"About\". A list has more down: scroll down with until \"About\"",
            McpServerText.forTools("Nothing matches \"About\". A list has more down: robomori input scroll down --until \"About\""))
        assertEquals("No text field has focus. Name one with into, such as \"@2\".",
            McpServerText.forTools("No text field has focus. Name one with --into, such as --into @2."))
        assertEquals("Every running device is claimed. Start another emulator, then call the tool again.",
            McpServerText.forTools("Every running device is claimed. Start another emulator, then run 'robomori device claim'."))
    }

    @Test
    fun `earlier command names still work as the commands they now are`() {
        assertEquals(listOf("input", "tap", "@3", "--quiet"), CliHelp.canonical(listOf("tap", "@3", "--quiet")))
        assertEquals(listOf("screen", "shot", "-o", "a.png"), CliHelp.canonical(listOf("screenshot", "-o", "a.png")))
        assertEquals(listOf("device", "location", "clear"), CliHelp.canonical(listOf("simulate", "location", "clear")))
        assertEquals(listOf("apps"), CliHelp.canonical(listOf("apps")), "Short names that never shipped are gone")
        assertEquals(listOf("device", "claim"), CliHelp.canonical(listOf("device", "claim")), "Current names stay as they are")
    }

    @Test
    fun `help lists every command under a group, with no earlier names`() {
        val help = CliHelp.text(emptyList())!!
        listOf("input tap", "screen read", "screen shot", "app logs", "device rotate", "device location", "device clipboard").forEach { assertTrue(it in help, it) }
        listOf("\n  tap ", "\n  screenshot ", "\n  rotate ", "simulate location").forEach { assertFalse(it in help, it) }
        assertTrue(CliHelp.text(listOf("input"))!!.contains("Commands:\n  tap "), "Most used first")
    }
}
