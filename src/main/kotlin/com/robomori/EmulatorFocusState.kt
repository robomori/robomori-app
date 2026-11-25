package com.robomori

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState

internal class EmulatorFocusState(private val window: WindowState) {
    var active by mutableStateOf(false)
        private set
    private var deviceSize: DpSize? = null

    fun enter(size: DpSize, available: DpSize, position: WindowPosition) {
        if (active) return
        window.position = position
        window.size = focusWindowSize(size, DpSize(minOf(800.dp, available.width), minOf(800.dp, available.height)))
        deviceSize = size
        active = true
    }

    fun resize(size: DpSize, available: DpSize) {
        val previous = deviceSize ?: return
        if (!active || previous == size) return
        window.size = focusWindowSize(size, available, previous, window.size)
        deviceSize = size
    }

    fun exit() { active = false }
}

// Header (64dp), toolbar (42dp), and the preview's fixed padding.
private val focusChrome = DpSize(devicePreviewPadding * 2, 106.dp + devicePreviewPadding * 2)

internal fun focusWindowSize(
    device: DpSize,
    available: DpSize,
    previousDevice: DpSize? = null,
    previousWindow: DpSize? = null,
): DpSize {
    val viewport = DpSize((available.width - focusChrome.width).coerceAtLeast(1.dp),
        (available.height - focusChrome.height).coerceAtLeast(1.dp))
    val desired = if (previousDevice != null && previousWindow != null) {
        val previousViewport = DpSize((previousWindow.width - focusChrome.width).coerceAtLeast(1.dp),
            (previousWindow.height - focusChrome.height).coerceAtLeast(1.dp))
        val scale = fitDevicePreview(previousViewport, previousDevice).width / previousDevice.width
        DpSize(device.width * scale, device.height * scale)
    } else fitDevicePreview(viewport, device)
    val fit = minOf(1f, viewport.width / desired.width, viewport.height / desired.height)
    return DpSize(
        (desired.width * fit + focusChrome.width).coerceIn(minOf(320.dp, available.width), available.width),
        (desired.height * fit + focusChrome.height).coerceIn(minOf(200.dp, available.height), available.height),
    )
}
