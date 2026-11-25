package com.robomori

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class LocalhostPortsTest {
    @TempDir lateinit var root: File

    @Test fun `devices reach 3000 and 8080 until other ports are chosen`() {
        val store = LocalhostPortsStore(File(root, "localhost-ports.properties"))
        assertEquals(listOf(3000, 8080), store.load())
        assertFalse(store.chosen())

        store.save(listOf(5173))
        assertEquals(listOf(5173), store.load())
        store.save(emptyList())
        assertEquals(emptyList(), store.load(), "Removing every port leaves none, not the defaults.")

        store.reset()
        assertEquals(listOf(3000, 8080), store.load())
    }

    @Test fun `ports are read the way people type them`() {
        assertEquals(3000, parsePort(" 3000 ").getOrThrow())
        assertEquals(3000, parsePort("localhost:3000").getOrThrow())
        assertTrue(parsePort("0").isFailure)
        assertTrue(parsePort("70000").isFailure)
        assertTrue(parsePort("http").isFailure)
        assertEquals(listOf(3000, 8080), parsePorts("3000, 8080,3000"))
        assertEquals(emptyList(), parsePorts(""))
    }

    @Test fun `adb's reverse list is read as device port to computer port`() {
        val output = "emulator-5554 tcp:3000 tcp:3000\nUsbFfs tcp:8081 tcp:8081\n(reverse) tcp:9000 tcp:4000\n"
        assertEquals(mapOf(3000 to 3000, 8081 to 8081, 9000 to 4000), parseReverseRules(output))
        assertEquals(emptyMap(), parseReverseRules(""))
    }

    @Test fun `ports are set up on a device without touching rules other tools made`() {
        val adb = mutableListOf<List<String>>()
        val existing = "UsbFfs tcp:8081 tcp:8081\nUsbFfs tcp:8080 tcp:4000\nUsbFfs tcp:5000 tcp:5000\n"
        val android = AndroidService(AppSettings(sdk = root.path, avds = root.path), CommandExecutor { command, _, _ ->
            adb += command.args
            if (command.args.takeLast(2) == listOf("reverse", "--list")) existing else ""
        })

        // 5000 was one of ours before; 8081 belongs to another tool.
        android.reverseLocalhostPorts("emulator-5554", listOf(3000, 8080), dropped = listOf(5000))

        val changes = adb.filter { "--list" !in it }
        assertEquals(listOf(
            // 5000 is no longer wanted, so it goes; 8081 isn't ours, so it stays.
            listOf("-s", "emulator-5554", "reverse", "--remove", "tcp:5000"),
            // 8080 already goes somewhere else on this device, so it's left as it is.
            listOf("-s", "emulator-5554", "reverse", "tcp:3000", "tcp:3000"),
        ), changes)
    }

    @Test fun `only devices adb can use get the ports`() {
        val android = AndroidService(AppSettings(sdk = root.path, avds = root.path), CommandExecutor { command, _, _ ->
            if (command.args == listOf("devices")) "List of devices attached\nemulator-5554\tdevice\nR58M\toffline\n2B24\tunauthorized\n192.168.1.5:5555\tdevice\n" else ""
        })
        File(root, "platform-tools/" + HostPlatform().executable("adb")).apply { parentFile.mkdirs(); writeText(""); setExecutable(true) }
        assertEquals(listOf("emulator-5554", "192.168.1.5:5555"), android.onlineSerials())
    }
}
