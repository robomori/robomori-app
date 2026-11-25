package com.robomori.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import com.composeunstyled.LocalContentColor
import com.composeunstyled.LocalTextStyle
import com.composeunstyled.TextInput
import com.composeunstyled.UnstyledTextField
import com.robomori.theme.RobomoriTheme

/** Text fields are capsules, like buttons, with room for their round ends. */
private val FieldShape = RoundedCornerShape(50)
private val FieldMinHeight = 28.dp

/**
 * A one-line text field: [placeholder] shows while it's empty. Its outline turns the accent color while it has focus,
 * as macOS fields do.
 */
@Composable
fun TextField(
    state: TextFieldState,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    placeholder: (@Composable () -> Unit)? = null,
    textStyle: TextStyle = RobomoriTheme.typography.body,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    /** Shown before the text, such as a magnifying glass in a search field. */
    leadingIcon: (@Composable () -> Unit)? = null,
    /** Shown after the text, such as a button that clears it. */
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    StyledTextField(state, modifier.defaultMinSize(minHeight = FieldMinHeight), enabled, placeholder, textStyle, keyboardOptions,
        FieldShape, TextFieldLineLimits.SingleLine, horizontalPadding = 12.dp, verticalPadding = 0.dp, leadingIcon, trailingIcon)
}

/** Several lines of text, such as a body or headers, with the same outline as [TextField]. */
@Composable
fun TextArea(
    state: TextFieldState,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    placeholder: (@Composable () -> Unit)? = null,
    textStyle: TextStyle = RobomoriTheme.typography.body,
) {
    StyledTextField(state, modifier, enabled, placeholder, textStyle, KeyboardOptions.Default, RoundedCornerShape(6.dp),
        TextFieldLineLimits.MultiLine(), horizontalPadding = 9.dp, verticalPadding = 6.dp, null, null)
}

@Composable
private fun StyledTextField(state: TextFieldState, modifier: Modifier, enabled: Boolean, placeholder: (@Composable () -> Unit)?,
                            textStyle: TextStyle, keyboardOptions: KeyboardOptions, shape: Shape, lineLimits: TextFieldLineLimits,
                            horizontalPadding: androidx.compose.ui.unit.Dp, verticalPadding: androidx.compose.ui.unit.Dp,
                            leadingIcon: (@Composable () -> Unit)?, trailingIcon: (@Composable () -> Unit)?) {
    val colors = RobomoriTheme.colors
    val interaction = remember { MutableInteractionSource() }
    UnstyledTextField(
        state = state,
        enabled = enabled,
        interactionSource = interaction,
        textStyle = textStyle,
        textColor = if (enabled) colors.text else colors.textDisabled,
        cursorBrush = SolidColor(colors.text),
        selectionColors = colors.textSelectionColors,
        lineLimits = lineLimits,
        keyboardOptions = keyboardOptions,
        modifier = modifier.fieldFrame(interaction, enabled, shape),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = horizontalPadding, vertical = verticalPadding),
            verticalAlignment = if (lineLimits == TextFieldLineLimits.SingleLine) Alignment.CenterVertically else Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            leadingIcon?.invoke()
            // A placeholder in the field's type, grayed, as macOS shows one.
            TextInput(Modifier.weight(1f), placeholder = placeholder?.let { shown -> {
                CompositionLocalProvider(LocalContentColor provides colors.textDisabled, LocalTextStyle provides textStyle, content = shown)
            } })
            trailingIcon?.invoke()
        }
    }
}

/** A field's surface: white, outlined, with the outline in the accent color while it has focus. */
@Composable
internal fun Modifier.fieldFrame(interaction: InteractionSource, enabled: Boolean, shape: Shape): Modifier {
    val focused by interaction.collectIsFocusedAsState()
    val colors = RobomoriTheme.colors
    val border = when {
        !enabled -> colors.controlTrack
        focused -> colors.accent
        else -> colors.popupBorder
    }
    return clip(shape).background(if (enabled) colors.popupBackground else Color.Transparent, shape).border(1.dp, border, shape)
}
