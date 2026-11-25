package com.robomori

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.Properties
import kotlinx.serialization.json.*
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.TargetDataLine

/** A camera attached to the computer. [label] is how the emulator addresses it at launch, such as webcam0. */
data class HostCamera(val id: String, val name: String, val label: String)

/** What feeds one of an emulator's cameras: the emulator's own simulated camera, or one attached to the computer. */
sealed interface CameraSource {
    data object Emulated : CameraSource
    data class Host(val id: String) : CameraSource
}

/**
 * Which of the computer's cameras feed every emulator's front and back cameras, and which microphone it hears.
 * Without a choice, or when the chosen one is detached, each shows the computer's first camera. They stay emulated only
 * when chosen so, or when the computer has no camera. Without a microphone choice, or when the chosen one is
 * detached, emulators hear the computer's default input.
 */
data class EmulatorMedia(
    val frontCamera: CameraSource? = null,
    val backCamera: CameraSource? = null,
    val microphone: String? = null,
)

/** The computer's camera the front camera shows, or null when it stays emulated. */
fun EmulatorMedia.frontCamera(cameras: List<HostCamera>): HostCamera? = resolve(frontCamera, cameras)

/** The computer's camera the back camera shows, or null when it stays emulated. */
fun EmulatorMedia.backCamera(cameras: List<HostCamera>): HostCamera? = resolve(backCamera, cameras)

private fun resolve(source: CameraSource?, cameras: List<HostCamera>): HostCamera? = when (source) {
    CameraSource.Emulated -> null
    is CameraSource.Host -> cameras.firstOrNull { it.id == source.id } ?: cameras.firstOrNull()
    null -> cameras.firstOrNull()
}

/** Whether emulators would start with different cameras under [other]. */
fun EmulatorMedia.sameCamerasAs(other: EmulatorMedia, cameras: List<HostCamera>): Boolean =
    frontCamera(cameras) == other.frontCamera(cameras) && backCamera(cameras) == other.backCamera(cameras)

/** The chosen microphone when it's attached; null means the computer's default input. */
fun EmulatorMedia.microphone(microphones: List<String>): String? = microphone?.takeIf { it in microphones }

/**
 * The computer's default input. On a Mac that's its name in System Settings → Sound,
 * such as MacBook Pro Microphone; elsewhere a generic name. Null when there is no microphone.
 */
fun hostMicrophoneName(): String? =
    if (HostOs.detect() == HostOs.MAC) runCatching {
        val process = ProcessBuilder("system_profiler", "SPAudioDataType", "-json").redirectErrorStream(true).start()
        defaultInputName(process.inputStream.bufferedReader().use { it.readText() })
    }.getOrNull()
    else "Computer microphone".takeIf { attachedMicrophones().isNotEmpty() }

/** The default input device's name in the output of `system_profiler SPAudioDataType -json`. */
fun defaultInputName(systemProfiler: String): String? = runCatching {
    Json.parseToJsonElement(systemProfiler).jsonObject["SPAudioDataType"]?.jsonArray.orEmpty()
        .flatMap { it.jsonObject["_items"]?.jsonArray.orEmpty() }.map { it.jsonObject }
        .firstOrNull { it["coreaudio_default_audio_input_device"]?.jsonPrimitive?.content == "spaudio_yes" }
        ?.get("_name")?.jsonPrimitive?.content
}.getOrNull()

// Java Sound lists the system's default input alongside the devices themselves.
private val defaultInputMixers = setOf("Default Audio Device", "Primary Sound Capture Driver", "default [default]")

/** The computer's microphones by name, such as MacBook Pro Microphone, as Java Sound can record them. */
fun attachedMicrophones(): List<String> = runCatching {
    AudioSystem.getMixerInfo().filter { info ->
        info.name !in defaultInputMixers &&
            AudioSystem.getMixer(info).targetLineInfo.any { TargetDataLine::class.java.isAssignableFrom(it.lineClass) }
    }.map { it.name }.distinct()
}.getOrDefault(emptyList())

private val webcamLine = Regex("Camera '(.+)' can be specified by label as '(webcam\\d+)' or by id as '([^']+)'")

fun parseHostCameras(webcamList: String): List<HostCamera> =
    webcamList.lineSequence().mapNotNull { webcamLine.find(it) }
        .map { HostCamera(id = it.groupValues[3], name = it.groupValues[1], label = it.groupValues[2]) }.toList()

fun hostMediaArgs(config: Map<String, String>, media: EmulatorMedia, cameras: List<HostCamera>): List<String> = buildList {
    // Without a microphone attached, the emulator hears silence as it would without the flag.
    add("-allow-host-audio")
    // A camera left emulated, or one the device doesn't have, keeps what its profile sets.
    media.frontCamera(cameras)?.takeIf { config["hw.camera.front"] != "none" }?.let { addAll(listOf("-camera-front", it.label)) }
    media.backCamera(cameras)?.takeIf { config["hw.camera.back"] != "none" }?.let { addAll(listOf("-camera-back", it.label)) }
}

class EmulatorMediaStore(private val file: File = File(HostPlatform().configDirectory, "emulator-media.properties")) {
    fun load(): EmulatorMedia {
        val values = Properties().apply { if (file.isFile) file.inputStream().use(::load) }
        // Before front and back were chosen apart, one "camera" fed both, and it carries over to both.
        val both = values.getProperty("camera")
        return EmulatorMedia(
            frontCamera = (values.getProperty("camera.front") ?: both)?.let(::cameraSource),
            backCamera = (values.getProperty("camera.back") ?: both)?.let(::cameraSource),
            microphone = values.getProperty("microphone.input"),
        )
    }

    fun save(media: EmulatorMedia) {
        val values = Properties()
        media.frontCamera?.let { values["camera.front"] = it.stored() }
        media.backCamera?.let { values["camera.back"] = it.stored() }
        media.microphone?.let { values["microphone.input"] = it }
        file.parentFile.mkdirs()
        val temporary = File.createTempFile("emulator-media-", ".tmp", file.parentFile)
        try {
            temporary.outputStream().use { values.store(it, null) }
            Files.move(temporary.toPath(), file.toPath(), REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
}

private const val EMULATED_CAMERA = "emulated"

private fun cameraSource(value: String): CameraSource = if (value == EMULATED_CAMERA) CameraSource.Emulated else CameraSource.Host(value)

private fun CameraSource.stored(): String = when (this) {
    CameraSource.Emulated -> EMULATED_CAMERA
    is CameraSource.Host -> id
}

private val cameraClient = Regex("Client Package Name: ([^,)]+)")

/** The apps holding a camera open, from the Active Camera Clients section of `dumpsys media.camera`. */
fun cameraClients(dumpsys: String): List<String> =
    dumpsys.substringAfter("Active Camera Clients:", "").substringBefore("\n]").let { section -> cameraClient.findAll(section).map { it.groupValues[1].trim() }.toList() }
