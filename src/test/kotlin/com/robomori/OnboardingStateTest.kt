package com.robomori

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OnboardingStateTest {
    private val phone = SystemImage("system-images;android-37;google_apis_playstore;arm64-v8a", "Phone", installed = false)

    @Test fun `the SDK is ready only when every tool is found`() {
        assertEquals(SdkStatus.Ready, sdkStatus(mapOf("sdkmanager" to true, "avdmanager" to true, "emulator" to true, "adb" to true)))
        assertEquals(SdkStatus.Missing, sdkStatus(mapOf("sdkmanager" to false, "avdmanager" to false, "emulator" to false, "adb" to false)))
        // Google's command-line tools alone have no emulator or adb.
        assertEquals(SdkStatus.Incomplete(listOf("emulator", "adb")),
            sdkStatus(mapOf("sdkmanager" to true, "avdmanager" to true, "emulator" to false, "adb" to false)))
    }

    @Test fun `steps go forward, and licensed computers skip the license page`() {
        assertEquals(OnboardingStep.Sdk, nextStep(OnboardingStep.Welcome, licensed = false))
        assertEquals(OnboardingStep.Platforms, nextStep(OnboardingStep.Sdk, licensed = false))
        assertEquals(OnboardingStep.CommandLine, nextStep(OnboardingStep.Platforms, licensed = false))
        assertEquals(OnboardingStep.CommandLine, nextStep(OnboardingStep.Platforms, licensed = true))
        assertEquals(OnboardingStep.License, nextStep(OnboardingStep.CommandLine, licensed = false))
        assertNull(nextStep(OnboardingStep.CommandLine, licensed = true))
        assertNull(nextStep(OnboardingStep.License, licensed = false))
        assertEquals(OnboardingStep.CommandLine, previousStep(OnboardingStep.License))
        assertEquals(OnboardingStep.Platforms, previousStep(OnboardingStep.CommandLine))
        assertEquals(OnboardingStep.License, previousStep(OnboardingStep.Activate))
        assertNull(nextStep(OnboardingStep.Activate, licensed = true))
        assertEquals(OnboardingStep.Welcome, previousStep(OnboardingStep.Sdk))
    }

    @Test fun `without the version list a platform says why`() {
        assertEquals(PlatformStatus.NeedsSdk, platformStatus(SdkStatus.Missing, listLoading = false, listFailed = false, image = null, download = null, size = null))
        assertEquals(PlatformStatus.NeedsSdk, platformStatus(SdkStatus.Incomplete(listOf("sdkmanager")), false, false, null, null, null))
        // Missing the emulator still allows listing versions.
        assertEquals(PlatformStatus.Loading, platformStatus(SdkStatus.Incomplete(listOf("emulator")), listLoading = true, false, null, null, null))
        assertEquals(PlatformStatus.ListFailed, platformStatus(SdkStatus.Ready, listLoading = false, listFailed = true, null, null, null))
        assertEquals(PlatformStatus.NoCompatibleVersion, platformStatus(SdkStatus.Ready, false, false, null, null, null))
    }

    @Test fun `a platform's version can be got, downloads, fails or is installed`() {
        assertEquals(PlatformStatus.Available(phone, 1_600L), platformStatus(SdkStatus.Ready, false, false, phone, null, 1_600L))
        val download = AndroidDownload().apply { state = AndroidDownloadState.Downloading; progress.value = DownloadProgress(50) }
        assertEquals(PlatformStatus.Downloading(phone, 40, installing = false, cancelling = false), platformStatus(SdkStatus.Ready, false, false, phone, download, null))
        download.state = AndroidDownloadState.Installing; download.progress.value = DownloadProgress(50)
        assertEquals(PlatformStatus.Downloading(phone, 90, installing = true, cancelling = false), platformStatus(SdkStatus.Ready, false, false, phone, download, null))
        download.state = AndroidDownloadState.Failed(RuntimeException("offline"))
        assertEquals(PlatformStatus.DownloadFailed(phone), platformStatus(SdkStatus.Ready, false, false, phone, download, null))
        // A failed download that later installs another way shows as installed, not failed.
        val installed = phone.copy(installed = true)
        assertEquals(PlatformStatus.Installed(installed), platformStatus(SdkStatus.Ready, false, false, installed, download, null))
    }

    @Test fun `an older installed version offers the latest as an update`() {
        val older = phone.copy(path = "system-images;android-35;google_apis_playstore;arm64-v8a", installed = true)
        assertEquals(PlatformStatus.Outdated(phone, older, 1_600L), platformStatus(SdkStatus.Ready, false, false, phone, null, 1_600L, older))
        // Once the latest is installed, it's installed, whatever else is there.
        val latest = phone.copy(installed = true)
        assertEquals(PlatformStatus.Installed(latest), platformStatus(SdkStatus.Ready, false, false, latest, null, null, older))
    }
}
