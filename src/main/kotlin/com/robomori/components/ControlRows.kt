package com.robomori.components

import com.composeunstyled.AnchorAlignment
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.composeunstyled.Thumb
import com.composeunstyled.Track
import com.composeunstyled.UnstyledSwitch
import com.robomori.theme.RobomoriTheme
import com.robomori.theme.appFocusRing
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    tag: String,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    icon: ImageVector,
) {
    val interaction = remember { MutableInteractionSource() }
    UnstyledSwitch(
        checked = checked,
        onCheckedChange = onChange,
        enabled = enabled,
        interactionSource = interaction,
        modifier =
            Modifier.fillMaxWidth()
                .testTag(tag)
                .appFocusRing(interaction, RoundedCornerShape(4.dp)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SettingLabel(title, icon)
            Track(
                Modifier.size(36.dp, 22.dp)
                    .background(
                        if (checked) RobomoriTheme.colors.accent
                        else RobomoriTheme.colors.shelfBorder,
                        CircleShape,
                    )
            ) {
                Thumb(
                    Modifier.padding(3.dp)
                        .size(16.dp)
                        .background(RobomoriTheme.colors.panelBackground, CircleShape),
                    animationSpec = tween(150),
                )
            }
        }
    }
}

@Composable
private fun SettingLabel(title: String, icon: ImageVector?) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, tint = RobomoriTheme.colors.textSecondary, modifier = Modifier.size(18.dp))
        Label(title, maxLines = 1)
    }
}

@Composable
fun SelectRow(
    title: String,
    icon: ImageVector?,
    value: String,
    options: List<String>,
    tag: String,
    enabled: Boolean = true,
    menuAlignment: AnchorAlignment = AnchorAlignment.Start,
    onChange: (String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingLabel(title, icon)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            com.robomori.components.Select(
                value,
                options,
                onChange,
                tag = tag,
                enabled = enabled,
                style = SelectStyle.Ghost,
                menuAlignment = menuAlignment,
            )
        }
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun SliderRow(
    title: String,
    value: Float,
    options: List<Float>,
    label: (Float) -> String,
    tag: String,
    onChange: (Float) -> Unit,
    enabled: Boolean = true,
    locked: Boolean = false,
    onLockedClick: () -> Unit = {},
    description: String = title,
    icon: ImageVector,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            Tooltip(description) {
                SettingLabel(title, icon)
            }
        }
        Box(Modifier.weight(1f)) {
            Tooltip(label(value)) {
                Slider(
                    enabled = enabled && !locked,
                    value = options.indices.minBy { abs(options[it] - value) }.toFloat(),
                    onValueChange = {
                        val next = options[it.roundToInt().coerceIn(options.indices)]
                        if (next != value) onChange(next)
                    },
                    valueRange = 0f..options.lastIndex.toFloat(),
                    steps = options.size - 2,
                    modifier =
                        Modifier.fillMaxWidth().testTag(tag).semantics {
                            contentDescription = description
                            stateDescription = label(value)
                        },
                )
            }
            if (enabled && locked) {
                Box(
                    Modifier.matchParentSize()
                        .clickable(role = Role.Button, onClick = onLockedClick)
                        .semantics { contentDescription = "Unlock $description" }
                )
            }
        }
    }
}
