package com.robomori.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.robomori.iconography.*
import com.robomori.theme.RobomoriTheme

/**
 * A value in a set, such as a port, with a ✕ that removes it. Lay several out in a FlowRow so they wrap.
 * [removeDescription] names the ✕ for screen readers, such as "Remove port 3000".
 */
@Composable
fun Chip(text: String, onRemove: () -> Unit, removeDescription: String, modifier: Modifier = Modifier,
         enabled: Boolean = true, tag: String? = null) {
    val pill = RoundedCornerShape(50)
    Row(modifier.then(if (tag != null) Modifier.testTag(tag) else Modifier).height(28.dp).clip(pill)
        .background(RobomoriTheme.colors.cardBackground).border(1.dp, RobomoriTheme.colors.shelfBorder, pill)
        .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Label(text)
        val interaction = remember { MutableInteractionSource() }
        val hovered by interaction.collectIsHoveredAsState()
        val content = RobomoriTheme.contentColor
        Box(Modifier.size(20.dp).clip(CircleShape)
            .background(if (hovered && enabled) content.copy(alpha = .08f) else Color.Transparent)
            .hoverable(interaction)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onRemove)
            .semantics { contentDescription = removeDescription }
            .then(if (tag != null) Modifier.testTag("$tag-Remove") else Modifier),
            contentAlignment = Alignment.Center) {
            Icon(Icons.XMark, tint = if (enabled) content else RobomoriTheme.colors.textDisabled, modifier = Modifier.size(12.dp))
        }
    }
}
