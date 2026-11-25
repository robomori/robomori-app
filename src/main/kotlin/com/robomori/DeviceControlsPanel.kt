package com.robomori

import com.robomori.components.SliderRow
import com.robomori.components.SwitchRow
import com.robomori.components.Button
import com.robomori.components.ButtonStyle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.robomori.components.Card
import com.robomori.components.CardDivider
import com.robomori.components.Label
import com.robomori.components.Icon
import com.robomori.components.DeviceStateMessage
import com.robomori.iconography.Icons
import com.robomori.iconography.AppWindow
import com.robomori.iconography.ArrowRightLeft
import com.robomori.iconography.Turtle
import com.robomori.iconography.Contrast
import com.robomori.iconography.PersonStanding
import com.robomori.iconography.ALargeSmall
import com.robomori.iconography.AdjustmentsHorizontal
import com.robomori.theme.RobomoriTheme
import com.robomori.theme.verticalScroll
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.roundToInt

internal val controlsPanelPadding = 16.dp
private val settingsCardPadding = 12.dp

@Composable
internal fun rememberDeviceControls(state: AppState, visible: Boolean): DeviceControlsState? {
    val serial = reachableSerial(state) ?: return null
    return key(serial, state.settings) {
        val scope = rememberCoroutineScope()
        val controls = remember {
            // Dark mode, font size, language, TalkBack and animations are free, so an agent or a person can test like a user.
            DeviceControlsState(state.deviceSettingsService, serial, scope)
        }
        LaunchedEffect(controls, visible) {
            controls.observe(visible)
        }
        controls
    }
}

/** The serial of the selected device when the app can reach it: a running emulator, or a connected device. */
internal fun reachableSerial(state: AppState): String? {
    val connected = state.connected.selected
    // A wireless device that is not sharing its screen is not connected as far as the user is concerned.
    return connected?.takeIf { it.available && (!it.wireless || state.connected.sharing(it)) }?.serial
        ?: state.previewDevice?.let { state.running[it.name] }
}

@Composable
internal fun DeviceControlsPanel(state: AppState, controls: DeviceControlsState?, visible: Boolean) {
    val device = state.previewDevice
    val connected = state.connected.selected
    val serial = connected?.takeIf { it.available }?.serial ?: device?.let { state.running[it.name] }
    val workspace = state.presets
    val unavailable = connected != null && (!connected.available || (connected.wireless && !state.connected.sharing(connected)))
    val target = if (!unavailable && controls?.booted == true && state.selected.size <= 1) serial?.let(workspace::target) else null
    DisposableEffect(target, visible) {
        if (target != null && visible) workspace.locations.setActive(target, true)
        onDispose { if (target != null) workspace.locations.setActive(target, false) }
    }
    val message = when {
        unavailable -> "Connect this device to customize its behavior and appearance."
        connected != null -> if (controls?.booted != true) "Waiting for your device to be ready…" else null
        state.selected.size > 1 -> "Select one emulator to change its settings."
        device == null -> "Select a device to customize its behavior and appearance."
        serial == null -> "Start this emulator to customize its behavior and appearance."
        device.name in state.stopping -> "Wait for the emulator to shut down."
        controls?.booted != true -> "The emulator is starting. Controls will be available when it is ready."
        else -> null
    }
    if (message != null) {
        Box(Modifier.fillMaxSize().padding(controlsPanelPadding).testTag("DeviceControlsPanel"),
            contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.AdjustmentsHorizontal, tint = RobomoriTheme.colors.textSecondary, modifier = Modifier.size(28.dp))
                DeviceStateMessage(message)
            }
        }
        return
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(controlsPanelPadding).testTag("DeviceControlsPanel"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val type = connected?.type ?: device?.type
        if (controls != null && type != null) {
            RunningDeviceControls(controls, type, state.license.unlocked, state.settings, visible, workspace, target) {
                state.showDemoDialog = true
            }
        }
    }
}

@Composable
private fun RunningDeviceControls(controls: DeviceControlsState, type: DeviceType, licensed: Boolean, appSettings: AppSettings,
                                  visible: Boolean, workspace: DevicePresetWorkspace, target: DevicePresetTarget?,
                                  onLockedClick: () -> Unit) {
    val settings = controls.settings
    SideEffect { if (target != null && settings != null) workspace.settingsSnapshots[target.identity] = settings }
    DevicePresetControls(workspace, target, controls, type)
    Card(contentPadding = PaddingValues(horizontal = settingsCardPadding)) {
        // Wear OS and Android TV are always dark, so they have no theme to switch.
        if (type != DeviceType.WEAR && type != DeviceType.TV) {
            SwitchRow("Dark theme", settings?.darkMode ?: false, "DeviceDarkMode", controls::setDarkMode,
                enabled = settings != null, icon = Icons.Contrast)
            CardDivider()
        }
        SliderRow("Font Size", settings?.fontScale ?: 1f, textSizeOptions,
            label = { if (settings == null) "—" else "${(it * 100).roundToInt()}%" }, tag = "DeviceTextSize",
            onChange = controls::setFontScale, enabled = settings != null, icon = Icons.ALargeSmall)
        CardDivider()
        SwitchRow("TalkBack", settings?.talkBackEnabled ?: false, "DeviceTalkBack", controls::setTalkBack,
            enabled = settings != null && settings.talkBackInstalled, icon = Icons.PersonStanding)
        CardDivider()
        DeviceLocaleControl(appSettings, controls.serial, workspace.settingsRevision,
            presetLocale = target?.let { workspace.pendingLocales[it.identity] }, cache = workspace.localeCache,
            deviceKey = target?.identity ?: controls.serial) { info ->
            if (target != null) {
                workspace.localeInfos[target.identity] = info
                workspace.pendingLocales.remove(target.identity)
            }
        }
    }
    Card(contentPadding = PaddingValues(horizontal = settingsCardPadding)) {
        DeviceLocationControl(appSettings, controls.serial, licensed, onLockedClick, active = visible,
            sharedState = target?.let { workspace.locations.state(it) })
    }
    Card(contentPadding = PaddingValues(horizontal = settingsCardPadding)) {
        SwitchRow("Slow Animations", settings?.animationScale == slowAnimationScale, "DeviceAnimations",
            controls::setSlowAnimations, enabled = settings != null, icon = Icons.Turtle)
    }
    controls.error?.let { message ->
        Label(message, modifier = Modifier.testTag("DeviceSettingsError"), color = RobomoriTheme.colors.danger)
        Button(onClick = {
            controls.retry()
        }, style = ButtonStyle.Secondary, tag = "RetryDeviceSettings") { Label("Retry") }
    }
}

internal sealed interface DeviceControlsSnapshot {
    data object WaitingForBoot : DeviceControlsSnapshot
    data object Loading : DeviceControlsSnapshot
    data class Ready(val settings: DeviceSettings) : DeviceControlsSnapshot
    data class ReadFailed(val cause: Throwable, val settings: DeviceSettings?) : DeviceControlsSnapshot
    data class WriteFailed(val cause: Throwable, val settings: DeviceSettings?) : DeviceControlsSnapshot
}

internal class DeviceControlsState(
    private val service: DeviceSettingsService,
    val serial: String,
    private val scope: CoroutineScope,
    private val canEdit: () -> Boolean = { true },
    private val onLocked: () -> Unit = {},
) {
    var snapshot by mutableStateOf<DeviceControlsSnapshot>(DeviceControlsSnapshot.WaitingForBoot); private set
    val booted get() = snapshot != DeviceControlsSnapshot.WaitingForBoot
    val settings: DeviceSettings? get() = when (val current = snapshot) {
        is DeviceControlsSnapshot.Ready -> current.settings
        is DeviceControlsSnapshot.ReadFailed -> current.settings
        is DeviceControlsSnapshot.WriteFailed -> current.settings
        else -> null
    }
    private val commands = Mutex()
    private var pendingChanges = 0
    private var revision = 0L
    private val latestChanges = mutableMapOf<String, Long>()

    fun retry() { scope.launch { refresh() } }

    suspend fun applyPreset(values: Map<PresetSetting, PresetValue>, write: suspend () -> PresetOperationResult): PresetOperationResult {
        val current = settings ?: error("Wait for device settings to load.")
        revision++
        pendingChanges++
        snapshot = DeviceControlsSnapshot.Ready(current.withPresetValues(values))
        return try { commands.withLock { write() } }
        finally { pendingChanges-- }
    }

    suspend fun observe(visible: Boolean) {
        refresh()
        while (visible && currentCoroutineContext().isActive) {
            delay(3000)
            if (!booted || (snapshot !is DeviceControlsSnapshot.ReadFailed && snapshot !is DeviceControlsSnapshot.WriteFailed)) refresh()
        }
    }

    suspend fun refresh(clearError: Boolean = true) {
        if (pendingChanges > 0) return
        commands.withLock {
            if (pendingChanges > 0) return@withLock
            if (!booted) {
                val ready = try {
                    runInterruptible(Dispatchers.IO) { service.isBooted(serial) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
                if (!ready) return@withLock
                snapshot = DeviceControlsSnapshot.Loading
            }
            val requestedRevision = revision
            try {
                val actual = runInterruptible(Dispatchers.IO) { service.read(serial) }
                if (requestedRevision == revision) {
                    snapshot = when (val current = snapshot) {
                        is DeviceControlsSnapshot.ReadFailed -> if (clearError) DeviceControlsSnapshot.Ready(actual) else current.copy(settings = actual)
                        is DeviceControlsSnapshot.WriteFailed -> if (clearError) DeviceControlsSnapshot.Ready(actual) else current.copy(settings = actual)
                        else -> DeviceControlsSnapshot.Ready(actual)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (requestedRevision == revision) snapshot = DeviceControlsSnapshot.ReadFailed(failure, settings)
            }
        }
    }

    fun setDarkMode(enabled: Boolean) = change(
        key = "darkMode",
        update = { it.copy(darkMode = enabled) },
        write = { service.setDarkMode(serial, enabled) },
    )

    fun setAnimationScale(value: Float) = change(
        key = "animations",
        update = { it.copy(animationScales = AnimationScaleSetting.entries.associateWith { value }) },
        write = { service.setAnimationScale(serial, value) },
    )

    /** Slows every animation right down, or puts them back to normal speed. */
    fun setSlowAnimations(enabled: Boolean) = setAnimationScale(if (enabled) slowAnimationScale else 1f)

    fun setFontScale(value: Float) = change(
        key = "fontScale",
        update = { it.copy(fontScale = value) },
        write = { service.setFontScale(serial, value) },
    )

    fun setTalkBack(enabled: Boolean) = change(
        key = "talkBack",
        update = { it.copy(talkBackEnabled = enabled) },
        write = { service.setTalkBack(serial, enabled) },
    )

    private fun change(key: String, update: (DeviceSettings) -> DeviceSettings, write: () -> Unit) {
        if (!canEdit()) { onLocked(); return }
        val current = settings ?: return
        val next = update(current)
        if (next == current) return
        snapshot = DeviceControlsSnapshot.Ready(next)
        revision++
        val changeRevision = revision
        latestChanges[key] = changeRevision
        pendingChanges++
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                commands.withLock {
                    if (latestChanges[key] == changeRevision) {
                        runInterruptible(Dispatchers.IO) { write() }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (latestChanges[key] == changeRevision) {
                    snapshot = DeviceControlsSnapshot.WriteFailed(failure, settings)
                }
            } finally {
                if (latestChanges[key] == changeRevision) latestChanges.remove(key)
                pendingChanges--
            }
            if (pendingChanges == 0) refresh(clearError = false)
        }
    }
}
