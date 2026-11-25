package com.robomori

import kotlinx.serialization.json.*

/** The buttons `robomori input press` presses, the same as the app's device toolbar and TV remote. */
private val buttons = linkedMapOf(
    "back" to "KEYCODE_BACK", "home" to "KEYCODE_HOME", "recents" to "KEYCODE_APP_SWITCH", "power" to "KEYCODE_POWER",
    "crown" to "KEYCODE_HOME", "side" to "KEYCODE_STEM_PRIMARY",
    "up" to "KEYCODE_DPAD_UP", "down" to "KEYCODE_DPAD_DOWN", "left" to "KEYCODE_DPAD_LEFT", "right" to "KEYCODE_DPAD_RIGHT",
    "select" to "KEYCODE_DPAD_CENTER", "play-pause" to "KEYCODE_MEDIA_PLAY_PAUSE",
    "enter" to "KEYCODE_ENTER", "delete" to "KEYCODE_DEL", "tab" to "KEYCODE_TAB", "escape" to "KEYCODE_ESCAPE",
    "search" to "KEYCODE_SEARCH", "volume-up" to "KEYCODE_VOLUME_UP", "volume-down" to "KEYCODE_VOLUME_DOWN",
    "space" to "KEYCODE_SPACE", "minus" to "KEYCODE_MINUS", "plus" to "KEYCODE_PLUS", "equals" to "KEYCODE_EQUALS",
    "period" to "KEYCODE_PERIOD", "comma" to "KEYCODE_COMMA", "slash" to "KEYCODE_SLASH", "at" to "KEYCODE_AT",
)

/** System panels and actions `robomori input press` opens, which no key does. */
private val panels = listOf("notifications", "quick-settings", "power-menu", "lock")
private val modifiers = setOf("shift", "ctrl", "alt", "meta")

/**
 * [name] as the device's agent takes it: a button as its key code, a panel, or a key with modifiers such as ctrl+a,
 * whose key is a button, a letter or digit, a KEYCODE_ name or a number.
 */
private fun keyFor(name: String): String {
    if (name in panels) return name
    val parts = name.split('+')
    val key = parts.last()
    parts.dropLast(1).firstOrNull { it !in modifiers }?.let {
        throw UsageException("Unknown modifier: $it.${suggestion(it, modifiers)}", "Modifiers: ${modifiers.joinToString(", ")}.")
    }
    // A single digit is that digit's key; longer numbers are key codes.
    val code = buttons[key] ?: key.takeIf { it.matches(Regex("[0-9]")) }?.let { "KEYCODE_$it" }
        ?: key.takeIf { it.matches(Regex("[a-zA-Z]|KEYCODE_[A-Z0-9_]+|\\d{2,}")) }
        ?: throw UsageException("Unknown button: $key.${suggestion(key, buttons.keys + panels)}",
            "Buttons: ${(buttons.keys + panels).joinToString(", ")}, a letter, or a KEYCODE_ name.")
    return (parts.dropLast(1) + code).joinToString("+")
}

internal fun Cli.press(options: Options) {
    options.expect(1..1, values = setOf("device"), flags = setOf("long", "quiet"), what = "button: ${(buttons.keys + panels).joinToString(", ")}")
    val button = options.positional.single()
    val key = keyFor(button)
    val long = options.flag("long")
    val device = target(options)
    agent(device).use { agent ->
        try { agent.text(*listOfNotNull("key", key, "long".takeIf { long }).toTypedArray()) } catch (e: DeviceAgentException) {
            // The agent can't connect while UI tests or uiautomator hold the device; adb still presses plain keys, slower.
            if (key.contains('+') || key in panels) throw e
            android.command(android.toolchain.adb, listOf("-s", device.serial!!, "shell", "input", "keyevent")
                + listOfNotNull("--longpress".takeIf { long }) + key)
            if (jsonOutput) printJson(buildJsonObject { put("device", device.name); put("button", button) })
            return
        }
        val screen = if (!options.flag("quiet")) agent.text("ui", "diff") else null
        if (jsonOutput) printJson(buildJsonObject { put("device", device.name); put("button", button); screen?.let { put("screen", it) } })
        else screen?.let(out::println)
    }
}

internal fun Cli.rotate(options: Options) {
    options.expect(0..1, values = setOf("device"), flags = setOf("quiet"))
    val direction = options.positional.firstOrNull() ?: "clockwise"
    if (direction !in setOf("clockwise", "counterclockwise")) throw UsageException(
        "Unknown direction: $direction.${suggestion(direction, listOf("clockwise", "counterclockwise"))}", "Use clockwise or counterclockwise.")
    val device = target(options)
    check(device.type.supportsRotation) { "${device.name} doesn't rotate." }
    val before = runCatching { android.displayRotation(device.serial!!) }.getOrNull()
    android.rotateScreen(device.serial!!, clockwise = direction == "clockwise")
    // Wait for the screen to turn, so the next command sees it turned.
    if (before != null) {
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline && runCatching { android.displayRotation(device.serial!!) }.getOrNull() == before) pause(100)
    }
    // The whole layout changes, so print it as other actions do: when UI tests hold the device, the screen can't be read.
    val screen = if (!options.flag("quiet")) runCatching { agent(device).use { it.text("ui", "diff", "acted") } }.getOrNull() else null
    if (jsonOutput) printJson(buildJsonObject { put("device", device.name); put("rotated", direction); screen?.let { put("screen", it) } })
    else screen?.let(out::println)
}

internal fun Cli.deviceRestart(options: Options) {
    options.expect(values = setOf("device"), flags = setOf("no-wait"))
    val device = target(options)
    val serial = device.serial!!
    progress("Restarting ${device.name}…")
    android.command(android.toolchain.adb, listOf("-s", serial, "reboot"))
    if (!options.flag("no-wait")) {
        // Wait for Android to go down first, so the old boot isn't mistaken for the new one.
        val deadline = System.currentTimeMillis() + bootTimeoutMillis
        fun waitUntil(what: String, condition: () -> Boolean) {
            while (!condition()) {
                check(System.currentTimeMillis() < deadline) { "${device.name} did not $what in time." }
                pause(1000)
            }
        }
        waitUntil("restart") { !runCatching { android.bootCompleted(serial) }.getOrDefault(false) }
        waitUntil("finish booting") { runCatching { android.bootCompleted(serial) }.getOrDefault(false) }
    }
    if (jsonOutput) printJson(device.json()) else out.println(serial)
}

internal fun Cli.emulatorFold(options: Options, folded: Boolean) {
    options.expect(1..1, what = "emulator")
    val emulator = emulator(options.positional.single())
    check(emulator.type == DeviceType.FOLDABLE) { "${emulator.name} isn't a foldable." }
    requireNotClaimedByOthers(emulator.name)
    val serial = android.running()[emulator.name]
        ?: throw IllegalStateException("${emulator.name} isn't running. Start it with 'robomori emulator start ${shellWord(emulator.name)}'.")
    EmulatorFoldControl(adb = { arguments -> android.command(android.toolchain.adb, listOf("-s", serial) + arguments) }).fold(folded)
    if (jsonOutput) printJson(buildJsonObject { put("name", emulator.name); put("folded", folded) })
}

internal fun Cli.presetSave(options: Options) {
    options.expect(1..1, values = setOf("device"), what = "preset's name")
    val name = options.positional.single().trim()
    val device = target(options)
    val serial = device.serial!!
    val current = DeviceSettingsService(settings, executor).read(serial)
    val locale = runCatching { DeviceLocaleService(settings, executor).read(serial).current }.getOrNull()
    // A simulated location can't be read back from the device, so presets saved here leave location unchanged.
    val preset = DevicePreset(name, current.presetValues(device.type, locale, null))
    presets.create(preset)
    if (jsonOutput) printJson(buildJsonObject { put("name", preset.name); put("device", device.name) }) else out.println(preset.name)
}

internal fun Cli.presetDelete(options: Options) {
    options.expect(1..1, what = "preset")
    val name = options.positional.single()
    val file = presets.files().firstOrNull { file -> runCatching { DevicePresetStore(file).load().single().name }.getOrNull().equals(name.trim(), true) }
        ?: throw IllegalArgumentException("No preset named $name. See 'robomori preset list'.")
    check(file.delete()) { "Could not delete $name." }
}
