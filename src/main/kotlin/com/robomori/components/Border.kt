package com.robomori.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection

enum class BorderSide { Start, Top, End, Bottom }

fun Modifier.border(width: Dp, color: Color, sides: List<BorderSide>): Modifier = drawWithContent {
    drawContent()
    val thickness = width.toPx().coerceAtLeast(0f)
    val horizontal = thickness.coerceAtMost(size.height)
    val vertical = thickness.coerceAtMost(size.width)
    if (BorderSide.Top in sides) drawRect(color, size = Size(size.width, horizontal))
    if (BorderSide.Bottom in sides) drawRect(color, Offset(0f, size.height - horizontal), Size(size.width, horizontal))
    val left = if (layoutDirection == LayoutDirection.Ltr) BorderSide.Start else BorderSide.End
    val right = if (layoutDirection == LayoutDirection.Ltr) BorderSide.End else BorderSide.Start
    if (left in sides) drawRect(color, size = Size(vertical, size.height))
    if (right in sides) drawRect(color, Offset(size.width - vertical, 0f), Size(vertical, size.height))
}
