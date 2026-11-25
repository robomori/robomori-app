@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.robomori.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.compose.ripple.rememberRippleIndication
import com.composeunstyled.LocalContentColor
import com.composeunstyled.LocalTextStyle
import com.composeunstyled.UnstyledButton
import com.robomori.theme.RobomoriTheme
import com.robomori.theme.appFocusRing

enum class ButtonStyle {
    Primary,
    Secondary,
    Destructive,
    Ghost,
    Outlined,
}

/**
 * macOS (AppKit) control sizes: capsule buttons of these heights and text sizes. [Large] is the app's default;
 * [ExtraLarge] is for a screen's one main action. [Icon] is a round icon-only button.
 */
enum class ButtonSize(internal val height: Dp, internal val horizontalPadding: Dp, internal val fontSize: TextUnit) {
    Mini(16.dp, 8.dp, 9.sp),
    Small(20.dp, 10.dp, 11.sp),
    Regular(24.dp, 12.dp, 13.sp),
    Large(28.dp, 14.dp, 13.sp),
    ExtraLarge(36.dp, 18.dp, 13.sp),
    Icon(34.dp, 8.dp, 13.sp),
}

/** A button's colors in each state; [border] is its rim. */
@Immutable
internal class ButtonColors(
    val background: Brush,
    val backgroundDisabled: Brush,
    val backgroundPressed: Brush,
    val backgroundHovered: Brush,
    val content: Color,
    val contentDisabled: Color,
    val border: Brush,
    val borderDisabled: Brush,
)

/** A button's size: capsule [cornerSize] ends around [padding], at least [minHeight] tall and wide. */
@Immutable
internal class ButtonMetrics(val padding: PaddingValues, val minHeight: Dp, val borderWidth: Dp = 1.dp)

@Immutable
internal class ResolvedButtonStyle(val colors: ButtonColors, val metrics: ButtonMetrics)

/** The text style of a button's label at [size]. */
@Composable
internal fun ButtonTextStyle(size: ButtonSize, content: @Composable () -> Unit) {
    val base = RobomoriTheme.typography.body
    CompositionLocalProvider(LocalTextStyle provides base.copy(fontSize = size.fontSize, lineHeight = size.fontSize * 1.3f),
        content = content)
}

@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ButtonStyle = ButtonStyle.Secondary,
    size: ButtonSize = ButtonSize.Large,
    enabled: Boolean = true,
    contentDescription: String? = null,
    tag: String? = contentDescription,
    content: @Composable RowScope.() -> Unit,
) {
    require(size != ButtonSize.Icon || !contentDescription.isNullOrBlank()) {
        "Icon buttons need a content description."
    }
    val resolved = resolveButtonStyle(style, size)
    val colors = resolved.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val background = when {
        !enabled -> colors.backgroundDisabled
        pressed -> colors.backgroundPressed
        hovered -> colors.backgroundHovered
        else -> colors.background
    }
    val foreground = if (enabled) colors.content else colors.contentDisabled
    val button: @Composable () -> Unit = {
        UnstyledButton(
            onClick = onClick,
            enabled = enabled,
            interactionSource = interaction,
            // The same ripple as every other button, toggles included, inside the capsule.
            indication = rememberRippleIndication(color = colors.content),
            contentPadding = resolved.metrics.padding,
            modifier = modifier
                .defaultMinSize(resolved.metrics.minHeight, resolved.metrics.minHeight)
                .then(if (tag != null) Modifier.testTag(tag) else Modifier)
                .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier)
                .appFocusRing(interaction, ButtonShape)
                .clip(ButtonShape)
                .background(background, ButtonShape)
                .border(resolved.metrics.borderWidth, if (enabled) colors.border else colors.borderDisabled, ButtonShape),
        ) {
            CompositionLocalProvider(LocalContentColor provides foreground) {
                ButtonTextStyle(size) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, content = content)
                }
            }
        }
    }
    // Centred above the button, or below it when there's no room above.
    if (size == ButtonSize.Icon) Tooltip(contentDescription!!) { button() }
    else button()
}

/**
 * The colors of a raised button filled with [fill]: a gradient that's [shine] of the way to white at the top, darker when
 * hovered and pressed, and a rim that's faint on top and darker underneath, as light from above falls on it.
 */
private fun raisedColors(fill: Color, shine: Float, content: Color, contentDisabled: Color): ButtonColors {
    fun lit(color: Color) = Brush.verticalGradient(listOf(lerp(color, Color.White, shine), color))
    val rim = Brush.verticalGradient(listOf(Color.Black.copy(alpha = .05f), Color.Black.copy(alpha = .14f)))
    return ButtonColors(
        background = lit(fill),
        backgroundDisabled = SolidColor(fill.copy(alpha = .5f)),
        backgroundPressed = lit(lerp(fill, Color.Black, .12f)),
        backgroundHovered = lit(lerp(fill, Color.Black, .05f)),
        content = content,
        contentDisabled = contentDisabled,
        border = rim,
        borderDisabled = SolidColor(Color.Transparent),
    )
}

/** Buttons are capsules. */
internal val ButtonShape = RoundedCornerShape(50)

/** Each button style's colors at [size]; focus shows as [appFocusRing], not as a look of the button's own. */
@Composable
internal fun resolveButtonStyle(style: ButtonStyle, size: ButtonSize): ResolvedButtonStyle {
    val theme = RobomoriTheme.colors
    val colors = when (style) {
        // Lit from above: brighter at the top, with a rim darker towards the bottom.
        // Disabled, the label stays white over the faded fill, so the button reads as dimmed rather than changed.
        ButtonStyle.Primary -> raisedColors(theme.accent, shine = .15f, content = Color.White, contentDisabled = Color.White.copy(alpha = .85f))
        ButtonStyle.Destructive -> raisedColors(theme.danger, shine = .15f, content = Color.White, contentDisabled = Color.White.copy(alpha = .85f))
        // On a dark background, such as the onboarding's, a dark fill keeps the light label readable.
        ButtonStyle.Secondary, ButtonStyle.Outlined -> if (theme.text.luminance() > .5f) raisedColors(theme.cardBackground, shine = .06f,
            content = theme.text, contentDisabled = theme.textDisabled)
        else raisedColors(Color(0xFFF4F4F5), shine = .7f, content = theme.text, contentDisabled = theme.textDisabled)
        ButtonStyle.Ghost -> {
            val foreground = RobomoriTheme.contentColor.takeOrElse(theme.text)
            ButtonColors(
                background = SolidColor(Color.Transparent),
                backgroundDisabled = SolidColor(Color.Transparent),
                backgroundPressed = SolidColor(foreground.copy(alpha = .12f)),
                backgroundHovered = SolidColor(foreground.copy(alpha = .06f)),
                content = foreground,
                contentDisabled = theme.textDisabled,
                border = SolidColor(Color.Transparent),
                borderDisabled = SolidColor(Color.Transparent),
            )
        }
    }
    val padding = if (size == ButtonSize.Icon) PaddingValues(size.horizontalPadding) else PaddingValues(horizontal = size.horizontalPadding)
    return ResolvedButtonStyle(colors, ButtonMetrics(padding, size.height))
}

private fun Color.takeOrElse(other: Color) = if (this == Color.Unspecified) other else this
