package com.robomori

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.composeunstyled.AnchorAlignment
import com.robomori.components.*
import com.robomori.iconography.ScanBox
import com.robomori.iconography.ScanFace
import com.robomori.iconography.Icons
import com.robomori.iconography.Mic
import com.robomori.theme.RobomoriTheme

private const val EMULATED = "Emulated"

@Composable
fun EmulatorMediaPage(state: AppState) {
    LaunchedEffect(Unit) { state.refreshHostMedia() }
    val cameras = state.hostCameras
    val media = state.pendingMedia ?: state.media
    val cameraNames = cameras.map { it.name } + EMULATED
    fun source(name: String): CameraSource =
        cameras.firstOrNull { it.name == name }?.let { CameraSource.Host(it.id) } ?: CameraSource.Emulated
    val microphones = state.hostMicrophones
    // Only a Mac tells the default input's name; elsewhere it's a generic one.
    val systemDefault = state.hostMicrophone?.takeIf { HostOs.detect() == HostOs.MAC }?.let { "Same as System ($it)" } ?: "Same as System"
    val microphone = state.media.microphone(microphones) ?: systemDefault.takeIf { microphones.isNotEmpty() } ?: EMULATED
    SettingsPageScaffold(SettingsSection.Media.title, "EmulatorMediaPage") {
        Card(contentPadding = PaddingValues(horizontal = 16.dp)) {
            SelectRow("Front camera", Icons.ScanFace, media.frontCamera(cameras)?.name ?: EMULATED, cameraNames,
                "EmulatorFrontCamera", enabled = cameras.isNotEmpty(), menuAlignment = AnchorAlignment.End) { name ->
                state.changeMedia(state.media.copy(frontCamera = source(name)))
            }
            CardDivider()
            SelectRow("Back camera", Icons.ScanBox, media.backCamera(cameras)?.name ?: EMULATED, cameraNames,
                "EmulatorBackCamera", enabled = cameras.isNotEmpty(), menuAlignment = AnchorAlignment.End) { name ->
                state.changeMedia(state.media.copy(backCamera = source(name)))
            }
            CardDivider()
            SelectRow("Microphone", Icons.Mic, microphone, listOf(systemDefault) + microphones, "EmulatorMicrophone",
                enabled = microphones.isNotEmpty(), menuAlignment = AnchorAlignment.End) { name ->
                state.changeMicrophone(name.takeIf { it in microphones })
            }
        }
    }
}

@Composable
fun MediaRestartDialog(state: AppState) {
    val laterFocus = remember { FocusRequester() }
    AppDialogPanel("Restart running emulators?", "MediaRestartDialog", Modifier.width(460.dp)) {
        LaunchedEffect(Unit) { laterFocus.requestFocus() }
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Label("All running emulators will restart to use the new camera.",
                color = RobomoriTheme.colors.textSecondary)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Spacer(Modifier.weight(1f))
                Button(onClick = { state.applyMediaChange(restartNow = false) }, style = ButtonStyle.Outlined,
                    modifier = Modifier.focusRequester(laterFocus), tag = "ApplyMediaOnNextLaunch") { Label("Apply on next launch") }
                Button(onClick = { state.applyMediaChange(restartNow = true) }, style = ButtonStyle.Primary,
                    tag = "RestartForMedia", enabled = !state.busy) { Label("Restart now") }
            }
        }
    }
}
