package com.robomori


import com.robomori.components.Button
import com.robomori.components.ButtonStyle
import com.robomori.components.AppDialogPanel
import com.robomori.components.Label
import com.robomori.theme.RobomoriTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp

@Composable
fun WipeDataDialog(state: AppState, device: Device) {
    val cancelFocus = remember { FocusRequester() }
    fun close() { state.wipeDataDevice = null }

    AppDialogPanel("Wipe data?", "WipeDataDialog", Modifier.width(460.dp)) {
        LaunchedEffect(Unit) { cancelFocus.requestFocus() }
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Label("Wipe data from ${device.displayName}?")
            Label("Installed apps, accounts, and settings on this emulator will be erased. The emulator will start with fresh data. This cannot be undone.",
                color = RobomoriTheme.colors.textSecondary)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Spacer(Modifier.weight(1f))
                Button(onClick = ::close, style = ButtonStyle.Outlined, modifier = Modifier.focusRequester(cancelFocus), tag = "CancelWipeData") { Label("Cancel") }
                Button(onClick = {
                    close()
                    state.launch(device, wipeData = true)
                }, style = ButtonStyle.Primary, tag = "ConfirmWipeData", enabled = !state.busy && device.name !in state.deviceOperations) { Label("Wipe data and start") }
            }
        }
    }
}
