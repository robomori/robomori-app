package com.robomori

import androidx.compose.runtime.*
import kotlinx.coroutines.*

/**
 * Code updates: Robomori's own code for the installed version, downloaded in the background and run from the next
 * launch. [download] fetches the newest one the license covers; [ready] tells which downloaded one the next launch
 * runs, such as 2 for 1.6.2+2.
 */
class CodeUpdates internal constructor(private val scope: CoroutineScope,
                                       private val download: () -> Unit, private val ready: () -> Int?) {
    /** The downloaded code update the next launch runs; null when there's none. */
    var readyPatch by mutableStateOf<Int?>(null); private set
    var checking by mutableStateOf(false); private set

    /** Looks for a newer code update and downloads it. */
    fun check() {
        if (checking) return
        checking = true
        scope.launch {
            try { readyPatch = withContext(Dispatchers.IO) { runCatching(download); runCatching(ready).getOrNull() } }
            finally { checking = false }
        }
    }

    /** Picks up a code update downloaded in an earlier session, without looking for a newer one. */
    fun refresh() = scope.launch { readyPatch = withContext(Dispatchers.IO) { runCatching(ready).getOrNull() } }
}
