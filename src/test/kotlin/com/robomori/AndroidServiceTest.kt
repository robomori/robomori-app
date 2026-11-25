package com.robomori

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.*

class FakeAndroid(val root:File) : CommandExecutor {
    val sdk=File(root,"sdk");val avds=File(root,"avds")
    // Iterated by tests while the app's background work still adds to it.
    val commands=java.util.concurrent.CopyOnWriteArrayList<Command>()
    val active=linkedMapOf<String,String>()
    var additionalImages = ""
    var installFailure: String? = null
    init {
        Toolchain(sdk).tools.values.map { it.relativeTo(sdk).path }.forEach { path -> File(sdk,path).apply{parentFile.mkdirs();writeText("#!/bin/sh\n");setExecutable(true)} }
        avds.mkdirs()
    }
    val settings get()=AppSettings(sdk.path,avds.path)
    fun device(name:String,api:String="36",profile:String="pixel_9"):Device {
        val dir=File(avds,"$name.avd").apply{mkdirs()}
        writeIni(File(avds,"$name.ini"),mapOf("path" to dir.path,"target" to "android-$api"))
        writeIni(File(dir,"config.ini"),mapOf("avd.ini.displayname" to name.replace('_',' '),"hw.device.name" to profile,"image.sysdir.1" to "system-images/android-$api/google_apis/arm64-v8a/"))
        File(dir,"userdata-qemu.img").writeText("fixture-data")
        return AndroidService(settings,this).devices().first{it.name==name}
    }
    override fun run(command:Command,cancelled:AtomicBoolean,output:(String)->Unit):String {
        commands+=command
        if(command.args==listOf("--list"))return """
            Installed packages:
            system-images;android-36;google_apis;arm64-v8a | 1 | Google APIs ARM 64 System Image
            Available Packages:
            system-images;android-37;google_apis_playstore;arm64-v8a | 2 | Google Play ARM 64 System Image
            system-images;android-36;google_apis;arm64-v8a | 1 | Google APIs ARM 64 System Image
            system-images;android-35;android-tv;x86_64 | 1 | Android TV System Image
        """.trimIndent()+additionalImages
        if(command.args==listOf("list","device"))return "id: 1 or \"pixel_9\"\n    Name: Pixel 9\n\nid: 2 or \"pixel_9_pro\"\n    Name: Pixel 9 Pro\n"
        if(command.args==listOf("devices"))return "List of devices attached\n"+active.entries.joinToString("\n"){"${it.value}\tdevice"}
        if(command.args.contains("name")&&command.args.contains("emu"))return (active.entries.firstOrNull{it.value==command.args[1]}?.key ?: "")+"\nOK\n"
        if(command.args.takeLast(2)==listOf("getprop","sys.boot_completed"))return "1\n"
        if(command.args.contains("kill")){active.entries.removeIf{it.value==command.args[1]};return "OK"}
        if(command.detached&&FORWARD_MICROPHONE_ARGUMENT in command.args)return ""
        if(command.detached){active[command.args[1]]="emulator-5554";return ""}
        if(command.args.firstOrNull()=="--install" && installFailure != null) {
            val log=(1..250).joinToString("\n") { "[$it%] Downloading and unpacking system.img" }+"\n"+installFailure+"\nEND OF ACTIVITY\n"
            output(log)
            throw IllegalStateException(androidCommandError(log,1))
        }
        if(command.args.take(2)==listOf("create","avd")) {
            val name=command.args[command.args.indexOf("-n")+1]
            val profile=command.args.getOrNull(command.args.indexOf("-d")+1)?.takeIf { "-d" in command.args } ?: "pixel_9"
            val created=device(name,profile=profile)
            val image=command.args[command.args.indexOf("-k")+1].replace(';','/')+"/"
            writeIni(File(created.directory,"config.ini"),created.config + ("image.sysdir.1" to image))
            output("Created $name\n");return "Created $name"
        }
        if(command.args.take(2)==listOf("delete","avd")) {
            val name=command.args.last();File(avds,"$name.avd").deleteRecursively();File(avds,"$name.ini").delete();return "Deleted $name"
        }
        output("Download complete.\n");return ""
    }
}
class AndroidServiceTest {
    @TempDir lateinit var root:File
    @Test fun `a long-running command's output is passed on without being kept`() {
        val javaCommand = ProcessHandle.current().info().command().get()
        val seen = StringBuilder()
        val result = ProcessExecutor().run(Command(File(javaCommand), listOf("-version"), streamed = true), java.util.concurrent.atomic.AtomicBoolean()) { seen.append(it) }
        assertEquals("", result)
        assertTrue("version" in seen.toString())
    }

    @Test fun `microphone checks are read from one adb shell, also when its output arrives in pieces`() {
        val fake = FakeAndroid(root)
        val idle = "1. Port ID: 21; Session ID: 33; uid 10123; State: Active\n"
        val recording = idle + "1. Port ID: 37; Session ID: 89; uid 2000; State: Active\nSource: 1; Flags: 00000000; is soundtrigger: 0\n"
        val output = idle + "--robomori-check--\n" + recording + "--robomori-check--\n" + idle + "--robomori-check--\n"
        val adb = CommandExecutor { command, _, onOutput ->
            assertTrue(command.args.last().startsWith("while :; do dumpsys media.audio_policy"))
            output.chunked(7).forEach(onOutput); ""
        }
        val checks = mutableListOf<Boolean>()
        AndroidService(fake.settings, adb).watchMicrophoneUse("emulator-5554", java.util.concurrent.atomic.AtomicBoolean()) { checks += it }
        assertEquals(listOf(false, true, false), checks)
    }

    @Test fun `stopping all emulators kills every one adb lists`() {
        val fake = FakeAndroid(root)
        fake.active["Phone"] = "emulator-5554"
        fake.active["Tablet"] = "emulator-5556"
        AndroidService(fake.settings, fake).stopAll()
        assertEquals(emptyMap(), fake.active)
    }

    @Test fun `catalog retains installed state and filters device types`() {
        val fake=FakeAndroid(root);val images=AndroidService(fake.settings,fake).images()
        assertEquals(3,images.size);assertTrue(images.first{it.api=="36"}.installed)
        assertEquals(1,images.count{DeviceType.TV.accepts(it.tag)})
        assertEquals(2,images.count{DeviceType.PHONE.accepts(it.tag)})
    }
    @Test fun `legacy creation enables the hardware keyboard`() {
        val fake = FakeAndroid(root)
        val service = AndroidService(fake.settings, fake)
        val image = service.images().first()
        service.create(CreateSpec("Hardware_Keyboard", image, "pixel_9", 4096, 32, null, false), AtomicBoolean()) {}
        assertEquals("yes", readIni(File(fake.avds, "Hardware_Keyboard.avd/config.ini"))["hw.keyboard"])
    }

    @Test fun `duplicating an emulator enables a previously disabled hardware keyboard`() {
        val fake = FakeAndroid(root)
        val original = fake.device("Original")
        writeIni(File(original.directory, "config.ini"), original.config + ("hw.keyboard" to "no"))
        val clone = AndroidService(fake.settings, fake).duplicate(original)
        assertEquals("yes", readIni(File(fake.avds, "$clone.avd/config.ini"))["hw.keyboard"])
    }

    @Test fun `duplicating a software rendered emulator gives the copy hardware graphics`() {
        val fake = FakeAndroid(root)
        val original = fake.device("Original")
        writeIni(File(original.directory, "config.ini"), original.config + ("hw.gpu.mode" to "swiftshader"))

        val clone = AndroidService(fake.settings, fake).duplicate(original)

        assertEquals("host", readIni(File(fake.avds, "$clone.avd/config.ini"))["hw.gpu.mode"])
    }

    @Test fun `launch draws with the chosen graphics without changing the emulator's configuration`() {
        val fake = FakeAndroid(root)
        val device = fake.device("Pixel")
        val service = AndroidService(fake.settings, fake)
        val config = File(device.directory, "config.ini").readBytes()

        service.launch(service.devices().single())
        var args = fake.commands.last { it.detached }.args
        assertEquals("host", args[args.indexOf("-gpu") + 1])

        service.launch(service.devices().single(), graphics = EmulatorGraphics.Software)
        args = fake.commands.last { it.detached }.args
        assertEquals("swiftshader_indirect", args[args.indexOf("-gpu") + 1])
        assertContentEquals(config, File(device.directory, "config.ini").readBytes())
    }

    @Test fun `the WebView crash from missing OpenGL ES 3_1 is recognised`() {
        assertTrue(isWebViewGraphicsCrash("11-16 20:17:32.491  2962  3707 F chromium: [FATAL:ui/gl/gl_version_info.cc:73] " +
            "Chrome runs only on top of OpenGL ES through either ANGLE or native: VERSION = "))
        assertFalse(isWebViewGraphicsCrash("11-16 20:17:32.491  2962  3707 F chromium: [FATAL:base/check.cc:1] Check failed"))
    }

    @Test fun `the chosen graphics are remembered, hardware by default`() {
        val store = EmulatorGraphicsStore(File(root, "emulator-graphics.properties"))
        assertEquals(EmulatorGraphics.Hardware, store.load())
        store.save(EmulatorGraphics.Software)
        assertEquals(EmulatorGraphics.Software, store.load())
    }

    @Test fun `create uses selected locations and writes configuration without links`() {
        val fake=FakeAndroid(root);val service=AndroidService(fake.settings,fake)
        val image=service.images().first();service.create(CreateSpec("Test_Pixel",image,"pixel_9",4096,32,2,false),AtomicBoolean()){}
        val command=fake.commands.first{it.args.take(2)==listOf("create","avd")}
        assertEquals(fake.avds.path,command.env["ANDROID_AVD_HOME"])
        assertEquals(File(fake.avds,"Test_Pixel.avd").path,command.args[command.args.indexOf("-p")+1])
        val config=readIni(File(fake.avds,"Test_Pixel.avd/config.ini"))
        assertEquals("4096",config["hw.ramSize"]);assertEquals("32G",config["disk.dataPartition.size"]);assertEquals("no",config["showDeviceFrame"])
        assertEquals("yes", config["hw.gpu.enabled"])
        assertEquals("host", config["hw.gpu.mode"])
        assertFalse(service.devices().single().needsInitialSetup)
        assertFalse(Files.isSymbolicLink(File(fake.sdk,"system-images").toPath()))
    }
    @Test fun `tv creation and duplication use hardware graphics`() {
        val fake = FakeAndroid(root); val service = AndroidService(fake.settings, fake)
        val image = SystemImage("system-images;android-36;android-tv;arm64-v8a", "Android TV", true)
        service.create(CreateSpec("Android_TV", image, "tv_1080p"), AtomicBoolean()) {}
        val device = service.devices().single()
        assertEquals("host", readIni(File(device.directory, "config.ini"))["hw.gpu.mode"])

        val clone = service.duplicate(device)
        assertEquals("host", readIni(File(fake.avds, "$clone.avd/config.ini"))["hw.gpu.mode"])
    }
    @Test fun `new tv enables wifi after first boot`() = runBlocking {
        val fake = FakeAndroid(root)
        val service = AndroidService(fake.settings, fake)
        val image = SystemImage("system-images;android-36;android-tv;arm64-v8a", "Android TV", true)
        service.create(CreateSpec("Android_TV", image, "tv_1080p"), AtomicBoolean()) {}
        val state = AppState(this, initial = fake.settings, executor = fake)
        state.refresh()
        val device = state.devices.single()
        assertTrue(device.needsInitialSetup)

        state.launch(device)
        withTimeout(5_000) { while (state.starting.isNotEmpty()) delay(10) }

        assertFalse("-no-ethernet" in fake.commands.single { it.detached }.args)
        assertTrue(fake.commands.any { it.args.takeLast(4) == listOf("cmd", "wifi", "set-wifi-enabled", "enabled") })
        assertFalse(AndroidService(fake.settings, fake).devices().single().needsInitialSetup)
    }
    @Test fun `broken image link fails before running install`() {
        val fake=FakeAndroid(root);val service=AndroidService(fake.settings,fake)
        val link=File(fake.sdk,"system-images");val target=File(root,"offline/images")
        Files.createSymbolicLink(link.toPath(),target.toPath())
        val error=assertFailsWith<IllegalArgumentException>{service.create(CreateSpec("Test",SystemImage("system-images;android-36;google_apis;arm64-v8a","Android",false),"pixel_9"),AtomicBoolean()) {}}
        assertContains(error.message.orEmpty(),"unavailable drive");assertTrue(fake.commands.isEmpty());assertFalse(target.exists())
    }
    @Test fun `duplicate rename and delete preserve original device data`() {
        val fake=FakeAndroid(root);val original=fake.device("Pixel_Original");val service=AndroidService(fake.settings,fake)
        val clone=service.duplicate(original);assertEquals("Pixel_Original_Copy",clone)
        assertEquals("yes", readIni(File(fake.avds, "$clone.avd/config.ini"))["hw.gpu.enabled"])
        assertEquals("host", readIni(File(fake.avds, "$clone.avd/config.ini"))["hw.gpu.mode"])
        assertEquals("fixture-data",File(original.directory,"userdata-qemu.img").readText())
        val copy=service.devices().first{it.name==clone};service.rename(copy,"Renamed")
        assertFalse(File(fake.avds,"$clone.ini").exists());assertEquals(File(fake.avds,"Renamed.avd").path,readIni(File(fake.avds,"Renamed.ini"))["path"])
        service.delete(service.devices().first{it.name=="Renamed"});assertEquals(listOf("Pixel_Original"),service.devices().map{it.name})
    }
    @Test fun `settings round trip independent of legacy defaults`() {
        val store=SettingsStore(File(root,"prefs/settings.properties"));val settings=AppSettings("/tmp/My SDK","/tmp/My Devices")
        store.save(settings);assertEquals(settings,store.load())
    }
    @Test fun `command failure reports disk exhaustion without repeating progress output`() {
        val streamed=StringBuilder()
        val failure=assertFailsWith<IllegalStateException> {
            ProcessExecutor().run(fixtureCommand("fail"),AtomicBoolean()) { streamed.append(it) }
        }
        assertContains(streamed.toString(),"55%")
        assertContains(failure.message.orEmpty(),"Not enough disk space")
        assertFalse(failure.message.orEmpty().contains("55%"))
    }
    @Test fun `command failure keeps the relevant SDK error`() {
        assertEquals("Error: Package not found", androidCommandError("Loading repository\r[===] 10% fetching\nError: Package not found\n",1))
    }
    @Test fun `unavailable device directory is reported and cannot launch`() {
        val fake=FakeAndroid(root);val device=fake.device("Pixel");val service=AndroidService(fake.settings,fake)
        device.directory.deleteRecursively();assertTrue(service.devices().single().missingStorage)
        assertFailsWith<IllegalArgumentException>{service.launch(service.devices().single())};assertTrue(fake.commands.isEmpty())
    }

    @Test fun `emulator list ignores the hidden metadata files macOS writes on external drives`() {
        val fake = FakeAndroid(root)
        fake.device("Comet_check")
        File(fake.avds, "._Comet_check.ini").writeBytes(byteArrayOf(0, 5, 22, 7, 0, 2, 0, 0))
        assertEquals(listOf("Comet_check"), AndroidService(fake.settings, fake).devices().map { it.name })
    }
}
