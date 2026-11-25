package com.robomori

import kotlinx.coroutines.*

/** Shares the live controls' simulation owner with preset application and restoration. */
internal class DevicePresetLocations(private val app: AppState, private val executor: CommandExecutor = ProcessExecutor()) : PresetLocationAccess {
    private data class Key(val identity: String, val serial: String, val settings: AppSettings)
    private val controls = mutableMapOf<Key, DeviceLocationControlState>()
    private val active = mutableSetOf<DevicePresetTarget>()

    fun setActive(target: DevicePresetTarget, visible: Boolean) {
        if (visible) active += target else {
            active -= target
            controls.filterKeys { it.identity == target.identity && it.serial == target.serial }.values.forEach { it.close() }
        }
    }

    fun state(target: DevicePresetTarget): DeviceLocationControlState =
        controls.getOrPut(Key(target.identity, target.serial, app.settings)) {
            DeviceLocationControlState(DeviceLocationService(app.settings, executor), target.serial, app.scope,
                { app.license.unlocked }, { app.showDemoDialog = true })
        }

    private suspend fun ready(target: DevicePresetTarget): Pair<DeviceLocationControlState, DeviceLocationCapability> {
        val control = state(target)
        if (control.snapshot !is LocationControlSnapshot.Ready) {
            control.refresh()
            await(control)
        }
        val snapshot = control.snapshot
        val capability = (snapshot as? LocationControlSnapshot.Ready)?.capability ?: error(
            (snapshot as? LocationControlSnapshot.Unavailable)?.reason ?: "Location controls are unavailable. Check your connection.")
        return control to capability
    }

    override suspend fun support(target: DevicePresetTarget, value: PresetValue.Location): PresetSupport = try {
        val (control, capability) = ready(target)
        PresetSupport.Supported(when {
            confirmedCoordinates(control) != null -> null
            capability == DeviceLocationCapability.Emulator ->
                "Restore can restore whether Location was enabled. The previous GPS coordinates are unknown."
            else -> "Restore stops this simulation and returns to the device's real location providers."
        })
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) { PresetSupport.Unsupported(failure.message ?: "Location is unavailable.") }

    override suspend fun capture(target: DevicePresetTarget): PresetRestorePoint {
        val (control, capability) = ready(target)
        await(control)
        val enabled = if (capability == DeviceLocationCapability.Emulator) withContext(Dispatchers.IO) {
            val android = AndroidService(app.settings, executor)
            android.command(android.toolchain.adb, listOf("-s", target.serial, "shell", "settings", "--user", "current",
                "get", "secure", "location_mode")).trim().let {
                check(it in listOf("0", "1", "2", "3")) { "Could not read whether Location is enabled." }
                it != "0"
            }
        } else null
        val coordinates = confirmedCoordinates(control)
        val limit = (support(target, PresetValue.Location(coordinates)) as? PresetSupport.Supported)?.restoreLimit
        return LocationPoint(coordinates, enabled, limit)
    }

    suspend fun currentValue(target: DevicePresetTarget): PresetValue.Location? {
        val point = capture(target) as LocationPoint
        return when {
            point.coordinates != null -> PresetValue.Location(point.coordinates)
            point.enabled != true -> PresetValue.Location(null)
            else -> null // An enabled emulator's previous GPS position cannot be inferred.
        }
    }

    override suspend fun apply(target: DevicePresetTarget, value: PresetValue.Location) {
        val (control, capability) = ready(target)
        check(capability != DeviceLocationCapability.Mock || target in active) {
            "Keep this device's controls open while simulating its location."
        }
        if (value.coordinates == null) control.stop() else control.apply(value.coordinates)
        val result = await(control)
        val delivered = when (result) {
            is LocationDelivery.Delivered -> result.coordinates
            is LocationDelivery.Simulating -> result.coordinates
            else -> null
        }
        check(if (value.coordinates == null) result == LocationDelivery.Idle else delivered == value.coordinates) {
            (result as? LocationDelivery.Failed)?.message
                ?: "Location changed or simulation stopped before this preset finished. Apply the preset again."
        }
    }

    override suspend fun restore(target: DevicePresetTarget, previous: PresetRestorePoint) {
        val point = previous as? LocationPoint ?: error("The saved location cannot be restored.")
        when {
            point.coordinates != null -> apply(target, PresetValue.Location(point.coordinates))
            point.enabled == true -> {
                withContext(Dispatchers.IO) {
                    val android = AndroidService(app.settings, executor)
                    val sdk = android.command(android.toolchain.adb, listOf("-s", target.serial, "shell", "getprop", "ro.build.version.sdk")).trim().toInt()
                    val command = if (sdk >= 28) listOf("cmd", "location", "set-location-enabled", "true", "--user", "current")
                        else listOf("settings", "put", "secure", "location_mode", "3")
                    android.command(android.toolchain.adb, listOf("-s", target.serial, "shell") + command)
                }
                state(target).clearSelection()
            }
            else -> apply(target, PresetValue.Location(null))
        }
        if (point.enabled == false && point.coordinates != null) apply(target, PresetValue.Location(null))
    }

    fun disconnected(identity: String) {
        active.removeAll { it.identity == identity }
        controls.filterKeys { it.identity == identity }.values.forEach { it.close() }
        controls.keys.removeAll { it.identity == identity }
    }

    fun confirmedCoordinates(target: DevicePresetTarget): DeviceCoordinates? = confirmedCoordinates(state(target))

    private fun confirmedCoordinates(control: DeviceLocationControlState): DeviceCoordinates? =
        when (val delivery = (control.snapshot as? LocationControlSnapshot.Ready)?.delivery) {
            is LocationDelivery.Delivered -> delivery.coordinates
            is LocationDelivery.Simulating -> delivery.coordinates
            else -> null
        }

    private suspend fun await(control: DeviceLocationControlState): LocationDelivery =
        withTimeoutOrNull(20_000) { control.awaitDelivery() } ?: run {
            control.close()
            error("Location did not respond in time. Check your connection and apply the setting again.")
        }

    private data class LocationPoint(val coordinates: DeviceCoordinates?, val enabled: Boolean?,
                                     override val limitation: String?) : PresetRestorePoint
}
