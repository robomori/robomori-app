package com.robomori.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.robomori.theme.RobomoriTheme

/**
 * A field that holds a set of values as [Chip]s, like the To field of an email, followed by where the next one is
 * typed. Enter, or leaving the field, calls [onSubmit] with what's typed; Backspace in the empty field removes the last
 * chip. Clicking anywhere in the field puts the cursor at the end.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipField(
    chips: List<String>,
    onRemove: (String) -> Unit,
    removeDescription: (String) -> String,
    input: TextFieldState,
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    tag: String? = null,
) {
    val focus = remember { FocusRequester() }
    FlowRow(modifier.then(if (tag != null) Modifier.testTag(tag) else Modifier).fillMaxWidth()
        .clip(RoundedCornerShape(10.dp)).background(RobomoriTheme.colors.shelfBackground)
        .clickable(remember { MutableInteractionSource() }, indication = null) { focus.requestFocus() }
        .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        chips.forEach { chip ->
            Chip(chip, { onRemove(chip) }, removeDescription(chip), tag = if (tag != null) "$tag-$chip" else null)
        }
        Box(Modifier.height(28.dp).widthIn(min = 120.dp).weight(1f).padding(horizontal = 6.dp), contentAlignment = Alignment.CenterStart) {
            val textStyle = RobomoriTheme.typography.body.copy(color = RobomoriTheme.colors.text)
            if (input.text.isEmpty()) Label(placeholder, color = RobomoriTheme.colors.textDisabled)
            BasicTextField(input, Modifier.fillMaxWidth().focusRequester(focus)
                .then(if (tag != null) Modifier.testTag("$tag-Input") else Modifier)
                // Leaving the field adds what was typed, as Enter does.
                .onFocusChanged { if (!it.isFocused && input.text.isNotBlank()) onSubmit(input.text.toString()) }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when {
                        event.key == Key.Enter -> { onSubmit(input.text.toString()); true }
                        event.key == Key.Backspace && input.text.isEmpty() && chips.isNotEmpty() -> { onRemove(chips.last()); true }
                        else -> false
                    }
                },
                textStyle = textStyle, cursorBrush = SolidColor(RobomoriTheme.colors.text), lineLimits = TextFieldLineLimits.SingleLine)
        }
    }
}
