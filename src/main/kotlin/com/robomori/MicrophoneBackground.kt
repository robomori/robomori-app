package com.robomori

import kotlinx.coroutines.*
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

internal const val FORWARD_MICROPHONE_ARGUMENT = "forward-microphone"
/**
 * Started like the command line, so code updates treat it as a tool: an app launch would count an update being tried as
 * failed, since the forwarder never reports in.
 */
internal val forwardMicrophoneArguments = listOf(CLI_ARGUMENT, FORWARD_MICROPHONE_ARGUMENT)
/** The launchd job that runs the forwarder on a Mac. */
internal const val MICROPHONE_FORWARDER_JOB = "com.alexstyl.robomori.microphone"

/**
 * How to start the background process that feeds the chosen microphone to emulators. On a Mac, launchd starts the
 * installed app's executable: macOS then asks for microphone access on Robomori's behalf rather than the terminal's,
 * and, unlike `open`, shows no Dock icon for it. A development run starts this JVM's classes again.
 */
internal fun microphoneForwarderCommand(os: HostOs = HostOs.detect(), bundle: File? = appBundle(),
                                        appImage: String? = System.getenv("APPIMAGE"),
                                        appPath: String? = System.getProperty("jpackage.app-path"),
                                        java: String? = ProcessHandle.current().info().command().orElse(null),
                                        classPath: String = System.getProperty("java.class.path")): List<String>? = when {
    // launchd refuses a second job with the same label, and keeps a submitted job running until it removes itself.
    os == HostOs.MAC && bundle != null -> listOf("/bin/launchctl", "submit", "-l", MICROPHONE_FORWARDER_JOB, "--",
        File(bundle, "Contents/MacOS/$APP_NAME").path) + forwardMicrophoneArguments
    os == HostOs.LINUX && !appImage.isNullOrBlank() -> listOf(appImage) + forwardMicrophoneArguments
    // The command line runs robomori-cli.exe; the app, which opens no console, is the launcher next to it.
    os == HostOs.WINDOWS && appPath?.endsWith(".exe", ignoreCase = true) == true ->
        listOf(File(File(appPath).parentFile, "$APP_NAME.exe").path) + forwardMicrophoneArguments
    java != null -> listOf(java, "-cp", classPath, "com.robomori.MainKt") + forwardMicrophoneArguments
    else -> null
}

/**
 * The background process's work: feeds the chosen microphone to every running emulator, whether the app or the command
 * line started it, so it works while the app is closed. Returns once the choice goes back to the default input, or no
 * emulator has run for [idleLimit], which covers an emulator that is still booting.
 */
internal suspend fun forwardMicrophoneWhileEmulatorsRun(
    android: () -> AndroidService,
    mediaStore: EmulatorMediaStore,
    microphones: () -> List<String> = ::attachedMicrophones,
    connections: EmulatorConnections = EmulatorConnections(),
    openSink: (String) -> MicrophoneSink? = connections::sink,
    openInput: (String) -> MicrophoneInput? = JavaSoundMicrophone::open,
    idleLimit: Duration = 3.minutes,
    checkInterval: Duration = 2.seconds,
) = coroutineScope {
    val service = AtomicReference(android())
    val serials = AtomicReference(emptyList<String>())
    val microphone = AtomicReference<String?>(null)
    val watches = RecordingWatches { serial, cancelled, onCheck -> service.get().watchMicrophoneUse(serial, cancelled, onCheck) }
    val forwarding = launch {
        MicrophoneForwarder(recording = watches::next, openSink = openSink, openInput = openInput,
            prepare = { watches.keep(it); connections.keep(it) })
            .run(microphone = { microphone.get() }, serials = { serials.get() })
    }
    var idleSince = TimeSource.Monotonic.markNow()
    while (true) {
        val chosen = mediaStore.load().microphone ?: break
        service.set(android())
        serials.set(withContext(Dispatchers.IO) { runCatching { service.get().running().values.toList() }.getOrDefault(emptyList()) })
        if (serials.get().isNotEmpty()) idleSince = TimeSource.Monotonic.markNow()
        else if (idleSince.elapsedNow() > idleLimit) break
        microphone.set(withContext(Dispatchers.IO) { chosen.takeIf { it in microphones() } })
        delay(checkInterval)
    }
    forwarding.cancelAndJoin()
}

/** The background process started with [forwardMicrophoneArguments]. It shows nothing, and only one runs at a time. */
internal fun forwardMicrophoneInBackground() {
    System.setProperty("java.awt.headless", "true")
    try {
        val lock = exclusiveLock(File(HostPlatform().configDirectory, "microphone-forwarder.lock")) ?: return
        try {
            runBlocking { forwardMicrophoneWhileEmulatorsRun(android = { AndroidService(SettingsStore().load()) }, mediaStore = EmulatorMediaStore()) }
        } finally { lock.close() }
    } finally {
        // launchd would start the job again; removing it also ends this process.
        if (System.getenv("XPC_SERVICE_NAME") == MICROPHONE_FORWARDER_JOB)
            ProcessBuilder("/bin/launchctl", "remove", MICROPHONE_FORWARDER_JOB).start().waitFor()
    }
}

/**
 * Holds [file]'s lock until the returned channel is closed or the process ends; null while another process holds it.
 * The file is closed whenever the lock isn't held, as Windows can't delete a file that is still open.
 */
internal fun exclusiveLock(file: File): FileChannel? {
    file.parentFile.mkdirs()
    val channel = runCatching { RandomAccessFile(file, "rw").channel }.getOrNull() ?: return null
    return channel.takeIf { runCatching { it.tryLock() }.getOrNull() != null } ?: run { channel.close(); null }
}
