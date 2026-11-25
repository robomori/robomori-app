package com.robomori.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.robomori.theme.RobomoriTheme

@Composable fun Icon(icon: ImageVector, tint: Color = RobomoriTheme.contentColor, modifier: Modifier = Modifier.size(18.dp)) {
    Image(icon, contentDescription = null, modifier = modifier, colorFilter = ColorFilter.tint(tint))
}
