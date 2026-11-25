package com.robomori

import com.robomori.components.Button
import com.robomori.components.ButtonStyle
import com.robomori.components.ButtonSize
import com.robomori.components.Icon
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.robomori.components.IconButtonGroup
import com.robomori.components.Label
import com.robomori.iconography.*

@Composable
internal fun TvRemote(state: AppState, device: Device) {
    TvRemote { key -> state.sendInput(device, listOf("keyevent", key)) }
}

@Composable
internal fun TvRemote(press: (String) -> Unit) {
    Row(Modifier.testTag("TvRemote"), horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconButtonGroup(contentPadding = 0.dp) {
            Button(onClick = { press("KEYCODE_BACK") }, style = ButtonStyle.Ghost, size = ButtonSize.Icon, contentDescription = "Back", tag = "RemoteBack") { Icon(Icons.ArrowLeft) }
        }
        IconButtonGroup(contentPadding = 0.dp) {
            Button(onClick = { press("KEYCODE_HOME") }, style = ButtonStyle.Ghost, size = ButtonSize.Icon, contentDescription = "Home", tag = "RemoteHome") { Icon(Icons.RemoteHome) }
        }
        IconButtonGroup(contentPadding = 0.dp) {
            Button(onClick = { press("KEYCODE_MEDIA_PLAY_PAUSE") }, style = ButtonStyle.Ghost, size = ButtonSize.Icon, contentDescription = "Play or pause", tag = "RemotePlayPause") { Icon(Icons.RemotePlayPause) }
        }
        listOf("Left" to 90f, "Up" to 180f).forEach { (direction, turn) ->
            IconButtonGroup(contentPadding = 0.dp) {
                Button(onClick = {
                    press("KEYCODE_DPAD_${direction.uppercase()}")
                }, style = ButtonStyle.Ghost, size = ButtonSize.Icon, contentDescription = direction, tag = "Remote$direction") { Icon(Icons.ChevronDown, modifier = Modifier.size(18.dp).rotate(turn)) }
            }
        }
        IconButtonGroup(contentPadding = 0.dp) {
            Button(
                onClick = { press("KEYCODE_DPAD_CENTER") },
                style = ButtonStyle.Ghost, size = ButtonSize.Icon,
                tag = "RemoteOk", contentDescription = "OK",
            ) {
                Label("OK")
            }
        }
        listOf("Down" to 0f, "Right" to -90f).forEach { (direction, turn) ->
            IconButtonGroup(contentPadding = 0.dp) {
                Button(onClick = {
                    press("KEYCODE_DPAD_${direction.uppercase()}")
                }, style = ButtonStyle.Ghost, size = ButtonSize.Icon, contentDescription = direction, tag = "Remote$direction") { Icon(Icons.ChevronDown, modifier = Modifier.size(18.dp).rotate(turn)) }
            }
        }
    }
}
