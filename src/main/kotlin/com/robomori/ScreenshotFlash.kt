package com.robomori

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/** A white camera flash over a device's screen each time [screenshots], its count of screenshots taken, goes up. */
@Composable fun BoxScope.ScreenshotFlash(screenshots: Int) {
    val shown = remember { intArrayOf(screenshots) }
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(screenshots) {
        if (screenshots == shown[0]) return@LaunchedEffect
        shown[0] = screenshots
        alpha.snapTo(.85f)
        alpha.animateTo(0f, tween(durationMillis = 450, easing = FastOutSlowInEasing))
    }
    if (alpha.value > 0f) Box(Modifier.matchParentSize().background(Color.White.copy(alpha = alpha.value)))
}
