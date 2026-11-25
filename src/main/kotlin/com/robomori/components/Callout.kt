package com.robomori.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.robomori.iconography.ExclamationCircle
import com.robomori.iconography.Icons
import com.robomori.iconography.InformationCircle
import com.robomori.theme.RobomoriTheme
import com.composeunstyled.Text

/**
 * A note that stands apart from what's around it, such as why a request can't be read: [text] beside its [tone]'s
 * icon, on a light tint of the tone with a border in it. Success and Neutral tones read as Info.
 */
@Composable
fun Callout(text: String, tone: StatusTone, modifier: Modifier = Modifier) {
    val colors = RobomoriTheme.colors
    val color = when (tone) {
        StatusTone.Warning -> colors.warning
        StatusTone.Danger -> colors.danger
        else -> colors.info
    }
    val shape = RoundedCornerShape(8.dp)
    Row(modifier.fillMaxWidth().background(color.copy(alpha = 0.1f), shape).border(1.dp, color.copy(alpha = 0.35f), shape)
        .padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Icon(if (tone == StatusTone.Warning || tone == StatusTone.Danger) Icons.ExclamationCircle else Icons.InformationCircle,
            tint = color, modifier = Modifier.padding(top = 1.dp).size(16.dp))
        Text(text, color = colors.text, style = RobomoriTheme.typography.small)
    }
}
