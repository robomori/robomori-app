package com.robomori

import kotlinx.serialization.json.*

/**
 * The settings `robomori config` reads and changes: the app's SDK Locations, in the order the app shows them, then the
 * computer's ports devices reach as localhost (Settings → Network).
 */
private val configKeys = listOf("sdk", "avds", "adb", "emulator", "sdkmanager", "avdmanager", "ports")
private val folders = setOf("sdk", "avds")

private fun configKey(key: String) = key.takeIf { it in configKeys }
    ?: throw UsageException("Unknown setting: $key.${suggestion(key, configKeys)}", "See 'robomori config list'.")

/** A setting's value as Robomori uses it, and whether it was set, is a default, or comes from the SDK folder. */
private fun Cli.configValues(): Map<String, Pair<String, String>> {
    // Read again rather than reuse settings: a change made by this command shows right away.
    val settings = settingsStore.load()
    val chosen = settingsStore.chosen()
    val tools = Toolchain(settings)
    return configKeys.associateWith { key ->
        when (key) {
            "sdk" -> settings.sdk to if (key in chosen) "set" else "default"
            "avds" -> settings.avds to if (key in chosen) "set" else "default"
            "ports" -> localhostPorts.load().joinToString(",") to if (localhostPorts.chosen()) "set" else "default"
            else -> tools.tools.getValue(key).path to if (key in chosen) "set" else "from sdk"
        }
    }
}

internal fun Cli.configList(options: Options) {
    options.expect()
    val values = configValues()
    if (jsonOutput) return printJson(JsonArray(values.map { (key, value) ->
        buildJsonObject { put("key", key); put("value", value.first); put("source", value.second) }
    }))
    printTable(listOf("KEY", "VALUE", "SOURCE"), values.map { (key, value) -> listOf(key, value.first, value.second) })
}

internal fun Cli.configGet(options: Options) {
    options.expect(1..1, what = "setting")
    val key = configKey(options.positional.single())
    val (value, source) = configValues().getValue(key)
    if (jsonOutput) printJson(buildJsonObject { put("key", key); put("value", value); put("source", source) }) else out.println(value)
}

internal fun Cli.configSet(options: Options) {
    options.expect(2..2, what = "setting and its value")
    val (name, value) = options.positional
    val key = configKey(name)
    if (key == "ports") return savePorts(parsePorts(value))
    save(key, withSetting(settings, key, value), value)
}

internal fun Cli.configUnset(options: Options) {
    options.expect(1..1, what = "setting")
    val key = configKey(options.positional.single())
    if (key == "ports") return savePorts(null)
    val default = when (key) { "sdk" -> HostPlatform().defaultSdk.path; "avds" -> defaultAvdHome(); else -> "" }
    settingsStore.save(withSetting(settings, key, default), defaults = folders - settingsStore.chosen() + key)
    if (jsonOutput) configGet(Options(listOf(key), emptyMap(), emptySet())) else out.println(configValues().getValue(key).first)
}

private fun withSetting(settings: AppSettings, key: String, value: String) = when (key) {
    "sdk" -> settings.copy(sdk = value)
    "avds" -> settings.copy(avds = value)
    "adb" -> settings.copy(adb = value)
    "emulator" -> settings.copy(emulator = value)
    "sdkmanager" -> settings.copy(sdkmanager = value)
    else -> settings.copy(avdmanager = value)
}

/** Saves [draft] after the same checks as the app's SDK Locations page, keeping untouched folders at their defaults. */
private fun Cli.save(key: String, draft: AppSettings, value: String) {
    when (draft.locationProblem()) {
        null -> Unit
        SettingsSaveResult.InvalidSdkFolder -> throw IllegalArgumentException("$value isn't a folder Robomori can write to. Choose your Android SDK folder.")
        SettingsSaveResult.InvalidAvdFolder -> throw IllegalArgumentException("$value isn't a folder Robomori can write to.")
        is SettingsSaveResult.InvalidExecutables -> throw IllegalArgumentException("$value isn't an executable file.")
        else -> throw IllegalArgumentException("Enter a folder for $key.")
    }
    settingsStore.save(draft.normalizedLocations(), defaults = folders - settingsStore.chosen() - key)
    if (jsonOutput) configGet(Options(listOf(key), emptyMap(), emptySet())) else out.println(configValues().getValue(key).first)
}

/** Saves the localhost ports, or goes back to the defaults when [ports] is null, and sets them up on running devices now. */
private fun Cli.savePorts(ports: List<Int>?) {
    val previous = localhostPorts.load()
    if (ports == null) localhostPorts.reset() else localhostPorts.save(ports)
    android.reverseLocalhostPortsEverywhere(localhostPorts.load(), dropped = previous)
    if (jsonOutput) configGet(Options(listOf("ports"), emptyMap(), emptySet())) else out.println(configValues().getValue("ports").first)
}
