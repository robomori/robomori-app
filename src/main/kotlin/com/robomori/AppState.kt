package com.robomori


import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.Channel
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val CatalogCheckInterval = 1.hours
private val CameraCheckInterval = 3.seconds
private val CameraFeedbackDelay = 3.seconds

class AppState(val scope: CoroutineScope, private val store: SettingsStore = SettingsStore(),
               initial: AppSettings = store.load(), private val executor: CommandExecutor = ProcessExecutor(),
               val display: DeviceDisplay = AdbDeviceDisplay(),
               /** Where the SDK catalog is kept between launches; none keeps it in memory only. */
               private val catalogCache: SdkCatalogCache? = null,
               private val capture: DeviceCapture = AdbDeviceCapture(),
               val license: AppLicense = AppLicense(scope),
               /** First-launch welcome; none never shows it, as in tests. */
               private val welcome: WelcomeNotice? = null,
               /** Where the funnel's events go; none records nothing, as in tests. */
               val analytics: Analytics = Analytics.None,
               /** Which computer camera and microphone emulators use; in memory only when absent, as in tests. */
               private val mediaStore: EmulatorMediaStore? = null,
               /** The graphics emulators start with; in memory only when absent, as in tests. */
               private val graphicsStore: EmulatorGraphicsStore? = null,
               /** Heard when a screenshot is saved; silent when absent, as in tests. */
               private val shutterSound: ShutterSound = ShutterSound.None,
               /** Where failures are written for Export Logs; none keeps nothing, as in tests. */
               private val logs: AppLogs? = null,
               private val storagePlanner: (CreateSpec, AppSettings) -> StoragePlan = ::planDeviceStorage) {
    var settings by mutableStateOf(initial); private set
    /** The settings file's save time when it was last read, so only changes made after that are picked up. */
    private var settingsSavedAt = store.savedAt()
    internal val presets by lazy { DevicePresetWorkspace(this) }
    /** The `robomori` command, installed from Settings. */
    internal val commandLine by lazy { CommandLineToolState(scope) }
    internal val workspaceLayout by lazy { WorkspaceLayoutState(FileWorkspaceLayoutStore(), scope) }
    internal val agentSkill by lazy { AgentSkillState(scope) }
    internal val localhostPorts by lazy { LocalhostPortsState(scope, { service }) }
    var devices by mutableStateOf(emptyList<Device>()); private set
    var running by mutableStateOf(emptyMap<String, String>()); private set
    var selected by mutableStateOf(setOf<String>())
    var previewName by mutableStateOf<String?>(null)
    /** How big the main window shows the device, as a multiple of the size that fits it; 1 fits it. */
    private var chosenZoom by mutableStateOf(1f)
    /** Zoom applies only to a device showing its screen: a running emulator, or a connected device that's available. */
    val canZoom get() = connected.selected?.available ?: (selected.size <= 1 && previewDevice?.name?.let { it in running } == true)
    /** How far the device preview is zoomed; it fits the space while there's no screen to zoom. */
    val previewZoom get() = if (canZoom) chosenZoom else 1f
    fun zoomIn() { if (canZoom) chosenZoom = zoomedIn(chosenZoom) }
    fun zoomOut() { if (canZoom) chosenZoom = zoomedOut(chosenZoom) }
    fun zoomToFit() { chosenZoom = 1f }
    val connected = ConnectedDevicesState(scope, settings = { settings },
        licensed = { license.unlocked }, onLocked = { showDemoDialog = true }, analytics = analytics)
    var pendingCreation by mutableStateOf<Device?>(null); private set
    val visibleDevices get() = pendingCreation?.let { pending ->
        devices.filterNot { it.name == pending.name || it.displayName == pending.displayName } + pending
    } ?: devices
    val previewDevice get() = if (connected.selected != null) null else visibleDevices.firstOrNull { it.name == previewName } ?: selectedDevices.firstOrNull() ?: visibleDevices.firstOrNull()
    val canFocusDevice get() = connected.selected != null || (previewDevice != null && selected.size <= 1)
    /** The devices open in their own windows, oldest first. Each keeps its window until it's closed or the device goes away. */
    var focusTargets by mutableStateOf<List<DeviceTarget>>(emptyList()); private set
    val focusedDevices: List<DeviceTarget> get() = focusTargets.mapNotNull(::current)
    private fun current(target: DeviceTarget): DeviceTarget? = when (target) {
        is Device -> visibleDevices.firstOrNull { it.directory.absolutePath == target.directory.absolutePath }
        is ConnectedDevice -> connected.devices.firstOrNull { it.identity == target.identity && it.available }
        else -> null
    }
    /** Opens the workspace's device in its own window. Returns the device, also when its window was already open. */
    fun openFocus(): DeviceTarget? {
        if (!canFocusDevice) return null
        return openFocus(connected.selected ?: previewDevice ?: return null)
    }
    fun openFocus(target: DeviceTarget): DeviceTarget? {
        if (!unlocked() || focusDeviceSize(target) == null) return null
        if (focusTargets.none { it.focusKey == target.focusKey }) {
            focusTargets = focusTargets + target
            analytics.pageview(PagePath.Focus)
        }
        return target
    }
    fun closeFocus(target: DeviceTarget) {
        if (focusTargets.none { it.focusKey == target.focusKey }) return
        focusTargets = focusTargets.filterNot { it.focusKey == target.focusKey }
        if (focusTargets.isEmpty()) analytics.pageview(PagePath.Devices)
    }
    fun closeAllFocus() = focusTargets.forEach(::closeFocus)
    val frames = mutableStateMapOf<String, androidx.compose.ui.graphics.ImageBitmap>()
    val frameRotations = mutableStateMapOf<String, Int>()
    /** Whether each running foldable was last seen folded, to show the right screen before its stream starts. */
    val foldedDevices = mutableStateMapOf<String, Boolean>()
    val displayErrors = mutableStateMapOf<String, DisplayFailure>()
    val recordingStates = mutableStateMapOf<String, RecordingState>()
    private val screenshots = mutableStateMapOf<String, OperationState>()
    val recordingDevices get() = recordingStates.mapValues { true }
    val recordingStartedAt get() = recordingStates.mapValues { (_, state) ->
        when (state) {
            is RecordingState.Recording -> state.startedAt
            is RecordingState.Saving -> state.startedAt
        }
    }
    val savingCaptures get() = (screenshots.filterValues { it.isRunning }.keys +
        recordingStates.filterValues { it is RecordingState.Saving }.keys).associateWith { true }
    private val recordings = mutableMapOf<String, CompletableDeferred<Recording>>()
    var starting by mutableStateOf(setOf<String>()); private set
    var stopping by mutableStateOf(setOf<String>()); private set
    val deviceOperations = mutableStateMapOf<String, EmulatorOperation>()
    private var selectionAnchor: String? = null
    fun selectPreview(device: Device) = selectDevice(device)
    fun selectDevice(device: Device, toggle: Boolean = false, extend: Boolean = false,
                     orderedNames: List<String> = devices.map { it.name }) {
        val anchor = orderedNames.indexOf(selectionAnchor)
        val target = orderedNames.indexOf(device.name)
        val nextSelection = when {
            extend && anchor >= 0 && target >= 0 -> {
                val range = orderedNames.subList(minOf(anchor, target), maxOf(anchor, target) + 1).toSet()
                if (toggle) selected + range else range
            }
            toggle -> if (device.name in selected) selected - device.name else selected + device.name
            else -> setOf(device.name)
        }
        if (nextSelection.size > 1 && !unlocked()) return
        connected.selected = null
        selected = nextSelection
        if (!extend || anchor < 0) selectionAnchor = device.name
        previewName = if (device.name in selected) device.name else orderedNames.firstOrNull { it in selected }
    }
    fun selectForContextMenu(device: Device) {
        if (device.name !in selected) selectDevice(device)
    }
    fun selectAll(orderedNames: List<String>) {
        val names = orderedNames.toSet()
        if (names.size > 1 && !unlocked()) return
        selected = names
        connected.selected = null
    }
    private fun allowBulk(devices: List<Device>) = devices.distinctBy { it.name }.size <= 1 || unlocked()
    fun openDelete(devices: List<Device>) {
        if (allowBulk(devices)) deleteDevices = devices
    }
    suspend fun observeDisplays() = coroutineScope {
        val streams = mutableMapOf<Triple<AppSettings, String, String>, Job>()
        snapshotFlow {
            (listOfNotNull(previewDevice) + focusedDevices.filterIsInstance<Device>())
                .distinctBy { it.directory.absolutePath }
                .mapNotNull { device ->
                    running[device.name]?.takeUnless { device.name in stopping }?.let { serial ->
                        Triple(settings, device.directory.absolutePath, serial) to device
                    }
                }.toMap()
        }.collect { requested ->
            for (key in streams.keys.toList()) {
                if (key !in requested) streams.remove(key)?.cancelAndJoin()
            }
            requested.forEach { (key, device) ->
                if (key !in streams) streams[key] = launch {
                    val (currentSettings, frameKey, serial) = key
                    frames.remove(frameKey)
                    displayErrors.remove(device.name)
                    try {
                        while (currentCoroutineContext().isActive) {
                            try {
                                display.stream(currentSettings, device, serial).collect { frame ->
                                    frames[frameKey] = frame.bitmap
                                    frameRotations[frameKey] = frame.rotation
                                    displayErrors.remove(device.name)
                                }
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                if (device.name !in stopping) displayErrors[device.name] = DisplayFailure.Connection(e)
                            } finally {
                                frames.remove(frameKey)
                            }
                            delay(1000)
                        }
                    } finally {
                        frames.remove(frameKey)
                        displayErrors.remove(device.name)
                    }
                }
            }
        }
    }
    private val inputMutex = Mutex()
    fun toggleSoftwareKeyboard() {
        if (connected.selected?.available != true && previewDevice?.let { running[it.name] } == null) return
        val backend = deviceSettingsService
        connected.selected?.takeIf { it.available }?.let { phone ->
            val keyboard = connected.softwareKeyboard(phone) ?: return
            scope.launch {
                try {
                    inputMutex.withLock { withContext(Dispatchers.IO) { keyboard.toggle() } }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                }
            }
            return
        }
        val serial = previewDevice?.let { running[it.name] } ?: return
        scope.launch {
            // Like other input, a failed toggle shows no error over the device's screen.
            try { inputMutex.withLock { withContext(Dispatchers.IO) { backend.toggleSoftwareKeyboard(serial) } } }
            catch (e: Exception) { if (e is CancellationException) throw e }
        }
    }

    val rotations = mutableStateMapOf<String, EmulatorRotation>()
    private val rotationJobs = mutableMapOf<String, Job>()
    private val rotationBases = mutableMapOf<String, Int>()
    private val appliedRotations = mutableMapOf<String, EmulatorRotation>()
    fun rotateCurrent(clockwise: Boolean): Boolean {
        val device = previewDevice ?: return false
        return rotate(device, clockwise)
    }
    fun rotate(device: Device, clockwise: Boolean): Boolean {
        if (!device.type.supportsRotation) return false
        val serial = running[device.name] ?: return false
        val previous = rotations[device.name]?.takeIf { it.serial == serial }
        if (previous == null) rotationJobs.remove(device.name)?.cancel()
        // Publish before doing any IO so every press immediately retargets the preview.
        rotations[device.name] = EmulatorRotation(
            turns = (previous?.turns ?: 0) + if (clockwise) 1 else -1,
            initialRotation = previous?.initialRotation ?: frameRotations[device.directory.absolutePath],
            serial = serial,
        )
        if (rotationJobs[device.name]?.isActive == true) return true
        val backend = service
        rotationJobs[device.name] = scope.launch {
            try {
                val base = rotationBases[serial] ?: (
                    rotations[device.name]?.initialRotation ?: withContext(Dispatchers.IO) {
                        backend.displayRotation(serial)
                    }
                ).also { rotationBases[serial] = it }
                while (running[device.name] == serial) {
                    val latest = rotations[device.name]?.takeIf { it.serial == serial } ?: break
                    val applied = appliedRotations[device.name]?.takeIf { it.serial == serial }
                    if (latest.turns == (applied?.turns ?: 0)) break
                    val target = Math.floorMod(base - latest.turns, 4)
                    withContext(Dispatchers.IO) {
                        display.rotate(serial, target)
                    }
                    appliedRotations[device.name] = latest
                    // Re-read the latest target instead of queuing intermediate keypresses.
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (rotations[device.name]?.serial == serial) {
                    val applied = appliedRotations[device.name]?.takeIf { it.serial == serial }
                    if (applied == null) rotations.remove(device.name) else rotations[device.name] = applied
                }
            }
        }
        return true
    }
    /** Turns a physical device's screen a quarter turn clockwise, fixing its rotation as it goes. */
    fun rotate(device: ConnectedDevice) {
        if (!device.available || !device.type.supportsRotation) return
        val current = settings
        scope.launch {
            // Like the other toolbar buttons, a failed press is retried by pressing again rather than reported.
            try { withContext(Dispatchers.IO) { AndroidService(current).rotateScreen(device.serial, clockwise = true) } }
            catch (e: Exception) { if (e is CancellationException) throw e }
        }
    }
    fun sendTouch(device: Device, x: Int, y: Int, pressed: Boolean) {
        val serial = running[device.name] ?: return
        scope.launch {
            try { inputMutex.withLock { withContext(Dispatchers.IO) { display.touch(serial, x, y, pressed) } } }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                displayErrors[device.name] = DisplayFailure.Touch(e)
            }
        }
    }
    fun fold(device: Device, folded: Boolean) {
        val serial = running[device.name] ?: return
        val current = settings
        scope.launch {
            // Input fails quietly: it happens too often to report each failure.
            try { inputMutex.withLock { withContext(Dispatchers.IO) { display.fold(current, serial, folded) } } }
            catch (e: Exception) { if (e is CancellationException) throw e }
        }
    }
    fun sendInput(device: Device, arguments: List<String>) {
        val serial = running[device.name] ?: return
        val current = settings
        scope.launch {
            try { inputMutex.withLock { withContext(Dispatchers.IO) { display.input(current, serial, arguments) } } }
            catch (e: Exception) { if (e is CancellationException) throw e }
        }
    }
    fun captureScreenshot(device: DeviceTarget) {
        val serial = if (device is ConnectedDevice) device.serial.takeIf { device.available } else running[device.name]
        if (serial == null) return
        // One at a time: each changes the device's status bar and animation speed, and restores them afterwards.
        if (savingCaptures[device.name] == true || screenshots[device.name] == OperationState.Running) return
        screenshots[device.name] = OperationState.Running
        dismissCaptureNotice(device.name)
        // The click answers at once; saving the file finishes a moment later.
        screenshotsTaken[device.name] = (screenshotsTaken[device.name] ?: 0) + 1
        shutterSound.play()
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) { capture.screenshot(settings, serial, device.displayName, device.type,
                    clean = license.unlocked) }
                showCapture(device.name, CaptureNotice.Saved(CaptureKind.Screenshot, file))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                showCapture(device.name, CaptureNotice.Failed(CaptureStep.Screenshot, e))
            } finally { screenshots.remove(device.name) }
        }
    }
    /**
     * Whether a feature may be used: with a license, or during the free trial. After it, this opens the purchase prompt
     * instead, though the whole app is locked then anyway.
     */
    fun unlocked(): Boolean {
        if (license.unlocked) return true
        showDemoDialog = true
        return false
    }
    /** Pairing a phone over Wi-Fi is free, as in Android Studio. */
    fun pairDeviceWirelessly() = connected.openPairing()
    fun openRename(device: Device) { renameDevice = device }
    fun openWipeData(device: Device) { wipeDataDevice = device }
    /**
     * Starts or stops recording [device]. The button turns into a running stop button the moment it's clicked, while
     * the recorder starts behind it; a stop clicked before the recorder is ready stops it as soon as it is.
     */
    fun toggleRecording(device: DeviceTarget) {
        val existing = recordings[device.name]
        if (existing != null) {
            val current = recordingStates[device.name] as? RecordingState.Recording ?: return
            recordingStates[device.name] = RecordingState.Saving(current.startedAt)
            scope.launch {
                // A recorder that failed to start reports that itself.
                val recording = runCatching { existing.await() }.getOrNull() ?: return@launch
                try { withContext(Dispatchers.IO) { recording.stop() } }
                catch (e: Exception) {
                    if (e is CancellationException) throw e
                    showCapture(device.name, CaptureNotice.Failed(CaptureStep.StopRecording, e))
                    if (recordings[device.name] === existing) recordingStates[device.name] = current
                }
            }
            return
        }
        val serial = (if (device is ConnectedDevice) device.serial.takeIf { device.available } else running[device.name]) ?: return
        if (savingCaptures[device.name] == true) return
        val started = CompletableDeferred<Recording>()
        recordings[device.name] = started
        recordingStates[device.name] = RecordingState.Recording(System.nanoTime())
        dismissCaptureNotice(device.name)
        val mirror = (device as? ConnectedDevice)?.let { connected.sharingSession(it)?.mirror }
        scope.launch {
            val recording = try {
                withContext(Dispatchers.IO) { capture.startRecording(settings, serial, device.displayName, device.type, mirror,
                    clean = license.unlocked) }
                    .also(started::complete)
            } catch (e: Exception) {
                started.completeExceptionally(e)
                recordings.remove(device.name)
                recordingStates.remove(device.name)
                if (e is CancellationException) throw e
                showCapture(device.name, CaptureNotice.Failed(CaptureStep.StartRecording, e))
                return@launch
            }
            try {
                val file = withContext(Dispatchers.IO) { recording.save() }
                showCapture(device.name, CaptureNotice.Saved(CaptureKind.Recording, file))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                showCapture(device.name, CaptureNotice.Failed(CaptureStep.SaveRecording, e))
            } finally {
                recordings.remove(device.name)
                recordingStates.remove(device.name)
            }
        }
    }
    fun openCapture(device: DeviceTarget, file: File) {
        dismissCaptureNotice(device.name)
        scope.launch {
            try { withContext(Dispatchers.IO) { revealInFileManager(file) } }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                showCapture(device.name, CaptureNotice.Failed(CaptureStep.OpenLocation, e, file))
            }
        }
    }
    fun type(device: Device, text: String) {
        val serial = running[device.name] ?: return
        scope.launch {
            try { inputMutex.withLock { withContext(Dispatchers.IO) { display.type(serial, text) } } }
            catch (e: Exception) { if (e is CancellationException) throw e }
        }
    }
    fun paste(device: Device) {
        val serial = running[device.name] ?: return
        val current = settings
        scope.launch {
            try {
                inputMutex.withLock { withContext(Dispatchers.IO) {
                    val text = Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as String
                    if (text.isNotEmpty()) display.paste(current, serial, text)
                } }
            } catch (e: Exception) { if (e is CancellationException) throw e }
        }
    }
    var images by mutableStateOf(emptyList<SystemImage>()); private set
    var creationImages by mutableStateOf(emptyList<SystemImage>()); private set
    var creationImagesState by mutableStateOf<OperationState>(OperationState.Idle); private set
    val creationImagesLoading get() = creationImagesState.isRunning
    private var workspaceOperation by mutableStateOf<OperationState>(OperationState.Idle)
    val busy get() = workspaceOperation.isRunning || creating
    var catalogState by mutableStateOf<OperationState>(OperationState.Idle); private set
    val catalogLoading get() = catalogState.isRunning
    var showSettings by mutableStateOf(false); private set
    var settingsSection by mutableStateOf<SettingsSection>(SettingsSection.Images); private set
    fun openSettings(section: SettingsSection = settingsSection) {
        // Outcomes from an earlier visit to Settings no longer apply.
        license.clearOutcome()
        updates.clearOutcome()
        settingsSection = section
        showSettings = true
        analytics.pageview(PagePath.of(section))
        if (section == SettingsSection.Images) {
            clearImageDeleteFailures()
            loadCatalog()
        }
    }
    fun closeSettings() {
        showSettings = false
        analytics.pageview(PagePath.Devices)
    }
    val downloads = AndroidDownloads(scope)
    /** Images being uninstalled. Only their own rows are blocked; SDK changes queue on [AndroidDownloads.sdkLock]. */
    val imageDeletions = mutableStateMapOf<String, OperationState>()
    val deletingImagePaths get() = imageDeletions.filterValues { it.isRunning }.keys
    private fun clearImageDeleteFailures() {
        imageDeletions.keys.filter { imageDeletions[it] is OperationState.Failed }.forEach(imageDeletions::remove)
    }
    fun deleteImage(image: SystemImage) {
        if (!image.installed || image.path in deletingImagePaths) return
        val backend = service
        imageDeletions[image.path] = OperationState.Running
        scope.launch {
            try {
                downloads.sdkLock.withLock { withContext(Dispatchers.IO) { backend.deleteImage(image) } }
                if (settings == backend.settings) {
                    images = images.map { if (it.path == image.path) it.copy(installed = false) else it }
                    downloads.tasks.remove(image.path)
                    imageDeletions[image.path] = OperationState.Succeeded
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (settings == backend.settings) imageDeletions[image.path] = OperationState.Failed(e)
            } finally {
                if (settings == backend.settings && imageDeletions[image.path]?.isRunning == true) imageDeletions.remove(image.path)
            }
        }
    }
    fun downloadImage(image: SystemImage) {
        val backend = service
        downloads.start(image, backend) {
            if (settings == backend.settings) images = images.map { if (it.path == image.path) it.copy(installed = true) else it }
        }
    }
    val hasOpenDialog get() = showSettings || showCreate || renameDevice != null ||
        deleteDevices.isNotEmpty() || wipeDataDevice != null || pendingMedia != null || showDemoDialog || showWelcome || connected.showConnect ||
        connected.renameTarget != null || connected.unpairTarget != null
    var showCreate by mutableStateOf(false)
    var createType by mutableStateOf(DeviceType.PHONE); private set
    var renameDevice by mutableStateOf<Device?>(null)
    var deleteDevices by mutableStateOf(emptyList<Device>())
    var wipeDataDevice by mutableStateOf<Device?>(null)
    var showDemoDialog by mutableStateOf(false)
    var showWelcome by mutableStateOf(welcome?.pending == true); private set

    init {
        if (!showWelcome) analytics.pageview(PagePath.Devices)
    }

    /** The onboarding was opened from Debug settings, so it shows every page a new user sees, the license offer too. */
    var onboardingAsNewUser by mutableStateOf(false); private set

    /** Debug settings: runs the onboarding again, as on a first launch. */
    fun showOnboardingForDevelopment() { onboardingAsNewUser = true; showWelcome = true }

    fun dismissWelcome() {
        showWelcome = false
        onboardingAsNewUser = false
        welcome?.dismiss()
        analytics.pageview(PagePath.Devices)
    }
    var creation by mutableStateOf<CreationState>(CreationState.Idle); private set
    val creating get() = creation == CreationState.SettingUp || creation == CreationState.Finalizing || creation == CreationState.Cancelling
    val createCancelling get() = creation == CreationState.Cancelling
    var creationSpec by mutableStateOf<CreateSpec?>(null); private set
    private var cancellation = AtomicBoolean()
    private var catalogJob: Job? = null
    private var catalogRevision = 0L
    private var creationImagesRevision = 0L
    val service get() = AndroidService(settings, executor)
    val deviceSettingsService get() = DeviceSettingsService(settings, executor)
    internal val installedAppsService get() = InstalledAppsService(settings, executor)
    private val apkInstalls = mutableMapOf<String, ApkInstallState>()
    /** The APKs dropped on [serial]'s screen, kept while the app runs so switching devices doesn't stop an install. */
    internal fun apkInstall(serial: String) = apkInstalls.getOrPut(serial) { ApkInstallState(installedAppsService, serial, scope) }
    internal val deviceFilesService get() = DeviceFilesService(AdbFileTransport(settings, executor))
    internal val appDatabasesService get() = AppDatabasesService(deviceFilesService)
    internal val appPreferencesService get() = AppPreferencesService(deviceFilesService)
    internal val liveAppData: LiveAppData get() = AgentLiveAppData(AdbFileTransport(settings, executor))
    private val networkCertificates by lazy { NetworkCertificateAuthority(HostPlatform().configDirectory) }
    private val networkInspectors = mutableMapOf<String, NetworkInspectorState>()
    private val filesPlacesStore = FilesPlacesStore()
    /** Where the Files page was on each device, by its name, kept on disk so it picks up there after a restart or quit. */
    internal val filesPlaces: MutableMap<String, FilesPlace> by lazy { filesPlacesStore.load().toMutableMap() }
    private var savingFilesPlaces: Job? = null

    /** Keeps [place] as [device]'s, saving it a moment later together with any other changes in the meantime. */
    internal fun rememberFilesPlace(device: String, place: FilesPlace) {
        if (filesPlaces[device] == place) return
        filesPlaces[device] = place
        savingFilesPlaces?.cancel()
        savingFilesPlaces = scope.launch {
            delay(500)
            val places = filesPlaces.toMap()
            withContext(Dispatchers.IO) { runCatching { filesPlacesStore.save(places) } }
        }
    }
    private val networkSessions by lazy { NetworkSessionStore() }
    /** The rules that change apps' responses on the Network page, the same for every device. */
    internal val networkRules by lazy { NetworkRulesState(FileNetworkRulesStore(), scope, watchMillis = 1000) }

    /**
     * The network inspector of the running emulator [serial], kept while it runs so inspecting goes on in the
     * background. Its requests are kept between the emulator's runs, when its AVD is known.
     */
    internal fun networkInspector(serial: String): NetworkInspectorState = networkInspectors.getOrPut(serial) {
        val avd = running.entries.firstOrNull { it.value == serial }?.key
        val device = devices.firstOrNull { it.name == avd }
        NetworkInspectorState(NetworkInspectorService(settings, executor), { networkCertificates }, serial, scope,
            rules = { networkRules.active }, avd = avd, sessions = avd?.let { networkSessions },
            canCreateGoogleApisCopy = device != null && googleApisCopy(device, devices.flatMap { listOf(it.name, it.displayName) }) != null,
            listApps = { installedAppsService.appsWithData(it, everyApp = true) })
    }

    /**
     * Creates an emulator like [avd] with Google APIs in place of Google Play, whose image can be rooted, so the
     * Network page reads every app's traffic on it.
     */
    internal fun createGoogleApisCopy(avd: String) {
        val device = devices.firstOrNull { it.name == avd } ?: return
        googleApisCopy(device, devices.flatMap { listOf(it.name, it.displayName) })?.let(::create)
    }

    /**
     * Takes off the device [serial] what Robomori put on it, such as its helper app and the certificate the Network
     * page installs, after it stops recording there.
     */
    internal fun removeRobomoriFromDevice(serial: String, name: String) {
        networkInspectors[serial]?.stop()
        val cleanup = DeviceCleanupService(settings, executor)
        scope.launch {
            try { withContext(Dispatchers.IO) { cleanup.clean(serial) } }
            catch (e: Exception) { if (e is CancellationException) throw e; notifyFailure(FailedOperation.RemoveRobomori(listOf(name))) }
        }
    }
    val ready get() = service.toolchain.hasTools("emulator", "adb", "avdmanager")
    val selectedDevices get() = devices.filter { it.name in selected }
    val totalBytes get() = devices.sumOf(Device::bytes)

    /** An emulator inspected by a version of Robomori from before its VPN may still send its traffic to a proxy that's gone. */
    private suspend fun clearStaleNetworkRoute(backend: AndroidService, serial: String) {
        withTimeoutOrNull(120_000) {
            while (!withContext(Dispatchers.IO) { runCatching { backend.bootCompleted(serial) }.getOrDefault(false) }) delay(2000)
        } ?: return
        networkInspector(serial).clearStaleRoute()
    }

    suspend fun refresh() {
        val backend = service
        val list = withContext(Dispatchers.IO) { backend.devices() }
        if (settings == backend.settings) {
            if (devices != list) clearImageDeleteFailures()
            devices = list
            selected = selected.intersect(visibleDevices.map { it.name }.toSet())
        }
        val active = withContext(Dispatchers.IO) { runCatching { backend.running() }.getOrDefault(emptyMap()) }
        if (settings == backend.settings) {
            running.filter { (name, serial) -> active[name] != serial }.forEach { (name, serial) ->
                networkInspectors.remove(serial)?.stop()
                rotationJobs.remove(name)?.cancel()
                rotations.remove(name)
                appliedRotations.remove(name)
                rotationBases.remove(serial)
                devices.firstOrNull { it.name == name }?.let {
                    frames.remove(it.directory.absolutePath)
                    frameRotations.remove(it.directory.absolutePath)
                    foldedDevices.remove(it.directory.absolutePath)
                }
                displayErrors.remove(name)
            }
            running = active
            stopping = stopping.intersect(active.keys)
        }
    }
    fun refreshAsync() = scope.launch {
        try { refresh() }
        catch (e: Exception) { if (e is CancellationException) throw e; notifyFailure(FailedOperation.RefreshDevices) }
    }
    /** Operations the user started that did not complete, each delivered once to whoever shows them. */
    internal val failureEvents = Channel<FailedOperation>(Channel.UNLIMITED)
    val failures: Flow<FailedOperation> = failureEvents.receiveAsFlow()
    private fun notifyFailure(operation: FailedOperation) { failureEvents.trySend(operation) }

    /** How many screenshots each device has taken since launch; each new one flashes its screen. */
    val screenshotsTaken = mutableStateMapOf<String, Int>()
    /** The latest capture result for each device, shown above its toolbar until it is dismissed or replaced. */
    val captureNotices = mutableStateMapOf<String, CaptureNotice>()
    private fun showCapture(device: String, notice: CaptureNotice) { captureNotices[device] = notice }
    fun dismissCaptureNotice(device: String) { captureNotices.remove(device) }
    /** Dismisses [notice] only if it is still the one showing, so an older timer cannot hide a newer result. */
    fun dismissCaptureNotice(device: String, notice: CaptureNotice) { captureNotices.remove(device, notice) }
    /**
     * Runs a device operation. On failure, [failed] turns the display names it failed for into a notice;
     * operations that show their own error, such as rename in its dialog, pass none.
     */
    private fun action(onResult: (Throwable?) -> Unit = {}, operations: Map<String, EmulatorOperation> = emptyMap(),
                       blocksWorkspace: Boolean = true, failed: ((List<String>) -> FailedOperation)? = null,
                       block: (AndroidService) -> Unit) {
        if (busy || operations.keys.any { it in deviceOperations }) return
        val backend = service
        val titles = operations.keys.map { name -> devices.firstOrNull { it.name == name }?.displayName ?: name }
        deviceOperations.putAll(operations)
        if (blocksWorkspace) workspaceOperation = OperationState.Running
        scope.launch {
            try { withContext(Dispatchers.IO) { block(backend) }; refresh(); onResult(null) }
            catch (t: Exception) {
                if (t is CancellationException) throw t
                runCatching { refresh() }
                failed?.let { notifyFailure(it((t as? DevicesFailed)?.devices ?: titles)) }
                onResult(t)
            }
            finally {
                operations.keys.forEach(deviceOperations::remove)
                if (blocksWorkspace) workspaceOperation = OperationState.Idle
            }
        }
    }
    fun launch(device: Device, wipeData: Boolean = false) {
        if (busy || device.name in running || device.name in starting || device.name in deviceOperations) return
        selectPreview(device)
        launch(listOf(device), wipeData = wipeData)
    }
    fun launch(devices: List<Device>, coldBoot: Boolean = false, wipeData: Boolean = false) {
        if (busy) return
        if (!allowBulk(devices)) return
        val targets = devices.distinctBy { it.name }.filter {
            it.name !in running && it.name !in starting && it.name !in deviceOperations && !it.missingStorage
        }
        if (targets.isEmpty()) return
        val names = targets.map { it.name }.toSet()
        // Without a license, one emulator runs at a time.
        if ((running.keys + starting).any { it !in names } && !unlocked()) return
        starting = starting + names
        workspaceOperation = OperationState.Running
        val backend = service
        scope.launch {
            val failedToStart = mutableListOf<String>()
            val failedSetup = mutableListOf<String>()
            val launched = mutableSetOf<String>()
            try {
                for (device in targets) {
                    try {
                        withContext(Dispatchers.IO) { backend.launch(device, coldBoot, wipeData, media, graphics) }
                        launched += device.name
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        logs?.record("Could not launch ${device.name}", e)
                        failedToStart += device.displayName
                        starting = starting - device.name
                    }
                }
            } catch (cancelled: CancellationException) {
                starting = starting - names
                throw cancelled
            } finally { workspaceOperation = OperationState.Idle }
            try {
                val started = withTimeoutOrNull(90_000) {
                    while (!running.keys.containsAll(launched)) { refresh(); delay(1000) }
                    true
                } ?: false
                if (!started) {
                    val missing = targets.filter { it.name in launched && it.name !in running }
                    logs?.record("${missing.joinToString { it.name }} did not connect to adb within 90 seconds. Connected: ${running.keys.joinToString().ifEmpty { "none" }}")
                    failedToStart += missing.map { it.displayName }
                }
                targets.filter { it.name in launched && it.name in running }.forEach { analytics.track(FunnelEvent.DeviceStarted, emulatorMetadata(it)) }
                targets.filter { it.name in launched }.mapNotNull { running[it.name] }.forEach { serial ->
                    scope.launch { clearStaleNetworkRoute(backend, serial) }
                }
                if (started) targets.filter { it.name in launched && it.needsInitialSetup }.forEach { device ->
                    val serial = running[device.name] ?: return@forEach
                    val booted = withTimeoutOrNull(90_000) {
                        while (!withContext(Dispatchers.IO) { runCatching { backend.bootCompleted(serial) }.getOrDefault(false) }) delay(1000)
                        true
                    } ?: false
                    if (!booted) failedSetup += device.displayName
                    else try { withContext(Dispatchers.IO) { backend.configureInitialSettings(device, serial) } }
                    catch (e: Exception) {
                        if (e is CancellationException) throw e
                        failedSetup += device.displayName
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logs?.record("Could not follow the start of ${names.joinToString()}", e)
                failedToStart += targets.filter { it.name !in running }.map { it.displayName }
            } finally { starting = starting - names }
            // A failed start matters more than initial setup, and only one notice shows at a time.
            if (failedToStart.isNotEmpty()) notifyFailure(FailedOperation.Start(failedToStart.distinct()))
            else if (failedSetup.isNotEmpty()) notifyFailure(FailedOperation.InitialSetup(failedSetup))
        }
    }
    /** Export Logs' progress, shown on the Updates page. */
    var logsExport by mutableStateOf<OperationState>(OperationState.Idle); private set
    /** Zips the logs, with diagnostics about the SDK and this computer, into [target] for a support request. */
    fun exportLogs(target: File) {
        val logs = logs ?: return
        if (logsExport.isRunning) return
        logsExport = OperationState.Running
        val backend = service
        scope.launch {
            logsExport = try {
                withContext(Dispatchers.IO) {
                    val diagnostics = runCatching { backend.diagnostics() }.getOrElse { "Could not read diagnostics: ${it.stackTraceToString()}" }
                    logs.export(target, diagnostics)
                }
                OperationState.Succeeded
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                OperationState.Failed(e)
            }
        }
    }
    private fun forEachDevice(devices: List<Device>, block: (Device) -> Unit) {
        val failures = mutableListOf<String>()
        devices.distinctBy { it.name }.forEach { device ->
            try { block(device) }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                failures += device.displayName
            }
        }
        if (failures.isNotEmpty()) throw DevicesFailed(failures)
    }
    fun stop(device: Device) = stop(listOf(device))
    fun stop(devices: List<Device>) {
        if (!allowBulk(devices)) return
        val targets = devices.filter { it.name in running && it.name !in starting && it.name !in stopping }
        stopRunning(targets.associate { it.name to running.getValue(it.name) })
    }
    private fun stopRunning(serials: Map<String, String>) {
        if (busy || serials.isEmpty() || serials.keys.any { it in deviceOperations }) return
        if (serials.size > 1 && !unlocked()) return
        val titles = devices.associate { it.name to it.displayName }
        stopping = stopping + serials.keys
        serials.keys.forEach(displayErrors::remove)
        action(onResult = { failure ->
            if (failure != null) stopping = stopping - serials.keys
        }, operations = serials.mapValues { EmulatorOperation.Stopping }, blocksWorkspace = false,
            failed = FailedOperation::Stop) { backend ->
            val failures = mutableListOf<String>()
            serials.forEach { (name, serial) ->
                try { backend.stop(serial) }
                catch (e: Exception) {
                    if (e is CancellationException) throw e
                    failures += titles[name] ?: name
                }
            }
            if (failures.isNotEmpty()) throw DevicesFailed(failures)
        }
    }
    fun restart(device: Device) = restart(listOf(device))
    fun restart(devices: List<Device>) {
        val targets = devices.filter { it.name in running && it.name !in starting }
        if (targets.isEmpty() || !unlocked()) return
        val serials = running.toMap()
        action(operations = targets.associate { it.name to EmulatorOperation.Restarting }, failed = FailedOperation::Restart) { backend ->
            forEachDevice(targets) { device -> serials[device.name]?.let(backend::restart) }
        }
    }
    fun stopAll() = stopRunning(running.filterKeys { it !in stopping })

    /** The emulator whose camera just opened, to ask about in its own panel. */
    var cameraFeedbackDevice by mutableStateOf<String?>(null); private set
    /**
     * Picks up emulators created, deleted, started or stopped outside the app, such as from the command line. It watches
     * the emulator folder and the files running emulators announce themselves with, which are cheap to list.
     */
    fun watchOutsideChanges() = scope.launch {
        var seen: Set<String>? = null
        while (isActive) {
            val current = withContext(Dispatchers.IO) { emulatorFiles(service.avdRoot) }
            if (seen != null && current != seen) {
                refresh()
                // A new emulator announces itself before adb can reach it, and adb lists a stopped one a little longer.
                val announced = runningEmulators(current)
                withTimeoutOrNull(60_000) { while (running.size != announced) { delay(OutsideChangeInterval); refresh() } }
            }
            seen = current
            delay(OutsideChangeInterval)
        }
    }
    /** Devices to bring to the front, from the command line. Null brings the main window to the front. */
    val shownWindows = kotlinx.coroutines.flow.MutableSharedFlow<DeviceTarget?>(extraBufferCapacity = 16)
    /**
     * Shows the emulators the command line asks for, each in its own window. Without a license, the request selects the
     * emulator in the main window instead.
     */
    internal fun watchWindowRequests(requests: AppWindowRequests) = scope.launch {
        while (isActive) {
            val names = withContext(Dispatchers.IO) { requests.take() }
            if (reloadSavedSettings() || (names.isNotEmpty() && names.any { name -> devices.none { it.name == name } })) refresh()
            names.mapNotNull { name -> devices.firstOrNull { it.name == name } }.forEach { device ->
                if (license.unlocked) shownWindows.tryEmit(openFocus(device))
                else { selectDevice(device); shownWindows.tryEmit(null) }
            }
            delay(WindowRequestInterval)
        }
    }
    /** Asks how the camera feels a few seconds after an app on an emulator opens one fed by the computer's camera. */
    fun watchCameraUse() = scope.launch {
        var inUse = emptySet<String>()
        while (isActive) {
            delay(CameraCheckInterval)
            val backend = service
            val serials = running.values.toList()
            val now = withContext(Dispatchers.IO) { serials.filter { runCatching { backend.cameraInUse(it) }.getOrDefault(false) }.toSet() }
            val opened = now - inUse
            inUse = now
            // Only an emulator fed by one of the computer's cameras tells us about the camera experience.
            if (opened.isNotEmpty() && withContext(Dispatchers.IO) { backend.hostCameras().isNotEmpty() }) {
                delay(CameraFeedbackDelay)
                cameraFeedbackDevice = running.entries.firstOrNull { it.value in opened }?.key
            }
        }
    }
    fun answerCameraFeedback(good: Boolean) {
        cameraFeedbackDevice = null
        analytics.track(CameraFeedbackEvent.Name, mapOf("rating" to if (good) CameraFeedbackEvent.Up else CameraFeedbackEvent.Down))
    }
    fun dismissCameraFeedback() { cameraFeedbackDevice = null }

    /** Real frames for connected phones by model, from the SDK's skins; null while loading or when there is none. */
    val phoneSkins = mutableStateMapOf<String, PhoneSkinArt?>()
    fun loadPhoneSkin(model: String) {
        if (model in phoneSkins) return
        phoneSkins[model] = null
        val sdk = expandPath(settings.sdk)
        scope.launch { phoneSkins[model] = withContext(Dispatchers.IO) { loadPhoneSkinArt(sdk, model) } }
    }

    /** How emulators draw, which they take when they start. */
    var graphics by mutableStateOf(graphicsStore?.load() ?: EmulatorGraphics.Hardware); private set
    fun changeGraphics(next: EmulatorGraphics) {
        graphics = next
        graphicsStore?.save(next)
    }

    private val graphicsCrashEvents = Channel<String>(Channel.BUFFERED)
    /** The names of emulators whose WebView just crashed on hardware graphics, the known Android 17 emulator bug. */
    val graphicsCrashes: Flow<String> = graphicsCrashEvents.receiveAsFlow()

    /**
     * Watches Android 17 and newer emulators on hardware graphics for the WebView crash their missing OpenGL ES 3.1
     * causes, telling [graphicsCrashes] at most once a minute for each.
     */
    fun watchGraphicsCrashes() = scope.launch {
        val watchers = mutableMapOf<String, Job>()
        while (isActive) {
            val targets = if (graphics != EmulatorGraphics.Hardware) emptyMap() else running.mapNotNull { (name, serial) ->
                devices.firstOrNull { it.name == name }?.takeIf { (it.api?.substringBefore('.')?.toIntOrNull() ?: 0) >= 37 }
                    ?.let { serial to it }
            }.toMap()
            watchers.keys.filter { it !in targets }.forEach { watchers.remove(it)?.cancel() }
            targets.filterKeys { it !in watchers }.forEach { (serial, device) ->
                val backend = service
                watchers[serial] = launch(Dispatchers.IO) {
                    var told = 0L
                    // Interruptible, so cancelling the watcher stops the blocked log reader.
                    runCatching {
                        runInterruptible { backend.watchWebViewGraphicsCrashes(serial, java.util.concurrent.atomic.AtomicBoolean()) {
                            val now = System.currentTimeMillis()
                            if (now - told > 60_000) { told = now; graphicsCrashEvents.trySend(device.displayName) }
                        } }
                    }
                    watchers.remove(serial)
                }
            }
            delay(3000)
        }
    }

    var media by mutableStateOf(mediaStore?.load() ?: EmulatorMedia()); private set
    /** A camera change waiting for the user to choose when running emulators pick it up. */
    var pendingMedia by mutableStateOf<EmulatorMedia?>(null); private set
    var hostCameras by mutableStateOf(emptyList<HostCamera>()); private set
    /** The computer's default input, by name; null when there is no microphone. */
    var hostMicrophone by mutableStateOf<String?>(null); private set
    var hostMicrophones by mutableStateOf(emptyList<String>()); private set
    fun refreshHostMedia() = scope.launch {
        val backend = service
        hostCameras = withContext(Dispatchers.IO) { backend.hostCameras() }
        hostMicrophone = withContext(Dispatchers.IO) { hostMicrophoneName() }
        hostMicrophones = withContext(Dispatchers.IO) { attachedMicrophones() }
    }
    /** Emulators switch microphones while running, so the choice applies right away; null follows the default input. */
    fun changeMicrophone(name: String?) {
        saveMedia(media.copy(microphone = name))
        pendingMedia = pendingMedia?.copy(microphone = name)
        if (name != null && running.isNotEmpty()) {
            val backend = service
            scope.launch(Dispatchers.IO) { backend.startMicrophoneForwarder() }
        }
    }
    fun changeMedia(next: EmulatorMedia) {
        if (next.sameCamerasAs(media, hostCameras)) return
        if (running.isEmpty()) saveMedia(next) else pendingMedia = next
    }
    fun dismissMediaChange() { pendingMedia = null }
    /** Emulators take their camera and microphone at launch, so running ones restart to switch now. */
    fun applyMediaChange(restartNow: Boolean) {
        val next = pendingMedia ?: return
        pendingMedia = null
        saveMedia(next)
        if (restartNow) relaunchRunning()
    }
    private fun saveMedia(next: EmulatorMedia) {
        media = next
        mediaStore?.save(next)
    }
    private fun relaunchRunning() {
        val serials = running.filterKeys { it !in starting && it !in stopping && it !in deviceOperations }
        val targets = devices.filter { it.name in serials }
        if (busy || targets.isEmpty() || !allowBulk(targets)) return
        stopping = stopping + serials.keys
        val backend = service
        scope.launch {
            try {
                withContext(Dispatchers.IO) { forEachDevice(targets) { backend.stop(serials.getValue(it.name)) } }
                withTimeoutOrNull(30_000) { while (running.keys.any { it in serials }) { refresh(); delay(500) } }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                notifyFailure(FailedOperation.Restart((e as? DevicesFailed)?.devices ?: targets.map { it.displayName }))
                return@launch
            } finally { stopping = stopping - serials.keys }
            launch(targets)
        }
    }
    fun duplicate(device: Device) = action(
        operations = mapOf(device.name to EmulatorOperation.Duplicating), failed = FailedOperation::Duplicate) {
        require(device.name !in running && device.name !in starting) { "Stop ${device.displayName} before duplicating it." }
        it.duplicate(device)
    }
    fun rename(device: Device, name: String, onResult: (Throwable?) -> Unit = {}) = action(onResult,
        operations = mapOf(device.name to EmulatorOperation.Renaming)) {
        require(device.name !in running && device.name !in starting) { "Stop ${device.displayName} before renaming it." }; it.rename(device, name.trim())
    }
    fun delete(devices: List<Device>) {
        if (!allowBulk(devices)) return
        action(
            operations = devices.associate { it.name to EmulatorOperation.Deleting }, blocksWorkspace = false,
            failed = FailedOperation::Remove) { backend ->
            forEachDevice(devices) { device ->
                require(device.name !in starting) { "Wait for this emulator to finish starting before removing it." }
                running[device.name]?.let(backend::stop); backend.delete(device)
            }
        }
    }
    fun reveal(device: Device) = reveal(listOf(device))
    fun reveal(devices: List<Device>) {
        if (!allowBulk(devices)) return
        action(failed = { FailedOperation.Reveal(it.ifEmpty { devices.map(Device::displayName) }) }) { backend ->
            forEachDevice(devices) { device ->
                val file = device.directory.takeIf(File::exists) ?: File(backend.avdRoot, "${device.name}.ini")
                require(file.exists()) { "The emulator's storage is unavailable." }
                revealInFileManager(file)
            }
        }
    }
    fun saveSettings(sdk: String, avds: String): SettingsSaveResult = saveSettings(settings.copy(sdk = sdk, avds = avds))

    fun saveSettings(draft: AppSettings): SettingsSaveResult {
        if (downloads.activeCount > 0) return SettingsSaveResult.DownloadsActive
        if (busy) return SettingsSaveResult.WorkspaceBusy
        draft.locationProblem()?.let { return it }
        val updated = draft.normalizedLocations()
        return runCatching {
            store.save(updated)
            settingsSavedAt = store.savedAt()
            useSettings(updated)
            refreshAsync(); SettingsSaveResult.Saved
        }.getOrElse { SettingsSaveResult.Failed(it) }
    }

    private fun useSettings(updated: AppSettings) {
        if (settings == updated) return
        settings = updated; selected = emptySet(); images = emptyList()
        creationImagesRevision++
        creationImages = emptyList(); creationImagesState = OperationState.Idle
        downloads.tasks.clear()
        imageDeletions.clear()
        catalogRevision++
        catalogJob?.cancel(); catalogState = OperationState.Idle
    }

    /**
     * Picks up settings saved outside the app, such as by 'robomori config set avds', so it lists the emulators the
     * command line sees. Waits while downloads or other work depend on the current locations. Returns whether they changed.
     */
    fun reloadSavedSettings(): Boolean {
        if (downloads.activeCount > 0 || busy) return false
        val savedAt = store.savedAt()
        if (savedAt == settingsSavedAt) return false
        settingsSavedAt = savedAt
        val saved = runCatching { store.load().normalizedLocations() }.getOrNull() ?: return false
        if (saved == settings) return false
        useSettings(saved)
        return true
    }
    /**
     * Shows the cached SDK catalog with what is installed on disk now, and fetches a new one from Google's
     * repository once it is a day old, or when [force]d.
     */
    fun loadCatalog(force: Boolean = false) {
        if (catalogLoading) return
        val backend = service
        val revision = ++catalogRevision
        catalogState = OperationState.Running
        catalogJob = scope.launch {
            try {
                val cached = withContext(Dispatchers.IO) { catalogCache?.read(backend.settings) }
                val local = runCatching { withContext(Dispatchers.IO) { backend.installedImages() } }.getOrNull()
                val known = cached?.let { parseImages(it.text) } ?: images
                if (settings == backend.settings && revision == catalogRevision) images = local?.let { withInstalled(known, it) } ?: known
                if (force || cached == null || cached.age >= CatalogMaxAge) {
                    backend.toolchain.requireTools("sdkmanager")
                    val text = downloads.sdkLock.withLock { withContext(Dispatchers.IO) { backend.catalog() } }
                    withContext(Dispatchers.IO) { catalogCache?.write(backend.settings, text) }
                    if (settings == backend.settings && revision == catalogRevision) images = parseImages(text)
                }
                if (settings == backend.settings && revision == catalogRevision) catalogState = OperationState.Succeeded
            } catch (t: Exception) {
                if (t is CancellationException) throw t
                if (settings == backend.settings && revision == catalogRevision) catalogState = OperationState.Failed(t)
            } finally {
                if (revision == catalogRevision && catalogState.isRunning) catalogState = OperationState.Idle
            }
        }
    }
    /** Keeps the catalog no more than a day old while the app runs, so Platforms is ready when opened. */
    fun keepCatalogFresh() = scope.launch {
        while (isActive) {
            if (service.toolchain.hasTools("sdkmanager")) loadCatalog()
            delay(CatalogCheckInterval)
        }
    }
    suspend fun reviewStorage(spec: CreateSpec): LoadState<StoragePlan> = try {
        val current = settings
        LoadState.Ready(withContext(Dispatchers.IO) { storagePlanner(spec, current) })
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        LoadState.Failed(e)
    }

    suspend fun imageSize(image: SystemImage): LoadState<Long?> = try {
        val sdk = expandPath(settings.sdk)
        LoadState.Ready(withContext(Dispatchers.IO) { image.sizeOnDisk(sdk) })
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        LoadState.Failed(e)
    }

    /** Opens the purchase page; [source] says which Buy button it was, for the funnel. */
    fun purchaseLicense(source: String) {
        analytics.track(FunnelEvent.BuyClicked, mapOf("source" to source))
        openWebsite(purchaseLicenseUrl)
    }

    init {
        // Each license activation is a step of the funnel, wherever the key was entered.
        license.onActivated = { analytics.track(FunnelEvent.LicenseActivated) }
    }
    fun manageLicense() = openWebsite(licenseOrigin)
    /** Google's download page for the Android SDK command-line tools, for people without an SDK yet. */
    fun downloadAndroidSdk() = openWebsite("https://developer.android.com/studio#command-line-tools-only")
    private fun openWebsite(url: String) {
        scope.launch {
            try { withContext(Dispatchers.IO) { java.awt.Desktop.getDesktop().browse(URI(url)) } }
            catch (e: Exception) { if (e is CancellationException) throw e; notifyFailure(FailedOperation.OpenWebsite(url)) }
        }
    }
    fun create(spec: CreateSpec) {
        if (busy) return
        val resumeCreated = (creation as? CreationState.Failed)?.created == true && pendingCreation != null && creationSpec == spec
        creationSpec = spec
        cancellation = AtomicBoolean(); val token = cancellation; val backend = service
        pendingCreation = Device(spec.name, File(backend.avdRoot, "${spec.name}.avd"), buildMap {
            put("avd.ini.displayname", spec.displayName)
            put("hw.device.name", spec.profile.replace('-', '_'))
            put("image.sysdir.1", spec.image.path.replace(';', '/') + "/")
            spec.hardware?.let { profile ->
                put("robomori.profile", profile.id)
                put("showDeviceFrame", "yes")
                put("hw.lcd.width", profile.display.width.toString())
                put("hw.lcd.height", profile.display.height.toString())
                put("hw.lcd.density", profile.display.density.toString())
            }
        }, 0)
        selectPreview(pendingCreation!!)
        showCreate = false
        creation = CreationState.SettingUp
        scope.launch {
            var startWhenReady: Device? = null
            var createdOnDisk = resumeCreated
            try {
                val created = if (resumeCreated) spec else downloads.sdkLock.withLock {
                    val currentSpec = spec.copy(image = creationImages.firstOrNull { it.path == spec.image.path }
                        ?: images.firstOrNull { it.path == spec.image.path } ?: spec.image)
                    withContext(Dispatchers.IO) {
                        val plan = storagePlanner(currentSpec, backend.settings)
                        check(plan.canCreate) { "Not enough available disk space to create this emulator. Free space and review the storage requirements, then retry." }
                        backend.create(currentSpec, token) { }
                    }
                }
                createdOnDisk = true
                creationSpec = created
                pendingCreation = pendingCreation?.copy(name = created.name, directory = File(backend.avdRoot, "${created.name}.avd"))
                pendingCreation?.let(::selectPreview)
                creation = CreationState.Finalizing
                refresh(); selected = setOf(created.name); previewName = created.name
                images = images.map { if (it.path == spec.image.path) it.copy(installed = true) else it }
                val device = devices.firstOrNull { it.name == created.name } ?: error("Your emulator was created but could not be loaded. Try again.")
                pendingCreation = null
                creation = CreationState.Created
                if (!token.get()) startWhenReady = device
            } catch (t: Exception) {
                if (token.get()) {
                    discardPendingCreation()
                    refreshAsync()
                } else {
                    if (t is CancellationException) throw t
                    creation = CreationState.Failed(t, created = createdOnDisk)
                }
            }
            finally { if (creating) creation = CreationState.Idle }
            startWhenReady?.let(::launch)
        }
    }
    fun cancelCreate() { if (creating) { creation = CreationState.Cancelling; cancellation.set(true) } }
    fun resetCreate(type: DeviceType = createType) {
        createType = type
        showCreate = true
        if (creationImagesLoading) return
        val backend = service
        val revision = ++creationImagesRevision
        creationImagesState = OperationState.Running
        scope.launch {
            try {
                val installed = withContext(Dispatchers.IO) { backend.installedImages() }
                if (settings == backend.settings && revision == creationImagesRevision) {
                    creationImages = installed
                    creationImagesState = OperationState.Succeeded
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (settings == backend.settings && revision == creationImagesRevision) {
                    creationImages = emptyList()
                    creationImagesState = OperationState.Failed(e)
                }
            } finally {
                if (revision == creationImagesRevision && creationImagesState.isRunning) creationImagesState = OperationState.Idle
            }
        }
    }

    fun retryCreation() { if (!busy) creationSpec?.let(::create) }
    fun discardPendingCreation() {
        pendingCreation?.let { pending ->
            selected = selected - pending.name
            if (previewName == pending.name) previewName = devices.firstOrNull()?.name
        }
        pendingCreation = null; creationSpec = null; creation = CreationState.Idle
    }
    internal val licenseReminder = LicenseReminder()
    fun windowFocusChanged(focused: Boolean) {
        if (!focused) connected.releaseKeyboard()
        if (licenseReminder.focusChanged(focused, licensed = license.unlocked, canShow = !hasOpenDialog && !busy)) showDemoDialog = true
    }
    val updates = AppUpdates(scope, license)
    val codeUpdates = CodeUpdates(scope, download = { codePush?.downloadLatest { codeUpdateAllowed(it, license.updatesUntil) } },
        ready = { codePush?.readyPatch() })
    fun checkUpdates() {
        openSettings(SettingsSection.Updates)
        updates.check()
        codeUpdates.check()
    }
    /**
     * Updates Robomori in one go: a new version when there is one, which replaces any code update, otherwise a code
     * update that's ready.
     */
    fun updateNow() {
        when {
            updates.canUpdateNow -> updates.update { busy }
            codeUpdates.readyPatch != null -> restartToUpdate()
        }
    }
}

private val OutsideChangeInterval = 2.seconds
private val WindowRequestInterval = 500.milliseconds

/** The emulators' definitions and the files running emulators announce themselves with, with when they last changed. */
internal fun emulatorFiles(avdRoot: File, discovery: List<File> = HostPlatform().discoveryDirectories): Set<String> =
    (avdRoot.listFiles().orEmpty().filter { it.extension == "ini" } + discovery.flatMap { it.listFiles().orEmpty().toList() })
        .mapTo(mutableSetOf()) { "${it.path}@${it.lastModified()}" }

/** How many emulators are running, by their announcement files among [files]: `pid_<id>.ini`, with an `_info` twin. */
internal fun runningEmulators(files: Set<String>): Int =
    files.mapNotNull { Regex("pid_(\\d+)(_info)?\\.ini@").find(it.substringAfterLast(File.separatorChar))?.groupValues?.get(1) }.distinct().size

/** Tells focus windows apart: an emulator by its folder, a phone by its identity. */
internal val DeviceTarget.focusKey: String get() = when (this) {
    is Device -> "emulator:" + directory.absolutePath
    is ConnectedDevice -> "device:$identity"
    else -> "other:$name"
}
