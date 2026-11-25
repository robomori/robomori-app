package com.robomori

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.robomori.components.AppDialog
import com.robomori.components.AppDialogPanel
import com.robomori.components.Button
import com.robomori.components.ButtonStyle
import com.robomori.components.Card
import com.robomori.components.DeviceStateMessage
import com.robomori.components.Icon
import com.robomori.components.Label
import com.robomori.components.SidebarListItem
import com.robomori.iconography.Android
import com.robomori.iconography.Icons
import com.robomori.iconography.LayoutGrid
import com.robomori.theme.RobomoriTheme
import com.robomori.theme.verticalScroll
import com.composeunstyled.Text
import org.jetbrains.skia.Image as SkiaImage

/** The pages of the panel on the right of the device. */
internal enum class InspectorPage { Controls, Apps, Logcat, Files, Databases, Network }

@Composable
internal fun rememberInstalledApps(state: AppState, visible: Boolean): InstalledAppsState? {
    val serial = reachableSerial(state) ?: return null
    return key(serial, state.settings) {
        val scope = rememberCoroutineScope()
        val apps = remember {
            val network = state.networkInspector(serial)
            InstalledAppsState(state.installedAppsService, serial, scope, network::agentForLaunch)
        }
        LaunchedEffect(apps, visible) { if (visible) apps.observe(visible) }
        apps
    }
}

@Composable
internal fun InstalledAppsPanel(state: AppState, apps: InstalledAppsState?, onShowFiles: (InstalledApp) -> Unit) {
    val message = when {
        state.selected.size > 1 -> "Select one emulator to see its apps."
        apps == null && state.connected.selected != null -> "Connect this device to see its apps."
        apps == null && state.previewDevice == null -> "Select a device to see its apps."
        apps == null -> "Start this emulator to see its apps."
        else -> when (val snapshot = apps.snapshot) {
            InstalledAppsSnapshot.Loading -> "Reading the installed apps…"
            is InstalledAppsSnapshot.Failed -> snapshot.cause.message ?: "Could not read the installed apps."
            is InstalledAppsSnapshot.Loaded ->
                if (snapshot.apps.isEmpty()) "Your debuggable apps will appear here, as soon as you install any." else null
        }
    }
    val loaded = (apps?.snapshot as? InstalledAppsSnapshot.Loaded)?.apps
    if (message != null || loaded == null) {
        Box(Modifier.fillMaxSize().padding(16.dp).testTag("InstalledAppsPanel"), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.LayoutGrid, tint = RobomoriTheme.colors.textSecondary, modifier = Modifier.size(28.dp))
                DeviceStateMessage(message.orEmpty())
            }
        }
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("InstalledAppsPanel"),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(contentPadding = PaddingValues(4.dp)) {
            for (app in loaded) InstalledAppRow(apps, app, onShowFiles)
        }
    }
    AppDialog(apps.confirming, onClose = apps::dismissConfirmation) { request ->
        ConfirmAppActionDialog(request, onConfirm = apps::confirm, onCancel = apps::dismissConfirmation)
    }
    AppDialog(apps.action as? AppActionState.Failed, onClose = apps::dismissFailure) { failure ->
        AppActionFailedDialog(failure, onClose = apps::dismissFailure)
    }
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
private fun InstalledAppRow(apps: InstalledAppsState, app: InstalledApp, onShowFiles: (InstalledApp) -> Unit) {
    val selected = app.packageName in apps.selection
    ContextMenuArea(items = {
        val targets = apps.contextTargets(app)
        if (apps.action is AppActionState.Running) emptyList()
        else AppAction.entries.map { action ->
            ContextMenuItem(action.title + if (action.needsConfirmation) "…" else "") { apps.request(action, targets) }
        } + listOfNotNull(targets.singleOrNull()?.let { ContextMenuItem("Show Files") { onShowFiles(it) } })
    }) {
        SidebarListItem(selected = selected, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).testTag("InstalledApp:${app.packageName}")
                .semantics { this.selected = selected }
                // Selects as the button goes down: a click waits to rule out a double-click first, so selecting on
                // it would lag. Cmd adds or removes it, Shift selects the apps from the last one clicked.
                .onPointerEvent(PointerEventType.Press) { event ->
                    if (!event.buttons.isPrimaryPressed) return@onPointerEvent
                    val keys = event.keyboardModifiers
                    apps.select(app, toggle = keys.isMetaPressed || keys.isCtrlPressed, extend = keys.isShiftPressed)
                }
                // Double-clicking opens the app, as double-clicking an icon does on the desktop.
                .combinedClickable(onDoubleClick = { apps.request(AppAction.Launch, listOf(app)) }) {}) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(app, 32.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Label(app.name, maxLines = 1)
                    Spacer(Modifier.height(2.dp))
                    SecondaryText(app.packageName)
                }
                if (app.version.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    SecondaryText(app.version)
                }
            }
        }
    }
}

@Composable
private fun AppActionFailedDialog(failure: AppActionState.Failed, onClose: () -> Unit) {
    val okFocus = remember { FocusRequester() }
    AppDialogPanel("Couldn't ${failure.request.action.title.lowercase()} ${failure.app.name}", "AppActionFailed",
        Modifier.width(420.dp)) {
        LaunchedEffect(Unit) { okFocus.requestFocus() }
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            failure.cause.message?.takeIf { it.isNotBlank() }?.let { Label(it) }
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                Button(onClick = onClose, style = ButtonStyle.Primary, modifier = Modifier.focusRequester(okFocus),
                    tag = "DismissAppActionFailed") { Label("OK") }
            }
        }
    }
}

/** The apps a request acts on, as a sentence names them. */
private val AppActionRequest.subject get() = apps.singleOrNull()?.name ?: "${apps.size} apps"

@Composable
private fun ConfirmAppActionDialog(request: AppActionRequest, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val cancelFocus = remember { FocusRequester() }
    val single = request.apps.singleOrNull()
    AppDialogPanel("${request.action.title} ${request.subject}?", "ConfirmAppAction", Modifier.width(420.dp)) {
        LaunchedEffect(Unit) { cancelFocus.requestFocus() }
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Label(when (request.action) {
                AppAction.Uninstall -> if (single != null) "${single.name} and all its data will be removed from the device."
                    else "These apps and all their data will be removed from the device: ${request.apps.joinToString { it.name }}."
                else -> if (single != null) "${single.name}'s files, databases, settings and accounts will be erased, as if it was just installed."
                    else "The files, databases, settings and accounts of these apps will be erased, as if they were just installed: ${request.apps.joinToString { it.name }}."
            })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Spacer(Modifier.weight(1f))
                Button(onClick = onCancel, style = ButtonStyle.Outlined, modifier = Modifier.focusRequester(cancelFocus),
                    tag = "CancelAppAction") { Label("Cancel") }
                Button(onClick = onConfirm, style = ButtonStyle.Primary, tag = "ConfirmAppAction") { Label(request.action.title) }
            }
        }
    }
}

private val AppAction.title get() = when (this) {
    AppAction.Launch -> "Launch"
    AppAction.ForceStop -> "Force Stop"
    AppAction.ClearCache -> "Clear Cache"
    AppAction.ClearData -> "Clear Data"
    AppAction.Uninstall -> "Uninstall"
}

private val InstalledApp.version get() = listOfNotNull(versionName, versionCode?.let { "($it)" }).joinToString(" ")

@Composable
private fun SecondaryText(text: String) {
    Text(text, color = RobomoriTheme.colors.textSecondary, style = RobomoriTheme.typography.small,
        maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
internal fun AppIcon(app: InstalledApp, size: Dp) {
    val icon = remember(app.icon) {
        app.icon?.let { runCatching { SkiaImage.makeFromEncoded(it).toComposeImageBitmap() }.getOrNull() }
    }
    if (icon != null) Image(icon, contentDescription = null, modifier = Modifier.size(size))
    else Box(Modifier.size(size).background(RobomoriTheme.colors.deviceIconBackground, CircleShape), contentAlignment = Alignment.Center) {
        Icon(Icons.Android, tint = RobomoriTheme.colors.textSecondary, modifier = Modifier.size(size * 0.55f))
    }
}
