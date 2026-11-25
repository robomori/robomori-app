package com.robomori

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

internal val devicePreviewPadding = 12.dp

/** Returns the unrotated surface size whose rotated bounds fit the viewport. */
internal fun fitDevicePreview(viewport: DpSize, surface: DpSize, angle: Float = 0f, bezel: Dp = 0.dp): DpSize {
    val radians = Math.toRadians(angle.toDouble())
    val c = abs(cos(radians)).toFloat()
    val s = abs(sin(radians)).toFloat()
    val border = bezel * 2 * (c + s)
    val width = surface.width * c + surface.height * s
    val height = surface.width * s + surface.height * c
    val scale = minOf(
        (viewport.width - border).coerceAtLeast(0.dp) / width,
        (viewport.height - border).coerceAtLeast(0.dp) / height,
    )
    return DpSize(surface.width * scale, surface.height * scale)
}

internal fun AppState.focusDeviceSize(target: DeviceTarget? = connected.selected ?: previewDevice): DpSize? {
    if (target is ConnectedDevice) {
        return connected.sharingSession(target)?.frame?.let { DpSize(it.width.dp, it.height.dp) }
    }
    val device = target as? Device ?: return null
    val key = device.directory.absolutePath
    val rotation = frameRotations[key] ?: 0
    val hardware = HardwareProfiles.all.firstOrNull {
        device.config["showDeviceFrame"] != "no" &&
            (device.config["robomori.profile"] == it.id || device.config["hw.device.name"] == it.id.replace('-', '_'))
    }
    val cover = hardware?.folded?.takeIf { foldedDevices[key] == true }
    val native = frames[key]?.let { nativeScreenSize(it.width, it.height, rotation) }
    val width = native?.first ?: cover?.width ?: device.config["hw.lcd.width"]?.toIntOrNull() ?: 1080
    val height = native?.second ?: cover?.height ?: device.config["hw.lcd.height"]?.toIntOrNull() ?: 2400
    val shell = when {
        hardware == null -> null
        hardware.folded?.let { width == it.width && height == it.height } == true -> hardware.folded.frame
        width == hardware.display.width && height == hardware.display.height -> hardware.frame
        else -> null
    }
    val size = DpSize((shell?.width ?: width).dp, (shell?.height ?: height).dp)
    val request = rotations[device.name]?.takeIf { it.serial == running[device.name] }
    val quarterTurns = (devicePreviewAngle(rotation, request) / 90f).toInt()
    return if (quarterTurns % 2 == 0) size else DpSize(size.height, size.width)
}
