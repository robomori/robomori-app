package com.robomori

import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadOverallProgressTest {
    @Test fun `downloading then installing reads 0 to 100 once`() {
        val steps = listOf(
            AndroidDownloadState.CheckingStorage to DownloadProgress(),
            AndroidDownloadState.Downloading to DownloadProgress(0),
            AndroidDownloadState.Downloading to DownloadProgress(50),
            AndroidDownloadState.Downloading to DownloadProgress(100),
            AndroidDownloadState.WaitingToInstall to DownloadProgress(100),
            // Installing restarts the SDK's own count from nothing, then from 0.
            AndroidDownloadState.Installing to DownloadProgress(),
            AndroidDownloadState.Installing to DownloadProgress(0),
            AndroidDownloadState.Installing to DownloadProgress(50),
            AndroidDownloadState.Installing to DownloadProgress(100),
            AndroidDownloadState.Installed to DownloadProgress(100),
        )
        val percents = steps.map { (state, progress) -> state.overallPercent(progress) }
        assertEquals(listOf(0, 0, 40, 80, 80, 80, 80, 90, 100, 100), percents)
    }

    @Test fun `a job never shows less than it already showed`() {
        val task = AndroidDownload()
        task.state = AndroidDownloadState.Installing
        task.progress.value = DownloadProgress(60)
        assertEquals(92, task.overall)
        // The SDK's output can step back between messages.
        task.progress.value = DownloadProgress(10)
        assertEquals(92, task.overall)
    }
}
