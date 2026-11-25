package com.robomori.components

import com.robomori.theme.RobomoriTheme
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.composeunstyled.UnstyledSlider
import com.robomori.theme.appFocusRing

@Composable
fun Slider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val colors = RobomoriTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val dragged by interaction.collectIsDraggedAsState()
    val thumbBorder = when {
        !enabled -> colors.controlTrack
        pressed || dragged -> colors.textSecondary
        hovered -> colors.popupBorder
        else -> colors.textDisabled
    }
    val thumbWidth = ThumbSize + ThumbBorder * 2
    val thumbHeight = ThumbSize + ThumbBorder * 2

    Box(modifier.semantics(mergeDescendants = true) {}) {
        UnstyledSlider(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            valueRange = valueRange,
            steps = steps,
            onValueChangeFinished = onValueChangeFinished,
            interactionSource = interaction,
            modifier = Modifier.fillMaxWidth(),
            track = { slider ->
                Canvas(Modifier.fillMaxWidth().height(thumbHeight)) {
                    val inset = thumbWidth.toPx() / 2
                    val left = Offset(inset, size.height / 2)
                    val right = Offset((size.width - inset).coerceAtLeast(inset), size.height / 2)
                    val start = if (layoutDirection == LayoutDirection.Rtl) right else left
                    val end = if (layoutDirection == LayoutDirection.Rtl) left else right
                    drawLine(if (enabled) colors.shelfBorder else colors.separator,
                        start, end, TrackHeight.toPx(), StrokeCap.Round)
                    if (slider.fraction > 0f) {
                        drawLine(if (enabled) colors.accent else colors.controlTrack,
                            start, Offset(start.x + (end.x - start.x) * slider.fraction, start.y),
                            TrackHeight.toPx(), StrokeCap.Round)
                    }
                    if (steps > 0) {
                        val radius = 1.dp.toPx()
                        val y = size.height / 2 + TrackHeight.toPx() / 2 + 3.dp.toPx() + radius
                        repeat(steps + 2) { index ->
                            val x = left.x + (right.x - left.x) * index / (steps + 1)
                            drawCircle(colors.shelfBorder, radius, Offset(x, y))
                        }
                    }
                }
            },
            thumb = {
                Box(Modifier.size(thumbWidth, thumbHeight).hoverable(interaction, enabled)
                    .background(thumbBorder, CircleShape).padding(ThumbBorder)
                    .appFocusRing(interaction, CircleShape)) {
                    Spacer(Modifier.size(ThumbSize).background(colors.popupBackground, CircleShape))
                }
            },
        )
    }
}

private val ThumbSize = 14.dp
private val ThumbBorder = 1.dp
private val TrackHeight = 4.dp
