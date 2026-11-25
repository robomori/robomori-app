package com.robomori.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.composeunstyled.UnstyledProgress
import com.composeunstyled.Indicator
import com.robomori.theme.RobomoriTheme
import kotlinx.coroutines.delay

/**
 * Work going on for an unknown time, as the macOS spinner shows it: eight dashes around a circle, the brightest stepping
 * round. 16dp for inline use, as in a row or button; larger on its own, such as while a page loads.
 */
@Composable
fun Spinner(modifier: Modifier = Modifier, size: Dp = 16.dp) {
    var step by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(SpinnerFrameMillis)
            step = (step + 1) % SpinnerDashes
        }
    }
    val color = RobomoriTheme.colors.textDisabled
    Canvas(modifier.size(size).semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }) {
        val radius = this.size.minDimension / 2
        val width = radius * .2f
        for (dash in 0 until SpinnerDashes) {
            // The dash at the current step is darkest; those behind it fade.
            val age = (step - dash + SpinnerDashes) % SpinnerDashes
            rotate(360f / SpinnerDashes * dash) {
                drawLine(color.copy(alpha = 1f - age * .1f), Offset(center.x, center.y - radius * .45f),
                    Offset(center.x, center.y - radius + width / 2), width, StrokeCap.Round)
            }
        }
    }
}

private const val SpinnerDashes = 8
private const val SpinnerFrameMillis = 125L

/**
 * How far along a task is: [progress] from 0 to 1, or, with null, that it's going on for an unknown time, as a
 * highlight sweeping along the bar.
 */
@Composable
fun ProgressBar(progress: Float?, modifier: Modifier = Modifier) {
    val colors = RobomoriTheme.colors
    val shape = RoundedCornerShape(50)
    if (progress != null) {
        UnstyledProgress(progress.coerceIn(0f, 1f), modifier.fillMaxWidth().height(4.dp).clip(shape).background(colors.controlTrack)) {
            Indicator(Modifier.background(colors.accent, shape))
        }
        return
    }
    val sweep by rememberInfiniteTransition().animateFloat(0f, 1f,
        infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Restart))
    BoxWithConstraints(modifier.fillMaxWidth().height(4.dp).clip(shape).background(colors.accent.copy(alpha = .35f))
        .semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }) {
        val highlight = minOf(140.dp, maxWidth)
        Box(Modifier.width(highlight).fillMaxHeight().offset(x = (maxWidth + highlight) * sweep - highlight)
            .background(colors.accent, shape))
    }
}
