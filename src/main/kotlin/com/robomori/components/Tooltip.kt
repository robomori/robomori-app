package com.robomori.components

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import com.composeunstyled.AnchorSide
import com.composeunstyled.Text
import com.composeunstyled.TooltipPanel
import com.composeunstyled.UnstyledTooltip
import com.robomori.theme.RobomoriTheme

/**
 * [text] in a dark capsule above [content] while the pointer rests on it, or below when there's no room above. Needs a
 * [com.composeunstyled.TooltipHost] around the window's content.
 */
@Composable
fun Tooltip(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable () -> Unit) {
    val colors = RobomoriTheme.colors
    val shape = RoundedCornerShape(6.dp)
    UnstyledTooltip(
        enabled = enabled,
        side = AnchorSide.Top,
        sideOffset = 6.dp,
        hoverDelayMillis = TooltipDelayMillis,
        panel = {
            TooltipPanel(enter = fadeIn(tween(120)), exit = fadeOut(tween(100))) {
                Text(text, Modifier.shadow(6.dp, shape, ambientColor = colors.popupShadow, spotColor = colors.popupShadow)
                    .background(colors.toastBackground, shape).padding(horizontal = 8.dp, vertical = 5.dp),
                    color = colors.toastText, style = RobomoriTheme.typography.small)
            }
        },
    ) { Box(modifier) { content() } }
}

/** How long the pointer rests before a tooltip shows, as on macOS. */
private const val TooltipDelayMillis = 600L
