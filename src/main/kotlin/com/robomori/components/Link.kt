package com.robomori.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import com.composeunstyled.Text
import com.robomori.theme.RobomoriTheme
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.robomori.theme.appFocusRing

/** An inline action in text, such as "Change" next to a status. Use a [Button] for a screen's actions. */
@Composable
fun Link(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, tag: String? = null) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val colors = RobomoriTheme.colors
    Text(text, modifier.appFocusRing(interaction, RoundedCornerShape(2.dp))
        .hoverable(interaction, enabled)
        .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
        .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
        .then(if (tag != null) Modifier.testTag(tag) else Modifier),
        color = if (enabled) colors.link else colors.textDisabled,
        textDecoration = if (hovered && enabled) TextDecoration.Underline else null)
}
