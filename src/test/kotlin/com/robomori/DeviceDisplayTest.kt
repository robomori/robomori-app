package com.robomori

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class DeviceDisplayTest {
    @TempDir lateinit var root:File

    @Test fun `launch never rewrites the existing emulator configuration`() {
        val fake = FakeAndroid(root)
        val device = fake.device("Configured").let { it.copy(config = it.config + mapOf(
            "hw.keyboard" to "no", "hw.gpu.enabled" to "no", "hw.gpu.mode" to "swiftshader"
        )) }
        val config = File(device.directory, "config.ini")
        writeIni(config, device.config)
        config.appendText("# Keep custom configuration formatting.\n")
        config.setLastModified(1234567890000L)
        val originalBytes = config.readBytes()
        val originalModified = config.lastModified()

        AndroidService(fake.settings, fake).launch(device)

        assertContentEquals(originalBytes, config.readBytes())
        assertEquals(originalModified, config.lastModified())
    }

    @Test fun `disabled hardware keyboard does not prevent quick boot`() {
        val fake = FakeAndroid(root)
        val device = fake.device("Keyboard").let { it.copy(config = it.config + ("hw.keyboard" to "no")) }
        AndroidService(fake.settings, fake).launch(device)
        assertFalse(fake.commands.single { it.detached }.args.any { it.startsWith("-no-snapshot") })
    }
    @Test fun `screen coordinates scale and clamp to the current display`() {
        assertEquals(540 to 1200,screenPoint(150f,300f,300,600,1080,2400))
        assertEquals(1079 to 0,screenPoint(400f,-10f,300,600,1080,2400))
        assertEquals(960 to 540,screenPoint(200f,100f,400,200,1920,1080))
    }
    @Test fun `cover screen taps use the inner framebuffer coordinate range`() {
        val device = FakeAndroid(root).device("Fold", profile = "pixel_10_pro_fold").let {
            it.copy(config = it.config + mapOf("hw.lcd.width" to "2076", "hw.lcd.height" to "2152"))
        }
        val (width, height) = emulatorTouchSize(device, 1080, 2364)

        assertEquals(1268 to 1775, screenPoint(660f, 1950f, 1080, 2364, width, height))
    }

    @Test fun `ordinary emulator taps use the current screen dimensions`() {
        val device = FakeAndroid(root).device("Phone").let {
            it.copy(config = it.config + mapOf("hw.lcd.width" to "1080", "hw.lcd.height" to "2400"))
        }

        assertEquals(720 to 1600, emulatorTouchSize(device, 720, 1600))
    }
    @Test fun `all streamed orientations address the same natural touch surface`() {
        for (rotation in 0..3) {
            val imageWidth = if (rotation % 2 == 0) 1080 else 2424
            val imageHeight = if (rotation % 2 == 0) 2424 else 1080
            val (width, height) = nativeScreenSize(imageWidth, imageHeight, rotation)
            assertEquals(1080 to 2424, width to height)
            assertEquals(270 to 1818, screenPoint(75f, 450f, 300, 600, width, height))
            assertEquals(1079 to 2423, screenPoint(300f, 600f, 300, 600, width, height))
        }
    }
    @Test fun `launch uses a headless emulator with quick boot enabled`() {
        val fake=FakeAndroid(root); val device=fake.device("Preview")
        AndroidService(fake.settings,fake).launch(device)
        val args=fake.commands.single { it.detached }.args
        assertContains(args,"-no-window")
        assertFalse(args.any { it.startsWith("-no-snapshot") })
        assertEquals("host", args[args.indexOf("-gpu") + 1])
        assertContains(args, "-grpc-use-token")
        assertTrue(args[args.indexOf("-grpc") + 1].toInt() in 1..65535)
        assertFalse("-no-ethernet" in args)
        assertFalse("-wipe-data" in args)
    }

    @Test fun `tv launch leaves graphics configuration and Ethernet available`() {
        val fake = FakeAndroid(root)
        val device = fake.device("Android_TV").copy(config = mapOf("image.sysdir.1" to "system-images/android-36/android-tv/arm64-v8a/"))

        AndroidService(fake.settings, fake).launch(device)

        val args = fake.commands.single { it.detached }.args
        assertEquals("host", args[args.indexOf("-gpu") + 1])
        assertFalse(args.any { it.startsWith("-no-snapshot") })
        assertFalse("-no-ethernet" in args)
    }

    @Test fun `wear launch does not override graphics configuration`() {
        val fake = FakeAndroid(root)
        val device = fake.device("Watch").copy(config = mapOf("image.sysdir.1" to "system-images/android-37/android-wear-signed/arm64-v8a/"))

        AndroidService(fake.settings, fake).launch(device)

        val args = fake.commands.single { it.detached }.args
        assertEquals("host", args[args.indexOf("-gpu") + 1])
    }

    @Test fun `launch does not override a software renderer`() {
        val fake = FakeAndroid(root)
        val device = fake.device("Phone").copy(config = mapOf(
            "image.sysdir.1" to "system-images/android-37/google_apis_playstore/arm64-v8a/",
            "hw.gpu.mode" to "swiftshader"
        ))

        AndroidService(fake.settings, fake).launch(device)

        val args = fake.commands.single { it.detached }.args
        assertEquals("host", args[args.indexOf("-gpu") + 1])
    }

    @Test fun `software renderer keeps quick boot enabled`() {
        val fake = FakeAndroid(root)
        val device = fake.device("Phone").let { it.copy(config = it.config + ("hw.gpu.mode" to "swiftshader")) }
        writeIni(File(device.directory, "config.ini"), device.config)

        AndroidService(fake.settings, fake).launch(device)

        val args = fake.commands.single { it.detached }.args
        assertFalse(args.any { it.startsWith("-no-snapshot") })
    }

    @Test fun `an emulator's own graphics setting is left as it is`() {
        val fake = FakeAndroid(root)
        val device = fake.device("Phone").let { it.copy(config = it.config + ("hw.gpu.mode" to "swiftshader")) }
        writeIni(File(device.directory, "config.ini"), device.config)

        AndroidService(fake.settings, fake).launch(device)

        assertEquals("swiftshader", readIni(File(device.directory, "config.ini"))["hw.gpu.mode"])
    }

    @Test fun `host rendered emulator keeps quick boot enabled`() {
        val fake = FakeAndroid(root)
        val device = fake.device("Phone").let { it.copy(config = it.config + ("hw.gpu.mode" to "host")) }

        AndroidService(fake.settings, fake).launch(device)

        assertFalse(fake.commands.single { it.detached }.args.any { it.startsWith("-no-snapshot") })
    }

    @Test fun `cold boot skips snapshot loading but keeps snapshot saving enabled`() {
        val fake = FakeAndroid(root)
        val device = fake.device("Preview")

        AndroidService(fake.settings, fake).launch(device, coldBoot = true)

        val args = fake.commands.single { it.detached }.args
        assertContains(args, "-no-snapshot-load")
        assertFalse("-no-snapshot" in args)
        assertFalse("-no-snapshot-save" in args)
    }

    @Test fun `wipe data starts a fresh emulator without loading its snapshot`() {
        val fake = FakeAndroid(root)
        val device = fake.device("Preview")

        AndroidService(fake.settings, fake).launch(device, wipeData = true)

        val args = fake.commands.single { it.detached }.args
        assertContains(args, "-wipe-data")
        assertContains(args, "-no-snapshot-load")
    }
}
