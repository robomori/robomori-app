package com.robomori

import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import kotlin.test.*

/** One running emulator is free; more at once, bulk operations and workflow extras require a license. */
class LicensedFeaturesTest {
    @TempDir lateinit var root: File

    private fun launching(fake: FakeAndroid) = CommandExecutor { command, cancelled, output ->
        fake.run(command, cancelled, output).also {
            if (command.detached) fake.active[command.args[1]] = "emulator-${5554 + 2 * fake.active.size}"
        }
    }

    private fun CoroutineScope.demo(fake: FakeAndroid, executor: CommandExecutor = launching(fake)) =
        AppState(this, initial = fake.settings, executor = executor, license = expiredTrialForTest(this, File(root, "no-license")))

    @Test fun `one emulator runs without a license`() = runBlocking {
        val fake = FakeAndroid(root)
        fake.device("A")
        val state = demo(fake)
        state.refresh()
        state.launch(state.devices)
        coroutineContext.job.children.toList().joinAll()
        assertEquals(setOf("A"), state.running.keys)
        assertFalse(state.showDemoDialog)
    }

    @Test fun `a second emulator asks for a license while the first runs`() = runBlocking {
        val fake = FakeAndroid(root)
        fake.device("A"); fake.device("B")
        val state = demo(fake)
        state.refresh()
        state.launch(state.devices.filter { it.name == "A" })
        coroutineContext.job.children.toList().joinAll()
        state.launch(state.devices.filter { it.name == "B" })
        coroutineContext.job.children.toList().joinAll()
        assertTrue(state.showDemoDialog)
        assertTrue(state.starting.isEmpty())
        assertEquals(setOf("A"), state.running.keys)
    }

    @Test fun `starting several emulators at once asks for a license`() = runBlocking {
        val fake = FakeAndroid(root)
        fake.device("A"); fake.device("B")
        val state = demo(fake)
        state.refresh()
        state.launch(state.devices)
        assertTrue(state.showDemoDialog)
        assertTrue(fake.commands.none { it.detached })
    }

    @Test fun `an emulator launched elsewhere counts as the one that runs without a license`() = runBlocking {
        val fake = FakeAndroid(root)
        fake.device("A"); fake.device("Elsewhere")
        fake.active["Elsewhere"] = "emulator-5560"
        val state = demo(fake)
        state.refresh()
        state.launch(state.devices.filter { it.name == "A" })
        coroutineContext.job.children.toList().joinAll()
        assertEquals(setOf("Elsewhere"), state.running.keys)
        assertTrue(state.showDemoDialog)
    }

    @Test fun `another emulator can start after stopping the first without a license`() = runBlocking {
        val fake = FakeAndroid(root)
        fake.device("A"); fake.device("B")
        val state = demo(fake)
        state.refresh()
        state.launch(state.devices.filter { it.name == "A" })
        coroutineContext.job.children.toList().joinAll()
        fake.active.remove("A")
        state.refresh()
        state.launch(state.devices.filter { it.name == "B" })
        coroutineContext.job.children.toList().joinAll()
        assertEquals(setOf("B"), state.running.keys)
        assertFalse(state.showDemoDialog)
    }

    @ParameterizedTest
    @ValueSource(strings = ["toggle", "range", "all"])
    fun `selecting multiple devices asks for a license and preserves the selection`(method: String) = runBlocking {
        val fake = FakeAndroid(root)
        val a = fake.device("A"); val b = fake.device("B")
        val state = demo(fake)
        state.refresh()
        state.selectDevice(a)

        when (method) {
            "toggle" -> state.selectDevice(b, toggle = true)
            "range" -> state.selectDevice(b, extend = true)
            "all" -> state.selectAll(listOf(a.name, b.name))
        }

        assertTrue(state.showDemoDialog)
        assertEquals(setOf("A"), state.selected)
        assertEquals("A", state.previewName)
    }

    @Test fun `switching between individual devices does not ask for a license`() = runBlocking {
        val fake = FakeAndroid(root)
        val a = fake.device("A"); val b = fake.device("B")
        val state = demo(fake)
        state.refresh()
        state.selectDevice(a)
        state.selectDevice(b)
        assertEquals(setOf("B"), state.selected)
        assertFalse(state.showDemoDialog)
    }

    @ParameterizedTest
    @ValueSource(strings = ["start", "stop", "stopAll", "delete", "openDelete", "reveal"])
    fun `bulk actions require a license before changing anything`(action: String) = runBlocking {
        val fake = FakeAndroid(root)
        val devices = listOf(fake.device("A"), fake.device("B"))
        if (action == "stop" || action == "stopAll") {
            fake.active.putAll(mapOf("A" to "emulator-5554", "B" to "emulator-5556"))
        }
        val state = demo(fake)
        state.refresh()
        fake.commands.clear()

        when (action) {
            "start" -> state.launch(devices)
            "stop" -> state.stop(devices)
            "stopAll" -> state.stopAll()
            "delete" -> state.delete(devices)
            "openDelete" -> state.openDelete(devices)
            "reveal" -> state.reveal(devices)
        }
        coroutineContext.job.children.toList().joinAll()

        assertTrue(state.showDemoDialog)
        assertTrue(fake.commands.isEmpty())
        assertTrue(state.deviceOperations.isEmpty())
        assertTrue(state.deleteDevices.isEmpty())
        assertTrue(state.stopping.isEmpty())
    }

    @Test fun `cold boot is free for a single device`() = runBlocking {
        val fake = FakeAndroid(root)
        val device = fake.device("A")
        val state = demo(fake)
        state.refresh()
        state.launch(listOf(device), coldBoot = true)
        assertFalse(state.showDemoDialog)
    }

    @Test fun `restart requires a license for a single emulator`() = runBlocking {
        val fake = FakeAndroid(root)
        val device = fake.device("A")
        fake.active[device.name] = "emulator-5554"
        val state = demo(fake)
        state.refresh()
        fake.commands.clear()
        state.restart(device)
        assertTrue(state.showDemoDialog)
        assertTrue(fake.commands.isEmpty())
    }

    @ParameterizedTest
    @ValueSource(strings = ["pixel-10", "pixel-10-pro-fold", "pixel-tablet", "pixel-watch-4-41mm", "tv-1080p"])
    fun `any device type can be created for free while another emulator runs, and starting it asks for a license`(id: String) = runBlocking {
        val fake = FakeAndroid(root)
        val profile = HardwareProfiles.all.single { it.id == id }
        val tag = when (profile.type) {
            DeviceType.WEAR -> "android-wear-signed"
            DeviceType.TV -> "android-tv"
            else -> "google_apis_playstore"
        }
        val state = AppState(this, initial = fake.settings, executor = launching(fake),
            license = expiredTrialForTest(this, File(root, "no-license")), storagePlanner = { spec, settings ->
                calculateStoragePlan(settings, spec, ImageFootprint(0, 0)) { DiskSpace("disk", 512 * GIB) }
            })
        val existing = fake.device("Existing")
        state.refresh()
        state.launch(existing)
        coroutineContext.job.children.toList().joinAll()
        val spec = profile.createSpec("New device",
            SystemImage("system-images;android-37;$tag;${nativeImageAbi()!!}", "Android", true), state.devices.map(Device::displayName))

        state.create(spec)
        withTimeout(5_000) { while (state.creating || state.starting.isNotEmpty()) delay(10) }

        assertEquals(CreationState.Created, state.creation)
        assertTrue(state.devices.any { it.name == spec.name })
        assertEquals(setOf("Existing"), state.running.keys)
        assertTrue(state.showDemoDialog)
    }

    @ParameterizedTest
    @ValueSource(strings = ["pixel_10", "pixel_10_pro_fold", "pixel_tablet", "wear_os", "tv_1080p"])
    fun `single device deletion is free while another emulator is running`(profile: String) = runBlocking {
        val fake = FakeAndroid(root)
        val device = fake.device("Remove", profile = profile)
        fake.device("Other")
        fake.active["Other"] = "emulator-5554"
        val state = demo(fake)
        state.refresh()

        state.openDelete(listOf(device))
        assertEquals(listOf(device), state.deleteDevices)
        state.delete(listOf(device))
        coroutineContext.job.children.toList().joinAll()

        assertEquals(listOf("Other"), state.devices.map { it.name })
        assertEquals(setOf("Other"), state.running.keys)
        assertFalse(state.showDemoDialog)
    }

    @Test fun `stopping one emulator is free while another remains running`() = runBlocking {
        val fake = FakeAndroid(root)
        val a = fake.device("A"); fake.device("B")
        fake.active.putAll(mapOf("A" to "emulator-5554", "B" to "emulator-5556"))
        val state = demo(fake)
        state.refresh()
        state.stop(a)
        coroutineContext.job.children.toList().joinAll()
        assertEquals(setOf("B"), state.running.keys)
        assertFalse(state.showDemoDialog)
    }

    @Test fun `renaming and wiping an emulator are free`() = runBlocking {
        val fake = FakeAndroid(root)
        fake.device("A")
        val state = demo(fake)
        state.refresh()
        val device = state.devices.single()

        state.openRename(device)
        assertEquals(device, state.renameDevice)
        state.openWipeData(device)
        assertEquals(device, state.wipeDataDevice)
        assertFalse(state.showDemoDialog)
    }

    @Test fun `saving a preset asks for a license`() = runBlocking {
        // The preset workspace watches its folder until its scope ends.
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val state = scope.demo(FakeAndroid(root))
            assertFalse(state.presets.canSave())
            assertTrue(state.showDemoDialog)
        } finally { scope.cancel() }
    }

    @Test fun `screenshots and the keyboard toggle are free`() = runBlocking {
        val fake = FakeAndroid(root)
        fake.device("A")
        fake.active["A"] = "emulator-5554"
        val state = demo(fake)
        state.refresh()
        val device = state.devices.single()
        state.selectPreview(device)

        state.captureScreenshot(device)
        assertFalse(state.showDemoDialog)
        state.toggleSoftwareKeyboard()
        assertFalse(state.showDemoDialog)
    }

    @Test fun `renaming and restarting a connected device ask for a license`() = runBlocking {
        var locked = 0
        val connected = ConnectedDevicesState(this, { AppSettings(root.path, root.path) },
            DeviceNamesStore(File(root, "names")), PairedDevicesStore(File(root, "paired.json")), { TestDeviceMirror() },
            { AndroidService(it, CommandExecutor { _, _, _ -> error("No device commands without a license") }) },
            licensed = { false }, onLocked = { locked++ })
        val phone = ConnectedDevice("USB123", "Pixel", "device")
        connected.openRename(phone)
        connected.restart(phone)
        assertEquals(2, locked)
        assertNull(connected.renameTarget)
        assertEquals(OperationState.Idle, connected.action)
    }
}
