package com.robomori.components

import com.composables.compose.ripple.rememberRippleIndication
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.getValue
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.indication
import androidx.compose.foundation.hoverable
import com.robomori.theme.RobomoriTheme
import com.robomori.iconography.Check
import com.robomori.iconography.Icons
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.robomori.theme.appFocusRing

/** The existing settings checkbox row, shared with forms that include optional settings. */
@Composable
fun CheckboxRow(text: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit,
                modifier: Modifier = Modifier, enabled: Boolean = true, tag: String = text) {
    val interaction = remember { MutableInteractionSource() }
    Row(modifier.fillMaxWidth().heightIn(min = 30.dp).testTag(tag)
        .appFocusRing(interaction, RoundedCornerShape(4.dp))
        .hoverable(interaction, enabled)
        .toggleable(checked, enabled = enabled, role = Role.Checkbox, interactionSource = interaction, indication = null,
            onValueChange = onCheckedChange), verticalAlignment = Alignment.CenterVertically) {
        Label(text, Modifier.weight(1f))
        // The row takes focus, as it does clicks, so the checkbox doesn't also show focus of its own. It shows the
        // pointer over the row and the press, with the same ripple as the buttons.
        CheckboxBox(checked, enabled, interaction, Modifier.clearAndSetSemantics {})
    }
}

/** The box itself, as macOS draws one: outlined when off, filled with the accent and a white check when on. */
@Composable
private fun CheckboxBox(checked: Boolean, enabled: Boolean, interaction: MutableInteractionSource, modifier: Modifier = Modifier) {
    val colors = RobomoriTheme.colors
    val shape = RoundedCornerShape(3.dp)
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    // Darker under the pointer and darker still while pressed, as the buttons are.
    val shade = when {
        !enabled -> 0f
        pressed -> .12f
        hovered -> .05f
        else -> 0f
    }
    val fill = when {
        !enabled -> colors.panelBackground
        checked -> colors.accent
        else -> colors.popupBackground
    }.let { lerp(it, Color.Black, shade) }
    val border = when {
        !enabled -> colors.controlTrack
        checked -> colors.accent
        hovered || pressed -> colors.textSecondary
        else -> colors.textDisabled
    }.let { lerp(it, Color.Black, shade) }
    Box(modifier.size(16.dp).clip(shape).background(fill, shape).border(1.dp, border, shape)
        .indication(interaction, rememberRippleIndication(color = if (checked) Color.White else colors.text)),
        contentAlignment = Alignment.Center) {
        if (checked) Icon(Icons.Check, tint = if (enabled) Color.White else colors.popupBorder,
            modifier = Modifier.size(12.dp))
    }
}
