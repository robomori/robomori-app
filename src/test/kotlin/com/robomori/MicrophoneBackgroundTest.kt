package com.robomori

import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

class MicrophoneBackgroundTest {
    @TempDir lateinit var root: File

    @Test fun `the forwarder starts through the installed app so the system asks for the microphone on its behalf`() {
        assertEquals(listOf("/bin/launchctl", "submit", "-l", MICROPHONE_FORWARDER_JOB, "--",
            File("/Applications/Robomori.app/Contents/MacOS/Robomori").path, "cli", FORWARD_MICROPHONE_ARGUMENT),
            microphoneForwarderCommand(HostOs.MAC, bundle = File("/Applications/Robomori.app"), java = "/jdk/bin/java"))
        assertEquals(listOf("/apps/Robomori.AppImage", "cli", FORWARD_MICROPHONE_ARGUMENT),
            microphoneForwarderCommand(HostOs.LINUX, bundle = null, appImage = "/apps/Robomori.AppImage", java = "/jdk/bin/java"))
        assertEquals(listOf(File(File("C:/Robomori"), "Robomori.exe").path, "cli", FORWARD_MICROPHONE_ARGUMENT),
            microphoneForwarderCommand(HostOs.WINDOWS, bundle = null, appPath = "C:/Robomori/robomori-cli.exe", java = "/jdk/bin/java"))
    }

    @Test fun `a development run starts its own classes`() {
        assertEquals(listOf("/jdk/bin/java", "-cp", "classes", "com.robomori.MainKt", "cli", FORWARD_MICROPHONE_ARGUMENT),
            microphoneForwarderCommand(HostOs.MAC, bundle = null, appImage = null, appPath = null, java = "/jdk/bin/java", classPath = "classes"))
    }

    @Test fun `launching starts the forwarder only when a microphone is chosen`() {
        val fake = FakeAndroid(root)
        val service = AndroidService(fake.settings, fake)
        service.launch(fake.device("Phone"))
        assertTrue(fake.commands.none { FORWARD_MICROPHONE_ARGUMENT in it.args })

        service.launch(fake.device("Tablet"), media = EmulatorMedia(microphone = "MacBook Pro Microphone"))
        assertTrue(fake.commands.any { it.detached && FORWARD_MICROPHONE_ARGUMENT in it.args })
    }

    @Test fun `picking a microphone while emulators run starts the forwarder`() = runBlocking {
        val fake = FakeAndroid(root)
        fake.device("Phone")
        val state = AppState(this, initial = fake.settings, executor = fake)
        state.changeMicrophone("MacBook Pro Microphone")
        state.refresh()
        assertTrue(fake.commands.none { FORWARD_MICROPHONE_ARGUMENT in it.args })

        state.launch(state.devices.single())
        withTimeout(5_000) { while (state.starting.isNotEmpty()) delay(10) }
        fake.commands.clear()
        state.changeMicrophone(null)
        state.changeMicrophone("AirPods Pro")
        withTimeout(5_000) { while (fake.commands.none { FORWARD_MICROPHONE_ARGUMENT in it.args }) delay(10) }
        assertEquals(1, fake.commands.count { FORWARD_MICROPHONE_ARGUMENT in it.args })
    }

    private fun forward(fake: FakeAndroid, store: EmulatorMediaStore, opened: MutableList<String> = mutableListOf()) = runBlocking {
        withTimeout(5_000) {
            forwardMicrophoneWhileEmulatorsRun(android = { AndroidService(fake.settings, fake) }, mediaStore = store,
                microphones = { listOf("MacBook Pro Microphone") }, connections = EmulatorConnections { null },
                openSink = { opened += it; null }, openInput = { null },
                idleLimit = 100.milliseconds, checkInterval = 10.milliseconds)
        }
    }

    @Test fun `the forwarder ends right away without a chosen microphone`() {
        val fake = FakeAndroid(root)
        fake.active["Phone"] = "emulator-5554"
        forward(fake, EmulatorMediaStore(File(root, "emulator-media.properties")))
    }

    @Test fun `the forwarder ends once no emulator has run for a while`() {
        val store = EmulatorMediaStore(File(root, "emulator-media.properties")).also { it.save(EmulatorMedia(microphone = "MacBook Pro Microphone")) }
        forward(FakeAndroid(root), store)
    }

    @Test fun `the forwarder keeps going while emulators run and ends when the choice goes back to the default`() = runBlocking {
        val fake = FakeAndroid(root)
        fake.active["Phone"] = "emulator-5554"
        val store = EmulatorMediaStore(File(root, "emulator-media.properties")).also { it.save(EmulatorMedia(microphone = "MacBook Pro Microphone")) }
        val forwarding = launch(Dispatchers.Default) { forward(fake, store) }
        delay(300)
        assertTrue(forwarding.isActive)
        store.save(EmulatorMedia())
        withTimeout(5_000) { forwarding.join() }
    }

    @Test fun `only one forwarder runs at a time`() {
        val file = File(root, "microphone-forwarder.lock")
        val first = assertNotNull(exclusiveLock(file))
        assertNull(exclusiveLock(file))
        first.close()
        assertNotNull(exclusiveLock(file)).close()
        assertTrue(file.delete(), "Nothing keeps the lock file open")
    }
}
