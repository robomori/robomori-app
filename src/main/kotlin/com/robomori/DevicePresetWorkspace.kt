package com.robomori

import androidx.compose.runtime.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/** Saves device settings to files and applies settings loaded from files. */
internal class DevicePresetWorkspace(private val app: AppState) {
    private val directory = DevicePresetDirectory()
    val presetDirectory: File get() = directory.directory
    var presetFiles by mutableStateOf<List<File>>(emptyList()); private set
    val selectedFiles = mutableStateMapOf<String, File>()
    val settingsSnapshots = mutableStateMapOf<String, DeviceSettings>()
    val localeInfos = mutableStateMapOf<String, DeviceLocaleInfo>()
    val localeCache = DeviceLocaleCache()
    val pendingLocales = mutableStateMapOf<String, DeviceLocale>()
    val locations = DevicePresetLocations(app)
    private val access = AndroidDevicePresetAccess({ app.settings }, locations,
        deviceType = { target -> targets().firstOrNull { it.first == target }?.second },
        targetAvailable = { target -> targets().any { it.first == target } },
        knownSettings = { settingsSnapshots[it.identity] }, knownLocales = { localeInfos[it.identity] })
    private val controller = DevicePresetController(access, retainRestorePoints = false) { app.license.unlocked }
    private val unconfirmedTargets = mutableSetOf<String>()
    val busy = mutableStateMapOf<String, Boolean>()
    var settingsRevision by mutableIntStateOf(0); private set

    init {
        refreshFiles()
        app.scope.launch {
            var previous = emptyMap<String, DevicePresetTarget>()
            snapshotFlow { targets().map { it.first }.associateBy { it.identity } }.collectLatest { current ->
                previous.forEach { (identity, old) ->
                    if (current[identity] != old) {
                        controller.markDisconnected(identity)
                        locations.disconnected(identity)
                        settingsSnapshots.remove(identity)
                        localeInfos.remove(identity)
                        pendingLocales.remove(identity)
                        unconfirmedTargets.remove(identity)
                        if (identity.startsWith("emulator:")) controller.discardRestore(identity)
                    }
                }
                current.values.forEach(controller::markConnected)
                previous = current
            }
        }
    }

    fun target(serial: String): DevicePresetTarget? = targets().firstOrNull { it.first.serial == serial }?.first

    fun currentValues(target: DevicePresetTarget?, controls: DeviceControlsState, type: DeviceType): Map<PresetSetting, PresetValue>? {
        val location = target?.let { locations.state(it) }
        return controls.settings?.presetValues(type,
            target?.let { pendingLocales[it.identity] ?: localeInfos[it.identity]?.current },
            location?.takeIf { it.snapshot is LocationControlSnapshot.Ready }?.let { PresetValue.Location(it.selection) })
    }

    private fun targets(): List<Pair<DevicePresetTarget, DeviceType>> = buildList {
        app.visibleDevices.forEach { device -> app.running[device.name]?.let { serial ->
            if (device.name !in app.stopping) add(DevicePresetTarget("emulator:${device.directory.absolutePath}", serial) to device.type)
        } }
        app.connected.devices.filter { it.available }.forEach { device ->
            add(DevicePresetTarget("device:${device.identity}", device.serial) to device.type)
        }
    }

    fun refreshFiles() {
        app.scope.launch {
            try {
                presetFiles = withContext(Dispatchers.IO) { directory.files() }
                selectedFiles.entries.removeAll { it.value !in presetFiles }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                fileFailed(false, failure)
            }
        }
    }

    /** Whether presets may be saved; without a license this opens the purchase prompt instead. */
    fun canSave(): Boolean = app.unlocked()

    suspend fun create(target: DevicePresetTarget, name: String, values: Map<PresetSetting, PresetValue>) {
        if (!canSave()) return
        val preset = DevicePreset(name.trim(), values)
        withContext(NonCancellable) {
            val file = withContext(Dispatchers.IO) { directory.create(preset) }
            selectedFiles[target.identity] = file
            refreshFiles()
        }
    }

    suspend fun update(target: DevicePresetTarget, values: Map<PresetSetting, PresetValue>) {
        if (!canSave()) return
        val file = selectedFiles[target.identity] ?: error("Choose a preset to update.")
        withContext(NonCancellable + Dispatchers.IO) { directory.update(file, values) }
    }

    fun showInFolder() {
        app.scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    java.nio.file.Files.createDirectories(presetDirectory.toPath())
                    val desktop = if (java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop() else null
                    if (desktop != null && desktop.isSupported(java.awt.Desktop.Action.OPEN)) desktop.open(presetDirectory)
                    else revealInFileManager(presetDirectory)
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                app.failureEvents.trySend(FailedOperation.RevealPresets)
            }
        }
    }

    suspend fun loadFromFile(target: DevicePresetTarget, file: File, controls: DeviceControlsState, type: DeviceType) {
        if (!app.license.unlocked) { app.showDemoDialog = true; return }
        if (busy[target.identity] == true) return
        busy[target.identity] = true
        val previous = selectedFiles[target.identity]
        selectedFiles[target.identity] = file
        val preset = try { withContext(Dispatchers.IO) {
            require(file.isFile) { "This preset file is no longer available. Choose another file." }
            val presets = DevicePresetStore(file).load()
            require(presets.size == 1) { "Choose a file containing one device preset." }
            presets.single()
        } } catch (failure: Exception) {
            busy.remove(target.identity)
            if (previous == null) selectedFiles.remove(target.identity) else selectedFiles[target.identity] = previous
            throw failure
        }
        val current = currentValues(target, controls, type).orEmpty()
        val changed = if (target.identity in unconfirmedTargets) preset.values
            else preset.values.filter { (setting, value) -> current[setting] != value }
        if (changed.isNotEmpty()) apply(target, preset.copy(values = changed), file, previous, controls)
        else busy.remove(target.identity)
    }

    fun fileFailed(saving: Boolean, failure: Exception) {
        app.failureEvents.trySend(if (saving) FailedOperation.SavePreset(failure.message) else FailedOperation.LoadPreset(failure.message))
    }

    private fun apply(target: DevicePresetTarget, preset: DevicePreset, file: File, previous: File?, controls: DeviceControlsState) {
        unconfirmedTargets += target.identity
        (preset.values[PresetSetting.Locale] as? PresetValue.Locale)?.let { pendingLocales[target.identity] = DeviceLocale(it.tag) }
        app.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val result = controls.applyPreset(preset.values) { controller.apply(target, preset) }
                if (result.results.any { it.setting == PresetSetting.Locale && it.outcome != PresetSettingOutcome.Applied }) {
                    pendingLocales.remove(target.identity)
                }
                if (result.successful) {
                    unconfirmedTargets.remove(target.identity)
                    selectedFiles[target.identity] = file
                }
                else if (result.results.none { it.outcome == PresetSettingOutcome.Applied }) {
                    if (previous == null) selectedFiles.remove(target.identity) else selectedFiles[target.identity] = previous
                }
                if (result.locked) app.showDemoDialog = true
                else if (!result.successful) {
                    val problem = result.results.firstOrNull {
                        it.outcome is PresetSettingOutcome.Failed || it.outcome is PresetSettingOutcome.Skipped
                    }
                    val reason = when (val outcome = problem?.outcome) {
                        is PresetSettingOutcome.Failed -> outcome.reason
                        is PresetSettingOutcome.Skipped -> outcome.reason
                        else -> null
                    }
                    app.failureEvents.trySend(FailedOperation.ApplyPreset(reason))
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                pendingLocales.remove(target.identity)
                if (previous == null) selectedFiles.remove(target.identity) else selectedFiles[target.identity] = previous
                app.failureEvents.trySend(FailedOperation.ApplyPreset(failure.message))
            } finally {
                busy.remove(target.identity)
                settingsRevision++
                controls.retry()
            }
        }
    }
}
