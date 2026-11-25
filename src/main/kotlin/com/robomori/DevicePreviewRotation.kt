package com.robomori

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween

internal fun deviceRotationAnimationSpec() = tween<Float>(durationMillis = 300, easing = FastOutSlowInEasing)

internal fun devicePreviewAngle(frameRotation: Int, request: EmulatorRotation?): Float =
    -(request?.initialRotation ?: frameRotation) * 90f + (request?.turns ?: 0) * 90f
