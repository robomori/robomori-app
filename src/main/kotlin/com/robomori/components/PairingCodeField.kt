package com.robomori.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.robomori.theme.RobomoriTheme
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.SolidColor

@Composable
fun PairingCodeField(enabled: Boolean, onCodeChange: (String) -> Unit) {
    val digits = remember { mutableStateListOf(*Array(6) { TextFieldValue() }) }
    val focus = remember { List(6) { FocusRequester() } }
    fun updateCode() = onCodeChange(digits.joinToString("") { it.text })

    LaunchedEffect(Unit) { focus.first().requestFocus() }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        digits.forEachIndexed { index, value ->
            val interaction = remember { MutableInteractionSource() }
            BasicTextField(
                value = value,
                onValueChange = { updated ->
                    if (updated.text == value.text) {
                        digits[index] = updated
                    } else {
                        val insertedCount = updated.text.length - (value.text.length - value.selection.length)
                        val insertionStart = value.selection.min.coerceAtMost(updated.text.length)
                        val entered = if (insertedCount > 0) updated.text.substring(insertionStart, (insertionStart + insertedCount).coerceAtMost(updated.text.length))
                            else updated.text
                        val input = entered.filter { it in '0'..'9' }.take(6)
                        if (updated.text.isEmpty()) {
                            digits[index] = TextFieldValue()
                            updateCode()
                        } else if (input.isNotEmpty()) {
                            val start = if (input.length == 6) 0 else index
                            input.take(6 - start).forEachIndexed { offset, digit ->
                                digits[start + offset] = TextFieldValue(digit.toString(), TextRange(1))
                            }
                            updateCode()
                            focus[(start + input.length).coerceAtMost(5)].requestFocus()
                        }
                    }
                },
                interactionSource = interaction,
                singleLine = true,
                cursorBrush = SolidColor(RobomoriTheme.colors.text),
                decorationBox = { inner -> Box(contentAlignment = Alignment.Center) { inner() } },
                modifier = Modifier.width(42.dp).height(40.dp).testTag("PairingCodeDigit-${index + 1}")
                    .fieldFrame(interaction, enabled, RoundedCornerShape(8.dp))
                    .wrapContentHeight(Alignment.CenterVertically)
                    .semantics { contentDescription = "Pairing code digit ${index + 1} of 6" }
                    .focusRequester(focus[index])
                    .onFocusChanged {
                        if (!it.isFocused && !digits[index].selection.collapsed) {
                            digits[index] = digits[index].copy(selection = TextRange(digits[index].text.length))
                        }
                    }
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown || !enabled || event.isCtrlPressed || event.isMetaPressed || event.isAltPressed) false
                        else when {
                            event.key == Key.Backspace && digits[index].text.isEmpty() && index > 0 -> {
                                digits[index - 1] = TextFieldValue()
                                updateCode()
                                focus[index - 1].requestFocus()
                                true
                            }
                            event.key == Key.DirectionLeft && index > 0 -> { focus[index - 1].requestFocus(); true }
                            event.key == Key.DirectionRight && index < 5 -> { focus[index + 1].requestFocus(); true }
                            else -> false
                        }
                    },
                enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                textStyle = RobomoriTheme.typography.heading.copy(textAlign = TextAlign.Center,
                    color = if (enabled) RobomoriTheme.colors.text else RobomoriTheme.colors.textDisabled),
            )
        }
    }
}
