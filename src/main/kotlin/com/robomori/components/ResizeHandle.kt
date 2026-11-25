package com.robomori.components

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import java.awt.BasicStroke
import java.awt.Color as AwtColor
import java.awt.Cursor
import java.awt.Point
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.geom.Path2D
import java.awt.image.BaseMultiResolutionImage
import java.awt.image.BufferedImage

/**
 * A grab area that resizes something by dragging it sideways, such as a column or a pane. The size follows the pointer
 * from where the drag began, held between [min] and [max]: past a limit it stays put, and changes again only once the
 * pointer comes back to the edge. At a limit the cursor points only the way it can still go, like the sidebar's.
 *
 * [growsLeft] is for an edge on the thing's left, such as a column's start, which grows as it's dragged left.
 * It draws nothing; place it over the line it moves.
 */
@Composable
fun ResizeHandle(
    size: Dp,
    onResize: (Dp) -> Unit,
    min: Dp,
    max: Dp,
    modifier: Modifier = Modifier,
    growsLeft: Boolean = false,
    tag: String? = null,
) {
    val density = LocalDensity.current
    // The size the pointer asks for, unclamped, so the drag can pass a limit and come back.
    var requested by remember { mutableStateOf(size) }
    val drag = rememberDraggableState { delta ->
        requested += with(density) { delta.toDp() } * (if (growsLeft) -1 else 1)
        onResize(requested.coerceIn(min, max))
    }
    Box(modifier
        .pointerHoverIcon(resizeCursor(size <= min, size >= max, growsLeft))
        .draggable(drag, Orientation.Horizontal, onDragStarted = { requested = size })
        .let { if (tag != null) it.testTag(tag) else it })
}

/**
 * The cursor for an edge that's [atMin] or [atMax] of its size: both ways between them, otherwise pointing the only way
 * the edge can still move. An edge on the right of what it sizes moves right to grow; [growsLeft] turns that around.
 */
internal fun resizeCursor(atMin: Boolean, atMax: Boolean, growsLeft: Boolean): PointerIcon = when {
    atMin == atMax -> BothWaysResizeCursor
    atMin != growsLeft -> PointRightResizeCursor
    else -> PointLeftResizeCursor
}

internal val BothWaysResizeCursor = PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR))
internal val PointLeftResizeCursor by lazy { oneWayResizeCursor(pointsLeft = true) }
internal val PointRightResizeCursor by lazy { oneWayResizeCursor(pointsLeft = false) }

/**
 * A bar with an arrow on one side, like macOS's own one-way resize cursors, which Java can't ask for: AWT's east and
 * west resize cursors both show the two-way arrow on current macOS. Drawn at 1x and 2x for Retina screens.
 */
private fun oneWayResizeCursor(pointsLeft: Boolean): PointerIcon {
    val size = 24
    fun draw(scale: Int): BufferedImage {
        val image = BufferedImage(size * scale, size * scale, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.scale(scale.toDouble(), scale.toDouble())
        if (!pointsLeft) { g.translate(size.toDouble(), 0.0); g.scale(-1.0, 1.0) }
        // Drawn pointing left: the bar sits right of the hotspot, the arrow left of it.
        val bar = Path2D.Double().apply { moveTo(13.0, 5.0); lineTo(15.0, 5.0); lineTo(15.0, 19.0); lineTo(13.0, 19.0); closePath() }
        val arrow = Path2D.Double().apply {
            moveTo(3.0, 12.0); lineTo(8.0, 7.0); lineTo(8.0, 11.0); lineTo(12.0, 11.0)
            lineTo(12.0, 13.0); lineTo(8.0, 13.0); lineTo(8.0, 17.0); closePath()
        }
        g.color = AwtColor.WHITE
        g.stroke = BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.draw(bar); g.draw(arrow)
        g.fill(bar); g.fill(arrow)
        g.color = AwtColor.BLACK
        g.fill(bar); g.fill(arrow)
        g.dispose()
        return image
    }
    val image = BaseMultiResolutionImage(draw(1), draw(2))
    val hotspot = Point(if (pointsLeft) 14 else size - 14, size / 2)
    return PointerIcon(Toolkit.getDefaultToolkit().createCustomCursor(image, hotspot,
        if (pointsLeft) "resize-shrink-left" else "resize-shrink-right"))
}
