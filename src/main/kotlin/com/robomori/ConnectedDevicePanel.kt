package com.robomori

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.*
import androidx.compose.ui.awt.awtEventOrNull
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpSize
import com.robomori.components.*
import com.robomori.theme.RobomoriTheme
import kotlin.math.abs

@Composable fun ConnectedDevicePanel(device: ConnectedDevice, state: AppState, zoom: Float = 1f) {
    val session = state.connected.sharingSession(device)
    val frame = session?.frame
    val failure = session?.failure
    val keyboardFocus = remember(device.identity) { FocusRequester() }
    DisposableEffect(session) { onDispose { session?.mirror?.releaseKeyboard() } }
    fun input(vararg args: String) {
        if (args.firstOrNull() == "keyevent") args.getOrNull(1)?.let(::scrcpyKeyCode)?.let { session?.mirror?.key(it) }
    }
    val sharing = state.connected.sharing(device)
    if (!sharing || failure != null) {
        val connectionMessage = if (state.connected.canReconnect(device)) null else when (device.connectionState(sharingFailed = failure != null)) {
            DeviceConnectionState.Ready -> null
            DeviceConnectionState.AuthorizationRequired -> "Allow USB debugging on your device to continue."
            DeviceConnectionState.WirelessDisconnected -> "Connect the device to the same Wi-Fi as this computer and then enable Wireless debugging to continue."
            DeviceConnectionState.UsbDisconnected -> "Connect your device over USB and enable USB debugging to resume screen sharing."
        }
        InactiveDevicePanel(
            type = device.type,
            name = device.displayName,
            description = device.manufacturerAndModel,
            modifier = Modifier.testTag("ConnectedDevicePanel"),
        ) {
            key(device.identity) {
                Crossfade(connectionMessage, modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth(),
                    animationSpec = tween(180), label = "Device connection state") { message ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                        // USB devices start sharing on their own as soon as they are ready.
                        if (message == null && device.wireless) {
                            Button(onClick = {
                                state.connected.startSharing(device)
                            }, style = ButtonStyle.Primary, tag = "Start Screen Sharing", enabled = !state.connected.working) { Label("Start Screen Sharing") }
                        } else if (message != null) {
                            DeviceStateMessage(message)
                        }
                    }
                }
            }
            state.connected.actionFor(device).deviceActionMessage?.let { Spacer(Modifier.height(12.dp)); DeviceStateMessage(it) }
        }
        return
    }
    if (frame == null) {
        Box(Modifier.fillMaxSize().testTag("ConnectedDevicePanel"), contentAlignment = Alignment.Center) {
            com.robomori.components.Spinner(size = 32.dp, modifier =
                Modifier.testTag("StartingScreenSharingSpinner").semantics {
                    contentDescription = "Starting screen sharing for ${device.displayName}"
                },
            )
        }
        return
    }
    val frameRotation = if (frame.width > frame.height) 1 else 0
    val targetAngle = frameRotation * 90f
    val rotation = remember(device.identity, session) { Animatable(targetAngle) }
    LaunchedEffect(rotation, targetAngle) {
        rotation.animateTo(targetAngle, deviceRotationAnimationSpec(), initialVelocity = 0f)
    }
    val rotating by remember(rotation, targetAngle) {
        derivedStateOf { rotation.isRunning || abs(rotation.value - targetAngle) > .1f }
    }
    Column(Modifier.fillMaxSize().testTag("ConnectedDevicePanel"), horizontalAlignment = Alignment.CenterHorizontally) {
        ApkDropArea(state, Modifier.weight(1f).fillMaxWidth()) {
            ZoomedDevicePreview(zoom, Modifier.fillMaxSize().padding(devicePreviewPadding)) { viewport ->
                val picture = frame
                val (nativeWidth, nativeHeight) = nativeScreenSize(picture.width, picture.height, frameRotation)
                val ratio = nativeWidth.toFloat() / nativeHeight
                // Phones show in their real frame when the SDK has one for the model, otherwise in a drawn phone shell.
                LaunchedEffect(device.model) { if (device.type == DeviceType.PHONE) state.loadPhoneSkin(device.model) }
                val skin = state.phoneSkins[device.model]?.takeIf { device.type == DeviceType.PHONE && it.skin.fits(nativeWidth, nativeHeight) }
                val shelled = device.type == DeviceType.PHONE || device.type == DeviceType.TABLET || device.type == DeviceType.FOLDABLE
                // Some models the SDK has no frame for are drawn from their real measurements.
                val drawn = DrawnFrames.forModel(device.model)?.takeIf { skin == null }
                val width = when {
                    skin != null -> fitDevicePreview(viewport, DpSize(skin.skin.width.dp, skin.skin.height.dp), rotation.value).width *
                        skin.skin.screenWidth / skin.skin.width
                    drawn != null -> drawn.screenWidth(
                        fitDevicePreview(viewport, drawn.frameSize(DpSize(nativeWidth.dp, nativeHeight.dp)), rotation.value).width)
                    shelled -> PhoneShellProportions.screenWidth(
                        fitDevicePreview(viewport, PhoneShellProportions.shellSize(DpSize(nativeWidth.dp, nativeHeight.dp)), rotation.value).width,
                        nativeWidth.toFloat(), nativeHeight.toFloat())
                    else -> fitDevicePreview(viewport, DpSize(nativeWidth.dp, nativeHeight.dp), rotation.value, 9.dp).width
                }
                val height = width / ratio
                val corner = when (device.type) { DeviceType.WEAR -> 100.dp; DeviceType.TV -> 8.dp; else -> 16.dp }
                val screen: @Composable BoxScope.() -> Unit = {
                    Image(picture, "${device.displayName} screen", Modifier.align(Alignment.Center)
                        .requiredSize(if (frameRotation == 0) width else height, if (frameRotation == 0) height else width)
                        .graphicsLayer { rotationZ = -targetAngle }
                        .clip(RoundedCornerShape(when {
                            drawn != null -> drawn.screenCorner.dp
                            device.type == DeviceType.WEAR -> corner - 8.dp
                            else -> 6.dp
                        }))
                        .focusRequester(keyboardFocus)
                        .onFocusChanged { if (!it.isFocused) session.mirror.releaseKeyboard() }
                        .onPreviewKeyEvent { event ->
                            val native = event.awtEventOrNull
                            val usage = native?.let { physicalKeyUsage(it.keyCode, it.keyLocation) }
                            if (event.isMetaPressed) {
                                session.mirror.releaseKeyboard()
                                false
                            } else if (usage == null || rotating || !device.available ||
                                event.type !in listOf(KeyEventType.KeyDown, KeyEventType.KeyUp)) false else {
                                session.mirror.keyboardKey(usage, event.type == KeyEventType.KeyDown)
                                true
                            }
                        }.focusable()
                        .pointerInput(device.serial, picture.width, picture.height, rotating) {
                            if (!rotating) awaitEachGesture {
                                val down = awaitFirstDown()
                                keyboardFocus.requestFocus()
                                var last = down.position
                                fun send(action: Int) = session.mirror.touch(action,
                                    (last.x / size.width * picture.width).toInt(),
                                    (last.y / size.height * picture.height).toInt(), picture.width, picture.height)
                                send(0)
                                down.consume()
                                try {
                                    do {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        last = change.position
                                        if (change.pressed) send(2)
                                        change.consume()
                                    } while (event.changes.any { it.pressed })
                                } finally { send(1) }
                            }
                        }, contentScale = ContentScale.FillBounds)
                    // Inside the frame, rounded like the screen.
                    Box(Modifier.matchParentSize().clip(RoundedCornerShape(if (device.type == DeviceType.WEAR) corner - 8.dp else 6.dp))) {
                        ScreenshotFlash(state.screenshotsTaken[device.name] ?: 0)
                    }
                }
                val frameModifier = Modifier.testTag("ConnectedDeviceFrame").graphicsLayer { rotationZ = rotation.value }
                when {
                    skin != null -> {
                        val scale = width / skin.skin.screenWidth
                        Box(frameModifier.requiredSize(scale * skin.skin.width, scale * skin.skin.height)) {
                            Image(skin.image, "${device.displayName} frame", Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
                            Box(Modifier.offset(scale * skin.skin.screenX, scale * skin.skin.screenY).requiredSize(width, height), content = screen)
                        }
                    }
                    drawn != null -> DrawnFrame(drawn, DpSize(width, height), frameModifier, content = screen)
                    shelled -> PhoneShell(DpSize(width, height), frameModifier, content = screen)
                    else -> Box(frameModifier.requiredSize(width + 18.dp, height + 18.dp)
                        .background(RobomoriTheme.colors.deviceFrame, RoundedCornerShape(corner))
                        .border(2.dp, RobomoriTheme.colors.deviceFrameBorder, RoundedCornerShape(corner)).padding(9.dp), content = screen)
                }
            }
        }
        DeviceToolbar(device, state, sendInput = { input(*it.toTypedArray()) },
            rotate = { state.rotate(device) }.takeIf { device.type.supportsRotation }, enabled = device.available)
    }
}
