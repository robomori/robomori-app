package com.robomori.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composeunstyled.UnstyledButton
import com.composables.compose.ripple.rememberRippleIndication
import com.robomori.theme.RobomoriTheme
import com.robomori.theme.appFocusRing
import com.composeunstyled.LocalContentColor
import com.composeunstyled.Text

// Colors and measurements from TypingMind's buy button (Tailwind): red-600 → rose-600 → orange-500,
// a red-300/70 border, a red-500 glow, rounded-2xl, and a white/15 icon tile with a white/25 ring.
private val Shape = RoundedCornerShape(16.dp)
private val TileShape = RoundedCornerShape(12.dp)
private val Gradient = Brush.horizontalGradient(listOf(Color(0xFFDC2626), Color(0xFFE11D48), Color(0xFFF97316)))
private val Border = Color(0xFFFCA5A5).copy(alpha = .7f)
private val Glow = Color(0xFFEF4444)

/**
 * A red-to-orange gradient button for the one action a screen most wants taken, such as buying a license, after
 * TypingMind's: an optional [icon] in a frosted tile that sends out a wave, a bold [title] and a short [subtitle].
 * A shine sweeps across it on hover.
 */
@Composable
fun GradientButton(
    title: String,
    subtitle: String,
    icon: ImageVector? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tag: String? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val glow by animateFloatAsState(if (pressed) .2f else if (hovered) .35f else .25f, tween(150), label = "Glow")
    // The shine crosses the button once each time the pointer arrives, as with Tailwind's group-hover translate.
    val shine by animateFloatAsState(if (hovered) 1f else -1f, if (hovered) tween(700) else snap(), label = "Shine")
    UnstyledButton(
        onClick = onClick,
        role = Role.Button,
        interactionSource = interaction,
        indication = rememberRippleIndication(color = Color.White),
        modifier = modifier
            .shadow(14.dp, Shape, ambientColor = Glow.copy(alpha = glow), spotColor = Glow.copy(alpha = glow))
            .clip(Shape)
            .background(Gradient)
            .drawWithContent {
                drawContent()
                val sweep = size.width * shine
                drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = .25f), Color.Transparent),
                    startX = sweep, endX = sweep + size.width))
            }
            .border(1.dp, Border, Shape)
            .appFocusRing(interaction, Shape)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
    ) {
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = if (icon != null) 8.dp else 14.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) IconTile(icon)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, color = Color.White, fontWeight = FontWeight.Bold,
                        style = RobomoriTheme.typography.body.merge(TextStyle(fontSize = 14.sp, lineHeight = 16.sp)))
                    Text(subtitle, color = Color.White.copy(alpha = .85f), fontWeight = FontWeight.Medium,
                        style = RobomoriTheme.typography.small.merge(TextStyle(fontSize = 11.sp, lineHeight = 12.sp)))
                }
            }
        }
    }
}

/** A frosted tile holding [icon], with a copy of the tile that grows to twice its size and fades: a wave every second. */
@Composable
private fun IconTile(icon: ImageVector) {
    val wave = rememberInfiniteTransition(label = "Wave")
    // Tailwind's animate-ping: 1s, cubic-bezier(0, 0, .2, 1), reaching scale 2 and no opacity at 75%, then holding.
    val progress by wave.animateFloat(0f, 1f, infiniteRepeatable(keyframes {
        durationMillis = 1000
        0f at 0 using CubicBezierEasing(0f, 0f, .2f, 1f)
        1f at 750
        1f at 1000
    }), label = "Wave progress")
    Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.matchParentSize().clip(TileShape).background(Color.White.copy(alpha = .15f)).border(1.dp, Color.White.copy(alpha = .25f), TileShape))
        Icon(icon, tint = Color.White, modifier = Modifier.size(20.dp))
        Box(Modifier.matchParentSize().graphicsLayer {
            scaleX = 1f + progress
            scaleY = 1f + progress
            alpha = 1f - progress
        }.clip(TileShape).background(Color.White.copy(alpha = .35f)))
    }
}
