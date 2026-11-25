package com.robomori

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

/** The zoom levels, as multiples of the size that fits the device in its space; 1 fits it. */
internal val DeviceZoomLevels = listOf(0.25f, 0.33f, 0.5f, 0.67f, 0.75f, 0.9f, 1f, 1.25f, 1.5f, 2f, 3f, 4f)

/** The next level up from [zoom], or [zoom] when it's the largest. */
internal fun zoomedIn(zoom: Float) = DeviceZoomLevels.firstOrNull { it > zoom + 0.001f } ?: zoom

/** The next level down from [zoom], or [zoom] when it's the smallest. */
internal fun zoomedOut(zoom: Float) = DeviceZoomLevels.lastOrNull { it < zoom - 0.001f } ?: zoom

/**
 * The space a device preview sits in, at [zoom] times the size that fits it. [content] fits the device into the
 * viewport it's given, as it does without zoom. Zoom changes animate around the centre. Zoomed in past the space,
 * scrolling moves the device up and down, as far as its edges; it stays centred side to side. [overlay] stays in place over the space, such as a toast in its corner.
 */
@Composable
internal fun ZoomedDevicePreview(
    zoom: Float,
    modifier: Modifier = Modifier,
    overlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable BoxScope.(viewport: DpSize) -> Unit,
) {
    val animated by animateFloatAsState(zoom, tween(250, easing = FastOutSlowInEasing))
    // Not clipped: zoomed in, the device carries on behind the bars around it, which draw over it.
    BoxWithConstraints(modifier) {
        val space = DpSize(maxWidth, maxHeight)
        val viewport = DpSize(space.width * animated, space.height * animated)
        // How far the device can move up or down from the centre before its edge comes into the space.
        val limit = with(LocalDensity.current) { Offset(0f, ((viewport.height - space.height) / 2).toPx().coerceAtLeast(0f)) }
        var pan by remember { mutableStateOf(Offset.Zero) }
        val currentLimit by rememberUpdatedState(limit)
        fun clamp(offset: Offset) = Offset(offset.x.coerceIn(-currentLimit.x, currentLimit.x), offset.y.coerceIn(-currentLimit.y, currentLimit.y))
        // Moves the device by [delta], and returns how much of it was used before an edge stopped it.
        fun move(delta: Offset): Offset {
            val from = clamp(pan)
            pan = clamp(from + delta)
            return pan - from
        }
        val vertical = rememberScrollableState { move(Offset(0f, it)).y }
        val shown = clamp(pan)
        Box(Modifier.fillMaxSize()
            .scrollable(vertical, Orientation.Vertical)) {
            Box(Modifier.fillMaxSize().offset { IntOffset(shown.x.roundToInt(), shown.y.roundToInt()) },
                contentAlignment = Alignment.Center) {
                content(viewport)
            }
        }
        overlay()
    }
}
