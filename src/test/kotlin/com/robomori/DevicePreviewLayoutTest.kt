package com.robomori

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DevicePreviewLayoutTest {
    @TempDir lateinit var root: File

    @Test fun `focus dimensions follow the requested rotation before the stream catches up`() = runBlocking {
        val fake = FakeAndroid(root)
        val device = fake.device("Phone")
        fake.active[device.name] = "emulator-5554"
        val state = AppState(this, initial = fake.settings, executor = fake)
        state.refresh()
        state.selectPreview(device)
        state.frameRotations[device.directory.absolutePath] = 0
        val portrait = state.focusDeviceSize()!!
        state.rotations[device.name] = EmulatorRotation(1, 0, "emulator-5554")
        assertEquals(DpSize(portrait.height, portrait.width), state.focusDeviceSize())
    }

    @Test fun `focus dimensions ignore rotation requests from a previous emulator session`() = runBlocking {
        val fake = FakeAndroid(root)
        val device = fake.device("Phone")
        fake.active[device.name] = "emulator-5556"
        val state = AppState(this, initial = fake.settings, executor = fake)
        state.refresh()
        state.selectPreview(device)
        val portrait = state.focusDeviceSize()
        state.rotations[device.name] = EmulatorRotation(1, 0, "emulator-5554")
        assertEquals(portrait, state.focusDeviceSize())
    }

    @Test fun `portrait fills the available height while preserving its proportions`() {
        assertEquals(DpSize(300.dp, 600.dp), fitDevicePreview(DpSize(900.dp, 600.dp), DpSize(100.dp, 200.dp)))
    }

    @Test fun `landscape fits its rotated bounds`() {
        val size = fitDevicePreview(DpSize(900.dp, 600.dp), DpSize(100.dp, 200.dp), 90f)
        assertEquals(450f, size.width.value, .001f)
        assertEquals(900f, size.height.value, .001f)
    }

    @Test fun `square watch fills the shorter viewport dimension`() {
        assertEquals(DpSize(600.dp, 600.dp), fitDevicePreview(DpSize(900.dp, 600.dp), DpSize(100.dp, 100.dp)))
    }

    @Test fun `bezel stays inside the viewport`() {
        val size = fitDevicePreview(DpSize(900.dp, 600.dp), DpSize(200.dp, 100.dp), bezel = 9.dp)
        assertEquals(DpSize(882.dp, 441.dp), size)
    }

    @Test fun `surface remains inside the viewport during rotation`() {
        val size = fitDevicePreview(DpSize(900.dp, 600.dp), DpSize(100.dp, 200.dp), 45f, 9.dp)
        val angle = Math.toRadians(45.0)
        val width = (size.width.value + 18) * abs(cos(angle)) + (size.height.value + 18) * abs(sin(angle))
        val height = (size.width.value + 18) * abs(sin(angle)) + (size.height.value + 18) * abs(cos(angle))
        assertTrue(width <= 900.001 && height <= 600.001)
    }

    @Test fun `television fills available width`() {
        val size = fitDevicePreview(DpSize(800.dp, 600.dp), DpSize(1920.dp, 1080.dp))
        assertEquals(DpSize(800.dp, 450.dp), size)
    }
}
