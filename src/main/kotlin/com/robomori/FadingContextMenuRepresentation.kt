@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.robomori

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ContextMenuRepresentation
import androidx.compose.foundation.ContextMenuState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.window.rememberPopupPositionProviderAtPosition
import com.robomori.components.FadingMenu
import androidx.compose.ui.unit.dp
import com.robomori.components.MenuShadowRoom
import com.robomori.components.PopupMenu
import com.composeunstyled.Text

internal object FadingContextMenuRepresentation : ContextMenuRepresentation {
    @Composable
    override fun Representation(state: ContextMenuState, items: () -> List<ContextMenuItem>) {
        val open = state.status as? ContextMenuState.Status.Open
        val currentItems = if (open != null) items() else null
        var lastItems by remember { mutableStateOf(emptyList<ContextMenuItem>()) }
        var lastPosition by remember { mutableStateOf(Offset.Zero) }
        val position = open?.rect?.center ?: lastPosition
        val displayedItems = currentItems ?: lastItems

        LaunchedEffect(open != null) {
            if (open != null && currentItems != null) {
                lastItems = currentItems
                lastPosition = position
            }
        }
        if (open != null && currentItems.isNullOrEmpty()) {
            SideEffect { state.status = ContextMenuState.Status.Closed }
        }

        // The panel's top left just under the pointer; the popup is bigger by the room its shadow takes.
        val positionProvider = rememberPopupPositionProviderAtPosition(position,
            androidx.compose.ui.unit.DpOffset(-MenuShadowRoom, 2.dp - MenuShadowRoom))
        FadingMenu(open != null && displayedItems.isNotEmpty()) { fade ->
            PopupMenu(
                onDismissRequest = { state.status = ContextMenuState.Status.Closed; true },
                popupPositionProvider = positionProvider,
                modifier = fade,
            ) {
                displayedItems.forEach { entry ->
                    item(enabled = entry.enabled, onClick = {
                        state.status = ContextMenuState.Status.Closed
                        entry.onClick()
                    }) { Text(entry.label) }
                }
            }
        }
    }
}
