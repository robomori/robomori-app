@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
package com.robomori

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import java.io.File
import com.robomori.components.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import com.robomori.theme.RobomoriTheme
import com.robomori.theme.LazyColumn
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import com.robomori.iconography.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.background
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.awt.awtEventOrNull
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import robomori.generated.resources.Res
import robomori.generated.resources.app_icon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import kotlinx.coroutines.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import dev.nucleusframework.aot.runtime.AotRuntime
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import org.jetbrains.compose.resources.painterResource
import com.composeunstyled.UnstyledDrawerState
import com.composeunstyled.DialogHost
import com.composeunstyled.TooltipHost
import com.composeunstyled.FocusVisibilityProvider
import com.composeunstyled.Text

fun main(args: Array<String>) {
    // Libraries such as the SQLite driver log through SLF4J, which otherwise warns on every run that nothing collects it.
    System.setProperty("slf4j.internal.verbosity", "ERROR")
    if ("--diagnostics" in args) {
        println(AndroidService(SettingsStore().load()).diagnostics())
        return
    }
    if (args.toList() == forwardMicrophoneArguments) { forwardMicrophoneInBackground(); kotlin.system.exitProcess(0) }
    // The command line returns before the hook below: emulators it starts keep running after it exits.
    if (args.firstOrNull() == CLI_ARGUMENT) kotlin.system.exitProcess(Cli().run(args.drop(1)))
    // Emulators live only as long as the app: quitting it, or the system ending it, stops them all. A crash leaves them
    // running, as Robomori finds them again when it opens, and so does a development run, restarted as it's worked on.
    // Packaging's training run must leave the machine's emulators alone.
    if (System.getProperty("jpackage.app-path") == null) keepEmulatorsOnExit.set(true)
    val crashed = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
        keepEmulatorsOnExit.set(true)
        AppLogs().record("Crashed on ${thread.name}", failure)
        crashed?.uncaughtException(thread, failure) ?: failure.printStackTrace()
    }
    if (!AotRuntime.isTraining()) Runtime.getRuntime().addShutdownHook(Thread {
        // Turns the network inspector's VPN off at once, rather than when it notices Robomori is gone.
        NetworkInspectorState.inspecting.forEach { runCatching { NetworkInspectorService(SettingsStore().load()).unroute(it) } }
        if (!keepEmulatorsOnExit.get()) runCatching { AndroidService(SettingsStore().load()).stopAll() }
    })
    if (HostOs.detect() == HostOs.MAC) {
        System.setProperty("apple.laf.useScreenMenuBar", "true")
        System.setProperty("apple.awt.application.name", APP_NAME)
        System.setProperty("apple.awt.application.appearance", "NSAppearanceNameAqua")
        // Packaging's training run starts as a background app, without a Dock icon or menu bar, so building a release
        // doesn't pop Robomori up on the Mac it's built on.
        if (AotRuntime.isTraining()) System.setProperty("apple.awt.UIElement", "true")
    }
    application {
        if (AotRuntime.isTraining()) {
            // Packaging records startup in a training run, which must exit on its own once the app has settled.
            LaunchedEffect(Unit) { delay(20_000); exitApplication() }
        }
        val scope = rememberCoroutineScope()
        val windowRequests = remember { AppWindowRequests() }
        // Started to show emulators from the command line, the app opens just their windows. On a Mac, the Dock icon
        // brings up the main window.
        var mainVisible by remember { mutableStateOf(!windowRequests.pending()) }
        var mainFront by remember { mutableIntStateOf(0) }
        val state = remember {
            val analytics = appAnalytics().also { trackLaunch(it) }
            AppState(scope, catalogCache = SdkCatalogCache(), mediaStore = EmulatorMediaStore(), graphicsStore = EmulatorGraphicsStore(), welcome = WelcomeNotice(), shutterSound = ShutterSound.CameraClick,
                analytics = analytics, logs = AppLogs()).also {
                it.keepCatalogFresh(); it.watchCameraUse(); it.watchGraphicsCrashes()
                if (!AotRuntime.isTraining()) {
                    it.watchOutsideChanges(); it.commandLine.refresh(); it.agentSkill.refresh(); it.localhostPorts.keepApplied()
                    it.watchWindowRequests(windowRequests.also(AppWindowRequests::holdLock))
                }
            }
        }
        LaunchedEffect(Unit) { if (!AotRuntime.isTraining()) codePush?.let { keepCodeUpdated(it, state) } }
        val drawerState = rememberAppSidebarState(initialValue = state.workspaceLayout.layout.deviceListShown)
        // However the device list is shown or hidden, by its button, its shortcut or dragging its edge, it's kept for next time.
        LaunchedEffect(drawerState) { snapshotFlow { drawerState.targetValue }.collect(state.workspaceLayout::showDeviceList) }
        var alwaysOnTop by remember { mutableStateOf(false) }
        // Packaging runs the app once to record its startup. That run draws the window like any other, but off-screen
        // and without taking focus, so it doesn't pop up while a release is built.
        val training = remember { AotRuntime.isTraining() }
        val windowState = rememberWindowState(width = 1040.dp, height = 720.dp,
            position = if (training) WindowPosition((-20000).dp, (-20000).dp) else WindowPosition.PlatformDefault)
        // Each focus window counts its requests to come to the front, such as from the command line.
        val focusRequests = remember { mutableStateMapOf<String, Int>() }
        val windowIcon = if (HostOs.detect() == HostOs.MAC) null else painterResource(Res.drawable.app_icon)
        val topInset = if (HostOs.detect() == HostOs.MAC) 28.dp else 0.dp
        fun showFocus(target: DeviceTarget?) {
            target?.let { focusRequests[it.focusKey] = (focusRequests[it.focusKey] ?: 0) + 1 }
        }
        LaunchedEffect(state.license.unlocked) { if (!state.license.unlocked) state.closeAllFocus() }
        // Checks the time now and then, so the app locks when the trial runs out while it's open.
        LaunchedEffect(state.license) { while (true) { delay(60_000); state.license.tick() } }
        fun showMain() { mainVisible = true; mainFront++ }
        LaunchedEffect(Unit) {
            state.shownWindows.collect { target -> if (target != null) showFocus(target) else showMain() }
        }
        DisposableEffect(Unit) {
            val desktop = java.awt.Desktop.getDesktop().takeIf { it.isSupported(java.awt.Desktop.Action.APP_EVENT_REOPENED) }
            // Like other Mac apps, the Dock icon opens the main window only when no window is open. Otherwise macOS
            // already brings the open windows to the front.
            val listener = java.awt.desktop.AppReopenedListener { if (!mainVisible && state.focusTargets.isEmpty()) showMain() }
            desktop?.addAppEventListener(listener)
            onDispose { desktop?.removeAppEventListener(listener) }
        }
        // Without the main window, closing the last emulator window quits, as closing the main window does. On a Mac
        // the app stays in the Dock instead, like other Mac apps.
        LaunchedEffect(Unit) {
            snapshotFlow { mainVisible || state.focusTargets.isNotEmpty() }.collect { open ->
                if (!open && HostOs.detect() != HostOs.MAC) exitApplication()
            }
        }
        Window(
            onCloseRequest = { state.closeAllFocus(); exitApplication() },
            title = APP_NAME,
            icon = windowIcon,
            state = windowState,
            visible = mainVisible,
            focusable = !training,
            // The onboarding is laid out for the window's size; it can be resized again once it's done.
            resizable = !state.showWelcome,
            onPreviewKeyEvent = { event ->
                handleMainWindowShortcut(event, state) { drawerState.targetValue = !drawerState.targetValue }
            },
        ) {
            MenuBar {
                Menu("Keyboard") {
                    Item("Toggle Software Keyboard",
                        shortcut = KeyShortcut(Key.K, alt = true, meta = HostOs.detect() == HostOs.MAC, ctrl = HostOs.detect() != HostOs.MAC),
                        onClick = state::toggleSoftwareKeyboard)
                }
            }
            window.minimumSize = java.awt.Dimension(760, 540)
            ObserveWindowFocus(window, state)
            LaunchedEffect(mainFront) { if (mainFront > 0) { window.isMinimized = false; window.toFront(); window.requestFocus() } }
            configureTitleBar(window)
            AppSurface {
                App(state, topInset = topInset, drawerState = drawerState,
                    onOpenFocus = { showFocus(state.openFocus()) })
            }
        }
        state.focusedDevices.forEachIndexed { index, focusDevice ->
            key(focusDevice.focusKey) {
                FocusWindow(state, focusDevice, windowIcon, topInset, alwaysOnTop, { alwaysOnTop = it }, onExpand = ::showMain,
                    request = focusRequests[focusDevice.focusKey] ?: 0,
                    // New windows cascade from the main window so they don't stack exactly on top of each other.
                    position = windowState.position.offset(index * 28))
            }
        }
    }
}

@Composable private fun FocusWindow(state: AppState, focusDevice: DeviceTarget, windowIcon: androidx.compose.ui.graphics.painter.Painter?,
                                    topInset: Dp, alwaysOnTop: Boolean, onAlwaysOnTopChange: (Boolean) -> Unit,
                                    onExpand: () -> Unit, request: Int, position: WindowPosition) {
    val windowState = rememberWindowState()
    val focusMode = remember(windowState) {
        EmulatorFocusState(windowState).also { mode ->
            // Sized before the window appears: a window that's still hidden ignores a new size.
            state.focusDeviceSize(focusDevice)?.let { mode.enter(it, screenDeviceWindowSize(), position) }
        }
    }
    val close = { state.closeFocus(focusDevice) }
    Window(
        onCloseRequest = close,
        title = "${focusDevice.displayName} — $APP_NAME",
        icon = windowIcon,
        alwaysOnTop = alwaysOnTop,
        state = windowState,
        onPreviewKeyEvent = { event ->
            if (event.type == KeyEventType.KeyDown &&
                (if (HostOs.detect() == HostOs.MAC) event.isMetaPressed else event.isCtrlPressed) &&
                focusDevice is Device) when (event.key) {
                Key.DirectionRight -> state.rotate(focusDevice, clockwise = true)
                Key.DirectionLeft -> state.rotate(focusDevice, clockwise = false)
                else -> false
            } else false
        },
    ) {
        window.minimumSize = java.awt.Dimension(320, 200)
        configureTitleBar(window)
        ObserveWindowFocus(window, state)
        val deviceSize = state.focusDeviceSize(focusDevice)
        LaunchedEffect(request) { window.toFront(); window.requestFocus() }
        LaunchedEffect(deviceSize) {
            if (deviceSize != null) {
                focusMode.resize(deviceSize, window.availableDeviceWindowSize())
                window.keepFocusWindowOnScreen(windowState)
            }
        }
        AppSurface {
            // Leaving focus returns to the main window, also when the app was started without it.
            FocusedDeviceWorkspace(state, focusDevice, topInset, alwaysOnTop, onAlwaysOnTopChange) { close(); onExpand() }
        }
    }
}

private fun WindowPosition.offset(step: Int): WindowPosition =
    if (this is WindowPosition.Absolute) WindowPosition(x + step.dp, y + step.dp) else this

@Composable private fun AppSurface(content: @Composable () -> Unit) {
    RobomoriTheme {
        FocusVisibilityProvider {
            DialogHost {
                TooltipHost {
                    CompositionLocalProvider(LocalContextMenuRepresentation provides FadingContextMenuRepresentation, content = content)
                }
            }
        }
    }
}

private fun configureTitleBar(window: java.awt.Window) {
    if (HostOs.detect() == HostOs.MAC && window is javax.swing.JFrame) {
        window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
        window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
        window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
    }
}

@Composable private fun ObserveWindowFocus(window: java.awt.Window, state: AppState) {
    DisposableEffect(window, state) {
        val listener = object : java.awt.event.WindowAdapter() {
            override fun windowGainedFocus(event: java.awt.event.WindowEvent) = state.windowFocusChanged(true)
            override fun windowLostFocus(event: java.awt.event.WindowEvent) = state.windowFocusChanged(false)
        }
        window.addWindowFocusListener(listener)
        state.windowFocusChanged(window.isFocused)
        onDispose { window.removeWindowFocusListener(listener) }
    }
}

@Composable private fun FocusedDeviceWorkspace(state: AppState, device: DeviceTarget, topInset: Dp,
                                             alwaysOnTop: Boolean, onAlwaysOnTopChange: (Boolean) -> Unit,
                                             onClose: () -> Unit) {
    AppScaffold(topInset = topInset, title = device.displayName,
        subtitle = (device as? Device)?.platformTitle,
        modifier = Modifier.testTag("FocusDeviceWindow"), showSidebars = false, sidebar = {},
        topEndActions = {
            ToggleButton(value = alwaysOnTop, onValueChange = onAlwaysOnTopChange,
                style = ButtonStyle.Ghost, size = ButtonSize.Icon,
                contentDescription = if (alwaysOnTop) "Stop keeping window on top" else "Keep window on top") { Icon(Icons.Pin) }
            Button(onClick = onClose, style = ButtonStyle.Ghost, size = ButtonSize.Icon, contentDescription = "Close focus window", tag = "CloseFocusWindow") { Icon(Icons.Maximize2) }
        }) {
        when (device) {
            is ConnectedDevice -> ConnectedDevicePanel(device, state)
            is Device -> EmulatorPanel(device, state)
        }
    }
}

private fun screenDeviceWindowSize(): DpSize {
    val bounds = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
    return DpSize(bounds.width.dp, bounds.height.dp)
}

private fun java.awt.Window.availableDeviceWindowSize(): DpSize {
    val bounds = graphicsConfiguration.bounds
    val insets = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(graphicsConfiguration)
    return DpSize((bounds.width - insets.left - insets.right).dp, (bounds.height - insets.top - insets.bottom).dp)
}

private fun java.awt.Window.keepFocusWindowOnScreen(state: WindowState) {
    val bounds = graphicsConfiguration.bounds
    val insets = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(graphicsConfiguration)
    val left = bounds.x + insets.left
    val top = bounds.y + insets.top
    val position = state.position as? WindowPosition.Absolute
    val x = (position?.x?.value?.toInt() ?: x).coerceIn(left,
        maxOf(left, bounds.x + bounds.width - insets.right - state.size.width.value.toInt()))
    val y = (position?.y?.value?.toInt() ?: y).coerceIn(top,
        maxOf(top, bounds.y + bounds.height - insets.bottom - state.size.height.value.toInt()))
    state.position = WindowPosition(x.dp, y.dp)
}

// Handle shortcuts before focused content, including the emulator and text fields.
private fun handleMainWindowShortcut(event: KeyEvent, state: AppState, toggleSidebar: () -> Unit): Boolean {
    if (event.type != KeyEventType.KeyDown || state.hasOpenDialog) return false
    if (if (HostOs.detect() == HostOs.MAC) event.isMetaPressed else event.isCtrlPressed) when (event.key) {
        Key.DirectionRight -> return state.rotateCurrent(clockwise = true)
        Key.DirectionLeft -> return state.rotateCurrent(clockwise = false)
        Key.B -> { toggleSidebar(); return true }
    }
    if (!event.isMetaPressed && !event.isCtrlPressed) return false
    return when (event.key) {
        Key.N -> { state.resetCreate(); true }
        Key.Comma -> { state.openSettings(); true }
        // ⌘= and ⌘+ are the same key on most layouts; ⌘+ arrives with Shift held.
        Key.Equals, Key.Plus, Key.NumPadAdd -> { state.zoomIn(); true }
        Key.Minus, Key.NumPadSubtract -> { state.zoomOut(); true }
        Key.Zero, Key.NumPad0 -> { state.zoomToFit(); true }
        // ⌘⌫ deletes the selected emulators only from the device list, where it's handled, never from a page beside it,
        // where it deletes that page's own selection, such as files.
        else -> false
    }
}

@Composable fun App(state: AppState,
                    autoRefresh: Boolean = true, topInset: Dp = 0.dp,
                    onOpenFocus: () -> Unit = {},
                    drawerState: UnstyledDrawerState<Boolean> = rememberAppSidebarState()) {
    LaunchedEffect(state) { state.observeDisplays() }
    LaunchedEffect(state, autoRefresh) {
        if (autoRefresh) {
            while (isActive) {
                state.refresh()
                runCatching { state.connected.refresh() }
                delay(3000)
            }
        }
    }
    LaunchedEffect(state, autoRefresh) { if (autoRefresh) state.updates.monitor() }
    Box(Modifier.fillMaxSize()) {
        // Failures show over both the workspace and Settings, below the title bar.
        val toasts = rememberToastState()
        LaunchedEffect(state, toasts) {
            state.failures.collect { failure ->
                launch { toasts.show(ToastMessage(failure.message, ToastTone.Error), ToastPresentation.Top) }
            }
        }
        LaunchedEffect(state, toasts) {
            state.graphicsCrashes.collect { device ->
                launch {
                    toasts.show(ToastMessage("A WebView crashed on $device, a known Android 17 emulator bug. " +
                        "Switch to Software in Settings → Graphics."), ToastPresentation.Top)
                }
            }
        }
        ToastHost(toasts, Modifier.fillMaxSize(),
            insets = PaddingValues(start = 16.dp, end = 16.dp, top = topInset + 12.dp, bottom = 16.dp)) {
            Box(Modifier.fillMaxSize()) {
                // Drawn once the onboarding is done, so it's there as the onboarding fades away.
                if (!state.showWelcome) {
                    // After the trial, only buying or activating a license opens the app again.
                    // Development builds still open Settings, to turn off the Debug switches that pretend the trial ended.
                    if (!state.license.unlocked && !(developmentBuild && state.showSettings)) RobomoriTheme(dark = true) { TrialEndedScreen(state, topInset) }
                    else if (state.showSettings) SettingsScreen(state, topInset)
                    else EmulatorWorkspace(state, topInset, drawerState, onOpenFocus)
                }
                // The onboarding takes the whole window, in the dark theme, until it's finished, then fades out as it grows slightly.
                androidx.compose.animation.AnimatedVisibility(state.showWelcome, enter = androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(400)) +
                        androidx.compose.animation.scaleOut(androidx.compose.animation.core.tween(400), targetScale = 1.04f)) {
                    RobomoriTheme(dark = true) { WelcomeDialog(state, topInset) }
                }
            }
        }
        AppDialog(state.showDemoDialog.takeIf { it }, onClose = { state.showDemoDialog = false }) {
            DemoDialog(state)
        }
        AppDialog(state.renameDevice, onClose = { if (!state.busy) state.renameDevice = null }) { device ->
            RenameDeviceDialog(state, device)
        }
        AppDialog(state.wipeDataDevice, onClose = { state.wipeDataDevice = null }) { device ->
            WipeDataDialog(state, device)
        }
        AppDialog(state.pendingMedia, onClose = state::dismissMediaChange) {
            MediaRestartDialog(state)
        }
        // A stray click must not abort a pairing in progress.
        AppDialog(state.connected.showConnect.takeIf { it }, onClose = { state.connected.cancel(); state.connected.showConnect = false },
            dismissOnClickOutside = false) {
            ConnectDeviceDialog(state.connected)
        }
        AppDialog(state.connected.renameTarget, onClose = { if (!state.connected.working) state.connected.dismissRename() }) {
            RenameConnectedDeviceDialog(state.connected, it)
        }
        AppDialog(state.connected.unpairTarget, onClose = { if (!state.connected.working) state.connected.dismissUnpair() }) {
            UnpairDeviceDialog(state.connected, it)
        }
    }
}

@Composable private fun EmulatorWorkspace(state: AppState,
                                          topInset: Dp, drawerState: UnstyledDrawerState<Boolean>,
                                          onOpenFocus: () -> Unit) {
    // Each of the device's panels opens on the right from its own button, one at a time.
    // The panel open when Robomori last quit opens again.
    val layout = state.workspaceLayout
    val panelState = rememberAppSidebarState(initialValue = layout.layout.openPanel != null)
    var chosenPanel by remember { mutableStateOf(layout.layout.openPanel ?: InspectorPage.Controls) }
    LaunchedEffect(panelState) {
        snapshotFlow { chosenPanel.takeIf { panelState.targetValue } }.collect(layout::openPanel)
    }
    val panelOpen = panelState.targetValue
    // The panel switched away from, which keeps its content while it fades out.
    var fadingPanel by remember { mutableStateOf<InspectorPage?>(null) }
    LaunchedEffect(fadingPanel) { if (fadingPanel != null) { delay(PanelSwitchMillis.toLong()); fadingPanel = null } }
    fun open(page: InspectorPage?) {
        if (page != null && panelOpen && page != chosenPanel) fadingPanel = chosenPanel
        if (page != null) chosenPanel = page
        panelState.targetValue = page != null
    }
    fun shows(page: InspectorPage) = panelOpen && chosenPanel == page
    fun live(page: InspectorPage) = shows(page) || panelOpen && fadingPanel == page
    val deviceControls = rememberDeviceControls(state, live(InspectorPage.Controls))
    val installedApps = rememberInstalledApps(state, live(InspectorPage.Apps))
    val logcat = rememberLogcat(state, live(InspectorPage.Logcat))
    val deviceFiles = rememberDeviceFiles(state, live(InspectorPage.Files))
    val appDatabases = rememberAppDatabases(state, live(InspectorPage.Databases))
    var createMenuExpanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        AppScaffold(
            topInset = topInset,
            title = state.connected.selected?.displayName ?: if (state.selected.size > 1) "Multiple emulators selected" else state.previewDevice?.displayName ?: APP_NAME,
            subtitle = state.previewDevice?.takeIf { state.selected.size <= 1 }?.let { it.platformTitle },
            modifier = Modifier.testTag("DeviceShelf"),
            sidebarState = drawerState,
            initialSidebarWidth = layout.layout.deviceListWidth ?: 270.dp,
            onSidebarWidthChange = layout::resizeDeviceList,
            sidebar = { DeviceSidebar(state, createMenuExpanded, { createMenuExpanded = it }, Modifier.fillMaxWidth()) },
            trailingSidebarState = panelState,
            trailingSidebarWidth = layout.layout.panelWidth ?: PanelWidth,
            onTrailingSidebarWidthChange = layout::resizePanel,
            trailingSidebar = { headerHeight ->
                // Under the app bar, whose buttons sit over the panel's top. One panel fades into the next as it resizes.
                Crossfade(targetState = chosenPanel, modifier = Modifier.fillMaxSize().padding(top = headerHeight),
                    animationSpec = tween(PanelSwitchMillis), label = "Panel") { panel ->
                    when (panel) {
                        // Settings read best as a narrow column, however wide the panel.
                        InspectorPage.Controls -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                            Box(Modifier.widthIn(max = SettingsMaxWidth + controlsPanelPadding * 2)) { DeviceControlsPanel(state, deviceControls, panelOpen) }
                        }
                        InspectorPage.Apps -> InstalledAppsPanel(state, installedApps) { app ->
                            open(InspectorPage.Files)
                            deviceFiles?.showApp(app.packageName)
                        }
                        InspectorPage.Files -> DeviceFilesPanel(state, deviceFiles) { file ->
                            open(InspectorPage.Databases)
                            appPackageOf(file.path)?.let { appDatabases?.openFile(it, file.path) }
                        }
                        InspectorPage.Network -> NetworkPanel(state, networkInspectorOf(state))
                        InspectorPage.Databases -> AppDatabasesPanel(state, appDatabases)
                        InspectorPage.Logcat -> LogcatPanel(state, logcat)
                    }
                }
            },
            fixedEndActions = {
                IconButtonGroup(elevated = true) {
                    for ((page, name, icon) in PanelPages) {
                        ToggleButton(value = shows(page), onValueChange = { open(page.takeIf { _ -> it }) },
                            style = ButtonStyle.Ghost, size = ButtonSize.Icon, tag = "InspectorPage:${page.name}",
                            contentDescription = name) { Icon(icon) }
                    }
                }
            },
            topStartActions = {
                Button(onClick = { drawerState.targetValue = !drawerState.targetValue },
                    style = ButtonStyle.Ghost, size = ButtonSize.Icon,
                    contentDescription = if (drawerState.targetValue) "Hide sidebar" else "Show sidebar",
                    tag = "ToggleSidebar") { Icon(Icons.ViewColumns) }
                Spacer(Modifier.width(34.dp))
            },
            topEndActions = {
                DroppingFirstWhenNarrow(first = {
                    IconButtonGroup(elevated = true) {
                        Button(onClick = state::zoomOut, style = ButtonStyle.Ghost, size = ButtonSize.Icon, contentDescription = "Zoom out",
                            tag = "ZoomOut", enabled = state.canZoom && state.previewZoom > DeviceZoomLevels.first()) { Icon(Icons.ZoomOut) }
                        Button(onClick = state::zoomToFit, style = ButtonStyle.Ghost, size = ButtonSize.Icon, contentDescription = "Zoom to fit",
                            tag = "ZoomToFit", enabled = state.previewZoom != 1f) { Icon(Icons.Scan) }
                        Button(onClick = state::zoomIn, style = ButtonStyle.Ghost, size = ButtonSize.Icon, contentDescription = "Zoom in",
                            tag = "ZoomIn", enabled = state.canZoom && state.previewZoom < DeviceZoomLevels.last()) { Icon(Icons.ZoomIn) }
                    }
                }) {
                    IconButtonGroup(elevated = true) {
                        Button(onClick = onOpenFocus, style = ButtonStyle.Ghost, size = ButtonSize.Icon, contentDescription = "Open focus window", tag = "ToggleEmulatorFocus", enabled = state.canFocusDevice) { Icon(Icons.Minimize2) }
                        DeviceOverflowMenu(state)
                    }
                }
            },
        ) {
            Box(Modifier.fillMaxSize()) {
                val device = state.previewDevice
                if (state.connected.selected != null) {
                    ConnectedDevicePanel(state.connected.selected!!, state, state.previewZoom)
                } else if (state.selected.size > 1) {
                    Box(Modifier.fillMaxSize().testTag("MultipleDevicesSelected"), contentAlignment = Alignment.Center) {
                        DeviceStateMessage("Multiple emulators selected")
                    }
                } else if (device != null) EmulatorPanel(device, state, state.previewZoom)
                else Box(Modifier.fillMaxSize().testTag("EmptyShelf"), contentAlignment = Alignment.Center) {
                    Text("No Selection", color = RobomoriTheme.colors.textSecondary)
                }
            }
        }
        // A stray click must not discard a configured emulator.
        AppDialog(state.showCreate.takeIf { it }, onClose = { state.showCreate = false }, dismissOnClickOutside = false) { CreateDeviceDialog(state) }
        AppDialog(state.deleteDevices.takeIf { it.isNotEmpty() }, onClose = { state.deleteDevices = emptyList() }) { devices ->
            DeleteDevicesDialog(state, devices)
        }
    }
}

/**
 * [first] then [rest], 8dp apart as the app bar spaces its actions, leaving out [first] when both wouldn't fit with
 * [titleRoom] to spare for the title before them, so the actions that remain keep their size.
 */
@Composable
private fun DroppingFirstWhenNarrow(first: @Composable () -> Unit, titleRoom: Dp = 80.dp, rest: @Composable () -> Unit) {
    Layout(contents = listOf(first, rest)) { (firstMeasurables, restMeasurables), constraints ->
        val loose = constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity)
        val restPlaceables = restMeasurables.map { it.measure(loose) }
        val firstPlaceables = firstMeasurables.map { it.measure(loose) }
        val gap = 8.dp.roundToPx()
        val restWidth = restPlaceables.sumOf { it.width }
        val firstWidth = firstPlaceables.sumOf { it.width }
        val showsFirst = firstWidth + gap + restWidth + titleRoom.roundToPx() <= constraints.maxWidth
        val shown = if (showsFirst) firstPlaceables + restPlaceables else restPlaceables
        val width = shown.sumOf { it.width } + gap * (shown.size - 1).coerceAtLeast(0)
        val height = shown.maxOfOrNull { it.height } ?: 0
        layout(width, height) {
            var x = 0
            for (placeable in shown) {
                placeable.place(x, (height - placeable.height) / 2)
                x += placeable.width + gap
            }
        }
    }
}

@Composable private fun DeviceSidebar(state: AppState, createMenuExpanded: Boolean,
                                      onCreateMenuExpandedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val searchField = rememberTextFieldState()
    val query = searchField.text.toString().trim()
    Column(modifier.fillMaxHeight().testTag("DeviceSidebar")) {
        Row(Modifier.fillMaxWidth().padding(end = 8.dp),
            horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.Top) {
            CreateDeviceMenu(state, createMenuExpanded, onCreateMenuExpandedChange)
        }
        TextField(
            state = searchField,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(top = 8.dp, bottom = 4.dp)
                .testTag("DeviceSearch").semantics { contentDescription = "Search devices" },
            placeholder = { Text("Search devices") },
            leadingIcon = { Icon(Icons.MagnifyingGlass, tint = RobomoriTheme.colors.textSecondary, modifier = Modifier.size(18.dp).testTag("DeviceSearchIcon")) },
            trailingIcon = if (searchField.text.isNotEmpty()) {
                { Button(onClick = { searchField.setTextAndPlaceCursorAtEnd("") }, style = ButtonStyle.Ghost, size = ButtonSize.Icon, contentDescription = "Clear search", tag = "ClearDeviceSearch") { Icon(Icons.XMark) } }
            } else null,
        )
        val allDevices: List<DeviceTarget> = state.visibleDevices + state.connected.devices
        fun active(device: DeviceTarget) = when (device) {
            is ConnectedDevice -> state.connected.sharing(device)
            else -> device.name in state.running
        }
        val sidebarDevices = allDevices.filter { it.displayName.contains(query, ignoreCase = true) }.sortedWith(
            compareBy<DeviceTarget> { !active(it) }
                .thenBy { it !is ConnectedDevice }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayName }
                .thenBy { it.name }
        )
        val orderedNames = sidebarDevices.filterIsInstance<Device>().map { it.name }
        LazyColumn(Modifier.weight(1f).onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) false
            else if ((event.isMetaPressed || event.isCtrlPressed) && event.key == Key.A) {
                state.selectAll(orderedNames)
                true
            } else if ((event.isMetaPressed || event.isCtrlPressed) && event.key == Key.Backspace) {
                if (!state.busy && state.selectedDevices.none { it.name in state.starting }) state.openDelete(state.selectedDevices)
                true
            } else if (event.key == Key.DirectionUp || event.key == Key.DirectionDown) {
                val current = sidebarDevices.indexOfFirst { it.name == (state.connected.selected?.name ?: state.previewDevice?.name) }
                val next = (current + if (event.key == Key.DirectionDown) 1 else -1).coerceIn(0, (sidebarDevices.size - 1).coerceAtLeast(0))
                when (val target = sidebarDevices.getOrNull(next)) {
                    is ConnectedDevice -> { state.selected = emptySet(); state.connected.selected = target }
                    is Device -> state.selectDevice(target, extend = event.isShiftPressed, orderedNames = orderedNames)
                }
                true
            } else false
        }, contentPadding=PaddingValues(horizontal=8.dp, vertical=4.dp)) {
            if (sidebarDevices.isEmpty() && query.isNotEmpty()) {
                item {
                    Label("No matching devices", modifier = Modifier.padding(8.dp).testTag("NoDeviceMatches"),
                        color = RobomoriTheme.colors.textSecondary)
                }
            } else if (sidebarDevices.isEmpty()) {
                item {
                    Box(Modifier.fillParentMaxSize().testTag("NoDevices"), contentAlignment = Alignment.Center) {
                        Text("No Devices", color = RobomoriTheme.colors.textSecondary)
                    }
                }
            }
            items(sidebarDevices.size, key={sidebarDevices[it].name}) { index ->
                Box(Modifier.animateItem()) {
                    when (val device = sidebarDevices[index]) {
                        is ConnectedDevice -> ConnectedDeviceRow(device, state)
                        is Device -> DeviceRow(device, state, orderedNames)
                    }
                }
            }
        }
        if (!state.license.licensed) {
            Box(Modifier.fillMaxWidth().padding(12.dp)) {
                GradientButton("Demo version", "Click here to buy a license",
                    onClick = { state.purchaseLicense(BuySource.Toolbar) }, modifier = Modifier.fillMaxWidth(), tag = "BuyLicense")
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(RobomoriTheme.colors.shelfBorder))
        Column(Modifier.fillMaxWidth().padding(12.dp).testTag("SidebarFooter"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val downloading = state.updates.state as? AppUpdateState.Downloading
            if (state.updates.canUpdateNow || state.codeUpdates.readyPatch != null) {
                Button(onClick = state::updateNow, style = ButtonStyle.Primary, modifier = Modifier.fillMaxWidth(), tag = "RestartToUpdate",
                    enabled = !state.updates.working && !state.busy) {
                    Label(if (downloading != null) "Downloading update… ${downloading.percent}%" else "Update Robomori")
                }
            }
            Button(onClick = {
                java.awt.Desktop.getDesktop().mail(java.net.URI("mailto", "$FEEDBACK_EMAIL?subject=$APP_NAME $APP_DISPLAY_VERSION feedback", null))
            }, style = ButtonStyle.Outlined, modifier = Modifier.fillMaxWidth(), tag = "SendFeedback") { Label("Send feedback") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(APP_NAME, style = RobomoriTheme.typography.body, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                    modifier = Modifier.testTag("SidebarAppName"))
                Spacer(Modifier.width(6.dp))
                Text(APP_DISPLAY_VERSION, style = RobomoriTheme.typography.small,
                    color = RobomoriTheme.colors.textSecondary, modifier = Modifier.testTag("SidebarAppVersion"))
                Spacer(Modifier.weight(1f))
                SettingsButton(state)
            }
        }
    }
}

@Composable private fun ConnectedDeviceRow(device: ConnectedDevice, state: AppState) {
    val selected = state.connected.selected?.identity == device.identity
    val subtitle = device.manufacturerAndModel
    ContextMenuArea(items = { connectedDeviceActions(state, device) }) {
        SidebarListItem(selected = selected, contentPadding = PaddingValues(8.dp),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).testTag("Connected-${device.serial}")
                .semantics { contentDescription = "${device.displayName}, $subtitle"; this.selected = selected }
                .onPointerEvent(PointerEventType.Press, pass = PointerEventPass.Initial) { event ->
                    val modifiers = event.keyboardModifiers
                    if (event.buttons.isPrimaryPressed && event.awtEventOrNull?.clickCount == 2 &&
                        !modifiers.isMetaPressed && !modifiers.isCtrlPressed && !modifiers.isShiftPressed) {
                        state.selected = emptySet()
                        state.connected.selected = device
                        state.connected.startSharing(device)
                    }
                }
                .clickable { state.selected = emptySet(); state.connected.selected = device }) {
            DeviceRowContent(device.type, state.connected.sharing(device), device.displayName, subtitle)
        }
    }
}

@Composable private fun DeviceRow(device: Device, state: AppState, orderedNames: List<String>) {
    val selected = device.name in state.selected
    val running = device.name in state.running
    val pending = state.pendingCreation?.name == device.name
    val operation = if (pending) {
        if (state.creating) "Setting up…" else "Setup failed"
    } else if (device.name in state.stopping) "Shutting down…" else state.deviceOperations[device.name]?.label
        ?: if (device.name in state.starting) "Starting…" else null
    val subtitle = operation ?: if (device.missingStorage) "Storage unavailable" else device.platformTitle
    var clickModifiers by remember { mutableStateOf(PointerKeyboardModifiers()) }
    val menuState = remember { ContextMenuState() }
    LaunchedEffect(menuState.status) {
        if (menuState.status is ContextMenuState.Status.Open) state.selectForContextMenu(device)
    }
    ContextMenuArea(state = menuState, items = {
        val targets = if (device.name in state.selected) state.selectedDevices else listOf(device)
        deviceActions(state, device, targets)
    }) {
        SidebarListItem(selected = selected, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).testTag("Device-${device.name}")
                .semantics { contentDescription="${device.displayName}, $subtitle"; this.selected = selected }
                .onPointerEvent(PointerEventType.Press, pass = PointerEventPass.Initial) { event ->
                    if (event.buttons.isPrimaryPressed) {
                        clickModifiers = event.keyboardModifiers
                        // Detect double clicks from the OS click count so single clicks select
                        // immediately instead of waiting out a double-tap timeout.
                        if (event.awtEventOrNull?.clickCount == 2 && !clickModifiers.isMetaPressed &&
                            !clickModifiers.isCtrlPressed && !clickModifiers.isShiftPressed) {
                            state.selectDevice(device)
                            if (!pending && device.name !in state.stopping) state.launch(device)
                        }
                    }
                }
                .clickable(onClick = {
                    state.selectDevice(device, toggle = clickModifiers.isMetaPressed || clickModifiers.isCtrlPressed,
                        extend = clickModifiers.isShiftPressed, orderedNames = orderedNames)
                    clickModifiers = PointerKeyboardModifiers()
                })) {
            DeviceRowContent(device.type, running, device.displayName, subtitle,
                busy = operation != null && (!pending || state.creating))
        }
    }
}

@Composable internal fun DeviceRowContent(type: DeviceType, running: Boolean, name: String, subtitle: String, busy: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(32.dp).background(if (running) RobomoriTheme.colors.runningIconBackground else RobomoriTheme.colors.deviceIconBackground, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            DeviceGlyph(type, running, size = 20.dp)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Label(name, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = RobomoriTheme.colors.textSecondary, style = RobomoriTheme.typography.small,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (busy) {
            Spacer(Modifier.width(10.dp))
            com.robomori.components.Spinner(Modifier.size(16.dp))
        }
    }
}

/**
 * Confirms the running code came up, so codepush keeps it, then downloads newer code updates in the background,
 * only those the license covers, and offers to restart once one is ready.
 * A downloaded update runs from the next launch.
 */
private suspend fun keepCodeUpdated(client: com.composables.codepush.CodePushClient, state: AppState) = withContext(Dispatchers.IO) {
    runCatching { client.markHealthy() }
    // An update downloaded in an earlier session and not yet running.
    state.codeUpdates.refresh()
    delay(30.seconds)
    while (isActive) {
        state.codeUpdates.check()
        delay(6.hours)
    }
}

/** A panel on the right: its button's name and icon, in the order the buttons show. */
private data class PanelPage(val page: InspectorPage, val name: String, val icon: ImageVector)

private val PanelPages = listOf(
    PanelPage(InspectorPage.Controls, "Settings", Icons.AdjustmentsHorizontal),
    PanelPage(InspectorPage.Apps, "Apps", Icons.LayoutGrid),
    PanelPage(InspectorPage.Logcat, "Logcat", Icons.Cat),
    PanelPage(InspectorPage.Files, "Files", Icons.Folder),
    PanelPage(InspectorPage.Databases, "App data", Icons.CircleStack),
    PanelPage(InspectorPage.Network, "Network", Icons.Globe),
)

/** The panels' width, the same for each so switching between them doesn't move the device, and the settings' widest, padding aside. */
private val PanelWidth = 640.dp
private val SettingsMaxWidth = 600.dp

/** How long one panel takes to fade into the next. */
private const val PanelSwitchMillis = 250

/** How long the free trial has left, as the sidebar's Buy button says it. */
