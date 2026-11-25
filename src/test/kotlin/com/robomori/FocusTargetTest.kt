package com.robomori

import androidx.compose.runtime.snapshots.Snapshot
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class FocusTargetTest {
    @TempDir lateinit var root: File

    @Test fun `opening focus keeps the workspace selection unchanged`() = runBlocking {
        val fake = FakeAndroid(root)
        val device = fake.device("Phone")
        val state = AppState(this, initial = fake.settings, executor = fake,
            license = licensedForTest(this, File(root, "license")))
        state.refresh()
        state.selectDevice(device)

        assertNotNull(state.openFocus())

        assertEquals(setOf(device.name), state.selected)
        assertEquals(listOf(device.name), state.focusedDevices.map { it.name })
    }

    @Test fun `focus remains attached to its device after workspace selection changes`() = runBlocking {
        val fake = FakeAndroid(root)
        val first = fake.device("First")
        val second = fake.device("Second")
        val state = AppState(this, initial = fake.settings, executor = fake,
            license = licensedForTest(this, File(root, "license")))
        state.refresh()
        state.selectDevice(first)
        state.openFocus()

        state.selectDevice(second)

        assertEquals(listOf(first.name), state.focusedDevices.map { it.name })
        assertEquals(second.name, state.previewDevice?.name)
    }

    @Test fun `several devices stay open in their own windows`() = runBlocking {
        val fake = FakeAndroid(root)
        val first = fake.device("First")
        val second = fake.device("Second")
        val state = AppState(this, initial = fake.settings, executor = fake,
            license = licensedForTest(this, File(root, "license")))
        state.refresh()
        state.selectDevice(first)
        state.openFocus()
        state.selectDevice(second)
        state.openFocus()
        state.openFocus()

        assertEquals(listOf(first.name, second.name), state.focusedDevices.map { it.name })
        state.closeFocus(state.focusedDevices.first())
        assertEquals(listOf(second.name), state.focusedDevices.map { it.name })
    }

    @Test fun `closing focus leaves the selected emulator running`() = runBlocking {
        val fake = FakeAndroid(root)
        val device = fake.device("Running")
        fake.active[device.name] = "emulator-5554"
        val state = AppState(this, initial = fake.settings, executor = fake,
            license = licensedForTest(this, File(root, "license")))
        state.refresh()
        state.openFocus()

        state.closeAllFocus()

        assertTrue(state.focusTargets.isEmpty())
        assertEquals("emulator-5554", state.running[device.name])
    }

    @Test fun `removed device is no longer a focus target`() = runBlocking {
        val fake = FakeAndroid(root)
        val device = fake.device("Removed")
        val state = AppState(this, initial = fake.settings, executor = fake,
            license = licensedForTest(this, File(root, "license")))
        state.refresh()
        state.openFocus()

        device.directory.deleteRecursively()
        File(fake.settings.avds, "Removed.ini").delete()
        state.refresh()

        assertTrue(state.focusedDevices.isEmpty())
    }

    @Test fun `workspace and focus share one stream until the workspace selects another device`(): Unit = runBlocking {
        withTimeout(5_000) {
            val fake = FakeAndroid(root)
            val first = fake.device("First")
            val second = fake.device("Second")
            fake.active[first.name] = "emulator-5554"
            fake.active[second.name] = "emulator-5556"
            val started = Channel<String>(Channel.UNLIMITED)
            val stopped = Channel<String>(Channel.UNLIMITED)
            val display = object : DeviceDisplay by AdbDeviceDisplay() {
                override fun stream(settings: AppSettings, device: Device, serial: String) = flow<EmulatorFrame> {
                    started.send(device.name)
                    try { awaitCancellation() }
                    finally { stopped.trySend(device.name) }
                }
            }
            val state = AppState(this, initial = fake.settings, executor = fake, display = display,
                license = licensedForTest(this, File(root, "license")))
            state.refresh()
            state.selectDevice(first)
            state.openFocus()
            val observer = launch { state.observeDisplays() }
            try {
                assertEquals(first.name, started.receive())
                Snapshot.withMutableSnapshot { state.selectDevice(second) }
                Snapshot.sendApplyNotifications()
                assertEquals(second.name, started.receive())
                assertTrue(stopped.tryReceive().isFailure, "Focus keeps the first stream alive")
                assertTrue(started.tryReceive().isFailure, "The first device must not have duplicate streams")

                Snapshot.withMutableSnapshot { state.closeAllFocus() }
                Snapshot.sendApplyNotifications()
                assertEquals(first.name, stopped.receive())
                assertEquals(second.name, state.previewDevice?.name)
            } finally {
                observer.cancelAndJoin()
            }
        }
    }
}
