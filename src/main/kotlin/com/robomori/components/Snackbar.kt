package com.robomori.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.robomori.theme.RobomoriTheme
import com.composeunstyled.LocalContentColor

/**
 * A message that stays until the person acts on it, with its [actions] at the end, such as Upload and Discard. It
 * fills the width it's given up to Material's 600dp, and is at least Material's 48dp tall. It's dark, like a [Toast],
 * so [actions] inherit the toast's text color: Ghost buttons read on it, and a Primary button stands out.
 */
@Composable
fun Snackbar(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    /** Details after [text], dimmed, such as a file's size. */
    supportingText: String? = null,
    tag: String = "Snackbar",
    actions: @Composable RowScope.() -> Unit = {},
) {
    val shape = RoundedCornerShape(12.dp)
    CompositionLocalProvider(LocalContentColor provides RobomoriTheme.colors.toastText) {
        Row(modifier.widthIn(max = SnackbarMaxWidth).fillMaxWidth().heightIn(min = SnackbarMinHeight)
            .shadow(8.dp, shape).background(RobomoriTheme.colors.toastBackground, shape)
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp).testTag(tag),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (icon != null) Icon(icon, tint = RobomoriTheme.colors.toastText, modifier = Modifier.size(16.dp))
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Label(text, color = RobomoriTheme.colors.toastText, maxLines = 2, modifier = Modifier.weight(1f, fill = false))
                if (supportingText != null) Label(supportingText, color = RobomoriTheme.colors.toastText.copy(alpha = .6f), maxLines = 1)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), content = actions)
        }
    }
}

/** Material 3's snackbar measurements: a single line's height, and the widest a snackbar grows. */
val SnackbarMinHeight = 48.dp
val SnackbarMaxWidth = 600.dp
