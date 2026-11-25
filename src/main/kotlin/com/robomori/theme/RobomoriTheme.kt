package com.robomori.theme

import com.composables.composetheme.ColorScheme
import com.composables.composetheme.Theme
import com.composables.composetheme.buildTheme
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.DelegatableNode

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import com.robomori.HostOs
import com.composeunstyled.LocalContentColor
import com.composeunstyled.LocalTextStyle

// Light everywhere except the onboarding, which applies the dark scheme.
private val AppTheme = buildTheme {
    name = "Robomori"
    // Controls show their own pressed and hovered states.
    baseIndication = NoIndication
    provide(rememberRobomoriTypography())
    provide(LightColors)
    provide(LightShadows)
    baseTextSelectionColors = LightColors.textSelectionColors
    colorScheme(ColorScheme.Dark) {
        provide(DarkColors)
        provide(DarkShadows)
        textSelectionColors = DarkColors.textSelectionColors
    }
}

/** Draws nothing when pressed or hovered. */
private object NoIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = object : Modifier.Node() {}
    override fun equals(other: Any?) = other === this
    override fun hashCode() = 0
}

/** App-facing API: the design system's colors, type and shadows, on Compose Unstyled. */
object RobomoriTheme {
    val colors @Composable get() = Theme.get<RobomoriColors>()
    val typography @Composable get() = Theme.get<RobomoriTypography>()
    val shadows @Composable get() = Theme.get<RobomoriShadows>()
    val styles = RobomoriStyles

    // Inherit content color from surrounding controls, including selected and disabled states.
    val contentColor @Composable get() = LocalContentColor.current

    /** Applies the theme to [content]. [dark] uses the dark colors, for any part of the UI such as the onboarding. */
    @Composable
    operator fun invoke(dark: Boolean = false, content: @Composable () -> Unit) {
        AppTheme(if (dark) ColorScheme.Dark else ColorScheme.Light) { Themed(content) }
    }

    @Composable
    private fun Themed(content: @Composable () -> Unit) {
        val density = LocalDensity.current.density
        val overscrollFactory = if (HostOs.detect() == HostOs.MAC) {
            remember(density) { MacOverscrollFactory(density) }
        } else {
            null
        }
        CompositionLocalProvider(
            LocalMacOverscrollFactory provides overscrollFactory,
            LocalTextStyle provides typography.body,
            LocalContentColor provides colors.text,
            content = content,
        )
    }
}
