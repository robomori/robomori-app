package com.robomori

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NetworkCoverageTest {
    private val abi = nativeImageAbi()!!

    @Test fun `reads every app where Robomori's certificate is among the system's`() {
        assertEquals(NetworkCoverage.AllApps, networkCoverage(NetworkRouting.On(CertificateTrust.System), canCreateGoogleApisCopy = true))
    }

    @Test fun `reads debug apps where the device can't be rooted, offering a Google APIs copy where there can be one`() {
        assertEquals(NetworkCoverage.DebugAppsOnly(canCreateGoogleApisCopy = true),
            networkCoverage(NetworkRouting.On(CertificateTrust.AppConfiguration), canCreateGoogleApisCopy = true))
        assertEquals(NetworkCoverage.DebugAppsOnly(canCreateGoogleApisCopy = false),
            networkCoverage(NetworkRouting.On(CertificateTrust.AppConfiguration), canCreateGoogleApisCopy = false))
    }

    @Test fun `says nothing until recording has started`() {
        assertNull(networkCoverage(NetworkRouting.Starting, true))
        assertNull(networkCoverage(NetworkRouting.Off, true))
        assertNull(networkCoverage(NetworkRouting.Failed(IllegalStateException()), true))
    }

    @Test fun `copies a Google Play emulator with the same profile and Android version, on Google APIs`() {
        val device = emulator("Pixel_10", "Pixel 10", "google_apis_playstore")

        val copy = googleApisCopy(device, listOf("Pixel_10", "Pixel 10"))!!

        assertEquals("system-images;android-37.0;google_apis;$abi", copy.image.path)
        assertEquals("pixel-10", copy.profile)
        assertEquals("Pixel 10 (Google APIs)", copy.displayName)
    }

    @Test fun `names the copy apart from one made before`() {
        val device = emulator("Pixel_10", "Pixel 10", "google_apis_playstore")

        val copy = googleApisCopy(device, listOf("Pixel 10", "Pixel 10 (Google APIs)"))!!

        assertEquals("Pixel 10 (Google APIs) 2", copy.displayName)
    }

    @Test fun `has no copy for an emulator that already has Google APIs, or isn't from a profile`() {
        assertNull(googleApisCopy(emulator("Rooted", "Rooted", "google_apis"), emptyList()))
        assertNull(googleApisCopy(emulator("Old", "Old", "google_apis_playstore", profile = null), emptyList()))
    }

    @Test fun `records a device plugged in over USB, but not one on Wi-Fi only`() {
        assertEquals(PhoneNetworkAccess.Usb("R5CT1234"), phoneNetworkAccess(ConnectedDevice("R5CT1234", "Pixel 9", "device")))
        assertEquals(PhoneNetworkAccess.WifiOnly, phoneNetworkAccess(ConnectedDevice("192.168.1.2:5555", "Pixel 9", "device")))
        assertEquals(PhoneNetworkAccess.WifiOnly,
            phoneNetworkAccess(ConnectedDevice("adb-R5CT-abc._adb-tls-connect._tcp", "Pixel 9", "device")))
        assertEquals(PhoneNetworkAccess.Disconnected, phoneNetworkAccess(ConnectedDevice("R5CT1234", "Pixel 9", "unauthorized")))
    }

    private fun emulator(name: String, displayName: String, tag: String, profile: String? = "pixel-10") = Device(name, File("$name.avd"),
        buildMap {
            put("avd.ini.displayname", displayName)
            put("image.sysdir.1", "system-images/android-37.0/$tag/$abi/")
            profile?.let { put("robomori.profile", it) }
        }, 0)
}
