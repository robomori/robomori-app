package com.robomori.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.robomori.theme.RobomoriTheme
import com.composeunstyled.Text

@Composable
fun AppBar(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(modifier.heightIn(min = 64.dp).padding(start = 24.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, modifier = Modifier.testTag("PreviewDeviceName"),
                style = RobomoriTheme.typography.heading, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Label(subtitle, color = RobomoriTheme.colors.textSecondary, maxLines = 1)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

@Composable
/**
 * Buttons side by side in one lit capsule. [elevated] casts the theme's shadow under it, for a group layered over content,
 * such as the app bar's over the device; one sitting in its own panel has none.
 */
fun IconButtonGroup(modifier: Modifier = Modifier, contentPadding: Dp = 4.dp, elevated: Boolean = false,
                    content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = modifier.raised(if (elevated) RobomoriTheme.shadows.elevated else null).padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * Raised off what's under it, the way macOS's toolbar buttons are, without going all the way to glass: lit from above,
 * so its rim is brighter on top and its fill a touch darker towards the bottom, with a soft highlight just inside the top
 * edge, and [shadow] under it when it has one.
 */
private fun Modifier.raised(shadow: Shadow?): Modifier = this
    .then(if (shadow != null) Modifier.dropShadow(CircleShape, shadow) else Modifier)
    .background(Brush.verticalGradient(listOf(Color.White, Color(0xFFF6F6F7))), CircleShape)
    .innerShadow(CircleShape, Shadow(radius = 1.dp, color = Color.White, offset = DpOffset(0.dp, 1.dp)))
    .innerShadow(CircleShape, Shadow(radius = 3.dp, color = Color.Black, offset = DpOffset(0.dp, (-1).dp), alpha = 0.04f))
    .border(1.dp, Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.05f), Color.Black.copy(alpha = 0.13f))), CircleShape)
