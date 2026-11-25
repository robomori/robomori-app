package com.robomori

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.robomori.components.*
import com.robomori.iconography.Icons
import com.robomori.iconography.ThumbsDown
import com.robomori.iconography.ThumbsUp
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

/** Asks "How is the camera experience?" in the bottom right corner of [device]'s panel, and leaves on its own if nobody answers. */
@Composable fun CameraFeedbackToast(state: AppState, device: Device, modifier: Modifier = Modifier) {
    val asking = state.cameraFeedbackDevice == device.name
    // Counts down only while this emulator is on screen, so the question waits until it can be seen.
    LaunchedEffect(asking) {
        if (asking) {
            delay(20.seconds)
            state.dismissCameraFeedback()
        }
    }
    // Rises from below the window's bottom edge, like the app's other toasts.
    AnimatedVisibility(asking, modifier,
        enter = fadeIn(tween(200)) + slideInVertically(tween(200)) { it },
        exit = fadeOut(tween(160)) + slideOutVertically(tween(160)) { it }) {
        // As wide as the question, in its corner, rather than across the panel.
        Snackbar("How is the camera experience?", Modifier.padding(16.dp).width(IntrinsicSize.Max), tag = "CameraFeedback") {
            Button(onClick = { state.answerCameraFeedback(good = false) }, style = ButtonStyle.Ghost, size = ButtonSize.Icon,
                contentDescription = "Not good", tag = "CameraFeedbackDown") { Icon(Icons.ThumbsDown) }
            Button(onClick = { state.answerCameraFeedback(good = true) }, style = ButtonStyle.Ghost, size = ButtonSize.Icon,
                contentDescription = "Good", tag = "CameraFeedbackUp") { Icon(Icons.ThumbsUp) }
        }
    }
}
