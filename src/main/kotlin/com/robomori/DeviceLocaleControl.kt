package com.robomori

import com.robomori.components.SelectRow
import com.robomori.components.Button
import com.robomori.components.ButtonStyle
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.robomori.components.Label
import com.robomori.iconography.Icons
import com.robomori.iconography.Languages
import com.robomori.theme.RobomoriTheme

@Composable
internal fun DeviceLocaleControl(settings: AppSettings, serial: String, refreshKey: Int = 0,
                                 presetLocale: DeviceLocale? = null, cache: DeviceLocaleCache? = null, deviceKey: String = serial,
                                 onRead: (DeviceLocaleInfo) -> Unit = {}) {
    key(settings, serial, deviceKey, cache) {
        val scope = rememberCoroutineScope()
        val controller = remember {
            DeviceLocaleController(DeviceLocaleService(settings, cache = cache, deviceKey = deviceKey), serial, scope,
                canEdit = { true }, onLocked = {}, initialInfo = cache?.get(deviceKey)?.info)
        }
        LaunchedEffect(controller, refreshKey) { controller.refresh() }
        val state = controller.state
        val info = when (state) {
            is DeviceLocaleState.Ready -> state.info
            is DeviceLocaleState.Applying -> state.info.copy(current = state.selection)
            is DeviceLocaleState.Failed -> state.info
            DeviceLocaleState.Loading -> null
        }
        LaunchedEffect(info) { if (info != null) onRead(info) }
        val shown = info?.let {
            if (presetLocale == null) it else it.copy(current = presetLocale)
        }
        if (shown != null) {
            SelectRow("Locale", Icons.Languages, shown.current.label,
                shown.available.map { it.label }, "ChooseDeviceLocale") { selected ->
                shown.available.firstOrNull { it.label == selected }?.let(controller::apply)
            }
        } else {
            SelectRow("Locale", Icons.Languages,
                if (state is DeviceLocaleState.Failed) "Unavailable" else "Loading…",
                emptyList(), "ChooseDeviceLocale", enabled = false) {}
        }
        if (state is DeviceLocaleState.Failed) {
            Label(state.cause.message ?: "Could not read device locale. Try again.",
                color = RobomoriTheme.colors.danger, modifier = Modifier.padding(bottom = 10.dp))
            Button(onClick = { controller.refresh() }, style = ButtonStyle.Ghost, tag = "RetryDeviceLocale") { Label("Retry") }
        }
    }
}
