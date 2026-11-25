package com.robomori

import java.io.File

sealed interface CaptureKind {
    data object Screenshot : CaptureKind
    data object Recording : CaptureKind
}

sealed interface CaptureStep {
    data object Screenshot : CaptureStep
    data object StartRecording : CaptureStep
    data object StopRecording : CaptureStep
    data object SaveRecording : CaptureStep
    data object OpenLocation : CaptureStep
}

sealed interface CaptureNotice {
    val file: File?
    data class Saved(val kind: CaptureKind, override val file: File) : CaptureNotice
    data class Failed(val step: CaptureStep, val cause: Throwable, override val file: File? = null) : CaptureNotice
}

/** An operation the user started that did not complete, shown once at the top of the window. [devices] are the display names it failed for. */
sealed interface FailedOperation {
    data class Start(val devices: List<String>) : FailedOperation
    /** The emulators started, but their first-boot settings could not be applied. */
    data class InitialSetup(val devices: List<String>) : FailedOperation
    data class Stop(val devices: List<String>) : FailedOperation
    data class Restart(val devices: List<String>) : FailedOperation
    data class Duplicate(val devices: List<String>) : FailedOperation
    data class Remove(val devices: List<String>) : FailedOperation
    /** Robomori's helper app, certificate or files could not be taken off the device. */
    data class RemoveRobomori(val devices: List<String>) : FailedOperation
    data class Reveal(val devices: List<String>) : FailedOperation
    data object RefreshDevices : FailedOperation
    data class OpenWebsite(val url: String) : FailedOperation
    data class SavePreset(val reason: String?) : FailedOperation
    data class LoadPreset(val reason: String?) : FailedOperation
    data class ApplyPreset(val reason: String?) : FailedOperation
    data object RevealPresets : FailedOperation
}

/** Some devices in a batch failed. Carries their display names so the failure can name them. */
internal class DevicesFailed(val devices: List<String>) : Exception("Failed for ${devices.joinToString()}.")

sealed interface RecordingState {
    /** Shown as recording from the click, also while the recorder is still starting. */
    data class Recording(val startedAt: Long) : RecordingState
    data class Saving(val startedAt: Long) : RecordingState
}

/** The camera shutter heard when a screenshot is saved. */
fun interface ShutterSound {
    fun play()

    companion object {
        val None = ShutterSound {}

        /**
         * Android's camera click, bundled so it sounds the same on every computer. Silent when there's no audio output.
         * Opening the sound takes the first click about 90 ms and every later one 17, so it's opened once, ahead of the
         * first click, and played off the UI thread, which draws the flash meanwhile.
         */
        val CameraClick: ShutterSound by lazy {
            val player = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "shutter-sound").apply { isDaemon = true } }
            val clip = player.submit<javax.sound.sampled.Clip?> {
                runCatching {
                    val sound = checkNotNull(ShutterSound::class.java.getResourceAsStream("/sounds/camera-click.wav"))
                    javax.sound.sampled.AudioSystem.getClip().apply {
                        javax.sound.sampled.AudioSystem.getAudioInputStream(sound.buffered()).use { open(it) }
                    }
                }.getOrNull()
            }
            ShutterSound {
                player.execute {
                    runCatching { clip.get()?.run { stop(); framePosition = 0; start() } }
                }
            }
        }
    }
}
