package com.robomori

import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class DevicePresetLocationsTest {
    @TempDir lateinit var root: File

    @Test fun `a failed optimistic location is not captured as a known previous coordinate`(): Unit = runBlocking {
        withTimeout(5000) {
            val commands = Collections.synchronizedList(mutableListOf<Command>())
            val executor = CommandExecutor { command, _, _ ->
                commands += command
                when {
                    command.args.last() == "ro.build.version.sdk" -> "37"
                    command.args.last() == "location_mode" -> "3"
                    "geo" in command.args -> "KO: location unavailable"
                    else -> ""
                }
            }
            val manager = DevicePresetLocations(app(this), executor)
            val target = DevicePresetTarget("emulator:fixture", "emulator-5554")
            val requested = PresetValue.Location(DeviceCoordinates(51.5, -0.1))
            assertFailsWith<IllegalStateException> { manager.apply(target, requested) }
            assertEquals(requested.coordinates, manager.state(target).selection)

            val support = assertIs<PresetSupport.Supported>(manager.support(target, requested))
            val previous = manager.capture(target)
            val beforeRestore = commands.size
            manager.restore(target, previous)

            assertNotNull(support.restoreLimit)
            assertNotNull(previous.limitation)
            assertNull(manager.confirmedCoordinates(target))
            assertNull(manager.currentValue(target))
            assertFalse(commands.drop(beforeRestore).any { "geo" in it.args },
                "An unconfirmed coordinate must not be sent as a restoration value")
        }
    }

    @Test fun `an emulator with location disabled can save an explicit None value`(): Unit = runBlocking {
        val executor = CommandExecutor { command, _, _ ->
            when (command.args.last()) {
                "ro.build.version.sdk" -> "37"
                "location_mode" -> "0"
                else -> ""
            }
        }
        val manager = DevicePresetLocations(app(this), executor)

        assertEquals(PresetValue.Location(null), manager.currentValue(DevicePresetTarget("emulator:fixture", "emulator-5554")))
    }

    @Test fun `closing physical controls during startup does not report the location as applied`(): Unit = runBlocking {
        withTimeout(5000) {
            val starting = CompletableDeferred<Unit>()
            val releaseReady = CountDownLatch(1)
            val executor = CommandExecutor { command, cancelled, output ->
                when {
                    command.args.last() == "ro.build.version.sdk" -> "37"
                    command.args.last() == "help" -> "add-test-provider set-test-provider-location"
                    command.args.last() == "location" -> "gps provider:\nnetwork provider:"
                    "is-location-enabled" in command.args -> "true"
                    "get" in command.args -> "No operations.\nDefault mode: deny"
                    "sh" in command.args -> {
                        starting.complete(Unit)
                        check(releaseReady.await(5, TimeUnit.SECONDS)) { "Test did not release provider startup" }
                        output("ROBOMORI_LOCATION_READY\n")
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                        while (!cancelled.get()) {
                            check(System.nanoTime() < deadline) { "Simulation did not stop" }
                            Thread.sleep(1)
                        }
                        throw CancellationException("Operation cancelled.")
                    }
                    else -> ""
                }
            }
            val manager = DevicePresetLocations(app(this), executor)
            val target = DevicePresetTarget("device:fixture", "physical-serial")
            manager.setActive(target, true)
            val application = async {
                runCatching { manager.apply(target, PresetValue.Location(DeviceCoordinates(51.5, -0.1))) }
            }
            try {
                starting.await()
                manager.setActive(target, false)
            } finally {
                releaseReady.countDown()
            }

            assertIs<IllegalStateException>(application.await().exceptionOrNull())
            assertNull(manager.confirmedCoordinates(target))
        }
    }

    private fun app(scope: CoroutineScope): AppState {
        val android = FakeAndroid(root)
        return AppState(scope, store = SettingsStore(File(root, "settings.properties")),
            initial = android.settings, executor = android,
            license = licensedForTest(scope, File(root, "license")) { _, _ -> error("No network in preset tests") })
    }
}
