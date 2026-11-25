package com.robomori

import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class CaptureNoticeTest {
    @TempDir lateinit var root: File
    private val phone = ConnectedDevice("USB123", "Pixel", "device")

    private var shutters = 0

    private fun state(scope: CoroutineScope, screenshot: () -> File): AppState {
        val fake = FakeAndroid(root)
        val capture = object : DeviceCapture {
            override fun screenshot(settings: AppSettings, serial: String, deviceName: String, deviceType: DeviceType, clean: Boolean) = screenshot()
            override fun startRecording(settings: AppSettings, serial: String, deviceName: String, deviceType: DeviceType, mirror: DeviceMirror?, clean: Boolean): Recording =
                error("Unexpected recording")
        }
        return AppState(scope, initial = fake.settings, executor = fake, capture = capture,
            license = licensedForTest(scope, File(root, "license")), shutterSound = { shutters++ })
    }

    @Test fun `saved screenshot emits its file as data`(): Unit = runBlocking {
        val file = File(root, "capture.png")
        val state = state(this) { file }
        state.captureScreenshot(phone)
        coroutineContext.job.children.toList().joinAll()
        assertEquals(CaptureNotice.Saved(CaptureKind.Screenshot, file), state.captureNotices[phone.name])
    }

    @Test fun `each screenshot clicks the shutter and flashes the screen before it is saved`(): Unit = runBlocking {
        val state = state(this) { assertTrue(shutters > 0); File(root, "capture.png") }
        repeat(2) {
            state.captureScreenshot(phone)
            coroutineContext.job.children.toList().joinAll()
        }
        assertEquals(2, shutters)
        assertEquals(2, state.screenshotsTaken[phone.name])
    }

    @Test fun `failed screenshot emits a screenshot failure`(): Unit = runBlocking {
        val cause = IllegalStateException()
        val state = state(this) { throw cause }
        state.captureScreenshot(phone)
        coroutineContext.job.children.toList().joinAll()
        val failure = assertIs<CaptureNotice.Failed>(state.captureNotices[phone.name])
        assertEquals(CaptureStep.Screenshot, failure.step)
        assertIs<IllegalStateException>(failure.cause)
    }
}
