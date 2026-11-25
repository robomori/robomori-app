package com.robomori

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDate
import kotlin.test.assertEquals

class LaunchTrackingTest {
    @TempDir lateinit var root: File

    private class Recorder : Analytics {
        val events = mutableListOf<String>()
        override fun track(event: String, metadata: Map<String, String>) { events += event }
        override fun pageview(path: String) {}
    }

    @Test fun `the first launch ever and the first launch of each day are reported once`() {
        val directory = File(root, "Robomori")
        val analytics = Recorder()
        val monday = LocalDate.of(2026, 11, 2)

        trackLaunch(analytics, directory, monday)
        trackLaunch(analytics, directory, monday)
        trackLaunch(analytics, directory, monday.plusDays(1))

        assertEquals(listOf(
            FunnelEvent.FirstLaunch, FunnelEvent.ActiveDay, FunnelEvent.AppOpened,
            FunnelEvent.AppOpened,
            FunnelEvent.ActiveDay, FunnelEvent.AppOpened,
        ), analytics.events)
    }

    @Test fun `a new install is a first launch even though the code update launcher already wrote its folder`() {
        val directory = File(root, "Robomori").apply { File(this, "codepush/patches/1.6.1").mkdirs(); File(this, ".DS_Store").writeText("") }
        val analytics = Recorder()

        trackLaunch(analytics, directory, LocalDate.of(2026, 11, 2))

        assertEquals(listOf(FunnelEvent.FirstLaunch, FunnelEvent.ActiveDay, FunnelEvent.AppOpened), analytics.events)
    }

    @Test fun `people who used Robomori before this version are not counted as first launches`() {
        val directory = File(root, "Robomori").apply { mkdirs(); File(this, "settings.properties").writeText("") }
        val analytics = Recorder()

        trackLaunch(analytics, directory, LocalDate.of(2026, 11, 2))

        assertEquals(listOf(FunnelEvent.ActiveDay, FunnelEvent.AppOpened), analytics.events)
    }

    @Test fun `started emulators are tracked by form, Android version and hardware profile`() {
        val watch = Device("Watch", root, mapOf("hw.device.name" to "wearos_large_round",
            "image.sysdir.1" to "system-images/android-36/android-wear/arm64-v8a/"), 0)
        assertEquals(mapOf("type" to "wear", "android" to "36", "profile" to "wearos_large_round"), emulatorMetadata(watch))
        // Nothing unknown is sent as a value.
        assertEquals(mapOf("type" to "phone"), emulatorMetadata(Device("Bare", root, emptyMap(), 0)))
    }
}
