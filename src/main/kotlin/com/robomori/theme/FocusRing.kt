package com.robomori.theme

import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.composeunstyled.focusRing

/**
 * The ring around the control that has focus, drawn only when the keyboard moved focus there, not after a click, as
 * Compose Unstyled's [com.composeunstyled.FocusVisibilityProvider] around the app tells.
 */
@Composable
fun Modifier.appFocusRing(interaction: InteractionSource, shape: Shape, offset: Dp = 0.dp): Modifier =
    focusRing(interaction, 2.dp, RobomoriTheme.colors.accent, shape, offset = offset)
