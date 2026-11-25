package com.robomori

import org.junit.jupiter.api.Test
import kotlinx.coroutines.*
import kotlin.test.*

class DeviceLocationTest {
    @Test fun `coordinate input accepts inclusive limits`() {
        assertEquals(DeviceCoordinates(-90.0, 180.0), coordinatesFromInput(" -90 ", "180"))
    }

    @Test fun `coordinate input rejects incomplete input`() {
        assertNull(coordinatesFromInput("51.5", ""))
    }

    @Test fun `coordinate input rejects latitude outside range`() {
        assertNull(coordinatesFromInput("90.1", "0"))
    }

    @Test fun `coordinate input rejects longitude outside range`() {
        assertNull(coordinatesFromInput("0", "-180.1"))
    }

    @Test fun `coordinate input rejects nonfinite values`() {
        assertNull(coordinatesFromInput("NaN", "0"))
        assertNull(coordinatesFromInput("0", "Infinity"))
    }

    @Test fun `city search finds a country without case sensitivity`() {
        assertEquals(listOf("Canberra"), findLocationCities(" AUSTRALIA ").map { it.name })
    }

    @Test fun `city search works without accents`() {
        assertEquals(listOf("São Paulo"), findLocationCities("sao paulo").map { it.name })
    }

    @Test fun `emulator command sends longitude before latitude to the selected serial`() {
        val commands = mutableListOf<Command>()
        val service = service { command ->
            commands += command
            if (command.args.last() == "ro.build.version.sdk") "37" else "OK\n"
        }

        service.deliver("emulator-5556", DeviceCoordinates(13.75, 100.5))

        assertEquals(listOf("-s", "emulator-5556", "emu", "geo", "fix", "100.5", "13.75"), commands.last().args)
        assertEquals(listOf("-s", "emulator-5556", "shell", "cmd", "location", "set-location-enabled", "true", "--user", "current"), commands[1].args)
    }

    @Test fun `emulator delivery rejects unacknowledged command`() {
        assertFailsWith<IllegalStateException> {
            service { if (it.args.last() == "ro.build.version.sdk") "37" else "KO: emulator unavailable" }
                .deliver("emulator-5554", DeviceCoordinates(0.0, 0.0))
        }
    }

    @Test fun `physical serial cannot use emulator delivery`() {
        var commands = 0
        assertFailsWith<IllegalArgumentException> {
            service { commands++; "" }.deliver("phone-serial", DeviceCoordinates(0.0, 0.0))
        }
        assertEquals(0, commands)
    }

    @Test fun `older physical device returns an unsupported capability without mutations`() {
        val commands = mutableListOf<Command>()
        val capability = service { command -> commands += command; "30" }.capability("phone")

        assertIs<DeviceLocationCapability.Unavailable>(capability)
        assertEquals(listOf("-s", "phone", "shell", "getprop", "ro.build.version.sdk"), commands.single().args)
    }

    @Test fun `physical device without provider commands is unsupported`() {
        val capability = service { command ->
            if (command.args.last() == "ro.build.version.sdk") "35" else "Location service commands: help"
        }.capability("phone")

        assertIs<DeviceLocationCapability.Unavailable>(capability)
    }

    @Test fun `physical device supports valid location help with Android exit 255`() {
        val capability = service { command ->
            when (command.args.last()) {
                "ro.build.version.sdk" -> "37"
                "help" -> throw AndroidCommandException("Location service commands:\nproviders add-test-provider set-test-provider-location", 255)
                else -> "gps provider:\nnetwork provider:"
            }
        }.capability("phone")

        assertEquals(DeviceLocationCapability.Mock, capability)
    }

    @Test fun `physical device does not suppress actual errors with help exit 255`() {
        val service = service { command ->
            if (command.args.last() == "ro.build.version.sdk") "37"
            else throw AndroidCommandException("Permission denied", 255)
        }

        assertFailsWith<AndroidCommandException> { service.capability("phone") }
    }

    @Test fun `preexisting mock provider is not replaced`() {
        val capability = service { command ->
            when (command.args.last()) {
                "ro.build.version.sdk" -> "35"
                "help" -> "add-test-provider set-test-provider-location"
                else -> "Location Providers:\n    gps provider [mock]:\n      identity=other.app"
            }
        }.capability("phone")

        assertIs<DeviceLocationCapability.Unavailable>(capability)
    }

    @Test fun `provider detection ignores ordinary location history mentioning mock`() {
        assertFalse(hasMockLocationProvider("gps provider:\n last location=mock=false\nHistory: gps mock added"))
    }

    @Test fun `a phone older than Android 12 is told which version location needs`() {
        val error = assertFailsWith<IllegalStateException> { physicalService(sdk = 30) {}.mockSession("phone", DeviceCoordinates(0.0, 0.0)) }
        assertEquals("Location simulation on physical devices requires Android 12 or later.", error.message)
    }

    @Test fun `physical device with location disabled cannot start a mock session`() {
        val writes = mutableListOf<Command>()
        val service = physicalService(locationEnabled = false) { writes += it }

        assertFailsWith<IllegalStateException> { service.mockSession("phone", DeviceCoordinates(0.0, 0.0)) }
        assertTrue(writes.isEmpty())
    }

    @Test fun `unrecognized mock permission cannot start a mock session`() {
        val service = service { command ->
            when (command.args.last()) {
                "ro.build.version.sdk" -> "35"
                "help" -> "add-test-provider set-test-provider-location"
                "location" -> "gps provider:\nnetwork provider:"
                "current" -> "true"
                else -> "Unknown operation"
            }
        }

        assertFailsWith<IllegalStateException> { service.mockSession("phone", DeviceCoordinates(0.0, 0.0)) }
    }

    @Test fun `permission parser distinguishes missing entry from explicit mode`() {
        assertEquals("default", shellMockLocationMode("No operations.\nDefault mode: deny"))
        assertEquals("ignore", shellMockLocationMode("MOCK_LOCATION: ignore; time=+1s"))
    }

    @Test fun `cancelled session does not create a provider`() {
        val writes = mutableListOf<Command>()
        val session = physicalService { writes += it }.mockSession("phone", DeviceCoordinates(0.0, 0.0))
        session.cancel()

        session.run { fail("Cancelled session must not report readiness") }

        assertTrue(writes.isEmpty())
    }

    @Test fun `stopping active physical simulation returns controls to idle`(): Unit = runBlocking {
        val service = DeviceLocationService(AppSettings("/sdk", "/avds"), CommandExecutor { command, cancelled, output ->
            when {
                command.args.last() == "ro.build.version.sdk" -> "37"
                command.args.last() == "help" -> "add-test-provider set-test-provider-location"
                command.args.last() == "location" -> "gps provider:\nnetwork provider:"
                "is-location-enabled" in command.args -> "true"
                "get" in command.args -> "No operations.\nDefault mode: deny"
                "sh" in command.args -> {
                    output("ROBOMORI_LOCATION_READY\n")
                    val deadline = System.nanoTime() + 5_000_000_000L
                    while (!cancelled.get()) {
                        check(System.nanoTime() < deadline) { "Mock session was not stopped" }
                        Thread.sleep(1)
                    }
                    throw CancellationException("Operation cancelled.")
                }
                else -> ""
            }
        })
        val controls = DeviceLocationControlState(service, "phone", this, { true }, {})
        controls.refresh()
        coroutineContext.job.children.toList().joinAll()
        controls.apply(DeviceCoordinates(51.5, -0.1))
        withTimeout(5000) {
            while ((controls.snapshot as? LocationControlSnapshot.Ready)?.delivery !is LocationDelivery.Simulating) delay(1)
        }

        controls.stop()
        val operations = coroutineContext.job.children.toList()
        withTimeout(5000) { operations.joinAll() }

        assertEquals(LocationDelivery.Idle, assertIs<LocationControlSnapshot.Ready>(controls.snapshot).delivery)
    }

    private fun physicalService(locationEnabled: Boolean = true, sdk: Int = 35, write: (Command) -> Unit): DeviceLocationService = service { command ->
        when {
            command.args.last() == "ro.build.version.sdk" -> sdk.toString()
            command.args.last() == "help" -> "add-test-provider set-test-provider-location"
            command.args.last() == "location" -> "gps provider:\nnetwork provider:"
            "is-location-enabled" in command.args -> locationEnabled.toString()
            "get" in command.args -> "No operations.\nDefault mode: deny"
            else -> { write(command); "" }
        }
    }

    @Test fun `a newer location remains selected and is delivered after the active command`(): Unit = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        val writes = mutableListOf<List<String>>()
        val first = DeviceCoordinates(51.5, -0.1)
        val latest = DeviceCoordinates(35.6, 139.7)
        val service = service { command ->
            if (command.args.last() == "ro.build.version.sdk") return@service "37"
            if ("geo" !in command.args) return@service ""
            writes += command.args.takeLast(2)
            if (writes.size == 1) {
                started.complete(Unit)
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            }
            "OK\n"
        }
        val controls = DeviceLocationControlState(service, "emulator-5554", this, { true }, {})
        controls.refresh()
        coroutineContext.job.children.toList().joinAll()
        try {
            controls.apply(first)
            withTimeout(5000) { started.await() }

            controls.apply(latest)

            assertEquals(latest, controls.selection)
        } finally {
            release.countDown()
        }
        coroutineContext.job.children.toList().joinAll()
        assertEquals(listOf(listOf("-0.1", "51.5"), listOf("139.7", "35.6")), writes)
        assertEquals(LocationDelivery.Delivered(latest), assertIs<LocationControlSnapshot.Ready>(controls.snapshot).delivery)
    }

    @Test fun `none clears the selected location and disables location on the selected emulator`(): Unit = runBlocking {
        val commands = mutableListOf<Command>()
        val service = service { command ->
            commands += command
            if (command.args.last() == "ro.build.version.sdk") "37" else "OK\n"
        }
        val controls = DeviceLocationControlState(service, "emulator-5556", this, { true }, {})
        controls.refresh()
        coroutineContext.job.children.toList().joinAll()
        controls.apply(DeviceCoordinates(51.5, -0.1))
        coroutineContext.job.children.toList().joinAll()

        controls.stop()

        assertNull(controls.selection)
        coroutineContext.job.children.toList().joinAll()
        assertEquals(listOf("-s", "emulator-5556", "shell", "cmd", "location", "set-location-enabled", "false", "--user", "current"), commands.last().args)
        assertEquals(LocationDelivery.Idle, assertIs<LocationControlSnapshot.Ready>(controls.snapshot).delivery)
    }

    private fun service(answer: (Command) -> String) = DeviceLocationService(AppSettings("/sdk", "/avds"),
        CommandExecutor { command, _, _ -> answer(command) })
}
