@file:Suppress("DEPRECATION")

package com.robomori.components

import com.robomori.theme.verticalScroll

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.robomori.iconography.*
import com.composeunstyled.DropdownMenuPanel
import com.composeunstyled.UnstyledButton
import com.composeunstyled.UnstyledDropdownMenu
import com.composeunstyled.AnchorAlignment
import com.composeunstyled.AnchorSide
import com.robomori.theme.RobomoriTheme
import com.composeunstyled.Text
import com.robomori.theme.appFocusRing

enum class SelectStyle { Outlined, Ghost }

/** Selects are capsules as tall as a text field, with room at the end for the arrow. */
private val SelectMinHeight = 28.dp
private val SelectMinWidth = 78.dp
private val SelectPadding = PaddingValues(horizontal = 12.dp, vertical = 3.dp)
private val ArrowSize = DpSize(34.dp, 24.dp)

class SelectAction(val text: String, val onClick: () -> Unit, val enabled: Boolean = true)

@Composable
fun <T> Select(
    value: T,
    options: List<T>,
    onChange: (T) -> Unit,
    label: (T) -> String = { it.toString() },
    tag: String = "Select",
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: SelectStyle = SelectStyle.Outlined,
    actions: List<SelectAction> = emptyList(),
    optionsEnabled: Boolean = true,
    onOpen: () -> Unit = {},
    /** Which edge of the button the menu lines up with. End suits a button at the end of its row. */
    menuAlignment: AnchorAlignment = AnchorAlignment.Start,
    /** A second line under an option's label in the menu, such as an app's package name. */
    description: (T) -> String? = { null },
    /** An image before an option's label, such as an app's icon, in the menu and on the button. */
    icon: (@Composable (T, Dp) -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    var anchorWidth by remember { mutableStateOf(130.dp) }
    val density = LocalDensity.current
    val colors = RobomoriTheme.colors
    val ghost = style == SelectStyle.Ghost
    val shape = RoundedCornerShape(50)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val contentColor = when {
        !enabled -> colors.textDisabled
        ghost -> RobomoriTheme.contentColor
        else -> colors.text
    }
    val background = if (!ghost) (if (enabled) colors.popupBackground else colors.panelBackground) else when {
        enabled && pressed -> contentColor.copy(alpha = .12f)
        enabled && hovered -> contentColor.copy(alpha = .06f)
        else -> Color.Transparent
    }
    val border = when {
        ghost && !(enabled && (hovered || expanded)) -> Color.Transparent
        enabled -> colors.popupBorder
        else -> colors.controlTrack
    }

    UnstyledDropdownMenu(
        expanded = expanded && enabled,
        onExpandedChange = {
            if (it && enabled && !expanded) onOpen()
            expanded = it && enabled
        },
        modifier = modifier.then(if (ghost) Modifier.width(IntrinsicSize.Max) else Modifier.widthIn(min = 130.dp, max = 300.dp)),
        side = AnchorSide.Bottom,
        alignment = menuAlignment,
        sideOffset = 2.dp,
        // The panel keeps room around it for its shadow; at the end edge, that room is shifted past the button's edge.
        alignmentOffset = if (menuAlignment == AnchorAlignment.End) MenuShadowRoom else 0.dp,
        panel = {
            DropdownMenuPanel(
                Modifier.padding(MenuShadowRoom).width(maxOf(anchorWidth, 340.dp)).heightIn(max = MenuMaxHeight).menuSurface()
                    .verticalScroll(rememberScrollState()).padding(vertical = 7.dp, horizontal = 2.dp)
                    .testTag("$tag-Menu"),
                exit = fadeOut(tween(140)),
            ) {
                options.forEachIndexed { index, option ->
                    SelectOption(label(option), option == value, "$tag-Option-$index", enabled = optionsEnabled,
                        description = description(option), icon = icon?.let { { it(option, 24.dp) } }) {
                        expanded = false
                        onChange(option)
                    }
                }
                if (options.isNotEmpty() && actions.isNotEmpty()) MenuSeparator()
                actions.forEachIndexed { index, action ->
                    SelectOption(action.text, false, "$tag-Action-$index", enabled = action.enabled) {
                        expanded = false
                        action.onClick()
                    }
                }
            }
        },
        anchor = {
            UnstyledButton(
                enabled = enabled,
                onClick = {
                    if (!expanded) onOpen()
                    expanded = !expanded
                },
                role = Role.DropdownList,
                interactionSource = interaction,
                modifier = Modifier.fillMaxWidth()
                    .defaultMinSize(if (ghost) 0.dp else SelectMinWidth, SelectMinHeight)
                    .onSizeChanged { anchorWidth = with(density) { it.width.toDp() } }
                    .background(background, shape)
                    .border(1.dp, border, shape)
                    .appFocusRing(interaction, shape).testTag(tag),
            ) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                    Row(Modifier.padding(SelectPadding).padding(end = ArrowSize.width),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        icon?.invoke(value, 16.dp)
                        Text(label(value), color = contentColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Box(Modifier.size(ArrowSize).align(Alignment.CenterEnd), contentAlignment = Alignment.Center) {
                        Icon(Icons.ChevronDown, tint = contentColor, modifier = Modifier.size(16.dp))
                    }
                }
            }
        },
    )
}
