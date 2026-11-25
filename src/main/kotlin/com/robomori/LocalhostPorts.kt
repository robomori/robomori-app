package com.robomori

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.Properties

/** The ports devices reach the computer on until someone changes them: common dev server ports. */
internal val DefaultLocalhostPorts = listOf(3000, 8080)

/**
 * The computer's ports that apps on every emulator and phone reach as localhost, such as a dev server on 3000. Robomori
 * sets them up on each device with `adb reverse`, the same port on both sides.
 */
internal class LocalhostPortsStore(private val file: File = File(HostPlatform().configDirectory, "localhost-ports.properties")) {
    fun load(): List<Int> {
        if (!file.isFile) return DefaultLocalhostPorts
        val values = Properties().apply { file.inputStream().use(::load) }
        return values.getProperty("ports", "").split(',').mapNotNull { it.trim().toIntOrNull() }.filter(::isPort).distinct()
    }

    /** Whether the ports were chosen, rather than left at [DefaultLocalhostPorts]. */
    fun chosen(): Boolean = file.isFile

    fun save(ports: List<Int>) {
        val values = Properties().apply { setProperty("ports", ports.joinToString(",")) }
        file.parentFile.mkdirs()
        val temporary = File.createTempFile("localhost-ports-", ".tmp", file.parentFile)
        try {
            temporary.outputStream().use { values.store(it, null) }
            Files.move(temporary.toPath(), file.toPath(), REPLACE_EXISTING)
        } finally { temporary.delete() }
    }

    /** Back to [DefaultLocalhostPorts]. */
    fun reset() { file.delete() }

    /** When the ports were last saved, by the app or the command line; 0 while they're the defaults. */
    fun savedAt(): Long = file.lastModified()
}

private fun isPort(port: Int) = port in 1..65535

/** A port as someone types it, or a message saying what's wrong with it. */
internal fun parsePort(text: String): Result<Int> {
    val port = text.trim().removePrefix("localhost:").toIntOrNull()
    return if (port != null && isPort(port)) Result.success(port)
    else Result.failure(IllegalArgumentException("Enter a port from 1 to 65535, such as 3000."))
}

/** Ports separated by commas, such as `3000,8080`; empty for none. */
internal fun parsePorts(text: String): List<Int> =
    text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { parsePort(it).getOrThrow() }.distinct()

private val reverseRule = Regex("\\btcp:(\\d+)\\s+tcp:(\\d+)\\s*$")

/** The device ports in `adb reverse --list`, each with the computer port it reaches. */
internal fun parseReverseRules(output: String): Map<Int, Int> =
    output.lineSequence().mapNotNull { reverseRule.find(it.trim()) }
        .associate { it.groupValues[1].toInt() to it.groupValues[2].toInt() }

/** The serials of the emulators and phones adb can use right now. */
internal fun AndroidService.onlineSerials(): List<String> {
    if (!toolchain.adb.canExecute()) return emptyList()
    return command(toolchain.adb, listOf("devices")).lines().drop(1)
        .filter { it.endsWith("\tdevice") }.map { it.substringBefore('\t') }
}

/**
 * Makes [ports] reachable as localhost on the device [serial], and stops forwarding [dropped] ports. It leaves alone
 * a port another tool already sends elsewhere, such as Metro's 8081, and every rule it didn't make.
 */
internal fun AndroidService.reverseLocalhostPorts(serial: String, ports: List<Int>, dropped: Collection<Int> = emptyList()) {
    val rules = parseReverseRules(command(toolchain.adb, listOf("-s", serial, "reverse", "--list")))
    dropped.filter { it !in ports && rules[it] == it }.forEach { port ->
        command(toolchain.adb, listOf("-s", serial, "reverse", "--remove", "tcp:$port"))
    }
    ports.filter { it !in rules }.forEach { port ->
        command(toolchain.adb, listOf("-s", serial, "reverse", "tcp:$port", "tcp:$port"))
    }
}

/** Sets up [ports] on every device that's online. Used by the command line, which can't wait for the app to do it. */
internal fun AndroidService.reverseLocalhostPortsEverywhere(ports: List<Int>, dropped: Collection<Int> = emptyList()) {
    onlineSerials().forEach { serial -> runCatching { reverseLocalhostPorts(serial, ports, dropped) } }
}

/**
 * Settings → Network: the ports devices reach the computer on, kept set up on every device as they come online,
 * restart or reconnect. Changes made with `robomori config set ports` show up here too.
 */
internal class LocalhostPortsState(
    private val scope: CoroutineScope,
    private val service: () -> AndroidService,
    private val store: LocalhostPortsStore = LocalhostPortsStore(),
) {
    var ports by mutableStateOf(store.load()); private set
    /** Whether the last port someone tried to add wasn't one. Cleared by the next add or remove. */
    var invalidPort by mutableStateOf(false); private set

    /** Adds the port typed in [text]; false when it isn't a port, so the field keeps it for fixing. */
    fun add(text: String): Boolean {
        val port = parsePort(text).getOrNull()
        invalidPort = port == null
        if (port == null) return false
        if (port !in ports) change(ports + port)
        return true
    }

    fun remove(port: Int) {
        invalidPort = false
        change(ports - port)
    }

    /** Saves [updated]; [keepApplied] sets it up on the devices and removes the ports that were dropped. */
    private fun change(updated: List<Int>) {
        ports = updated
        scope.launch(Dispatchers.IO) {
            store.save(updated)
            savedAt = store.savedAt()
        }
    }

    private var savedAt = store.savedAt()

    /** Keeps the ports set up on every online device. adb forgets them whenever a device restarts or reconnects. */
    fun keepApplied() = scope.launch {
        var applied = emptyMap<String, List<Int>>()
        var rounds = 0
        while (isActive) {
            // Picks up `robomori config set ports`.
            if (store.savedAt() != savedAt) {
                savedAt = store.savedAt()
                ports = withContext(Dispatchers.IO) { store.load() }
            }
            // Now and then every device is checked again, in case adb lost its rules without the device going offline.
            if (rounds++ % LocalhostPortsRecheckRounds == 0) applied = emptyMap()
            val wanted = ports
            val previously = applied
            applied = withContext(Dispatchers.IO) {
                val backend = service()
                runCatching { backend.onlineSerials() }.getOrDefault(emptyList()).associateWith { serial ->
                    // A device that just came online, or whose ports changed, gets them set up; the rest already have them.
                    val previous = previously[serial]
                    if (previous == wanted) wanted
                    else runCatching { backend.reverseLocalhostPorts(serial, wanted, previous.orEmpty()) }.fold({ wanted }, { emptyList() })
                }
            }
            delay(LocalhostPortsInterval)
        }
    }
}

private const val LocalhostPortsInterval = 2000L
private const val LocalhostPortsRecheckRounds = 15
