package com.robomori

import com.robomori.components.TextField

import com.robomori.components.SelectRow
import com.robomori.components.Button
import com.robomori.components.ButtonStyle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.robomori.components.AppDialog
import com.robomori.components.AppDialogPanel
import com.robomori.components.Label
import com.robomori.iconography.Icons
import com.robomori.iconography.MapPin
import com.robomori.theme.RobomoriTheme
import kotlinx.coroutines.*
import com.composeunstyled.Text

internal sealed interface LocationDelivery {
    data object Idle : LocationDelivery
    data object Applying : LocationDelivery
    data class Delivered(val coordinates: DeviceCoordinates) : LocationDelivery
    data class Simulating(val coordinates: DeviceCoordinates) : LocationDelivery
    data object Stopping : LocationDelivery
    data class Failed(val message: String) : LocationDelivery
}

internal sealed interface LocationControlSnapshot {
    data object Loading : LocationControlSnapshot
    data class Unavailable(val reason: String) : LocationControlSnapshot
    data class Ready(val capability: DeviceLocationCapability, val delivery: LocationDelivery = LocationDelivery.Idle) : LocationControlSnapshot
    data object ReadFailed : LocationControlSnapshot
}

internal class DeviceLocationControlState(
    private val service: DeviceLocationService,
    private val serial: String,
    private val scope: CoroutineScope,
    private val canEdit: () -> Boolean,
    private val onLocked: () -> Unit,
) {
    var snapshot by mutableStateOf<LocationControlSnapshot>(LocationControlSnapshot.Loading)
        private set
    var selection by mutableStateOf<DeviceCoordinates?>(null)
        private set
    private var pending: DeviceCoordinates? = null
    private var pendingClear = false
    private var job: Job? = null
    private var session: DeviceMockLocationSession? = null
    private var stopping = false

    fun refresh() {
        if (job?.isActive == true) return
        snapshot = LocationControlSnapshot.Loading
        job = scope.launch {
            snapshot = try {
                when (val capability = withContext(Dispatchers.IO) { service.capability(serial) }) {
                    is DeviceLocationCapability.Unavailable -> LocationControlSnapshot.Unavailable(capability.reason)
                    else -> LocationControlSnapshot.Ready(capability)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                LocationControlSnapshot.ReadFailed
            }
        }
    }

    fun apply(coordinates: DeviceCoordinates) {
        if (!canEdit()) { onLocked(); return }
        val current = snapshot as? LocationControlSnapshot.Ready ?: return
        selection = coordinates
        pending = coordinates
        pendingClear = false
        deliverPending(current)
    }

    private fun deliverPending(current: LocationControlSnapshot.Ready) {
        if (job?.isActive == true) {
            stopping = true
            session?.cancel()
            return
        }
        job = scope.launch {
            while (pending != null || pendingClear) {
                val clear = pendingClear
                val next = pending
                pending = null
                pendingClear = false
                stopping = false
                snapshot = current.copy(delivery = LocationDelivery.Applying)
                try {
                    when (current.capability) {
                        DeviceLocationCapability.Emulator -> {
                            withContext(Dispatchers.IO) {
                                if (clear) service.clear(serial) else service.deliver(serial, requireNotNull(next))
                            }
                            snapshot = current.copy(delivery = if (clear) LocationDelivery.Idle
                                else LocationDelivery.Delivered(requireNotNull(next)))
                        }
                        DeviceLocationCapability.Mock -> runMock(current, requireNotNull(next))
                        is DeviceLocationCapability.Unavailable -> Unit
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    snapshot = current.copy(delivery = if (stopping) LocationDelivery.Idle else LocationDelivery.Failed(
                        if (clear) "Could not turn off location. Check your connection and choose None again."
                        else "Could not simulate this location. Check your connection, turn on Location on your device, and try again."))
                }
            }
        }
    }

    private suspend fun runMock(current: LocationControlSnapshot.Ready, coordinates: DeviceCoordinates) = coroutineScope {
        val active = withContext(Dispatchers.IO) { service.mockSession(serial, coordinates) }
        session = active
        if (stopping) active.cancel()
        val heartbeat = launch {
            while (isActive && !stopping) {
                withContext(Dispatchers.IO) { active.renew() }
                delay(2000)
            }
        }
        try {
            withContext(Dispatchers.IO) {
                try {
                    active.run {
                        scope.launch {
                            if (session === active && !stopping) snapshot = current.copy(delivery = LocationDelivery.Simulating(coordinates))
                        }
                    }
                } catch (cancelled: CancellationException) {
                    // An explicit stop cancels ADB without cancelling the controls' coroutine.
                    if (!stopping) throw cancelled
                }
            }
            snapshot = current.copy(delivery = if (stopping) LocationDelivery.Idle else LocationDelivery.Failed(
                "Location simulation ended. Check your connection and apply the location again."))
        } finally {
            withContext(NonCancellable) {
                heartbeat.cancelAndJoin()
                withContext(Dispatchers.IO) { active.stop() }
            }
            if (session === active) session = null
        }
    }

    fun stop() {
        pending = null
        selection = null
        stopping = true
        val current = snapshot as? LocationControlSnapshot.Ready ?: return
        if (current.capability == DeviceLocationCapability.Emulator) {
            pendingClear = true
            deliverPending(current)
            return
        }
        snapshot = current.copy(delivery = if (job?.isActive == true) LocationDelivery.Stopping else LocationDelivery.Idle)
        session?.cancel()
    }

    fun close() {
        pending = null
        pendingClear = false
        if ((snapshot as? LocationControlSnapshot.Ready)?.capability == DeviceLocationCapability.Mock) selection = null
        stopping = true
        session?.cancel()
    }

    suspend fun awaitDelivery(): LocationDelivery {
        yield()
        while (job?.isActive == true) {
            val delivery = (snapshot as? LocationControlSnapshot.Ready)?.delivery
            if (delivery is LocationDelivery.Simulating && !stopping && pending == null && !pendingClear) return delivery
            delay(20)
        }
        return (snapshot as? LocationControlSnapshot.Ready)?.delivery
            ?: error("Location controls are unavailable. Check your connection and try again.")
    }

    fun clearSelection() {
        selection = null
        if (job?.isActive != true) (snapshot as? LocationControlSnapshot.Ready)?.let {
            snapshot = it.copy(delivery = LocationDelivery.Idle)
        }
    }
}

@Composable
internal fun DeviceLocationControl(settings: AppSettings, serial: String, licensed: Boolean, onLocked: () -> Unit,
                                   active: Boolean = true, sharedState: DeviceLocationControlState? = null) {
    key(settings, serial) {
        val scope = rememberCoroutineScope()
        val allowed by rememberUpdatedState(licensed)
        val locked by rememberUpdatedState(onLocked)
        val state = sharedState ?: remember { DeviceLocationControlState(DeviceLocationService(settings), serial, scope, { allowed }, { locked() }) }
        LaunchedEffect(state, active, licensed) {
            if (active) state.refresh()
            if (!active || !licensed) state.close()
        }
        DisposableEffect(state) { onDispose { state.close() } }
        when (val snapshot = state.snapshot) {
            is LocationControlSnapshot.Ready -> LocationRow(snapshot, state.selection, licensed, state::apply, state::stop, onLocked)
            else -> {
                SelectRow("Location", Icons.MapPin,
                    if (snapshot == LocationControlSnapshot.Loading) "Checking…" else "Unavailable",
                    listOf("Check again"), "LocationCity", enabled = snapshot != LocationControlSnapshot.Loading) { state.refresh() }
                if (snapshot is LocationControlSnapshot.Unavailable)
                    Label(snapshot.reason, color = RobomoriTheme.colors.textSecondary, modifier = Modifier.padding(bottom = 10.dp))
                if (snapshot == LocationControlSnapshot.ReadFailed)
                    Label("Could not check location support. Choose Check again to retry.", color = RobomoriTheme.colors.danger,
                        modifier = Modifier.padding(bottom = 10.dp))
            }
        }
    }
}

@Composable
private fun LocationRow(snapshot: LocationControlSnapshot.Ready, coordinates: DeviceCoordinates?, licensed: Boolean,
                        apply: (DeviceCoordinates) -> Unit, stop: () -> Unit, onLocked: () -> Unit) {
    var custom by remember { mutableStateOf(false) }
    val delivery = snapshot.delivery
    val value = coordinates?.let { point ->
            locationCities.firstOrNull { it.coordinates == point }?.label ?: "${point.latitude}, ${point.longitude}"
        } ?: "None"
    val options = listOf("None") + locationCities.map { it.label } + "Custom…"
    SelectRow("Location", Icons.MapPin, value, options, "LocationCity") { selected ->
        when {
            selected == "None" -> stop()
            !licensed -> onLocked()
            selected == "Custom…" -> custom = true
            else -> locationCities.firstOrNull { it.label == selected }?.let { apply(it.coordinates) }
        }
    }
    if (delivery is LocationDelivery.Failed)
        Label(delivery.message, color = RobomoriTheme.colors.danger, modifier = Modifier.padding(bottom = 10.dp))
    AppDialog(custom.takeIf { it }, onClose = { custom = false }) {
        CustomLocationDialog(coordinates, onClose = { custom = false }, onApply = {
            custom = false
            apply(it)
        })
    }
}

@Composable
internal fun CustomLocationDialog(initial: DeviceCoordinates?,
                                 onClose: () -> Unit, onApply: (DeviceCoordinates) -> Unit) {
    val latitude = rememberTextFieldState(initial?.latitude?.toString().orEmpty())
    val longitude = rememberTextFieldState(initial?.longitude?.toString().orEmpty())
    val coordinates = coordinatesFromInput(latitude.text.toString(), longitude.text.toString())
    AppDialogPanel("Custom location", "CustomLocationDialog", Modifier.width(400.dp)) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Custom location", style = RobomoriTheme.typography.heading)
            Label("Send a simulated location to this device.", color = RobomoriTheme.colors.textSecondary)
            Label("Latitude")
            TextField(state = latitude, modifier = Modifier.fillMaxWidth().testTag("LocationLatitude")
                .semantics { contentDescription = "Latitude" }, placeholder = { Text("−90 to 90") })
            val lat = latitude.text.toString().trim().toDoubleOrNull()
            if (latitude.text.isNotBlank() && (lat == null || !lat.isFinite() || lat !in -90.0..90.0))
                Label("Enter a latitude from −90 to 90.", color = RobomoriTheme.colors.danger)
            Label("Longitude")
            TextField(state = longitude, modifier = Modifier.fillMaxWidth().testTag("LocationLongitude")
                .semantics { contentDescription = "Longitude" }, placeholder = { Text("−180 to 180") })
            val lon = longitude.text.toString().trim().toDoubleOrNull()
            if (longitude.text.isNotBlank() && (lon == null || !lon.isFinite() || lon !in -180.0..180.0))
                Label("Enter a longitude from −180 to 180.", color = RobomoriTheme.colors.danger)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End)) {
                Button(onClick = onClose, style = ButtonStyle.Secondary, tag = "Cancel") { Label("Cancel") }
                Button(onClick = {
                    coordinates?.let(onApply)
                }, style = ButtonStyle.Primary, tag = "ApplyLocation", enabled = coordinates != null) { Label("Apply") }
            }
        }
    }
}
