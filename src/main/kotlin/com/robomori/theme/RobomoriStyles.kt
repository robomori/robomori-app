package com.robomori.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Popups, menus and dialogs: their surface, outline and shadow. */
@Immutable
data class PopupStyle(
    val background: Color,
    val border: Color,
    val shadow: Color,
    val cornerSize: Dp = 8.dp,
    val borderWidth: Dp = 1.dp,
    val shadowSize: Dp = 12.dp,
)

object RobomoriStyles {
    /** Popups, menus and dialogs in the theme in use. */
    val popupContainer: PopupStyle
        @Composable get() {
            val colors = RobomoriTheme.colors
            return PopupStyle(colors.popupBackground, colors.popupBorder, colors.popupShadow)
        }
}
