package com.robomori

import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class CodeUpdatesTest {
    private suspend fun CoroutineScope.settle() { coroutineContext.job.children.toList().joinAll() }

    @Test fun `checking downloads the newest code update, then tells which one the next launch runs`(): Unit = runBlocking {
        val calls = mutableListOf<String>()
        var downloaded: Int? = null
        val updates = CodeUpdates(this, download = { calls += "download"; downloaded = 2 }, ready = { calls += "ready"; downloaded })
        assertNull(updates.readyPatch)
        updates.check()
        assertTrue(updates.checking)
        settle()
        assertFalse(updates.checking)
        assertEquals(2, updates.readyPatch)
        assertEquals(listOf("download", "ready"), calls)
    }

    @Test fun `a failed download keeps an update downloaded earlier`(): Unit = runBlocking {
        val updates = CodeUpdates(this, download = { error("offline") }, ready = { 1 })
        updates.check()
        settle()
        assertEquals(1, updates.readyPatch)
    }

    @Test fun `refreshing picks up an earlier download without downloading`(): Unit = runBlocking {
        var downloads = 0
        val updates = CodeUpdates(this, download = { downloads++ }, ready = { 3 })
        updates.refresh().join()
        assertEquals(3, updates.readyPatch)
        assertEquals(0, downloads)
    }
}
