package com.robomori

import com.google.protobuf.Empty
import com.robomori.emulator.protocol.AudioFormat
import com.robomori.emulator.protocol.AudioPacket
import io.grpc.ServerServiceDefinition
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.stub.ServerCalls
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Test
import java.util.Collections
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

class MicrophoneForwarderTest {
    private class Sink(val serial: String) : MicrophoneSink {
        val audio = Collections.synchronizedList(mutableListOf<ByteArray>())
        @Volatile override var open = true
        @Volatile var closed = false
        override fun offer(audio: ByteArray) { if (open) this.audio += audio }
        override fun close() { open = false; closed = true }
    }

    inner class Input(val microphone: String) : MicrophoneInput {
        @Volatile var closed = false
        override fun read(): ByteArray? { Thread.sleep(2); return if (closed) null else byteArrayOf(1, 2) }
        override fun close() { closed = true }
    }

    private inner class Harness {
        val recording = Collections.synchronizedSet(mutableSetOf<String>())
        val sinks = Collections.synchronizedList(mutableListOf<Sink>())
        val inputs = Collections.synchronizedList(mutableListOf<Input>())
        @Volatile var microphone: String? = "MacBook Pro Microphone"
        @Volatile var serials = listOf("emulator-5554")
        val forwarder = MicrophoneForwarder(recording = { it in recording },
            openSink = { serial -> Sink(serial).also { sinks += it } },
            openInput = { name -> onOpenInput(); Input(name).also { inputs += it } }, interval = 10.milliseconds)
        /** Runs while the microphone opens, which can take a while, such as when macOS asks for access. */
        @Volatile var onOpenInput: () -> Unit = {}
        val openInputs get() = inputs.filterNot { it.closed }

        fun CoroutineScope.start() = launch { forwarder.run(microphone = { microphone }, serials = { serials }) }
    }

    private suspend fun until(condition: () -> Boolean) = withTimeout(5_000) { while (!condition()) delay(5) }

    @Test fun `no stream opens and the microphone stays off while nothing on the emulator records`() = runBlocking {
        val harness = Harness()
        val job = with(harness) { start() }
        delay(150)
        assertEquals(emptyList(), harness.sinks)
        assertEquals(emptyList(), harness.inputs)
        job.cancelAndJoin()
    }

    @Test fun `an app recording on the emulator hears the chosen microphone`() = runBlocking {
        val harness = Harness()
        val job = with(harness) { start() }
        harness.recording += "emulator-5554"
        until { harness.sinks.singleOrNull()?.audio?.isNotEmpty() == true }
        assertEquals("emulator-5554", harness.sinks.single().serial)
        assertEquals(listOf("MacBook Pro Microphone"), harness.inputs.map { it.microphone })
        job.cancelAndJoin()
        assertTrue(harness.sinks.single().closed)
        assertTrue(harness.inputs.single().closed)
    }

    @Test fun `the stream opens once the microphone is ready, so the emulator never hears silence instead`() = runBlocking {
        val harness = Harness()
        harness.onOpenInput = { Thread.sleep(100); assertEquals(emptyList(), harness.sinks) }
        val job = with(harness) { start() }
        harness.recording += "emulator-5554"
        until { harness.sinks.singleOrNull()?.audio?.isNotEmpty() == true }
        job.cancelAndJoin()
    }

    @Test fun `no stream opens when the app stops recording while the microphone opens`() = runBlocking {
        val harness = Harness()
        harness.onOpenInput = { harness.recording.clear() }
        val job = with(harness) { start() }
        harness.recording += "emulator-5554"
        until { harness.inputs.isNotEmpty() }
        delay(150)
        assertEquals(emptyList(), harness.sinks)
        job.cancelAndJoin()
    }

    @Test fun `the microphone opens as soon as an app records, and the stream once a second check confirms it`() = runBlocking {
        val harness = Harness()
        val checks = Channel<Boolean>(Channel.UNLIMITED)
        val forwarder = MicrophoneForwarder(recording = { checks.receive() },
            openSink = { serial -> Sink(serial).also { harness.sinks += it } },
            openInput = { name -> Input(name).also { harness.inputs += it } }, interval = 1.milliseconds)
        val job = launch { forwarder.run(microphone = { "MacBook Pro Microphone" }, serials = { listOf("emulator-5554") }) }
        checks.send(true)
        until { harness.inputs.isNotEmpty() }
        assertEquals(emptyList(), harness.sinks)
        checks.send(true)
        until { harness.sinks.isNotEmpty() }
        job.cancelAndJoin()
    }

    private class FakeWatch {
        @Volatile var recording = false
        @Volatile var checking = true
        @Volatile var dropped = false
        val starts = Collections.synchronizedList(mutableListOf<String>())
        val watches = RecordingWatches(restartDelay = 50.milliseconds) { serial, cancelled, onCheck ->
            starts += serial
            while (!cancelled.get() && !dropped) { if (checking) onCheck(recording); Thread.sleep(2) }
        }
    }

    @Test fun `a recording check answers with a check made after it was asked`() = runBlocking {
        val fake = FakeWatch()
        fake.recording = true
        fake.watches.keep(setOf("emulator-5554"))
        delay(30)
        fake.checking = false
        delay(20)
        fake.recording = false
        val answer = async { fake.watches.next("emulator-5554") }
        delay(10)
        fake.checking = true
        assertFalse(answer.await())
        assertFalse(fake.watches.next("emulator-5556"))
        fake.watches.keep(emptySet())
    }

    @Test fun `a recording check that adb dropped starts again after a pause`() = runBlocking {
        val fake = FakeWatch()
        fake.dropped = true
        fake.watches.keep(setOf("emulator-5554"))
        delay(10)
        fake.watches.keep(setOf("emulator-5554"))
        assertEquals(1, fake.starts.size)
        assertFalse(fake.watches.next("emulator-5554"))
        fake.dropped = false
        delay(80)
        fake.watches.keep(setOf("emulator-5554"))
        fake.recording = true
        assertTrue(fake.watches.next("emulator-5554"))
        assertEquals(2, fake.starts.size)
        fake.watches.keep(emptySet())
    }

    @Test fun `an emulator that refuses a stream gets no new one until the recording ends`() = runBlocking {
        val harness = Harness()
        val job = with(harness) { start() }
        harness.recording += "emulator-5554"
        until { harness.sinks.isNotEmpty() }
        harness.sinks.single().open = false
        delay(150)
        assertEquals(1, harness.sinks.size)

        harness.recording.clear()
        delay(50)
        harness.recording += "emulator-5554"
        until { harness.sinks.size == 2 }
        job.cancelAndJoin()
    }

    @Test fun `between recordings audio stops but the stream stays open for the next one`() = runBlocking {
        val harness = Harness()
        val job = with(harness) { start() }
        harness.recording += "emulator-5554"
        until { harness.sinks.singleOrNull()?.audio?.isNotEmpty() == true }

        harness.recording.clear()
        until { harness.openInputs.isEmpty() }
        val sink = harness.sinks.single()
        val sent = sink.audio.size
        delay(100)
        assertEquals(sent, sink.audio.size)
        assertFalse(sink.closed)

        harness.recording += "emulator-5554"
        until { sink.audio.size > sent }
        assertEquals(1, harness.sinks.size)
        job.cancelAndJoin()
    }

    @Test fun `going back to the default input hands emulators back to it`() = runBlocking {
        val harness = Harness()
        val job = with(harness) { start() }
        harness.recording += "emulator-5554"
        until { harness.sinks.singleOrNull()?.audio?.isNotEmpty() == true }

        harness.microphone = null
        until { harness.sinks.single().closed && harness.openInputs.isEmpty() }
        job.cancelAndJoin()
    }

    @Test fun `switching microphones mid-recording keeps the stream and records from the new one`() = runBlocking {
        val harness = Harness()
        val job = with(harness) { start() }
        harness.recording += "emulator-5554"
        // The emulator's stream opens after the microphone does, so wait for both before switching.
        until { harness.inputs.isNotEmpty() && harness.sinks.isNotEmpty() }

        harness.microphone = "AirPods Pro"
        until { harness.openInputs.map { it.microphone } == listOf("AirPods Pro") }
        assertEquals(1, harness.sinks.size)
        job.cancelAndJoin()
    }

    @Test fun `only emulators with an app recording get audio and a stopped one's stream closes`() = runBlocking {
        val harness = Harness()
        harness.serials = listOf("emulator-5554", "emulator-5556")
        val job = with(harness) { start() }
        harness.recording += "emulator-5556"
        until { harness.sinks.singleOrNull()?.audio?.isNotEmpty() == true }
        assertEquals("emulator-5556", harness.sinks.single().serial)

        harness.serials = listOf("emulator-5554")
        until { harness.sinks.single().closed }
        job.cancelAndJoin()
    }

    @Test fun `recording apps are counted from the audio policy dump`() {
        val playing = """
             AudioTrack Clients:
              1. Port ID: 21; Session ID: 33; uid 10123; State: Active
                 AUDIO_FORMAT_PCM_16_BIT; 48000; Channel mask: 0x3
                 Attributes: { Content type: AUDIO_CONTENT_TYPE_MUSIC Usage: AUDIO_USAGE_MEDIA Source: AUDIO_SOURCE_DEFAULT }
                 Stream: 3; Flags: 00000000; Refcount: 1
        """.trimIndent()
        val recording = """
             Inputs (1):
              1. Port ID: 36; 0x0000 (AUDIO_INPUT_FLAG_NONE); I/O handle: 102
                 AudioRecord Clients (1):
                  1. Port ID: 37; Session ID: 89; uid 2000; State: Active
                     Attributes: { Content type: AUDIO_CONTENT_TYPE_UNKNOWN Usage: AUDIO_USAGE_UNKNOWN Source: AUDIO_SOURCE_MIC }
                     Source: 1; Flags: 00000000; is soundtrigger: 0
        """.trimIndent()
        assertEquals(1, recordingClients(recording))
        assertEquals(1, recordingClients(playing + "\n" + recording))
        assertEquals(0, recordingClients(playing))
        assertEquals(0, recordingClients(recording.replace("State: Active", "State: Silenced")))
        assertEquals(0, recordingClients(""))
    }

    @Test fun `recording apps are counted on Android 12 and earlier`() {
        val recording = """
            Client 1:
             - Port Id: 37 Session Id: 89 UID: 2000
             - State: Active
             - Source: 1 flags: 00000000
            Client 2:
             - Port Id: 38 Session Id: 90 UID: 10123
             - State: Inactive
             - Source: 6 flags: 00000000
        """.trimIndent()
        assertEquals(1, recordingClients(recording))
        assertEquals(0, recordingClients(recording.replace("State: Active", "State: Inactive")))
    }

    @Test fun `a stream starts with silence so the emulator sets it up while the app is still recording`() {
        val packets = LinkedBlockingQueue<AudioPacket>()
        val service = ServerServiceDefinition.builder("android.emulation.control.EmulatorController")
            .addMethod(injectAudioMethod, ServerCalls.asyncClientStreamingCall<AudioPacket, Empty> { _ ->
                object : StreamObserver<AudioPacket> {
                    override fun onNext(value: AudioPacket) { packets += value }
                    override fun onError(t: Throwable) {}
                    override fun onCompleted() {}
                }
            }).build()
        val server = NettyServerBuilder.forAddress(InetSocketAddress("localhost", 0)).addService(service).build().start()
        val channel = EmulatorEndpoint(server.port, "token").channel()
        val sink = EmulatorMicrophoneSink(channel)
        try {
            val first = assertNotNull(packets.poll(5, TimeUnit.SECONDS))
            assertEquals(48_000, first.format.samplingRate)
            assertEquals(AudioFormat.Channels.Mono, first.format.channels)
            assertEquals(AudioFormat.SampleFormat.AUD_FMT_S16, first.format.format)
            assertTrue(first.audio.toByteArray().all { it == 0.toByte() })
            assertEquals(960, first.audio.size())

            sink.offer(byteArrayOf(1, 2))
            assertContentEquals(byteArrayOf(1, 2), assertNotNull(packets.poll(5, TimeUnit.SECONDS)).audio.toByteArray())
        } finally {
            sink.close()
            channel.shutdownNow()
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
        }
    }
}
