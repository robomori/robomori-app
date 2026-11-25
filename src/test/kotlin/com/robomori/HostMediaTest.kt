package com.robomori

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

class HostMediaTest {
    @TempDir lateinit var root: File

    private val webcamList = """
        List of web cameras connected to the computer:
         Camera 'FaceTime HD Camera' can be specified by label as 'webcam0' or by id as '1F06' and will use pixel format 'NV12'
         Camera 'Alex’s iPhone Camera' can be specified by label as 'webcam1' or by id as '233E' and will use pixel format 'NV12'
    """.trimIndent()
    private val cameras = parseHostCameras(webcamList)

    @Test fun `host cameras are read from the emulator's webcam list`() {
        assertEquals(listOf(HostCamera("1F06", "FaceTime HD Camera", "webcam0"), HostCamera("233E", "Alex’s iPhone Camera", "webcam1")), cameras)
        assertEquals(emptyList(), parseHostCameras("List of web cameras connected to the computer:\n"))
    }

    @Test fun `the microphone is named after the Mac's default input`() {
        val audio = """{"SPAudioDataType":[{"_items":[
            {"_name":"Alex’s iPhone Microphone","coreaudio_device_input":1},
            {"_name":"MacBook Pro Microphone","coreaudio_default_audio_input_device":"spaudio_yes","coreaudio_device_input":1},
            {"_name":"MacBook Pro Speakers","coreaudio_default_audio_output_device":"spaudio_yes"}]}]}"""
        assertEquals("MacBook Pro Microphone", defaultInputName(audio))
        assertNull(defaultInputName("""{"SPAudioDataType":[{"_items":[{"_name":"MacBook Pro Speakers"}]}]}"""))
        assertNull(defaultInputName("not json"))
    }

    @Test fun `by default both cameras show the computer's first camera`() {
        assertEquals(listOf("-allow-host-audio", "-camera-front", "webcam0", "-camera-back", "webcam0"),
            hostMediaArgs(emptyMap(), EmulatorMedia(), cameras))
    }

    @Test fun `front and back cameras show the cameras chosen for them`() {
        val media = EmulatorMedia(frontCamera = CameraSource.Host("233E"), backCamera = CameraSource.Host("1F06"))
        assertEquals(listOf("-allow-host-audio", "-camera-front", "webcam1", "-camera-back", "webcam0"),
            hostMediaArgs(emptyMap(), media, cameras))
    }

    @Test fun `a camera left emulated keeps the emulator's own`() {
        val media = EmulatorMedia(frontCamera = CameraSource.Emulated, backCamera = CameraSource.Host("1F06"))
        assertEquals(listOf("-allow-host-audio", "-camera-back", "webcam0"), hostMediaArgs(emptyMap(), media, cameras))
    }

    @Test fun `a chosen camera that is no longer attached falls back to the first one`() {
        assertEquals(listOf("-allow-host-audio", "-camera-front", "webcam0", "-camera-back", "webcam1"),
            hostMediaArgs(emptyMap(), EmulatorMedia(frontCamera = CameraSource.Host("gone"), backCamera = CameraSource.Host("233E")), cameras))
    }

    @Test fun `without any camera attached emulators keep their emulated cameras`() {
        assertEquals(listOf("-allow-host-audio"), hostMediaArgs(emptyMap(), EmulatorMedia(), emptyList()))
    }

    @Test fun `devices without cameras stay without cameras`() {
        val tv = mapOf("hw.camera.front" to "none", "hw.camera.back" to "none")
        assertEquals(listOf("-allow-host-audio"), hostMediaArgs(tv, EmulatorMedia(backCamera = CameraSource.Host("1F06")), cameras))
    }

    @Test fun `the choice is remembered between runs`() {
        val store = EmulatorMediaStore(File(root, "emulator-media.properties"))
        assertEquals(EmulatorMedia(), store.load())
        val media = EmulatorMedia(frontCamera = CameraSource.Emulated, backCamera = CameraSource.Host("233E"))
        store.save(media)
        assertEquals(media, store.load())
    }

    @Test fun `the camera chosen before front and back were separate carries over to both`() {
        val file = File(root, "emulator-media.properties").apply { writeText("camera=233E\n") }
        assertEquals(EmulatorMedia(frontCamera = CameraSource.Host("233E"), backCamera = CameraSource.Host("233E")), EmulatorMediaStore(file).load())
    }

    @Test fun `the chosen microphone is remembered and used while it's attached`() {
        val store = EmulatorMediaStore(File(root, "emulator-media.properties"))
        store.save(EmulatorMedia(frontCamera = CameraSource.Host("233E"), microphone = "MacBook Pro Microphone"))
        val media = store.load()
        assertEquals(EmulatorMedia(frontCamera = CameraSource.Host("233E"), microphone = "MacBook Pro Microphone"), media)
        assertEquals("MacBook Pro Microphone", media.microphone(listOf("AirPods Pro", "MacBook Pro Microphone")))
        assertNull(media.microphone(listOf("AirPods Pro")))
        assertNull(EmulatorMedia().microphone(listOf("AirPods Pro")))
    }

    @Test fun `running emulators switch microphones without a restart`() = runBlocking {
        val fake = FakeAndroid(root)
        val store = EmulatorMediaStore(File(root, "emulator-media.properties"))
        fake.device("Phone")
        val state = AppState(this, initial = fake.settings, executor = withWebcams(fake), mediaStore = store)
        state.refresh()
        state.refreshHostMedia().join()
        state.launch(state.devices.single())
        withTimeout(5_000) { while (state.starting.isNotEmpty()) delay(10) }
        state.changeMedia(EmulatorMedia(frontCamera = CameraSource.Host("233E")))

        state.changeMicrophone("MacBook Pro Microphone")

        assertEquals(EmulatorMedia(microphone = "MacBook Pro Microphone"), store.load())
        state.applyMediaChange(restartNow = false)
        assertEquals(EmulatorMedia(frontCamera = CameraSource.Host("233E"), microphone = "MacBook Pro Microphone"), store.load())
        state.changeMicrophone(null)
        assertEquals(EmulatorMedia(frontCamera = CameraSource.Host("233E")), store.load())
    }

    @Test fun `with no emulator running a change applies right away`() = runBlocking {
        val fake = FakeAndroid(root)
        val store = EmulatorMediaStore(File(root, "emulator-media.properties"))
        val state = AppState(this, initial = fake.settings, executor = withWebcams(fake), mediaStore = store)
        state.refreshHostMedia().join()
        state.changeMedia(EmulatorMedia(frontCamera = CameraSource.Host("233E")))
        assertNull(state.pendingMedia)
        assertEquals(EmulatorMedia(frontCamera = CameraSource.Host("233E")), store.load())
    }

    @Test fun `running emulators pick up a change on their next launch when asked`() = runBlocking {
        val fake = FakeAndroid(root)
        val state = runningPhone(fake)
        state.changeMedia(EmulatorMedia(frontCamera = CameraSource.Host("233E")))
        assertEquals(EmulatorMedia(), state.media)

        state.applyMediaChange(restartNow = false)

        assertEquals(EmulatorMedia(frontCamera = CameraSource.Host("233E")), state.media)
        assertFalse(fake.commands.any { it.args.contains("kill") })
    }

    @Test fun `running emulators restart with the new choice when asked`() = runBlocking {
        val fake = FakeAndroid(root)
        val state = runningPhone(fake)
        fake.commands.clear()
        state.changeMedia(EmulatorMedia(frontCamera = CameraSource.Host("233E")))

        state.applyMediaChange(restartNow = true)
        withTimeout(5_000) { while (fake.commands.none { it.detached } || state.starting.isNotEmpty()) delay(10) }

        assertTrue(fake.commands.any { it.args.contains("kill") })
        val args = fake.commands.single { it.detached }.args
        assertEquals("webcam1", args[args.indexOf("-camera-front") + 1])
        assertEquals(mapOf("Phone" to "emulator-5554"), state.running)
    }

    private suspend fun CoroutineScope.runningPhone(fake: FakeAndroid): AppState {
        fake.device("Phone")
        val state = AppState(this, initial = fake.settings, executor = withWebcams(fake))
        state.refresh()
        state.refreshHostMedia().join()
        state.launch(state.devices.single())
        withTimeout(5_000) { while (state.starting.isNotEmpty()) delay(10) }
        return state
    }

    @Test fun `picking the camera already in use changes nothing`() = runBlocking {
        val state = runningPhone(FakeAndroid(root))
        state.changeMedia(EmulatorMedia(frontCamera = CameraSource.Host("1F06"), backCamera = CameraSource.Host("1F06")))
        assertNull(state.pendingMedia)
    }

    private fun withWebcams(fake: FakeAndroid) = CommandExecutor { command, cancelled: AtomicBoolean, output ->
        if (command.args == listOf("-webcam-list")) webcamList else fake.run(command, cancelled, output)
    }

    @Test fun `launch uses the chosen camera and microphone`() {
        val fake = FakeAndroid(root)
        AndroidService(fake.settings, withWebcams(fake)).launch(fake.device("Phone"), media = EmulatorMedia(frontCamera = CameraSource.Host("233E"), backCamera = CameraSource.Host("1F06")))
        val args = fake.commands.single { it.detached }.args
        assertEquals("webcam1", args[args.indexOf("-camera-front") + 1])
        assertEquals("webcam0", args[args.indexOf("-camera-back") + 1])
        assertContains(args, "-allow-host-audio")
    }
}
