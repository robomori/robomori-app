package com.robomori

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals

class GraphicsCrashTest {
    @TempDir lateinit var root: File

    @Test fun `a WebView crash on an Android 17 emulator with hardware graphics is reported`() = runBlocking {
        val fake = FakeAndroid(root)
        fake.device("Pixel_10", api = "37.0")
        val crash = "11-16 20:17:32.491  2962  3707 F chromium: [FATAL:ui/gl/gl_version_info.cc:73] " +
            "Chrome runs only on top of OpenGL ES through either ANGLE or native: VERSION = \n"
        val executor = CommandExecutor { command, cancelled: AtomicBoolean, output ->
            if ("logcat" in command.args && "chromium:F" in command.args) {
                output(crash)
                while (!cancelled.get() && !Thread.currentThread().isInterrupted) Thread.sleep(20)
                ""
            } else fake.run(command, cancelled, output)
        }
        val state = AppState(this, initial = fake.settings, executor = executor)
        state.refresh()
        state.launch(state.devices.single())
        withTimeout(5_000) { while (state.starting.isNotEmpty()) delay(10) }
        val watcher = state.watchGraphicsCrashes()
        try {
            assertEquals("Pixel 10", withTimeout(10_000) { state.graphicsCrashes.first() })
        } finally { watcher.cancel() }
    }
}
