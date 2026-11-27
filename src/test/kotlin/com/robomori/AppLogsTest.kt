package com.robomori

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile
import kotlin.test.*

class AppLogsTest {
    @TempDir lateinit var root: File
    private var time = LocalDateTime.of(2026, 11, 27, 10, 0)
    private fun logs(kept: Int = 20, maxBytes: Long = 1_000_000) =
        AppLogs(File(root, "logs"), { time.also { time = time.plusSeconds(1) } }, kept, maxBytes)

    @Test fun `each process run gets its own log, starting with its command`() {
        val log = logs().processLog("emulator-Pixel 9", listOf("emulator", "-avd", "Pixel_9"))
        assertEquals("emulator-Pixel_9-2026-11-27_10-00-00-000.log", log.name)
        assertTrue(log.readText().endsWith("emulator -avd Pixel_9\n"))
    }

    @Test fun `keeps only the newest process logs and never the app log`() {
        val logs = logs(kept = 2)
        logs.record("started")
        val files = (1..4).map { index -> logs.processLog("emulator", emptyList()).also { it.setLastModified(index * 1000L) } }
        logs.processLog("emulator", emptyList()).setLastModified(5000)
        val remaining = File(root, "logs").list()!!.toSet()
        assertEquals(3, remaining.size)
        assertTrue(AppLogs.APP_LOG in remaining)
        assertFalse(files[0].exists())
    }

    @Test fun `a full app log is moved aside instead of growing`() {
        val logs = logs(maxBytes = 10)
        logs.record("first message is long")
        logs.record("second")
        assertEquals(listOf("robomori.log", "robomori.old.log"), File(root, "logs").list()!!.sorted())
        assertTrue(File(root, "logs/robomori.log").readText().endsWith("second\n"))
    }

    @Test fun `records failures with their stack trace`() {
        logs().record("Could not launch Pixel", IllegalStateException("no hypervisor"))
        assertContains(File(root, "logs/robomori.log").readText(), "java.lang.IllegalStateException: no hypervisor")
    }

    @Test fun `exports every log and the diagnostics into one zip`() {
        val logs = logs()
        logs.record("failure")
        logs.processLog("emulator-Pixel", listOf("emulator")).appendText("HAXM is not installed\n")
        val target = File(root, "export.zip")
        logs.export(target, "Robomori 1.10.2")
        ZipFile(target).use { zip ->
            assertEquals(setOf("diagnostics.txt", "robomori.log", "emulator-Pixel-2026-11-27_10-00-01-000.log"), zip.entries().toList().map { it.name }.toSet())
            assertEquals("Robomori 1.10.2", zip.getInputStream(zip.getEntry("diagnostics.txt")).reader().readText())
        }
    }
}

class ProcessLogTest {
    @TempDir lateinit var root: File

    @Test fun `a detached process's output goes to its log`() {
        val logs = AppLogs(File(root, "logs"))
        val java = File(System.getProperty("java.home"), "bin/" + HostPlatform().executable("java"))
        ProcessExecutor(logs).run(Command(java, listOf("-version"), detached = true, log = "java"), AtomicBoolean()) {}
        val deadline = System.currentTimeMillis() + 10_000
        fun output() = File(root, "logs").listFiles().orEmpty().singleOrNull()?.readText().orEmpty()
        while ("version" !in output().lines().drop(1).joinToString() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertContains(output(), "-version\n")
        assertContains(output().lines().drop(1).joinToString("\n"), "version")
    }
}
