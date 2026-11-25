@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.robomori

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.robomori.components.Icon
import com.robomori.components.Button
import com.robomori.components.ButtonSize
import com.robomori.components.ButtonStyle
import com.robomori.iconography.Icons
import com.robomori.iconography.RecordCircle
import com.robomori.iconography.StopCircle
import com.robomori.theme.RobomoriTheme
import kotlinx.coroutines.delay
import com.composeunstyled.Text

@Composable internal fun RecordingButton(device: DeviceTarget, state: AppState, enabled: Boolean) {
    val recording = state.recordingDevices[device.name] == true
    val startedAt = state.recordingStartedAt[device.name]
    var elapsedSeconds by remember(device.name) { mutableLongStateOf(0) }
    LaunchedEffect(startedAt) {
        if (startedAt == null) {
            elapsedSeconds = 0
            return@LaunchedEffect
        }
        while (true) {
            elapsedSeconds = ((System.nanoTime() - startedAt) / 1_000_000_000L).coerceAtLeast(0)
            delay(250)
        }
    }
    val timer = "%02d:%02d".format(elapsedSeconds / 60, elapsedSeconds % 60)
    val description = if (recording) "Stop recording" else "Record video"
        Button(
            onClick = { state.toggleRecording(device) }, enabled = enabled,
            style = ButtonStyle.Secondary, size = ButtonSize.Icon,
            tag = "CaptureRecording",
            contentDescription = description,
        ) {
            // Animating the button itself would clip its focus ring to its bounds.
            Row(Modifier.animateContentSize(tween(220)), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (recording) Icons.StopCircle else Icons.RecordCircle, modifier = Modifier.size(18.dp))
                if (recording) Text(timer,
                    style = RobomoriTheme.typography.body.copy(fontFeatureSettings = "tnum"))
            }
        }
}
