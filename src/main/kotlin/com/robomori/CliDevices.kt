package com.robomori

import kotlinx.serialization.json.*

/**
 * A device as the command line sees it: an emulator, running or not, or a connected physical device. [name] is what
 * --device and the emulator commands take: an emulator's AVD name, or a physical device's name from the app.
 */
internal data class CliDevice(
    val name: String,
    val displayName: String,
    val physical: Boolean,
    val type: DeviceType,
    val api: String?,
    val android: String?,
    val serial: String?,
    val state: State,
    val emulator: Device? = null,
) {
    enum class State(val label: String) {
        RUNNING("running"), NOT_RUNNING("not running"), CONNECTED("connected"),
        UNAUTHORIZED("unauthorized"), OFFLINE("offline"),
    }

    val kind get() = if (physical) "physical" else "emulator"
    val ready get() = state == State.RUNNING || state == State.CONNECTED

    fun json() = buildJsonObject {
        put("name", name)
        put("displayName", displayName)
        put("kind", kind)
        put("type", type.label)
        put("api", api)
        put("android", android)
        put("state", state.label)
        put("running", ready)
        put("serial", serial)
    }
}

/** Every device: connected physical ones first, the way the app lists them, then emulators by name. */
internal fun Cli.devices(emulators: Boolean = true, physical: Boolean = true): List<CliDevice> =
    (if (physical) physicalDevices() else emptyList()) + (if (emulators) emulatorDevices() else emptyList())

internal fun Cli.emulatorDevices(): List<CliDevice> {
    val running = android.running()
    return android.devices().map { device ->
        val serial = running[device.name]
        CliDevice(device.name, device.displayName, physical = false, type = device.type, api = apiLabel(device.api),
            android = device.platformTitle, serial = serial,
            state = if (serial != null) CliDevice.State.RUNNING else CliDevice.State.NOT_RUNNING, emulator = device)
    }
}

private fun Cli.physicalDevices(): List<CliDevice> {
    if (!android.toolchain.adb.canExecute()) return emptyList()
    val names = runCatching { deviceNames.load() }.getOrDefault(emptyMap())
    return android.connectedDevices().map { connected ->
        if (!connected.available) return@map CliDevice(connected.displayName, connected.displayName, physical = true, type = connected.type,
            api = null, android = null, serial = connected.serial,
            state = if (connected.status == "unauthorized") CliDevice.State.UNAUTHORIZED else CliDevice.State.OFFLINE)
        val described = android.describeConnectedDevice(connected)
        fun property(name: String) = android.command(android.toolchain.adb, listOf("-s", connected.serial, "shell", "getprop", name)).trim()
        val name = names[described.identity] ?: described.displayName
        CliDevice(name, name, physical = true, type = described.type, api = property("ro.build.version.sdk").ifBlank { null },
            android = property("ro.build.version.release").ifBlank { null }?.let { "Android $it" }, serial = connected.serial,
            state = CliDevice.State.CONNECTED)
    }
}

/** Android versions as `image list` shows them and `emulator create --api` takes them: 37, not 37.0. */
internal fun apiLabel(api: String) = api.substringBefore("-ext").removeSuffix(".0")

/**
 * The running device --device names, by serial or name, or else ANDROID_SERIAL, as adb does. Without either, the device
 * this folder claimed, or else the only running device no other folder claimed; with none or several, a message that
 * says what to do.
 */
internal fun Cli.target(options: Options): CliDevice {
    val wanted = (options.value("device") ?: androidSerial)?.trim()?.ifEmpty { null }
    val all = devices()
    val claimed = claimedDevices(all)
    if (wanted == null) {
        val ready = all.filter { it.ready }
        val mine = ready.filter { claimed[it.name] == owner }
        if (mine.isNotEmpty()) return mine.singleOrNull() ?: throw IllegalStateException(
            "${displayPath(owner)} claimed ${mine.size} devices. Choose one with --device: " +
                mine.joinToString(", ") { "${shellWord(it.name)} (${it.serial})" } + ".")
        val free = ready.filter { it.name !in claimed }
        return free.singleOrNull() ?: throw IllegalStateException(when {
            ready.isEmpty() -> "No device is running. Start an emulator with 'robomori emulator start <emulator>', or connect a phone."
            free.isEmpty() -> "Every running device is claimed: " + ready.joinToString(", ") {
                "${it.name} by ${displayPath(claimed.getValue(it.name))}" } + ". Start another emulator, then run 'robomori device claim'."
            else -> "${free.size} devices are running. Choose one with --device: " +
                free.joinToString(", ") { "${shellWord(it.name)} (${it.serial})" } + "."
        })
    }
    return readyDevice(all, wanted).also { requireNotClaimedByOthers(it.name, claimed) }
}

/** The running device among [all] named [wanted], by serial or name, or a message that says why it can't be used. */
internal fun readyDevice(all: List<CliDevice>, wanted: String): CliDevice {
    val match = all.firstOrNull { it.serial == wanted } ?: all.firstOrNull { it.name.equals(wanted, true) }
        ?: all.filter { it.displayName.equals(wanted, true) }.singleOrNull()
        ?: throw IllegalArgumentException("No device named $wanted.${suggestion(wanted, all.map { it.name })} See 'robomori device list'.")
    return when (match.state) {
        CliDevice.State.RUNNING, CliDevice.State.CONNECTED -> match
        CliDevice.State.NOT_RUNNING -> throw IllegalStateException(
            "${match.name} isn't running. Start it with 'robomori emulator start ${shellWord(match.name)}'.")
        CliDevice.State.UNAUTHORIZED -> throw IllegalStateException("${match.name} hasn't allowed USB debugging from this computer. " +
            "Unlock it and tap Allow, then try again.")
        CliDevice.State.OFFLINE -> throw IllegalStateException("${match.name} is offline. Reconnect it, then try again.")
    }
}

/** An emulator by its name or the name shown in the app. */
internal fun Cli.emulator(name: String): Device {
    val devices = android.devices()
    return devices.firstOrNull { it.name.equals(name, true) }
        ?: devices.filter { it.displayName.equals(name.trim(), true) }.singleOrNull()
        ?: throw IllegalArgumentException("No emulator named $name.${suggestion(name, devices.map { it.name })} " +
            "See 'robomori device list --emulators'.")
}

internal fun Cli.deviceList(options: Options) {
    options.expect(flags = setOf("emulators", "physical", "long"))
    val emulators = options.flag("emulators"); val physical = options.flag("physical")
    if (emulators && physical) throw UsageException("Choose --emulators or --physical, or neither for both.")
    val devices = devices(emulators = !physical, physical = !emulators)
    val claimed = claimedDevices(devices)
    if (jsonOutput) return printJson(JsonArray(devices.map { device ->
        buildJsonObject { device.json().forEach { (key, value) -> put(key, value) }; put("claimedBy", claimed[device.name]) }
    }))
    if (devices.isEmpty()) return progress(when {
        physical -> "No physical devices connected."
        else -> "No devices yet. Create an emulator with 'robomori emulator create', or connect a phone."
    })
    fun withClaim(device: CliDevice, state: String) =
        claimed[device.name]?.let { "$state, claimed by ${if (it == owner) "you" else displayPath(it)}" } ?: state
    fun state(device: CliDevice) = withClaim(device, if (device.state == CliDevice.State.RUNNING) "running (${device.serial})" else device.state.label)
    if (options.flag("long")) printTable(listOf("NAME", "DISPLAY NAME", "KIND", "TYPE", "ANDROID", "STATE", "SERIAL"), devices.map {
        listOf(it.name, it.displayName, it.kind, it.type.label, it.android ?: "—", withClaim(it, it.state.label), it.serial ?: "—")
    }) else printTable(listOf("NAME", "KIND", "ANDROID", "STATE"), devices.map {
        listOf(it.name, it.kind, it.android ?: "—", state(it))
    })
}
