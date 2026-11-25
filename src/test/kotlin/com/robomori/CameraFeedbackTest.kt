package com.robomori

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

class CameraFeedbackTest {
    @TempDir lateinit var root: File

    private class Recorder : Analytics {
        val events = mutableListOf<Pair<String, Map<String, String>>>()
        override fun track(event: String, metadata: Map<String, String>) { events += event to metadata }
        override fun pageview(path: String) {}
    }

    private val inUse = """
        Active Camera Clients:
        [
        (Camera ID: 10, Cost: 100, PID: 3999, Score: 0, State: 2User Id: 0, Client Package Name: com.robomori.fullcam, Conflicting Client Devices: {})
        ]
    """.trimIndent()

    @Test fun `apps holding a camera are read from the camera service`() {
        assertEquals(listOf("com.robomori.fullcam"), cameraClients(inUse))
        assertEquals(emptyList(), cameraClients("Active Camera Clients:\n[]\nAllowed user IDs: 0\n"))
        assertEquals(emptyList(), cameraClients(""))
    }

    @Test fun `opening a camera fed by the computer's camera asks for feedback and the answer is tracked`() = runBlocking {
        val fake = FakeAndroid(root)
        fake.active["Phone"] = "emulator-5554"
        val executor = CommandExecutor { command, cancelled: AtomicBoolean, output ->
            when {
                command.args == listOf("-webcam-list") -> " Camera 'FaceTime HD Camera' can be specified by label as 'webcam0' or by id as '1F06'"
                command.args.any { it.startsWith("dumpsys media.camera") } -> inUse
                else -> fake.run(command, cancelled, output)
            }
        }
        val analytics = Recorder()
        val state = AppState(this, initial = fake.settings, executor = executor, analytics = analytics)
        state.refresh()

        val watching = state.watchCameraUse()
        withTimeout(15_000) { while (state.cameraFeedbackDevice == null) delay(50) }

        assertEquals("Phone", state.cameraFeedbackDevice)
        state.answerCameraFeedback(good = true)
        assertNull(state.cameraFeedbackDevice)
        assertEquals(listOf(CameraFeedbackEvent.Name to mapOf("rating" to CameraFeedbackEvent.Up)), analytics.events)

        // The same camera session staying open does not ask again.
        delay(4_000)
        assertNull(state.cameraFeedbackDevice)
        watching.cancel()
    }
}
