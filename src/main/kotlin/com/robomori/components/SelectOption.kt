@file:Suppress("DEPRECATION")

package com.robomori.components

import androidx.compose.runtime.*
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.robomori.theme.RobomoriTheme
import androidx.compose.ui.unit.dp
import com.robomori.iconography.Icons
import com.robomori.iconography.Check
import com.composeunstyled.UnstyledDropdownMenuItem
import com.composeunstyled.Text

@Composable
internal fun SelectOption(
    text: String,
    selected: Boolean,
    tag: String,
    enabled: Boolean = true,
    description: String? = null,
    icon: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val colors = RobomoriTheme.colors
    val shape = RoundedCornerShape(4.dp)
    UnstyledDropdownMenuItem(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp)
            // The chosen option stays highlighted, so it stands out from the rest while the menu is open.
            .clip(shape).background(if (enabled && (selected || hovered || focused)) colors.highlight else Color.Transparent)
            .semantics { this.selected = selected }.testTag(tag),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 20.dp).padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                if (selected) Icon(Icons.Check, tint = colors.textSecondary, modifier = Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(8.dp))
            if (icon != null) {
                icon()
                Spacer(Modifier.width(8.dp))
            }
            Column(Modifier.padding(vertical = if (description != null) 3.dp else 0.dp)) {
                Text(text, color = if (enabled) colors.text else colors.textDisabled, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontWeight = if (selected) FontWeight.SemiBold else null)
                if (description != null) Text(description, color = colors.textSecondary, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, style = RobomoriTheme.typography.small)
            }
        }
    }
}
