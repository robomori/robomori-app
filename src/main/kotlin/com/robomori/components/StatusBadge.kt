package com.robomori.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.robomori.theme.RobomoriTheme
import com.composeunstyled.Text

/** What a [StatusBadge] says about its status, which sets its color. */
enum class StatusTone { Success, Info, Warning, Danger, Neutral }

/** A short status, such as an HTTP status code, in its [tone]'s color on a light tint of it, as wide as its text. */
@Composable
fun StatusBadge(text: String, tone: StatusTone, modifier: Modifier = Modifier) {
    val colors = RobomoriTheme.colors
    val color = when (tone) {
        StatusTone.Success -> colors.success
        StatusTone.Info -> colors.info
        StatusTone.Warning -> colors.warning
        StatusTone.Danger -> colors.danger
        StatusTone.Neutral -> colors.textSecondary
    }
    Text(text, modifier.background(color.copy(alpha = 0.22f), RoundedCornerShape(4.dp)).padding(horizontal = BadgePadding, vertical = 1.dp),
        color = color, style = badgeTextStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** The badge's text, in the monospaced type its values read best in, for measuring room for one. */
internal val badgeTextStyle @Composable get() = RobomoriTheme.typography.codeSmall.copy(fontWeight = FontWeight.Medium)

/** The room on each side of a badge's text. */
internal val BadgePadding = 6.dp
