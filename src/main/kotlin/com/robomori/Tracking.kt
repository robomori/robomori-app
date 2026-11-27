package com.robomori

import com.alexstyl.simpleanalytics.SimpleAnalytics
import dev.nucleusframework.aot.runtime.AotRuntime
import java.io.File
import java.time.LocalDate

/** The funnel's events, named as they appear in Simple Analytics for app.robomori.com. */
internal object FunnelEvent {
    const val FirstLaunch = "first_launch"
    const val ActiveDay = "active_day"
    const val AppOpened = "app_opened"
    const val OnboardingStarted = "onboarding_started"
    const val OnboardingSdk = "onboarding_sdk"
    const val SdkSelected = "sdk_selected"
    const val OnboardingPlatforms = "onboarding_platforms"
    const val OnboardingCommandLine = "onboarding_cli"
    const val PlatformDownload = "platform_download"
    const val OnboardingLicense = "onboarding_license"
    const val OnboardingActivate = "onboarding_activate"
    const val OnboardingSkipped = "onboarding_skipped"
    const val OnboardingFinished = "onboarding_finished"
    const val BuyClicked = "buy_clicked"
    const val LicenseActivated = "license_activated"
    /** An emulator Robomori launched is up and running: the app has done its job. Carries [emulatorMetadata]. */
    const val DeviceStarted = "device_started"
}

/** Which devices people use Robomori with, besides the emulators of [FunnelEvent.DeviceStarted]. */
internal object DeviceEvent {
    /** A phone, tablet or watch showed up connected, once per device each run. Carries [deviceMetadata]. */
    const val Connected = "device_connected"
    /** A device was paired over Wi-Fi, with a `method` of `qr` or `code` besides [deviceMetadata]. */
    const val Paired = "device_paired"
}

/** What kind of emulator [device] is: its form, Android version and hardware profile, such as `pixel_10`. */
internal fun emulatorMetadata(device: Device): Map<String, String> = mapOf(
    "type" to device.type.name.lowercase(),
    "android" to device.api,
    "profile" to device.config["hw.device.name"].orEmpty(),
).filterValues { it.isNotBlank() && it != "—" }

/** What kind of device [device] is, and how it's connected. Never anything that identifies the person's own device. */
internal fun deviceMetadata(device: ConnectedDevice): Map<String, String> = mapOf(
    "type" to device.type.name.lowercase(),
    "manufacturer" to device.manufacturer.orEmpty().trim().lowercase(),
    "model" to device.factoryName.takeUnless { it == device.serial }.orEmpty(),
    "android" to device.androidVersion.orEmpty(),
    "connection" to if (device.wireless) "wifi" else "usb",
).filterValues { it.isNotBlank() }

/** Answers to "How is the camera experience?", with a `rating` of [Up] or [Down]. */
internal object CameraFeedbackEvent {
    const val Name = "camera_feedback"
    const val Up = "up"
    const val Down = "down"
}

/** Where a Buy button was pressed, the `source` of [FunnelEvent.BuyClicked]. */
internal object BuySource {
    const val Onboarding = "onboarding"
    const val OnboardingActivate = "onboarding_activate"
    const val Toolbar = "toolbar"
    const val Settings = "settings"
    const val Upsell = "upsell"
    /** The screen the app shows once the free trial is over. */
    const val TrialEnded = "trial_ended"
}

/** The app's screens, as the paths of their pageviews in Simple Analytics. */
internal object PagePath {
    const val Devices = "/"
    const val Focus = "/focus"

    fun of(step: OnboardingStep) = "/onboarding/" + when (step) {
        OnboardingStep.Welcome -> "welcome"
        OnboardingStep.Sdk -> "sdk"
        OnboardingStep.Platforms -> "platforms"
        OnboardingStep.CommandLine -> "cli"
        OnboardingStep.License -> "license"
        OnboardingStep.Activate -> "activate"
    }

    fun of(section: SettingsSection) = "/settings/" + when (section) {
        SettingsSection.License -> "license"
        SettingsSection.Images -> "platforms"
        SettingsSection.Storage -> "sdk-locations"
        SettingsSection.Media -> "camera-microphone"
        SettingsSection.Graphics -> "graphics"
        SettingsSection.Network -> "network"
        SettingsSection.Updates -> "updates"
        SettingsSection.CommandLine -> "command-line"
        SettingsSection.Logs -> "logs"
        SettingsSection.Debug -> "debug"
    }
}

/** Records what people do in the app, as named events with optional details. */
interface Analytics {
    /** Records [event]. Never blocks the caller and never throws. */
    fun track(event: String, metadata: Map<String, String> = emptyMap())

    /** Records that the screen at [path] was shown, like a website's pageview. Never blocks and never throws. */
    fun pageview(path: String)

    /** Records nothing, for tests and builds without analytics. */
    object None : Analytics {
        override fun track(event: String, metadata: Map<String, String>) {}
        override fun pageview(path: String) {}
    }
}

/**
 * Analytics for this run: a Simple Analytics session in the packaged app, nothing while developing. The session
 * measures time on each screen, pauses it while Robomori is minimised, keeps unsent events for the next run, and sends
 * what's left when Robomori quits.
 */
internal fun appAnalytics(): Analytics {
    val packaged = System.getProperty("jpackage.app-path") != null && !AotRuntime.isTraining()
    if (!packaged) return Analytics.None
    val session = SimpleAnalytics("app.robomori.com", APP_NAME, APP_DISPLAY_VERSION,
        queueDirectory = File(HostPlatform().configDirectory, "analytics")).start()
    return object : Analytics {
        override fun track(event: String, metadata: Map<String, String>) = session.track(event, metadata)
        override fun pageview(path: String) = session.pageview(path)
    }
}

/**
 * Reports this launch. [FunnelEvent.FirstLaunch] is sent when Robomori has never run on this computer, and
 * [FunnelEvent.ActiveDay] on its first launch of each day, so a day's count is that day's active users. Call it
 * before the app writes anything to [directory].
 */
internal fun trackLaunch(analytics: Analytics, directory: File = HostPlatform().configDirectory, today: LocalDate = LocalDate.now()) {
    if (neverRan(directory)) analytics.track(FunnelEvent.FirstLaunch)
    val lastActiveDay = File(directory, "last-active-day")
    if (runCatching { lastActiveDay.readText() }.getOrNull() != today.toString()) {
        analytics.track(FunnelEvent.ActiveDay)
        runCatching { atomicWrite(lastActiveDay, today.toString()) }
    }
    analytics.track(FunnelEvent.AppOpened)
}

/**
 * The code update launcher starts before the app and keeps its records in `codepush` inside [directory], so that
 * folder and hidden system files such as `.DS_Store` don't count as Robomori having run.
 */
private fun neverRan(directory: File): Boolean =
    directory.listFiles().orEmpty().none { it.name != "codepush" && !it.name.startsWith(".") }
