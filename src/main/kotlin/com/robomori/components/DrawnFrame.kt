package com.robomori.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize

/** A button on the top edge of a [DrawnFrameSpec], [start] millimetres from the body's left, [length] long. */
data class TopEdgeButton(val start: Float, val length: Float)

/**
 * A device drawn from its real measurements, in millimetres, held upright: for models the Android SDK has no frame
 * for. The screen sits centred in the body.
 */
data class DrawnFrameSpec(
    val bodyWidth: Float,
    val bodyHeight: Float,
    val screenWidth: Float,
    val screenHeight: Float,
    val corner: Float,
    /** The rounding of the screen's corners; 0 for e-paper and other panels under flat glass. */
    val screenCorner: Float,
    val body: Color,
    val edge: Color,
    val buttons: List<TopEdgeButton> = emptyList(),
) {
    /** The whole frame's size around an upright [screen]. */
    fun frameSize(screen: DpSize): DpSize {
        val scale = screen.width / screenWidth
        return DpSize(scale * bodyWidth, scale * bodyHeight)
    }

    /** The screen's width when the whole frame is [frameWidth] wide. */
    fun screenWidth(frameWidth: Dp): Dp = frameWidth * (screenWidth / bodyWidth)
}

/** The frames drawn for devices by their model name, as `adb devices -l` reports it. */
object DrawnFrames {
    /**
     * ONYX BOOX Go 10.3: a 183 × 235 mm off-white body around a 10.3" 4:3 e-paper screen, with even bezels, no
     * buttons on the front, and the power button on the top edge.
     */
    val BooxGo103 = DrawnFrameSpec(
        bodyWidth = 183f, bodyHeight = 235f, screenWidth = 157.2f, screenHeight = 209.6f, corner = 7f, screenCorner = 0f,
        body = Color(0xFFE9E8E4), edge = Color(0xFFC6C4BE), buttons = listOf(TopEdgeButton(start = 150f, length = 14f)),
    )

    fun forModel(model: String): DrawnFrameSpec? = when (model) {
        "Go103" -> BooxGo103
        else -> null
    }
}

/** [spec]'s body drawn around [content], which fills its upright screen of [screen] size. Rotate it to show it sideways. */
@Composable
fun DrawnFrame(spec: DrawnFrameSpec, screen: DpSize, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val scale = screen.width / spec.screenWidth
    val frame = spec.frameSize(screen)
    Box(modifier.requiredSize(frame).drawBehind {
        val mm = scale.toPx()
        val radius = CornerRadius(spec.corner * mm)
        // Buttons stand a little proud of the top edge, behind the body.
        spec.buttons.forEach { button ->
            drawRoundRect(spec.edge, Offset(button.start * mm, -mm), Size(button.length * mm, mm * 2), CornerRadius(mm))
        }
        drawRoundRect(spec.body, cornerRadius = radius)
        drawRoundRect(spec.edge, cornerRadius = radius, style = Stroke(mm * .6f))
    }, contentAlignment = androidx.compose.ui.Alignment.Center) {
        Box(Modifier.requiredSize(screen).clip(RoundedCornerShape(scale * spec.screenCorner)), content = content)
    }
}
