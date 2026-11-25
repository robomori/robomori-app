@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.robomori.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.composables.compose.ripple.rememberRippleIndication
import com.composeunstyled.UnstyledButton
import com.robomori.theme.appFocusRing
import com.composeunstyled.LocalContentColor
import com.composeunstyled.Text

@Composable
fun ToggleButton(
    value: Boolean,
    onValueChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    style: ButtonStyle = ButtonStyle.Ghost,
    size: ButtonSize = ButtonSize.Large,
    enabled: Boolean = true,
    contentDescription: String? = null,
    tag: String? = contentDescription,
    content: @Composable RowScope.() -> Unit,
) {
    require(size != ButtonSize.Icon || !contentDescription.isNullOrBlank()) {
        "Icon toggle buttons need a content description."
    }
    val resolved = resolveButtonStyle(style, size)
    val colors = resolved.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val background =
        when {
            !enabled -> colors.backgroundDisabled
            value || pressed -> colors.backgroundPressed
            hovered -> colors.backgroundHovered
            else -> colors.background
        }
    val foreground = if (enabled) colors.content else colors.contentDisabled
    val border = if (enabled) colors.border else colors.borderDisabled
    val shape = ButtonShape
    val button: @Composable () -> Unit = {
        UnstyledButton(
            onClick = { onValueChange(!value) },
            enabled = enabled,
            role = Role.Checkbox,
            interactionSource = interaction,
            indication = rememberRippleIndication(color = foreground),
            contentPadding = resolved.metrics.padding,
            modifier =
                modifier
                    .defaultMinSize(resolved.metrics.minHeight, resolved.metrics.minHeight)
                    .then(if (tag != null) Modifier.testTag(tag) else Modifier)
                    .semantics {
                        toggleableState = ToggleableState(value)
                        if (contentDescription != null) this.contentDescription = contentDescription
                    }
                    .appFocusRing(interaction, shape)
                    .clip(shape)
                    .background(background, shape)
                    .border(resolved.metrics.borderWidth, border, shape),
        ) {
            CompositionLocalProvider(LocalContentColor provides foreground) {
                ButtonTextStyle(size) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        content = content,
                    )
                }
            }
        }
    }
    if (size == ButtonSize.Icon) Tooltip(contentDescription!!) { button() }
    else button()
}
