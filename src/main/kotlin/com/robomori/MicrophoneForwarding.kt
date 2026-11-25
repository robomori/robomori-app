package com.robomori

import com.google.protobuf.ByteString
import com.google.protobuf.Empty
import com.robomori.emulator.protocol.AudioFormat
import com.robomori.emulator.protocol.AudioPacket
import io.grpc.CallOptions
import io.grpc.ConnectivityState
import io.grpc.ManagedChannel
import io.grpc.MethodDescriptor
import io.grpc.protobuf.ProtoUtils
import io.grpc.stub.ClientCallStreamObserver
import io.grpc.stub.ClientCalls
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.concurrent.thread

internal val injectAudioMethod: MethodDescriptor<AudioPacket, Empty> = MethodDescriptor.newBuilder<AudioPacket, Empty>()
    .setType(MethodDescriptor.MethodType.CLIENT_STREAMING)
    .setFullMethodName("android.emulation.control.EmulatorController/injectAudio")
    .setRequestMarshaller(ProtoUtils.marshaller(AudioPacket.getDefaultInstance()))
    .setResponseMarshaller(ProtoUtils.marshaller(Empty.getDefaultInstance()))
    .build()

private val clientState = Regex("State: (\\w+)")
// Only recording clients print their source as a number after their state: `- Source: 1 flags: …` up to Android 12,
// `Source: 1; Flags: …` since. Playing clients print a state too.
private val recordingSource = Regex("Source: \\d+;? [Ff]lags")

/** How many apps are recording, from the clients in `dumpsys media.audio_policy`. */
fun recordingClients(dumpsys: String): Int {
    var state: String? = null
    return dumpsys.lineSequence().count { line ->
        clientState.find(line)?.let { state = it.groupValues[1] }
        recordingSource.containsMatchIn(line) && state == "Active"
    }
}

private const val SampleRate = 48_000

/** What emulators get: 16-bit mono at 48 kHz. A stream keeps the format of its first packet. */
internal val forwardedFormat: AudioFormat = AudioFormat.newBuilder().setSamplingRate(SampleRate.toLong())
    .setChannels(AudioFormat.Channels.Mono).setFormat(AudioFormat.SampleFormat.AUD_FMT_S16).build()

/** Audio recorded from one of the computer's microphones, in [forwardedFormat]. */
internal interface MicrophoneInput : AutoCloseable {
    /** Blocks for the next few milliseconds of audio; null once the microphone is closed or gone. */
    fun read(): ByteArray?
}

/** One emulator's microphone, fed by Robomori. */
internal interface MicrophoneSink : AutoCloseable {
    /** False once the emulator has ended the stream, such as when it stopped. */
    val open: Boolean
    /** Sends audio in [forwardedFormat], or drops it while the emulator isn't taking any. */
    fun offer(audio: ByteArray)
}

/**
 * Lets emulators hear a chosen computer microphone. An emulator only hears the computer's default input, so Robomori
 * records the chosen microphone itself and streams it into each emulator's microphone.
 *
 * The emulator (37.2) crashes when a stream's first audio arrives while no app on it is recording, and garbles every
 * recording after the first one when audio is streamed in real time. So each emulator gets a single stream in the
 * default mode, opened only after two checks in a row found an app recording, and kept until the emulator stops or the
 * choice goes back to the default input. The microphone opens as soon as one check finds an app recording and closes
 * when none does; audio goes only to emulators with an app recording.
 *
 * [recording] answers with a check made after it was called. [prepare] gets the emulators that may need audio on every
 * round, to get ready ahead of a recording. An emulator that ends a stream while an app records, such as one that
 * can't take audio this way, gets no new one until that recording ends.
 */
internal class MicrophoneForwarder(
    private val recording: suspend (serial: String) -> Boolean,
    private val openSink: (serial: String) -> MicrophoneSink?,
    private val openInput: (microphone: String) -> MicrophoneInput?,
    private val prepare: (serials: Set<String>) -> Unit = {},
    private val interval: Duration = 20.milliseconds,
    /** How often to look again while no emulator needs the microphone. */
    private val idleInterval: Duration = 250.milliseconds,
) {
    private class Capture(val microphone: String, val input: MicrophoneInput, val job: Job) {
        fun close() { input.close(); job.cancel() }
    }

    /** Runs until cancelled. [microphone] is the chosen attached microphone, or null for the default input. */
    suspend fun run(microphone: suspend () -> String?, serials: () -> Collection<String>) = coroutineScope {
        val sinks = ConcurrentHashMap<String, MicrophoneSink>()
        val listening = AtomicReference(emptySet<String>())
        var recordingBefore = emptySet<String>()
        var refused = emptySet<String>()
        var capture: Capture? = null
        suspend fun recordingNow(serials: Collection<String>) = serials.map { serial ->
            async(Dispatchers.IO) { serial.takeIf { runCatching { recording(serial) }.getOrDefault(false) } }
        }.awaitAll().filterNotNull().toSet()
        try {
            while (true) {
                val chosen = microphone()
                val targets = if (chosen == null) emptySet() else serials().toSet()
                val ended = sinks.filterValues { !it.open }.keys
                sinks.entries.removeIf { (serial, sink) -> (serial !in targets || !sink.open).also { if (it) sink.close() } }
                prepare(targets)
                val recordingNow = recordingNow(targets)
                refused = (refused + ended) intersect recordingNow
                // Recording on two checks in a row, or already fed by a stream.
                val active = recordingNow intersect (recordingBefore + sinks.keys)
                recordingBefore = recordingNow
                val wanted = chosen.takeIf { recordingNow.isNotEmpty() }
                var opened = false
                if (capture?.microphone != wanted || capture?.job?.isActive == false) {
                    capture?.close()
                    capture = wanted?.let { name ->
                        opened = true
                        withContext(Dispatchers.IO) { runCatching { openInput(name) }.getOrNull() }?.let { input ->
                            Capture(name, input, launch(Dispatchers.IO) {
                                while (isActive) {
                                    val audio = input.read() ?: break
                                    listening.get().forEach { sinks[it]?.offer(audio) }
                                }
                            })
                        }
                    }
                }
                // A stream replaces what the emulator hears, so it opens once the microphone is ready. Opening the
                // microphone can take a while, such as when macOS asks for access, and the app may have stopped since.
                val unfed = active.filterNot { sinks.containsKey(it) || it in refused }
                if (capture != null && unfed.isNotEmpty()) withContext(Dispatchers.IO) {
                    (if (opened) recordingNow(unfed) else unfed).forEach { serial ->
                        runCatching { openSink(serial) }.getOrNull()?.let { sinks[serial] = it }
                    }
                }
                listening.set(active intersect sinks.keys)
                delay(if (targets.isEmpty()) idleInterval else interval)
            }
        } finally {
            capture?.close()
            sinks.values.forEach { it.close() }
            prepare(emptySet())
        }
    }
}

/**
 * Keeps a recording check running on each emulator, so the forwarder learns within about 0.1 s that an app records.
 * [watch] runs one emulator's checks until it's cancelled, telling each check's answer.
 */
internal class RecordingWatches(private val restartDelay: Duration = 1.seconds,
                                private val watch: (serial: String, cancelled: AtomicBoolean, onCheck: (Boolean) -> Unit) -> Unit) {
    private class Watch {
        val cancelled = AtomicBoolean()
        val checks = Channel<Boolean>(Channel.CONFLATED)
        @Volatile var ended: TimeSource.Monotonic.ValueTimeMark? = null
    }
    private val watches = ConcurrentHashMap<String, Watch>()

    /** Watches exactly [serials]. A watch that ended, such as when adb lost the emulator, starts again after [restartDelay]. */
    fun keep(serials: Set<String>) {
        watches.entries.removeIf { (serial, watch) -> (serial !in serials).also { if (it) watch.cancelled.set(true) } }
        serials.filter { serial -> watches[serial].let { it == null || (it.ended?.elapsedNow() ?: Duration.ZERO) > restartDelay } }.forEach { serial ->
            val started = Watch().also { watches[serial] = it }
            thread(isDaemon = true, name = "recording-watch") {
                runCatching { watch(serial, started.cancelled) { started.checks.trySend(it) } }
                started.ended = TimeSource.Monotonic.markNow()
            }
        }
    }

    /** The answer of the next check, made after this call; false when none comes within a second. */
    suspend fun next(serial: String): Boolean {
        val watch = watches[serial]?.takeIf { it.ended == null } ?: return false
        watch.checks.tryReceive()
        return withTimeoutOrNull(1.seconds) { watch.checks.receive() } ?: false
    }
}

/** One connection per running emulator, made ahead of time so a stream starts as soon as an app records. */
internal class EmulatorConnections(private val discover: (String) -> EmulatorEndpoint? = { EmulatorEndpoint.discover(it) }) {
    private val channels = ConcurrentHashMap<String, ManagedChannel>()

    /** Connects to exactly [serials], replacing connections that failed, such as after the emulator restarted. */
    fun keep(serials: Set<String>) {
        channels.entries.removeIf { (serial, channel) ->
            (serial !in serials || channel.getState(false) in setOf(ConnectivityState.TRANSIENT_FAILURE, ConnectivityState.SHUTDOWN))
                .also { if (it) channel.shutdown() }
        }
        (serials - channels.keys).forEach { serial ->
            discover(serial)?.channel()?.also { it.getState(true) }?.let { channels[serial] = it }
        }
    }

    fun sink(serial: String): MicrophoneSink? = channels[serial]?.let(::EmulatorMicrophoneSink)
}

/** A stream into one emulator's microphone over [channel], which stays open after the stream ends. */
internal class EmulatorMicrophoneSink(channel: ManagedChannel) : MicrophoneSink {
    @Volatile override var open = true; private set
    private val requests = ClientCalls.asyncClientStreamingCall(channel.newCall(injectAudioMethod, CallOptions.DEFAULT),
        object : StreamObserver<Empty> {
            override fun onNext(value: Empty) {}
            override fun onError(t: Throwable) { open = false }
            override fun onCompleted() { open = false }
        }) as ClientCallStreamObserver<AudioPacket>

    init {
        // The emulator sets the stream up on its first packet and crashes if no app records by then. The stream opens
        // right after recording was seen, so the first packet is 10 ms of silence sent now.
        send(ByteArray(SampleRate / 100 * 2))
    }

    @Synchronized override fun offer(audio: ByteArray) {
        // A backed-up stream means the emulator stopped reading; queued audio would play late, so drop it.
        if (open && requests.isReady) send(audio)
    }

    private fun send(audio: ByteArray) = requests.onNext(AudioPacket.newBuilder().setFormat(forwardedFormat)
        .setTimestamp(System.currentTimeMillis() * 1000).setAudio(ByteString.copyFrom(audio)).build())

    @Synchronized override fun close() {
        if (open) runCatching { requests.onCompleted() }
        open = false
    }
}

internal class JavaSoundMicrophone private constructor(private val line: TargetDataLine) : MicrophoneInput {
    // 10 ms of 16-bit mono audio.
    private val chunk = ByteArray(SampleRate / 100 * 2)

    override fun read(): ByteArray? {
        val read = line.read(chunk, 0, chunk.size)
        return if (read <= 0 || !line.isOpen) null else chunk.copyOf(read)
    }

    override fun close() = line.close()

    companion object {
        fun open(name: String): JavaSoundMicrophone? {
            val mixer = AudioSystem.getMixerInfo().firstOrNull { it.name == name }?.let(AudioSystem::getMixer) ?: return null
            val format = javax.sound.sampled.AudioFormat(SampleRate.toFloat(), 16, 1, true, false)
            val info = DataLine.Info(TargetDataLine::class.java, format)
            if (!mixer.isLineSupported(info)) return null
            val line = mixer.getLine(info) as TargetDataLine
            line.open(format, SampleRate / 20 * 2)
            line.start()
            return JavaSoundMicrophone(line)
        }
    }
}
