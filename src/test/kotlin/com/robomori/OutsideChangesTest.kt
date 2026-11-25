package com.robomori

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class OutsideChangesTest {
    @TempDir lateinit var root: File

    @Test fun `running emulators are counted by their announcement files`() {
        val avds = File(root, "avds").apply { mkdirs() }
        val running = File(root, "running").apply { mkdirs() }
        File(avds, "Phone.ini").writeText("path=x")
        File(running, "pid_42.ini").writeText("")
        File(running, "pid_42_info.ini").writeText("")
        File(running, "pid_7.ini").writeText("")
        val files = emulatorFiles(avds, listOf(running, File(root, "missing")))
        assertEquals(4, files.size)
        assertEquals(2, runningEmulators(files))
    }
}
