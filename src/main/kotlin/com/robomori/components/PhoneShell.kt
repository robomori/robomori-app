package com.robomori.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize

/**
 * The body of a phone around [screen]: a metal rim, a dark glass bezel, a front camera and side buttons. Its parts
 * scale with the screen's shorter side, so any phone, at any size, keeps the same proportions.
 */
object PhoneShellProportions {
    /** The metal rim's thickness. */
    const val RIM = .014f
    /** The dark glass between the rim and the screen. */
    const val BEZEL = .042f
    /** The outer corners' radius. */
    const val CORNER = .15f

    /** The shell's size around a portrait [screen]. */
    fun shellSize(screen: DpSize): DpSize {
        val edge = minOf(screen.width, screen.height) * (RIM + BEZEL) * 2
        return DpSize(screen.width + edge, screen.height + edge)
    }

    /** The screen's width when the whole shell is [shellWidth] wide, for a portrait screen [width] by [height]. */
    fun screenWidth(shellWidth: Dp, width: Float, height: Float): Dp =
        shellWidth / (1 + 2 * (RIM + BEZEL) * minOf(1f, height / width))
}

private val rimLight = Color(0xFFB4B6BD)
private val rimMid = Color(0xFF6E7078)
private val rimDark = Color(0xFF45464C)
private val glass = Color(0xFF0B0B0D)
private val lens = Color(0xFF1E2027)
private val lensGlint = Color(0xFF3A4152)

/**
 * A phone drawn around [content], which fills its portrait screen of [screen] size. Rotate the whole shell to show
 * the phone sideways.
 */
@Composable
fun PhoneShell(screen: DpSize, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val side = minOf(screen.width, screen.height)
    val inset = side * (PhoneShellProportions.RIM + PhoneShellProportions.BEZEL)
    val outerRadius = side * PhoneShellProportions.CORNER
    val screenRadius = outerRadius - inset * .8f
    Box(modifier.requiredSize(PhoneShellProportions.shellSize(screen)).drawBehind {
        val rim = side.toPx() * PhoneShellProportions.RIM
        val bezel = side.toPx() * PhoneShellProportions.BEZEL
        val radius = outerRadius.toPx()
        // Side buttons sit on the right edge, behind the rim: power above the longer volume rocker.
        val buttonDepth = rim * 1.1f
        listOf(.2f to .07f, .3f to .13f).forEach { (top, length) ->
            drawRoundRect(
                Brush.horizontalGradient(listOf(rimMid, rimLight), startX = size.width - rim, endX = size.width + buttonDepth),
                Offset(size.width - rim, size.height * top), Size(rim + buttonDepth, size.height * length),
                CornerRadius(buttonDepth),
            )
        }
        drawRoundRect(Brush.linearGradient(listOf(rimLight, rimMid, rimDark, rimMid, rimLight), Offset.Zero,
            Offset(size.width, size.height)), cornerRadius = CornerRadius(radius))
        drawRoundRect(glass, Offset(rim, rim), Size(size.width - rim * 2, size.height - rim * 2), CornerRadius(radius - rim))
        // The front camera, centred in the top bezel.
        val camera = Offset(size.width / 2, rim + bezel / 2)
        drawCircle(lens, bezel * .32f, camera)
        drawCircle(lensGlint, bezel * .12f, camera + Offset(-bezel * .08f, -bezel * .08f))
    }.padding(inset)) {
        Box(Modifier.requiredSize(screen).clip(RoundedCornerShape(screenRadius)), content = content)
    }
}
