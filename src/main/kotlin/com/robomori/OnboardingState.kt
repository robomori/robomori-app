package com.robomori

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The onboarding's pages, in order. */
internal sealed interface OnboardingStep {
    data object Welcome : OnboardingStep
    data object Sdk : OnboardingStep
    data object Platforms : OnboardingStep
    /** Installing the robomori command, for the terminal and coding agents. */
    data object CommandLine : OnboardingStep
    data object License : OnboardingStep
    /** Entering a key from a purchase, reached from the license page. */
    data object Activate : OnboardingStep
}

/** The Android SDK's tools, as the onboarding needs them. */
internal sealed interface SdkStatus {
    data object Ready : SdkStatus
    /** None of the tools were found where the SDK is set. */
    data object Missing : SdkStatus
    /** Some tools were found; these were not. */
    data class Incomplete(val missing: List<String>) : SdkStatus
}

/** Choosing the SDK folder from the onboarding. */
internal sealed interface SdkChoice {
    data object Idle : SdkChoice
    data object Picking : SdkChoice
    data object PickerUnavailable : SdkChoice
    data class NotSaved(val result: SettingsSaveResult) : SdkChoice
}

/** One platform's row: whether its latest Android version can be got, is downloading, or is installed. */
internal sealed interface PlatformStatus {
    data object NeedsSdk : PlatformStatus
    data object Loading : PlatformStatus
    data object ListFailed : PlatformStatus
    data object NoCompatibleVersion : PlatformStatus
    data class Available(val image: SystemImage, val size: Long?) : PlatformStatus
    /** An older version, [installed], is on this computer; [image] is the latest. */
    data class Outdated(val image: SystemImage, val installed: SystemImage, val size: Long?) : PlatformStatus
    data class Downloading(val image: SystemImage, val percent: Int, val installing: Boolean, val cancelling: Boolean) : PlatformStatus
    data class DownloadFailed(val image: SystemImage) : PlatformStatus
    data class Installed(val image: SystemImage) : PlatformStatus
}

/** What the person did on a page. */
internal sealed interface OnboardingAction {
    data object Next : OnboardingAction
    data object Back : OnboardingAction
    data object ChooseSdk : OnboardingAction
    data object DownloadSdk : OnboardingAction
    data object RetryPlatformList : OnboardingAction
    data class Get(val platform: DeviceType) : OnboardingAction
    data class Cancel(val platform: DeviceType) : OnboardingAction
    data object BuyLicense : OnboardingAction
    data object HaveLicense : OnboardingAction
    data object ContinueFree : OnboardingAction
    data object InstallCommandLine : OnboardingAction
    /** Asks which coding agent to install the skill for. */
    data object InstallSkill : OnboardingAction
}

/** The device families the onboarding offers, as Settings → Platforms lists them. */
internal val OnboardingPlatforms = listOf(DeviceType.PHONE, DeviceType.WEAR, DeviceType.TV)

/** The tools the onboarding checks; the catalog of versions also needs sdkmanager. */
internal fun sdkStatus(found: Map<String, Boolean>): SdkStatus {
    val missing = found.filterValues { !it }.keys.toList()
    return when {
        missing.isEmpty() -> SdkStatus.Ready
        missing.size == found.size -> SdkStatus.Missing
        else -> SdkStatus.Incomplete(missing)
    }
}

/** The step after [step]. Licensed computers have nothing to buy, so they finish after the command line; null means finished. */
internal fun nextStep(step: OnboardingStep, licensed: Boolean): OnboardingStep? = when (step) {
    OnboardingStep.Welcome -> OnboardingStep.Sdk
    OnboardingStep.Sdk -> OnboardingStep.Platforms
    OnboardingStep.Platforms -> OnboardingStep.CommandLine
    OnboardingStep.CommandLine -> if (licensed) null else OnboardingStep.License
    OnboardingStep.License, OnboardingStep.Activate -> null
}

internal fun previousStep(step: OnboardingStep): OnboardingStep = when (step) {
    OnboardingStep.Welcome, OnboardingStep.Sdk -> OnboardingStep.Welcome
    OnboardingStep.Platforms -> OnboardingStep.Sdk
    OnboardingStep.CommandLine -> OnboardingStep.Platforms
    OnboardingStep.License -> OnboardingStep.CommandLine
    OnboardingStep.Activate -> OnboardingStep.License
}

/**
 * A platform's status from what the app knows: whether the SDK can list versions, the version list, the platform's
 * newest image, its download, its size when known, and the newest older version already [installed].
 */
internal fun platformStatus(sdk: SdkStatus, listLoading: Boolean, listFailed: Boolean, image: SystemImage?,
                            download: AndroidDownload?, size: Long?, installed: SystemImage? = null): PlatformStatus = when {
    image?.installed == true -> PlatformStatus.Installed(image)
    image != null && download?.active == true -> PlatformStatus.Downloading(image, download.overall ?: 0,
        installing = download.state == AndroidDownloadState.Installing || download.state == AndroidDownloadState.WaitingToInstall,
        cancelling = download.cancelling)
    image != null && download?.state is AndroidDownloadState.Failed -> PlatformStatus.DownloadFailed(image)
    image != null && installed != null -> PlatformStatus.Outdated(image, installed, size)
    image != null -> PlatformStatus.Available(image, size)
    // Without sdkmanager there is no list of versions to choose from.
    sdk is SdkStatus.Missing || (sdk is SdkStatus.Incomplete && "sdkmanager" in sdk.missing) -> PlatformStatus.NeedsSdk
    listLoading -> PlatformStatus.Loading
    listFailed -> PlatformStatus.ListFailed
    else -> PlatformStatus.NoCompatibleVersion
}

/**
 * The onboarding's state holder. Pages render [step], [sdk], [sdkChoice] and [platform] and report what the person
 * does through [onAction]; setting up the SDK, downloads and buying happen here, against the app's state.
 */
internal class Onboarding(
    private val app: AppState,
    private val scope: CoroutineScope,
    /** Asks the person for a folder, starting near the given one; null when they cancel. */
    private val pickFolder: suspend (File?) -> File?,
    private val downloadSize: (SystemImage) -> Long = ::imageDownloadSize,
) {
    var step by mutableStateOf<OnboardingStep>(OnboardingStep.Welcome); private set

    init {
        app.analytics.track(FunnelEvent.OnboardingStarted)
        app.analytics.pageview(PagePath.of(step))
    }

    private fun show(page: OnboardingStep) {
        step = page
        if (page == OnboardingStep.CommandLine) { commandLine.load(); agentSkill.load() }
        app.analytics.pageview(PagePath.of(page))
    }

    // Each page reached is a step of the funnel.
    private fun go(to: OnboardingStep) {
        show(to)
        app.analytics.track(when (to) {
            OnboardingStep.Welcome -> FunnelEvent.OnboardingStarted
            OnboardingStep.Sdk -> FunnelEvent.OnboardingSdk
            OnboardingStep.Platforms -> FunnelEvent.OnboardingPlatforms
            OnboardingStep.CommandLine -> FunnelEvent.OnboardingCommandLine
            OnboardingStep.License -> FunnelEvent.OnboardingLicense
            OnboardingStep.Activate -> FunnelEvent.OnboardingActivate
        })
    }

    private fun finish(event: String) {
        app.analytics.track(event)
        app.dismissWelcome()
    }
    var sdkChoice by mutableStateOf<SdkChoice>(SdkChoice.Idle); private set

    /** The robomori command, which the command line page installs. */
    val commandLine: CommandLineToolState get() = app.commandLine

    /** The skill teaching coding agents the command, which the command line page installs next. */
    val agentSkill: AgentSkillState get() = app.agentSkill
    private val sizes = mutableStateMapOf<String, Long>()
    private val sizeRequests = mutableSetOf<String>()

    val sdk: SdkStatus get() = sdkStatus(app.service.toolchain.tools.mapValues { (_, file) -> file.isFile && file.canExecute() })

    /** The versions on offer; the platforms page loads their sizes whenever this changes. */
    val versions: List<SystemImage> get() = app.images

    /** The download of [type]'s latest version, when there is one; its progress is a flow for pages to collect. */
    fun download(type: DeviceType): AndroidDownload? = creationImages(type, app.images).firstOrNull()?.let { app.downloads.tasks[it.path] }

    fun platform(type: DeviceType): PlatformStatus {
        val images = creationImages(type, app.images)
        val image = images.firstOrNull()
        return platformStatus(sdk, app.catalogLoading, app.catalogError.isNotBlank(), image,
            image?.let { app.downloads.tasks[it.path] }, image?.let { sizes[it.path] }, images.drop(1).firstOrNull { it.installed })
    }

    /** Looks up the download size of each platform that can be got, once per image. */
    fun loadSizes() {
        OnboardingPlatforms.forEach { type ->
            val status = platform(type)
            when (status) {
                is PlatformStatus.Available -> if (status.size == null) requestSize(status.image)
                is PlatformStatus.Outdated -> if (status.size == null) requestSize(status.image)
                else -> {}
            }
        }
    }

    fun onAction(action: OnboardingAction) {
        when (action) {
            OnboardingAction.Next -> nextStep(step, app.license.licensed && !app.onboardingAsNewUser)?.let(::go) ?: finish(FunnelEvent.OnboardingFinished)
            // Going back is a pageview but not a step of the funnel.
            OnboardingAction.Back -> show(previousStep(step))
            OnboardingAction.ChooseSdk -> chooseSdk()
            OnboardingAction.DownloadSdk -> app.downloadAndroidSdk()
            OnboardingAction.RetryPlatformList -> app.loadCatalog(force = true)
            is OnboardingAction.Get -> creationImages(action.platform, app.images).firstOrNull()?.let { image ->
                val update = platform(action.platform) is PlatformStatus.Outdated
                app.analytics.track(FunnelEvent.PlatformDownload, mapOf("platform" to action.platform.name.lowercase(), "kind" to if (update) "update" else "new"))
                app.downloadImage(image)
            }
            is OnboardingAction.Cancel -> creationImages(action.platform, app.images).firstOrNull()?.let { app.downloads.cancel(it.path) }
            OnboardingAction.BuyLicense -> app.purchaseLicense(if (step == OnboardingStep.Activate) BuySource.OnboardingActivate else BuySource.Onboarding)
            OnboardingAction.HaveLicense -> go(OnboardingStep.Activate)
            OnboardingAction.ContinueFree -> finish(FunnelEvent.OnboardingSkipped)
            OnboardingAction.InstallCommandLine -> commandLine.install()
            OnboardingAction.InstallSkill -> agentSkill.choose()
        }
    }

    /** Activating a license finishes the onboarding. */
    fun licenseChanged(licensed: Boolean) {
        if (licensed && (step == OnboardingStep.License || step == OnboardingStep.Activate)) finish(FunnelEvent.OnboardingFinished)
    }

    private fun chooseSdk() {
        if (sdkChoice == SdkChoice.Picking) return
        sdkChoice = SdkChoice.Picking
        scope.launch {
            sdkChoice = try {
                val start = generateSequence(expandPath(app.settings.sdk)) { it.parentFile }.firstOrNull { it.isDirectory }
                val folder = pickFolder(start)
                if (folder == null) SdkChoice.Idle
                else when (val result = app.saveSettings(app.settings.copy(sdk = folder.absolutePath))) {
                    SettingsSaveResult.Saved -> SdkChoice.Idle.also { app.analytics.track(FunnelEvent.SdkSelected) }
                    else -> SdkChoice.NotSaved(result)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                SdkChoice.PickerUnavailable
            }
        }
    }

    // The download size is read from the image archive's metadata, once per image.
    private fun requestSize(image: SystemImage) {
        if (!sizeRequests.add(image.path)) return
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { downloadSize(image) } }.getOrNull()?.let { sizes[image.path] = it }
        }
    }
}
