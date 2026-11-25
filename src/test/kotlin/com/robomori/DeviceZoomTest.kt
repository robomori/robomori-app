package com.robomori

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DeviceZoomTest {
    @Test fun `zooming steps through the levels from fit`() {
        assertEquals(1.25f, zoomedIn(1f))
        assertEquals(0.9f, zoomedOut(1f))
    }

    @Test fun `zooming stops at the smallest and largest levels`() {
        assertEquals(DeviceZoomLevels.last(), zoomedIn(DeviceZoomLevels.last()))
        assertEquals(DeviceZoomLevels.first(), zoomedOut(DeviceZoomLevels.first()))
    }

    @Test fun `a zoom between levels moves to the next one`() {
        assertEquals(1.5f, zoomedIn(1.3f))
        assertEquals(1.25f, zoomedOut(1.3f))
    }

    @Test fun `the app zooms a running device in, out and back to fit`() = runBlocking {
        val fake = FakeAndroid(createTempDirectory().toFile())
        val device = fake.device("Phone")
        fake.active[device.name] = "emulator-5554"
        val state = AppState(this, initial = fake.settings, executor = fake)
        state.refresh()
        state.selectDevice(device)
        state.zoomIn(); state.zoomIn()
        assertEquals(1.5f, state.previewZoom)
        state.zoomOut()
        assertEquals(1.25f, state.previewZoom)
        state.zoomToFit()
        assertEquals(1f, state.previewZoom)
    }

    @Test fun `a stopped device can't be zoomed`() = runBlocking {
        val fake = FakeAndroid(createTempDirectory().toFile())
        val device = fake.device("Phone")
        val state = AppState(this, initial = fake.settings, executor = fake)
        state.refresh()
        state.selectDevice(device)
        state.zoomIn()
        assertFalse(state.canZoom)
        assertEquals(1f, state.previewZoom)
    }
}
