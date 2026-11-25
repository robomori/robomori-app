package com.robomori.components

import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.robomori.theme.RobomoriTheme
import com.composeunstyled.Text

@Composable
fun DeviceStateMessage(text: String, modifier: Modifier = Modifier) {
    DeviceStateMessage(AnnotatedString(text), modifier)
}

@Composable
fun DeviceStateMessage(text: AnnotatedString, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.widthIn(max = 360.dp),
        color = RobomoriTheme.colors.textSecondary,
        textAlign = TextAlign.Center,
    )
}
