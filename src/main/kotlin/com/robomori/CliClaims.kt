package com.robomori

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.util.Properties
import kotlinx.serialization.json.*

/** [owner] claimed a device while it ran as [session], so the claim ends when the device stops or restarts. */
internal data class DeviceClaim(val owner: String, val session: String)

/** Which folder claimed each device, by device name, so coding agents working in parallel each keep their own. */
internal class DeviceClaimsStore(private val file: File = File(HostPlatform().configDirectory, "device-claims.properties")) {
    fun load(): Map<String, DeviceClaim> = Properties().apply {
        if (file.isFile) file.inputStream().use(::load)
    }.entries.associate { (device, value) ->
        val (session, owner) = value.toString().split('|', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        device.toString() to DeviceClaim(owner, session)
    }

    /** Reads, changes and saves the claims under a lock, so two agents claiming at once never get the same device. */
    fun <T> update(change: (MutableMap<String, DeviceClaim>) -> T): T {
        file.parentFile.mkdirs()
        FileChannel.open(File(file.parentFile, "${file.name}.lock").toPath(), CREATE, WRITE).use { channel ->
            channel.lock().use {
                val claims = load().toMutableMap()
                val result = change(claims)
                save(claims)
                return result
            }
        }
    }

    private fun save(claims: Map<String, DeviceClaim>) {
        val temporary = File.createTempFile("device-claims-", ".tmp", file.parentFile)
        try {
            val properties = Properties().apply { claims.forEach { (device, claim) -> put(device, "${claim.session}|${claim.owner}") } }
            temporary.outputStream().use { output -> properties.store(output, null) }
            Files.move(temporary.toPath(), file.toPath(), REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
}

/** Who claims devices from [directory]: its git worktree, so every agent in one checkout shares one device. */
internal fun claimOwner(directory: File): String {
    val start = directory.absoluteFile.normalize()
    return (generateSequence(start) { it.parentFile }.firstOrNull { File(it, ".git").exists() } ?: start).path
}

/**
 * Identifies this run of the device, which changes when it stops or restarts: an emulator's process, through its
 * discovery file, since resuming a snapshot restores Android's boot ID; a physical device's boot ID.
 */
internal fun Cli.sessionId(device: CliDevice): String {
    val adb = android.toolchain.adb; val serial = device.serial!!
    return if (device.physical) android.command(adb, listOf("-s", serial, "shell", "cat", "/proc/sys/kernel/random/boot_id")).trim()
    else android.command(adb, listOf("-s", serial, "emu", "avd", "discoverypath")).lines().first { it.isNotBlank() && it != "OK" }.trim()
}

/** The claims that still hold, as device name to owner: made during the device's current run, from folders that still exist. */
internal fun Cli.liveClaims(claims: Map<String, DeviceClaim>, devices: List<CliDevice>): Map<String, String> {
    val ready = devices.filter { it.ready }.associateBy { it.name }
    return claims.filter { (device, claim) ->
        val running = ready[device] ?: return@filter false
        File(claim.owner).isDirectory && runCatching { sessionId(running) }.getOrNull() == claim.session
    }.mapValues { it.value.owner }
}

internal fun Cli.claimedDevices(devices: List<CliDevice>) = liveClaims(claims.load(), devices)

/** A claimed folder as people recognize it: ~/projects/app. */
internal fun displayPath(path: String): String {
    val home = System.getProperty("user.home")
    return if (path == home || path.startsWith(home + File.separator)) "~" + path.removePrefix(home) else path
}

/** Stops this folder from acting on a device another folder claimed. */
internal fun Cli.requireNotClaimedByOthers(device: String, claimed: Map<String, String> = claimedDevices(devices())) {
    val holder = claimed[device] ?: return
    if (holder != owner) throw IllegalStateException("$device is claimed by ${displayPath(holder)}. Claim a free one with " +
        "'robomori device claim', or take this one over with 'robomori device claim ${shellWord(device)} --force'.")
}

internal fun Cli.claim(options: Options) {
    options.expect(0..1, values = setOf("device"), flags = setOf("force", "no-animations"), what = "device")
    val wanted = (options.positional.firstOrNull() ?: options.value("device"))?.trim()
    val (device, already) = claimDevice(wanted, force = options.flag("force"))
    if (options.flag("no-animations") && !device.physical) {
        runCatching { DeviceSettingsService(settings, executor).setAnimationScale(device.serial!!, 0f) }
    }
    if (jsonOutput) return printJson(buildJsonObject {
        device.json().forEach { (key, value) -> put(key, value) }
        put("claimedBy", owner)
    })
    progress("${device.name} is ${if (already) "already" else "now"} claimed by ${displayPath(owner)}. Robomori commands run here " +
        "use it without --device. For adb and Gradle, run: export ANDROID_SERIAL=${device.serial}")
    out.println(device.serial)
}

/**
 * Claims [wanted] for this worktree, or without it, the device this worktree already claimed, or else a running
 * emulator no one claimed. Returns the device, and whether this worktree had already claimed it.
 */
internal fun Cli.claimDevice(wanted: String?, force: Boolean = false): Pair<CliDevice, Boolean> {
    val all = devices()
    val (device, already) = claims.update { stored ->
        val claimed = liveClaims(stored, all)
        stored.keys.retainAll(claimed.keys)
        val device = if (wanted != null) readyDevice(all, wanted).also {
            if (!force) requireNotClaimedByOthers(it.name, claimed)
        } else all.firstOrNull { claimed[it.name] == owner }
            ?: all.firstOrNull { !it.physical && it.ready && it.name !in claimed }
            ?: throw IllegalStateException(noFreeEmulator(all, claimed))
        val already = claimed[device.name] == owner
        stored[device.name] = DeviceClaim(owner, sessionId(device))
        device to already
    }
    if (!already) runCatching { saveSettingsForClaim(device) }.onFailure {
        progress("Couldn't keep ${device.name}'s settings to put back on release: ${it.message}")
    }
    return device to already
}

private fun noFreeEmulator(all: List<CliDevice>, claims: Map<String, String>): String {
    val busy = all.filter { !it.physical && it.ready }
    val reason = if (busy.isEmpty()) "No emulator is running." else "No free emulator: " +
        busy.joinToString(", ") { "${it.name} is claimed by ${displayPath(claims.getValue(it.name))}" } + "."
    val start = mostAlike(all.filter { !it.physical && it.state == CliDevice.State.NOT_RUNNING }, busy)
        ?.let { "Start one with 'robomori emulator start ${shellWord(it.name)}'" }
        ?: "Create one with 'robomori emulator create --start'"
    return "$reason $start, then run 'robomori device claim' again."
}

/**
 * The stopped emulator most like the running ones, which other agents already work on: the same hardware and Android
 * version first, then the same kind of device. With none running, a phone.
 */
internal fun mostAlike(stopped: List<CliDevice>, running: List<CliDevice>): CliDevice? {
    fun CliDevice.hardware() = emulator?.config?.get("hw.device.name")
    fun score(device: CliDevice) = running.maxOfOrNull { other ->
        (if (device.type == other.type) 4 else 0) + (if (device.hardware() != null && device.hardware() == other.hardware()) 2 else 0) +
            (if (device.api == other.api) 1 else 0)
    } ?: if (device.type == DeviceType.PHONE) 4 else 0
    return stopped.maxByOrNull(::score)
}

internal fun Cli.release(options: Options) {
    options.expect(0..1, values = setOf("device"), what = "device")
    val released = releaseClaims((options.positional.firstOrNull() ?: options.value("device"))?.trim())
    if (jsonOutput) return printJson(JsonArray(released.map(::JsonPrimitive)))
    if (released.isEmpty()) progress("Nothing is claimed by ${displayPath(owner)}.")
    else {
        progress("Released ${released.joinToString(", ")}: other worktrees can claim ${if (released.size == 1) "it" else "them"} now.")
        released.forEach(out::println)
    }
}

/**
 * Releases [wanted], or without it every device this worktree claimed, and puts back the controls each claim found.
 * Returns the released devices' names.
 */
internal fun Cli.releaseClaims(wanted: String?): List<String> {
    val all = devices()
    val released = claims.update { stored ->
        val claimed = liveClaims(stored, all)
        stored.keys.retainAll(claimed.keys)
        val mine = claimed.filterValues { it == owner }.keys.toList()
        val names = if (wanted == null) mine else {
            val name = mine.firstOrNull { it.equals(wanted, true) }
                ?: all.firstOrNull { it.serial == wanted || it.displayName.equals(wanted, true) }?.name?.takeIf { it in mine }
                ?: throw IllegalStateException("$wanted isn't claimed by ${displayPath(owner)}." +
                    if (mine.isEmpty()) "" else " Claimed here: ${mine.joinToString(", ")}.")
            listOf(name)
        }
        stored.keys.removeAll(names.toSet())
        names
    }
    for (name in released) all.firstOrNull { it.name == name && it.ready }?.let { device ->
        val failed = runCatching { restoreSettingsAfterClaim(device) }.getOrDefault(emptyList())
        if (failed.isNotEmpty()) progress("Couldn't put back ${device.name}'s ${failed.joinToString(", ")}. See 'robomori device controls'.")
    }
    return released
}

private fun Cli.deviceShell(device: CliDevice, script: String) =
    android.command(android.toolchain.adb, listOf("-s", device.serial!!, "shell", script))

/**
 * Where a claimed device keeps its controls and rotation from before the claim, one name=value a line. It lives on the
 * device, so a claim that ended without a release, as when the emulator stopped, still finds them on the next claim
 * and puts them back after it.
 */
private const val claimSettings = "/data/local/tmp/.robomori-claim-settings"

/** Keeps the device's controls and rotation on it, unless an earlier claim that never released already did. */
private fun Cli.saveSettingsForClaim(device: CliDevice) {
    if (deviceShell(device, "cat $claimSettings 2>/dev/null; true").isNotBlank()) return
    val lines = controlsSnapshot(device).map { (key, value) -> "$key=$value" }
    if (lines.isEmpty()) return
    deviceShell(device, "printf '%s\\n' ${lines.joinToString(" ") { "'" + it.replace("'", "") + "'" }} > $claimSettings")
}

/** Puts back the controls and rotation kept at the claim, and forgets them. Returns what couldn't be put back. */
private fun Cli.restoreSettingsAfterClaim(device: CliDevice): List<String> {
    val saved = deviceShell(device, "cat $claimSettings 2>/dev/null; true").lines().mapNotNull { line ->
        line.trim().split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
    }.toMap()
    if (saved.isEmpty()) return emptyList()
    val failed = restoreControls(device, saved)
    deviceShell(device, "rm -f $claimSettings")
    return failed
}
