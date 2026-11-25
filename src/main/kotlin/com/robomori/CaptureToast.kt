package com.robomori

import com.robomori.components.Icon
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.robomori.components.Label
import com.robomori.iconography.Icons
import com.robomori.iconography.ChevronDown
import com.robomori.iconography.ExclamationCircle
import com.robomori.theme.RobomoriTheme
import com.robomori.theme.appFocusRing
import com.composables.compose.ripple.rememberRippleIndication
import com.composeunstyled.UnstyledButton
import java.io.File

/** The result of a capture. When it saved a file, the whole bubble opens it. */
@Composable internal fun CaptureToast(message: String, file: File?, onOpen: () -> Unit) {
    // Colored like the app's other toasts, so it reads as one.
    val shape = RoundedCornerShape(percent = 50)
    val text = RobomoriTheme.colors.toastText
    val bubble = Modifier.padding(horizontal = 16.dp).widthIn(max = 500.dp)
    val surface = Modifier.background(RobomoriTheme.colors.toastBackground, shape)
    val content: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(if (file == null) 8.dp else 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (file == null) Icon(Icons.ExclamationCircle, tint = text)
            Label(message, modifier = Modifier.widthIn(max = 420.dp).testTag("CaptureNotice"), maxLines = 2, color = text)
            if (file != null) Icon(Icons.ChevronDown, modifier = Modifier.size(18.dp).rotate(-90f), tint = text)
        }
    }
    if (file == null) {
        Box(bubble.then(surface).padding(start = 12.dp, end = 16.dp, top = 6.dp, bottom = 6.dp)) { content() }
        return
    }
    val interaction = remember { MutableInteractionSource() }
    UnstyledButton(onClick = onOpen, interactionSource = interaction,
        indication = rememberRippleIndication(color = text),
        contentPadding = PaddingValues(start = 16.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
        modifier = bubble.testTag("OpenCaptureLocation")
            .semantics { onClick(label = "Open in ${HostPlatform().fileManagerName}") { onOpen(); true } }
            .appFocusRing(interaction, shape).clip(shape).then(surface)) { content() }
}
