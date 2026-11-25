package com.robomori

import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class AppWindowRequestsTest {
    @TempDir lateinit var root: File

    private fun requests() = AppWindowRequests(File(root, "open-requests"), File(root, "app.lock"))

    @Test fun `requests are taken once`() {
        val requests = requests()
        requests.request("Phone")
        requests.request("Tablet")
        assertEquals(setOf("Phone", "Tablet"), requests.take().toSet())
        assertEquals(emptyList(), requests.take())
    }

    @Test fun `the app counts as running while it holds the lock`() {
        assertFalse(requests().appRunning())
        val app = requests()
        app.holdLock()
        try { assertTrue(requests().appRunning()) } finally { app.releaseLock() }
        assertFalse(requests().appRunning())
    }

    @Test fun `the app opens each requested emulator in its own window`() = runBlocking {
        val fake = FakeAndroid(root)
        fake.device("Phone")
        fake.device("Tablet")
        val state = AppState(this, initial = fake.settings, executor = fake,
            license = licensedForTest(this, File(root, "license")))
        state.refresh()
        val requests = requests()
        requests.request("Phone")
        requests.request("Tablet")

        val watcher = state.watchWindowRequests(requests)
        try {
            withTimeout(5_000) { while (state.focusedDevices.size < 2) delay(10) }
        } finally { watcher.cancel() }

        assertEquals(setOf("Phone", "Tablet"), state.focusedDevices.map { it.name }.toSet())
    }
}
