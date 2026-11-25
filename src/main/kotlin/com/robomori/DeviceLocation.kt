package com.robomori

import java.util.UUID
import java.text.Normalizer
import java.util.concurrent.atomic.AtomicBoolean

data class DeviceCoordinates(val latitude: Double, val longitude: Double) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0) { "Enter a latitude from −90 to 90." }
        require(longitude.isFinite() && longitude in -180.0..180.0) { "Enter a longitude from −180 to 180." }
    }
}

internal fun coordinatesFromInput(latitude: String, longitude: String): DeviceCoordinates? {
    val lat = latitude.trim().toDoubleOrNull() ?: return null
    val lon = longitude.trim().toDoubleOrNull() ?: return null
    return runCatching { DeviceCoordinates(lat, lon) }.getOrNull()
}

internal sealed interface DeviceLocationCapability {
    data object Emulator : DeviceLocationCapability
    data object Mock : DeviceLocationCapability
    data class Unavailable(val reason: String) : DeviceLocationCapability
}

internal fun hasMockLocationProvider(snapshot: String): Boolean =
    Regex("(?m)^\\s*\\S+ provider \\[mock]:").containsMatchIn(snapshot)

internal fun shellMockLocationMode(output: String): String? {
    if (output.trim().startsWith("No operations.")) return "default"
    return Regex("(?:MOCK_LOCATION|android:mock_location):\\s*(allow|ignore|deny|default|foreground)\\b")
        .find(output)?.groupValues?.get(1)
}

internal class DeviceLocationService(settings: AppSettings, executor: CommandExecutor = ProcessExecutor()) {
    private val android = AndroidService(settings, executor)

    fun capability(serial: String): DeviceLocationCapability {
        require(serial.isNotBlank()) { "Select a connected device." }
        if (serial.startsWith("emulator-")) return DeviceLocationCapability.Emulator
        return physicalCapability(serial)
    }

    private fun physicalCapability(serial: String): DeviceLocationCapability {
        val version = shell(serial, "getprop", "ro.build.version.sdk").trim().toIntOrNull()
        if (version == null || version < 31) return DeviceLocationCapability.Unavailable(
            "Location simulation on physical devices requires Android 12 or later.")
        val help = try {
            shell(serial, "cmd", "location", "help")
        } catch (failure: AndroidCommandException) {
            // Some Android releases return 255 after successfully printing location help.
            if (failure.exitCode == 255 && "Location service commands:" in failure.output &&
                "add-test-provider" in failure.output && "set-test-provider-location" in failure.output) failure.output
            else throw failure
        }
        if ("add-test-provider" !in help || "set-test-provider-location" !in help) {
            return DeviceLocationCapability.Unavailable("This device does not support location simulation over USB or Wi-Fi.")
        }
        if (hasMockLocationProvider(shell(serial, "dumpsys", "location"))) {
            return DeviceLocationCapability.Unavailable("Another tool is simulating this device’s location. Stop it before using location here.")
        }
        return DeviceLocationCapability.Mock
    }

    fun deliver(serial: String, coordinates: DeviceCoordinates) {
        require(serial.startsWith("emulator-")) { "Select an emulator to send a location." }
        setEmulatorLocationEnabled(serial, true)
        val output = android.command(android.toolchain.adb,
            listOf("-s", serial, "emu", "geo", "fix", coordinates.longitude.toString(), coordinates.latitude.toString()))
        check(output.lineSequence().any { it.trim() == "OK" }) { "The emulator did not accept this location. Try again." }
    }

    fun clear(serial: String) {
        require(serial.startsWith("emulator-")) { "Select an emulator to clear its location." }
        setEmulatorLocationEnabled(serial, false)
    }

    private fun setEmulatorLocationEnabled(serial: String, enabled: Boolean) {
        val sdk = shell(serial, "getprop", "ro.build.version.sdk").trim().toIntOrNull()
            ?: error("Could not read the Android version. Check your connection and try again.")
        if (sdk >= 28) shell(serial, "cmd", "location", "set-location-enabled", enabled.toString(), "--user", "current")
        else shell(serial, "settings", "put", "secure", "location_mode", if (enabled) "3" else "0")
    }

    fun mockSession(serial: String, coordinates: DeviceCoordinates): DeviceMockLocationSession {
        when (val capability = physicalCapability(serial)) {
            DeviceLocationCapability.Mock -> {}
            // Says why, such as the Android version it needs, rather than a generic failure.
            is DeviceLocationCapability.Unavailable -> error(capability.reason)
            DeviceLocationCapability.Emulator -> error("Location simulation is unavailable on this device. Refresh and try again.")
        }
        check(shell(serial, "cmd", "location", "is-location-enabled", "--user", "current").trim() == "true") {
            "Turn on Location in your device’s settings, then try again."
        }
        val originalMode = shellMockLocationMode(shell(serial, "cmd", "appops", "get", "--user", "current", "com.android.shell", "android:mock_location"))
            ?: error("Could not read this device’s mock-location permission. Try again after checking Developer options.")
        return DeviceMockLocationSession(android, serial, coordinates, originalMode)
    }

    private fun shell(serial: String, vararg args: String): String =
        android.command(android.toolchain.adb, listOf("-s", serial, "shell") + args)
}

/** A device-side lease restores real providers even if the desktop app or ADB connection disappears. */
internal class DeviceMockLocationSession(
    private val android: AndroidService,
    private val serial: String,
    private val coordinates: DeviceCoordinates,
    private val originalMode: String,
) {
    private val token = "robomori-" + UUID.randomUUID().toString()
    private val lease = "/data/local/tmp/$token.location"
    private val cancelled = AtomicBoolean()

    fun renew() {
        if (!cancelled.get()) shell("touch", lease)
    }

    fun run(onReady: () -> Unit) {
        if (cancelled.get()) return
        renew()
        var ready = false
        val output = StringBuilder()
        try {
            android.command(android.toolchain.adb, listOf("-s", serial, "shell", "sh", "-c", quoteLocationShell(mockScript())),
                cancelled = cancelled, output = { chunk ->
                    output.append(chunk)
                    if (!ready && "ROBOMORI_LOCATION_READY" in output) {
                        ready = true
                        onReady()
                    }
                })
            check(ready || cancelled.get()) { "This device did not allow location simulation. Check Developer options and try again." }
        } finally {
            runCatching { shell("rm", "-f", lease) }
        }
    }

    fun stop() {
        cancel()
        // The remote process also expires without heartbeats, so unplugging cannot leave it running.
        runCatching { shell("rm", "-f", lease) }
    }

    fun cancel() { cancelled.set(true) }

    private fun shell(vararg args: String) =
        android.command(android.toolchain.adb, listOf("-s", serial, "shell") + args)

    private fun mockScript(): String = """
        set -eu
        gps_added=0
        network_added=0
        permission_changed=0
        owns_provider() {
          dumpsys location | sed -n "/^[[:space:]]*${'$'}1 provider \[mock\]:/,/^[[:space:]]*[a-z][a-z]* provider/p" | grep -F '$token' >/dev/null
        }
        cleanup() {
          set +e
          trap - EXIT HUP INT TERM
          if [ "${'$'}gps_added" = 1 ] && owns_provider gps; then cmd location providers remove-test-provider gps; fi
          if [ "${'$'}network_added" = 1 ] && owns_provider network; then cmd location providers remove-test-provider network; fi
          if [ "${'$'}permission_changed" = 1 ]; then cmd appops set --user current com.android.shell android:mock_location $originalMode; fi
          rm -f '$lease'
        }
        trap cleanup EXIT
        trap 'exit 0' HUP INT TERM
        if dumpsys location | grep -E '^[[:space:]]*[^ ]+ provider \[mock\]:' >/dev/null; then
          echo 'Another tool is simulating this device location.' >&2
          exit 1
        fi
        cmd appops set --user current com.android.shell android:mock_location allow
        permission_changed=1
        cmd location providers add-test-provider gps --requiresSatellite --supportsAltitude --accuracy 1 --extraAttributionTags '$token'
        gps_added=1
        cmd location providers add-test-provider network --requiresNetwork --accuracy 1 --extraAttributionTags '$token'
        network_added=1
        cmd location providers set-test-provider-enabled gps true
        cmd location providers set-test-provider-enabled network true
        ready=0
        while [ -f '$lease' ]; do
          owns_provider gps && owns_provider network || break
          now=${'$'}(date +%s)
          renewed=${'$'}(stat -c %Y '$lease')
          [ ${'$'}((now - renewed)) -lt 8 ] || break
          cmd location providers set-test-provider-location gps --location '${coordinates.latitude},${coordinates.longitude}' --accuracy 5
          cmd location providers set-test-provider-location network --location '${coordinates.latitude},${coordinates.longitude}' --accuracy 5
          if [ "${'$'}ready" = 0 ]; then echo ROBOMORI_LOCATION_READY; ready=1; fi
          sleep 2
        done
    """.trimIndent()
}

private fun quoteLocationShell(value: String): String = "'" + value.replace("'", "'\\''") + "'"

data class LocationCity(val name: String, val country: String, val coordinates: DeviceCoordinates) {
    val label get() = "$name, $country"
}

// Natural Earth 1:110m populated places, retrieved 2026-10-30. Coordinates are representative city points.
// Source: https://github.com/nvkelso/natural-earth-vector/blob/master/geojson/ne_110m_populated_places.geojson
// Public domain: https://www.naturalearthdata.com/about/terms-of-use/
val locationCities: List<LocationCity> = listOf(
    LocationCity("Abuja", "Nigeria", DeviceCoordinates(9.054620, 7.489505)),
    LocationCity("Bangkok", "Thailand", DeviceCoordinates(13.751945, 100.514699)),
    LocationCity("Beijing", "China", DeviceCoordinates(39.901720, 116.394201)),
    LocationCity("Berlin", "Germany", DeviceCoordinates(52.523764, 13.399603)),
    LocationCity("Brasília", "Brazil", DeviceCoordinates(-15.781394, -47.917998)),
    LocationCity("Buenos Aires", "Argentina", DeviceCoordinates(-34.610715, -58.432513)),
    LocationCity("Cairo", "Egypt", DeviceCoordinates(30.051906, 31.248022)),
    LocationCity("Canberra", "Australia", DeviceCoordinates(-35.283029, 149.129026)),
    LocationCity("Jakarta", "Indonesia", DeviceCoordinates(-6.172472, 106.827492)),
    LocationCity("London", "United Kingdom", DeviceCoordinates(51.501941, -0.118668)),
    LocationCity("Los Angeles", "United States", DeviceCoordinates(34.049219, -118.231986)),
    LocationCity("Madrid", "Spain", DeviceCoordinates(40.401972, -3.685297)),
    LocationCity("Mexico City", "Mexico", DeviceCoordinates(19.444388, -99.132934)),
    LocationCity("Nairobi", "Kenya", DeviceCoordinates(-1.281401, 36.814711)),
    LocationCity("New Delhi", "India", DeviceCoordinates(28.600023, 77.199980)),
    LocationCity("New York", "United States", DeviceCoordinates(40.721562, -73.995718)),
    LocationCity("Ottawa", "Canada", DeviceCoordinates(45.418643, -75.701961)),
    LocationCity("Paris", "France", DeviceCoordinates(48.858092, 2.352992)),
    LocationCity("Pretoria", "South Africa", DeviceCoordinates(-25.704975, 28.227483)),
    LocationCity("Riyadh", "Saudi Arabia", DeviceCoordinates(24.634497, 46.720487)),
    LocationCity("Rio de Janeiro", "Brazil", DeviceCoordinates(-22.907308, -43.212117)),
    LocationCity("Rome", "Italy", DeviceCoordinates(41.897902, 12.481313)),
    LocationCity("Santiago", "Chile", DeviceCoordinates(-33.440205, -70.650504)),
    LocationCity("San Francisco", "United States", DeviceCoordinates(37.784263, -122.399600)),
    LocationCity("São Paulo", "Brazil", DeviceCoordinates(-23.556734, -46.626966)),
    LocationCity("Seoul", "South Korea", DeviceCoordinates(37.568295, 126.997785)),
    LocationCity("Singapore", "Singapore", DeviceCoordinates(1.294979, 103.853875)),
    LocationCity("Tokyo", "Japan", DeviceCoordinates(35.686963, 139.749462)),
    LocationCity("Washington, D.C.", "United States", DeviceCoordinates(38.901495, -77.011364)),
    LocationCity("Wellington", "New Zealand", DeviceCoordinates(-41.292068, 174.777201)),
)

internal fun findLocationCities(query: String): List<LocationCity> =
    locationCities.filter { searchableCityText(it.label).contains(searchableCityText(query.trim()), ignoreCase = true) }

private fun searchableCityText(text: String) = Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
