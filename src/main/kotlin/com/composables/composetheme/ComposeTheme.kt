// A copy of com.composables:theme 1.0.0 (github.com/composablehorizons/composetheme), which isn't on Maven Central
// yet. Replace it with the published library once it is.
package com.composables.composetheme

import androidx.compose.foundation.Indication
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import kotlin.reflect.KClass
import kotlinx.coroutines.launch

/**
 * Reads the values provided by the current theme.
 *
 * @see buildTheme
 */
object Theme {
    /**
     * Returns the value that the current theme provides as type [T] with `provide()`.
     *
     * @throws IllegalStateException if the theme does not provide a value of type [T].
     */
    @Composable
    inline fun <reified T : Any> get(): T {
        return providedValue(T::class)
    }

    /**
     * Returns the value that the current theme provides as type [T] with `provide()`, or `null`
     * if it does not provide one.
     */
    @Composable
    inline fun <reified T : Any> getOrNull(): T? {
        return providedValueOrNull(T::class)
    }

    @PublishedApi
    @Composable
    internal fun <T : Any> providedValueOrNull(type: KClass<T>): T? {
        @Suppress("UNCHECKED_CAST")
        return LocalTheme.current[type] as T?
    }

    @PublishedApi
    @Composable
    internal fun <T : Any> providedValue(type: KClass<T>): T {
        return providedValueOrNull(type) ?: error(
            "There is no ${type.simpleName} value in the ${LocalTheme.current.name} theme. " +
                "To fix this, call provide<${type.simpleName}>(...) in your theme definition."
        )
    }
}

/**
 * A named set of overrides that a theme applies while it is selected.
 *
 * Use [Light] and [Dark] for the system color schemes, or create your own such as
 * `val Sepia = ColorScheme("sepia")`.
 *
 * @property name The name used to identify this color scheme.
 */
data class ColorScheme(val name: String) {
    init {
        require(name.isNotBlank()) { "A color scheme name cannot be blank." }
    }

    companion object {
        val Light = ColorScheme("light")
        val Dark = ColorScheme("dark")
    }
}

/** A composable theme returned by [buildTheme]. */
interface ThemeComposable {
    /**
     * Applies this theme with the color scheme selected by an enclosing theme, or the system's
     * [ColorScheme.Light] or [ColorScheme.Dark] scheme if there is none.
     */
    @Composable
    operator fun invoke(content: @Composable () -> Unit)

    /** Applies this theme with [colorScheme]'s overrides. */
    @Composable
    operator fun invoke(colorScheme: ColorScheme, content: @Composable () -> Unit)
}

/**
 * Returns a [ThemeComposable] that applies the theme to its content.
 *
 * Provide any value with `provide()` and read it within the theme's content with [Theme.get]:
 * ```kotlin
 * data class AppColors(val content: Color, val background: Color)
 *
 * val AppTheme = buildTheme {
 *     provide(AppColors(content = Color(0xFF212121), background = Color(0xFFFAFAFA)))
 *     colorScheme(ColorScheme.Dark) {
 *         provide(AppColors(content = Color(0xFFFAFAFA), background = Color(0xFF212121)))
 *     }
 * }
 *
 * @Composable
 * fun App() {
 *     AppTheme {
 *         val colors = Theme.get<AppColors>()
 *         Box(Modifier.background(colors.background)) {
 *             BasicText("Hello", style = TextStyle(color = colors.content))
 *         }
 *     }
 * }
 * ```
 */
fun buildTheme(themeAction: @Composable ThemeBuilder.() -> Unit = {}): ThemeComposable {
    return ThemeComposableImpl(themeAction)
}

private class ThemeComposableImpl(
    private val themeAction: @Composable ThemeBuilder.() -> Unit,
) : ThemeComposable {
    @Composable
    override fun invoke(content: @Composable () -> Unit) {
        val colorScheme = LocalColorScheme.current
            ?: if (isSystemInDarkTheme()) ColorScheme.Dark else ColorScheme.Light
        render(colorScheme, content)
    }

    @Composable
    override fun invoke(colorScheme: ColorScheme, content: @Composable () -> Unit) {
        render(colorScheme, content)
    }

    @Composable
    private fun render(colorScheme: ColorScheme, content: @Composable () -> Unit) {
        val builder = ThemeBuilder()
        builder.themeAction()
        val overrides = builder.colorSchemes[colorScheme]
        val theme = remember { ResolvedTheme() }
        theme.update(
            name = builder.name,
            values = if (overrides == null) builder.values else builder.values + overrides.values,
        )

        CompositionLocalProvider(
            LocalColorScheme provides colorScheme,
            LocalTheme provides theme,
            LocalIndication provides (overrides?.indication ?: builder.baseIndication),
            LocalTextSelectionColors provides (
                overrides?.textSelectionColors
                    ?: builder.baseTextSelectionColors
                    ?: LocalTextSelectionColors.current
                ),
        ) {
            builder.extension(content)
        }
    }
}

/**
 * Holds each provided type in its own state, so that content reading a type only recomposes when
 * the value of that type changes.
 */
@PublishedApi
@Stable
internal class ResolvedTheme {
    var name by mutableStateOf("Theme")
        private set

    private val values = mutableMapOf<KClass<*>, MutableState<Any?>>()

    operator fun get(type: KClass<*>): Any? = stateOf(type).value

    fun update(name: String, values: Map<KClass<*>, Any>) {
        this.name = name
        for ((type, state) in this.values) {
            if (type !in values) state.value = null
        }
        for ((type, value) in values) {
            stateOf(type).value = value
        }
    }

    private fun stateOf(type: KClass<*>): MutableState<Any?> {
        return values.getOrPut(type) { mutableStateOf(null) }
    }
}

@PublishedApi
internal val LocalTheme = compositionLocalOf<ResolvedTheme> {
    error(
        "No theme was set. In order to use the Theme object you need to wrap your content " +
            "with a theme @Composable returned by the buildTheme {} function."
    )
}

private val LocalColorScheme = compositionLocalOf<ColorScheme?> { null }

@DslMarker
annotation class ThemeBuilderMarker

@ThemeBuilderMarker
class ThemeBuilder internal constructor() {
    /**
     * The name of the theme. Useful for debugging purposes.
     */
    var name: String by mutableStateOf("Theme")

    /** The indication of the theme's content, unless the selected color scheme sets its own. */
    var baseIndication: Indication by mutableStateOf(DefaultDebugIndication)

    /**
     * The text selection colors of the theme's content, unless the selected color scheme sets its own.
     * When `null`, the content keeps the text selection colors of its parent.
     */
    var baseTextSelectionColors: TextSelectionColors? by mutableStateOf(null)

    @PublishedApi
    internal val values = mutableStateMapOf<KClass<*>, Any>()

    internal val colorSchemes = mutableStateMapOf<ColorScheme, ColorSchemeBuilder>()

    internal var extension: @Composable (@Composable () -> Unit) -> Unit by mutableStateOf({ content -> content() })

    /**
     * Provides [value] to the theme's content as type [T], which reads it with `Theme.get<T>()`.
     *
     * `provide(AppColors(...))` is read with `Theme.get<AppColors>()`. To read a subtype as its
     * supertype, provide it as that type: `provide<AppColors>(DarkColors)`. Providing another value
     * of the same type replaces the previous one.
     */
    inline fun <reified T : Any> provide(value: T) {
        values[T::class] = value
    }

    /**
     * Defines the values that apply while [colorScheme] is selected. Anything the color scheme does
     * not set falls back to the values set outside of this block.
     */
    @Composable
    fun colorScheme(colorScheme: ColorScheme, themeAction: @Composable ColorSchemeBuilder.() -> Unit) {
        val builder = ColorSchemeBuilder()
        builder.themeAction()
        colorSchemes[colorScheme] = builder
    }

    /**
     * Allows the integration with other design systems.
     * @param extension a composable function that wraps the theme composable of the other design system
     */
    fun extend(extension: @Composable (@Composable () -> Unit) -> Unit) {
        this.extension = extension
    }
}

@ThemeBuilderMarker
class ColorSchemeBuilder internal constructor() {
    /** The indication of the theme's content while this color scheme is selected. */
    var indication: Indication? by mutableStateOf(null)

    /** The text selection colors of the theme's content while this color scheme is selected. */
    var textSelectionColors: TextSelectionColors? by mutableStateOf(null)

    @PublishedApi
    internal val values = mutableStateMapOf<KClass<*>, Any>()

    /**
     * Provides [value] as type [T] while this color scheme is selected, replacing any value of the
     * same type provided outside of the color scheme.
     *
     * @see ThemeBuilder.provide
     */
    inline fun <reified T : Any> provide(value: T) {
        values[T::class] = value
    }
}

internal object DefaultDebugIndication : IndicationNodeFactory {

    override fun create(interactionSource: InteractionSource): DelegatableNode =
        DefaultDebugIndicationInstance(interactionSource)

    override fun hashCode(): Int = -1

    override fun equals(other: Any?) = other === this

    private class DefaultDebugIndicationInstance(private val interactionSource: InteractionSource) :
        Modifier.Node(), DrawModifierNode {
        private var isPressed = false
        private var isHovered = false
        private var isFocused = false

        override fun onAttach() {
            coroutineScope.launch {
                var pressCount = 0
                var hoverCount = 0
                var focusCount = 0
                interactionSource.interactions.collect { interaction ->
                    when (interaction) {
                        is PressInteraction.Press -> pressCount++
                        is PressInteraction.Release -> pressCount--
                        is PressInteraction.Cancel -> pressCount--
                        is HoverInteraction.Enter -> hoverCount++
                        is HoverInteraction.Exit -> hoverCount--
                        is FocusInteraction.Focus -> focusCount++
                        is FocusInteraction.Unfocus -> focusCount--
                    }
                    val pressed = pressCount > 0
                    val hovered = hoverCount > 0
                    val focused = focusCount > 0
                    var invalidateNeeded = false
                    if (isPressed != pressed) {
                        isPressed = pressed
                        invalidateNeeded = true
                    }
                    if (isHovered != hovered) {
                        isHovered = hovered
                        invalidateNeeded = true
                    }
                    if (isFocused != focused) {
                        isFocused = focused
                        invalidateNeeded = true
                    }
                    if (invalidateNeeded) invalidateDraw()
                }
            }
        }

        override fun ContentDrawScope.draw() {
            drawContent()
            if (isPressed) {
                drawRect(color = Color.Black.copy(alpha = 0.3f), size = size)
            } else if (isHovered || isFocused) {
                drawRect(color = Color.Black.copy(alpha = 0.1f), size = size)
            }
        }
    }
}
