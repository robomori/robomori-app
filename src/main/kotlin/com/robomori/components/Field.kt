package com.robomori.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.robomori.theme.RobomoriTheme

/** A text field; [placeholder] shows while it's empty, such as "Search". */
@Composable fun Field(state: androidx.compose.foundation.text.input.TextFieldState, tag: String, modifier: Modifier = Modifier,
                      placeholder: String? = null) {
    TextField(state = state, modifier = modifier.testTag(tag),
        placeholder = placeholder?.let { { Label(it, color = RobomoriTheme.colors.textSecondary, maxLines = 1) } })
}
