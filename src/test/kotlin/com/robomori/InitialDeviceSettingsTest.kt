package com.robomori

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

class InitialDeviceSettingsTest {
    @TempDir lateinit var root: File

    @Test fun `new foldable disables the lock screen on its first launch`() = runBlocking {
        val fake = FakeAndroid(root)
        val service = AndroidService(fake.settings, fake)
        createFoldable(service)
        val state = AppState(this, initial = fake.settings, executor = fake)
        state.refresh()

        state.launch(state.devices.single())
        withTimeout(5_000) { while (state.starting.isNotEmpty()) delay(10) }

        assertTrue(fake.commands.any { it.args == disableLockScreen })
        assertFalse(service.devices().single().needsInitialSetup)
    }

    @Test fun `initial settings do not overwrite later lock screen choices`() {
        val fake = FakeAndroid(root)
        val service = AndroidService(fake.settings, fake)
        createFoldable(service)
        val device = service.devices().single()
        service.configureInitialSettings(device, "emulator-5554")
        fake.commands.clear()

        // Use the original device snapshot too: the persisted marker is authoritative.
        service.configureInitialSettings(device, "emulator-5554")

        assertTrue(fake.commands.isEmpty())
    }

    @Test fun `failed lock screen setup remains pending for retry`() {
        val fake = FakeAndroid(root)
        val service = AndroidService(fake.settings, CommandExecutor { command, cancelled, output ->
            check(command.args != disableLockScreen) { "Device disconnected" }
            fake.run(command, cancelled, output)
        })
        createFoldable(service)

        assertFailsWith<IllegalStateException> {
            service.configureInitialSettings(service.devices().single(), "emulator-5554")
        }

        assertTrue(service.devices().single().needsInitialSetup)
    }

    @Test fun `existing foldables keep their lock screen settings`() {
        val fake = FakeAndroid(root)
        val device = fake.device("Existing_Fold", profile = "pixel_10_pro_fold")
        fake.commands.clear()

        AndroidService(fake.settings, fake).configureInitialSettings(device, "emulator-5554")

        assertTrue(fake.commands.isEmpty())
    }

    private fun createFoldable(service: AndroidService) {
        val profile = HardwareProfiles.all.single { it.id == "pixel-10-pro-fold" }
        val image = SystemImage("system-images;android-37;google_apis;${nativeImageAbi()!!}", "Android", true)
        service.create(profile.createSpec("Test Fold", image, emptyList()), AtomicBoolean()) {}
    }

    private val disableLockScreen = listOf("-s", "emulator-5554", "shell", "locksettings", "set-disabled", "true")
}
