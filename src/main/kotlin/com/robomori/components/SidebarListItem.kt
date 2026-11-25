package com.robomori.components

import androidx.compose.foundation.layout.*
import com.robomori.theme.RobomoriTheme
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun SidebarListItem(
    selected: Boolean,
    height: Dp = Dp.Unspecified,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
    /** The rounding of the selected and hovered background; 0 for rows that run edge to edge, like a table's. */
    cornerSize: Dp = 8.dp,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val colors = RobomoriTheme.colors
    Row(
        modifier.semantics(mergeDescendants = true) { this.selected = selected }
            .hoverable(interactionSource).fillMaxWidth().height(height)
            .background(when {
                selected -> colors.highlight
                hovered -> colors.controlTrack
                else -> Color.Transparent
            }, RoundedCornerShape(cornerSize))
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) { content() }
}
