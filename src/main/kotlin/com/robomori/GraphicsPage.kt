package com.robomori

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.composeunstyled.AnchorAlignment
import com.robomori.components.*

private val graphicsLabels = mapOf(
    EmulatorGraphics.Hardware to "Hardware (GPU)",
    EmulatorGraphics.Software to "Software",
)

@Composable
fun GraphicsPage(state: AppState) {
    SettingsPageScaffold(SettingsSection.Graphics.title, "GraphicsPage") {
        Card(contentPadding = PaddingValues(horizontal = 16.dp)) {
            SelectRow("Rendering Method", null, graphicsLabels.getValue(state.graphics), graphicsLabels.values.toList(),
                "EmulatorGraphics", menuAlignment = AnchorAlignment.End) { label ->
                graphicsLabels.entries.firstOrNull { it.value == label }?.let { state.changeGraphics(it.key) }
            }
        }
    }
}
