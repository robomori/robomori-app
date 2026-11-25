package com.robomori

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

/** The settings `robomori device controls` shows and changes, the same ones as the app's device controls. */
private enum class Control(val key: String) {
    DARK_MODE("dark-mode"), FONT_SCALE("font-scale"), LOCALE("locale"), TALKBACK("talkback"), ANIMATIONS("animations"), KEYBOARD("keyboard");

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key }
            ?: throw UsageException("Unknown control: $key.${suggestion(key, entries.map { it.key })}", "See 'robomori help device controls'.")
    }
}

internal fun Cli.controls(options: Options) {
    options.expect(0..2, values = setOf("device"))
    val control = options.positional.firstOrNull()?.let(Control::of)
    val value = options.positional.getOrNull(1)
    val device = target(options)
    val serial = device.serial!!
    val service = DeviceSettingsService(settings, executor)
    if (value != null) change(device, control!!, value)
    val current = service.read(serial)
    fun shown(control: Control): String = when (control) {
        Control.DARK_MODE -> onOff(current.darkMode)
        Control.FONT_SCALE -> cliNumber(current.fontScale)
        Control.LOCALE -> DeviceLocaleService(settings, executor).read(serial).current.tag
        Control.TALKBACK -> if (current.talkBackInstalled) onOff(current.talkBackEnabled) else "not installed"
        Control.KEYBOARD -> onOff(service.softwareKeyboard(serial))
        Control.ANIMATIONS -> current.animationScale?.let(::cliNumber) ?: "mixed (" + AnimationScaleSetting.entries.joinToString(", ") {
            "${it.name.lowercase()} ${cliNumber(current.animationScales.getValue(it))}"
        } + ")"
    }
    val shownControls = listOfNotNull(control).ifEmpty {
        Control.entries.filterNot { (it == Control.DARK_MODE && device.type in alwaysDark) || (it == Control.KEYBOARD && device.physical) }
    }
    val values = shownControls.associate { it.key to shown(it) }
    if (jsonOutput) return printJson(buildJsonObject {
        put("device", device.name)
        values.forEach { (key, shownValue) -> put(key, shownValue) }
    })
    if (control != null) return out.println(values.getValue(control.key))
    printTable(listOf("CONTROL", "VALUE"), values.map { listOf(it.key, it.value) })
}

private val alwaysDark = setOf(DeviceType.TV, DeviceType.WEAR)

private fun onOff(enabled: Boolean) = if (enabled) "on" else "off"

private fun Cli.change(device: CliDevice, control: Control, value: String) {
    val serial = device.serial!!
    val service = DeviceSettingsService(settings, executor)
    when (control) {
        Control.DARK_MODE -> {
            check(device.type !in alwaysDark) { "${device.name} always uses a dark theme." }
            service.setDarkMode(serial, toggle(control, value))
        }
        Control.FONT_SCALE -> {
            val scale = value.removeSuffix("%").toFloatOrNull()?.let { if (value.endsWith("%")) it / 100 else it }
                ?.takeIf { it in textSizeOptions }
                ?: throw UsageException("font-scale takes ${textSizeOptions.joinToString(", ") { cliNumber(it) }}, such as 1.3.")
            service.setFontScale(serial, scale)
        }
        Control.LOCALE -> {
            val locale = DeviceLocale.parse(value) ?: throw UsageException("locale takes a language tag, such as fr-FR or ja-JP.")
            val access = DeviceLocaleService(settings, executor)
            val available = access.read(serial).available
            check(available.any { it.tag == locale.tag }) {
                "${device.name} doesn't offer ${locale.tag}.${suggestion(locale.tag, available.map { it.tag })}"
            }
            access.apply(serial, locale)
        }
        Control.TALKBACK -> {
            val enabled = toggle(control, value)
            check(service.read(serial).talkBackInstalled) { "TalkBack isn't installed on ${device.name}." }
            service.setTalkBack(serial, enabled)
        }
        Control.KEYBOARD -> {
            check(!device.physical) { "${device.name} already shows its on-screen keyboard. This control is for emulators, which use your computer's keyboard." }
            service.setSoftwareKeyboard(serial, toggle(control, value))
        }
        Control.ANIMATIONS -> {
            val scale = (if (value == "off") 0f else value.toFloatOrNull())?.takeIf { it in animationScaleOptions }
                ?: throw UsageException("animations takes ${animationScaleOptions.joinToString(", ") { cliNumber(it) }}, or off.")
            service.setAnimationScale(serial, scale)
        }
    }
}

private fun toggle(control: Control, value: String) = when (value) {
    "on" -> true
    "off" -> false
    else -> throw UsageException("${control.key} takes on or off.")
}

/** The names `controls` uses for each preset setting, so presets read the same as the commands. */
private val PresetSetting.cliName get() = when (this) {
    PresetSetting.DarkMode -> "dark-mode"
    PresetSetting.FontScale -> "font-scale"
    PresetSetting.TalkBack -> "talkback"
    PresetSetting.Animations -> "animations"
    PresetSetting.Locale -> "locale"
    PresetSetting.Location -> "location"
}

private val PresetValue.cliText get() = when (this) {
    is PresetValue.Toggle -> onOff(enabled)
    is PresetValue.Scale -> cliNumber(value)
    is PresetValue.Locale -> tag
    is PresetValue.Location -> coordinates?.let { "${it.latitude},${it.longitude}" } ?: "none"
}

private fun Cli.savedPresets(): List<DevicePreset> = presets.files().mapNotNull { file ->
    runCatching { DevicePresetStore(file).load().single() }.getOrElse {
        progress("Skipping ${file.name}: it isn't a valid preset."); null
    }
}

internal fun Cli.presetList(options: Options) {
    options.expect()
    val saved = savedPresets()
    if (jsonOutput) return printJson(JsonArray(saved.map { preset ->
        buildJsonObject {
            put("name", preset.name)
            putJsonObject("controls") { preset.values.forEach { (setting, value) -> put(setting.cliName, value.cliText) } }
        }
    }))
    if (saved.isEmpty()) return progress("No presets yet. Save one from a device's controls in the Robomori app.")
    printTable(listOf("NAME", "CONTROLS"), saved.map { preset ->
        listOf(preset.name, preset.values.entries.joinToString(", ") { (setting, value) -> "${setting.cliName} ${value.cliText}" })
    })
}

internal fun Cli.presetApply(options: Options) {
    options.expect(1..1, values = setOf("device"), what = "preset")
    val name = options.positional.single()
    val saved = savedPresets()
    val preset = saved.firstOrNull { it.name.equals(name.trim(), true) }
        ?: throw IllegalArgumentException("No preset named $name.${suggestion(name, saved.map { it.name })} See 'robomori preset list'.")
    val device = target(options)
    val access = AndroidDevicePresetAccess({ settings }, CliLocations(this, device), executor, deviceType = { device.type })
    val result = runBlocking {
        DevicePresetController(access, retainRestorePoints = false).apply(DevicePresetTarget(device.serial!!, device.serial), preset)
    }
    val rows = result.results.map { (setting, outcome) ->
        val (state, reason) = when (outcome) {
            PresetSettingOutcome.Applied, PresetSettingOutcome.Restored -> "applied" to null
            is PresetSettingOutcome.Skipped -> "skipped" to outcome.reason
            is PresetSettingOutcome.Failed -> "failed" to outcome.reason
        }
        listOf(setting.cliName, preset.values.getValue(setting).cliText, state, reason.orEmpty())
    }
    if (jsonOutput) printJson(buildJsonObject {
        put("device", device.name); put("preset", preset.name)
        putJsonArray("controls") { rows.forEach { (control, value, state, reason) -> addJsonObject {
            put("control", control); put("value", value); put("result", state); if (reason.isNotEmpty()) put("reason", reason)
        } } }
    }) else if (rows.any { it[3].isNotEmpty() }) printTable(listOf("CONTROL", "VALUE", "RESULT", "REASON"), rows)
    else printTable(listOf("CONTROL", "VALUE", "RESULT"), rows.map { it.dropLast(1) })
    check(rows.none { it[2] == "failed" }) { "Some of ${preset.name} couldn't be applied." }
}

/** Preset locations on the command line, simulated the way `simulate location set` does. */
private class CliLocations(private val cli: Cli, private val device: CliDevice) : PresetLocationAccess {
    override suspend fun support(target: DevicePresetTarget, value: PresetValue.Location): PresetSupport = PresetSupport.Supported()

    override suspend fun capture(target: DevicePresetTarget): PresetRestorePoint = error("Presets applied here aren't restored.")

    override suspend fun apply(target: DevicePresetTarget, value: PresetValue.Location) {
        value.coordinates?.let { cli.setLocation(device, it) } ?: cli.clearLocation(device)
    }

    override suspend fun restore(target: DevicePresetTarget, previous: PresetRestorePoint) = error("Presets applied here aren't restored.")
}

/**
 * The device's controls and rotation as they are, by name, for [restoreControls] to put back: dark mode, text size,
 * language, TalkBack, the on-screen keyboard, each animation scale and which way the screen is turned. Leaves out what
 * the device can't read or doesn't have.
 */
internal fun Cli.controlsSnapshot(device: CliDevice): Map<String, String> {
    val serial = device.serial!!
    val service = DeviceSettingsService(settings, executor)
    val current = service.read(serial)
    return buildMap {
        if (device.type !in alwaysDark) put(Control.DARK_MODE.key, onOff(current.darkMode))
        put(Control.FONT_SCALE.key, current.fontScale.toString())
        runCatching { DeviceLocaleService(settings, executor).read(serial).current.tag }.onSuccess { put(Control.LOCALE.key, it) }
        if (current.talkBackInstalled) put(Control.TALKBACK.key, onOff(current.talkBackEnabled))
        if (!device.physical) runCatching { service.softwareKeyboard(serial) }.onSuccess { put(Control.KEYBOARD.key, onOff(it)) }
        AnimationScaleSetting.entries.forEach { put(it.key, current.animationScales.getValue(it).toString()) }
        if (!device.physical && device.type.supportsRotation) {
            runCatching { android.displayRotation(serial) }.onSuccess { put("rotation", it.toString()) }
        }
    }
}

/**
 * Puts back the controls and rotation [saved] holds, from [controlsSnapshot], changing only those that differ now.
 * Returns what couldn't be put back.
 */
internal fun Cli.restoreControls(device: CliDevice, saved: Map<String, String>): List<String> {
    val serial = device.serial!!
    val service = DeviceSettingsService(settings, executor)
    val now = controlsSnapshot(device)
    val failed = mutableListOf<String>()
    fun restore(key: String, apply: (String) -> Unit) {
        val value = saved[key] ?: return
        if (now[key] == value) return
        runCatching { apply(value) }.onFailure { failed += key }
    }
    restore(Control.DARK_MODE.key) { service.setDarkMode(serial, it == "on") }
    // Any size, as a person may have picked one the font-scale control doesn't offer.
    restore(Control.FONT_SCALE.key) {
        android.command(android.toolchain.adb, listOf("-s", serial, "shell", "settings", "--user", "current", "put", "system", "font_scale", it))
    }
    restore(Control.LOCALE.key) { tag -> DeviceLocale.parse(tag)?.let { DeviceLocaleService(settings, executor).apply(serial, it) } }
    restore(Control.TALKBACK.key) { service.setTalkBack(serial, it == "on") }
    restore(Control.KEYBOARD.key) { service.setSoftwareKeyboard(serial, it == "on") }
    AnimationScaleSetting.entries.forEach { setting ->
        restore(setting.key) { android.command(android.toolchain.adb, listOf("-s", serial, "shell", "settings", "put", "global", setting.key, it)) }
    }
    restore("rotation") { wanted ->
        // A quarter turn at a time, as the emulator turns.
        repeat(3) { if (android.displayRotation(serial).toString() != wanted) android.rotateScreen(serial, clockwise = true) }
    }
    return failed
}
