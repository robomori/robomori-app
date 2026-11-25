package com.robomori

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RestartToUpdateTest {
    @Test fun `on a Mac the app bundle reopens once this process has quit`() {
        // The bundle's path is written as this computer writes paths, so the test also passes when run on Windows.
        val bundle = File("/Applications/Robomori.app")
        assertEquals(listOf("/bin/sh", "-c", "while kill -0 42 2>/dev/null; do sleep 0.2; done; open -n '${bundle.path}'"),
            relaunchCommand(HostOs.MAC, 42, bundle, null, null))
    }

    @Test fun `on Linux the AppImage starts again`() {
        assertEquals(listOf("/bin/sh", "-c", "while kill -0 42 2>/dev/null; do sleep 0.2; done; exec '/home/a/Robo'\\''s.AppImage'"),
            relaunchCommand(HostOs.LINUX, 42, null, "/home/a/Robo's.AppImage", null))
    }

    @Test fun `on Windows the launcher starts again`() {
        assertEquals(listOf("powershell", "-NoProfile", "-Command",
            "Wait-Process -Id 42 -ErrorAction SilentlyContinue; Start-Process 'C:\\Program Files\\Robomori\\Robomori.exe'"),
            relaunchCommand(HostOs.WINDOWS, 42, null, null, "C:\\Program Files\\Robomori\\Robomori.exe"))
    }

    @Test fun `apps that aren't installed are not relaunched`() {
        assertNull(relaunchCommand(HostOs.MAC, 42, null, null, null))
        assertNull(relaunchCommand(HostOs.LINUX, 42, null, "", null))
        assertNull(relaunchCommand(HostOs.WINDOWS, 42, null, null, "C:\\jdk\\bin\\java"))
    }
}
