package com.robomori

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Shared with the live location control so presets do not create a second simulation owner. */
internal interface PresetLocationAccess {
    suspend fun support(target: DevicePresetTarget, value: PresetValue.Location): PresetSupport
    suspend fun capture(target: DevicePresetTarget): PresetRestorePoint
    suspend fun apply(target: DevicePresetTarget, value: PresetValue.Location)
    suspend fun restore(target: DevicePresetTarget, previous: PresetRestorePoint)
}

internal class AndroidDevicePresetAccess(
    private val settings: () -> AppSettings,
    private val location: PresetLocationAccess,
    private val executor: CommandExecutor = ProcessExecutor(),
    private val localeFactory: (AppSettings) -> DeviceLocaleAccess = { DeviceLocaleService(it, executor) },
    private val deviceType: (DevicePresetTarget) -> DeviceType? = { null },
    private val targetAvailable: (DevicePresetTarget) -> Boolean = { true },
    private val knownSettings: (DevicePresetTarget) -> DeviceSettings? = { null },
    private val knownLocales: (DevicePresetTarget) -> DeviceLocaleInfo? = { null },
) : DevicePresetAccess {
    override suspend fun support(target: DevicePresetTarget, setting: PresetSetting, value: PresetValue): PresetSupport {
        if (!targetAvailable(target)) return PresetSupport.Unsupported("This device is disconnected.")
        if (!setting.accepts(value)) return PresetSupport.Unsupported("This preset contains an invalid value.")
        if (setting == PresetSetting.Location) return location.support(target, value as PresetValue.Location)
        if (setting == PresetSetting.DarkMode && deviceType(target) in listOf(DeviceType.TV, DeviceType.WEAR))
            return PresetSupport.Unsupported("This device always uses a dark appearance.")
        return withContext(Dispatchers.IO) {
            val current = settings()
            when (setting) {
                PresetSetting.DarkMode -> PresetSupport.Supported()
                PresetSetting.TalkBack -> if ((knownSettings(target) ?: DeviceSettingsService(current, executor).read(target.serial)).talkBackInstalled)
                    PresetSupport.Supported() else PresetSupport.Unsupported("TalkBack is not installed on this device.")
                PresetSetting.Locale -> {
                    val access = localeFactory(current)
                    if ((knownLocales(target) ?: access.read(target.serial)).available.none { it.tag == (value as PresetValue.Locale).tag })
                        PresetSupport.Unsupported("This locale is not supported by the device.")
                    else PresetSupport.Supported(if (access is DeviceLocaleService) null else
                        "Only the previous primary locale can be restored by this connection.")
                }
                else -> PresetSupport.Supported()
            }
        }
    }

    override suspend fun capture(target: DevicePresetTarget, setting: PresetSetting): PresetRestorePoint {
        requireAvailable(target)
        if (setting == PresetSetting.Location) return location.capture(target)
        return withContext(Dispatchers.IO) {
            val current = settings()
            when (setting) {
                PresetSetting.DarkMode -> NightModePoint(readNightMode(current, target))
                PresetSetting.FontScale -> {
                    val raw = readSetting(current, target, setting)
                    scale(raw, setting)
                    ScalePoint(raw)
                }
                PresetSetting.Animations -> AnimationsPoint(AnimationScaleSetting.entries.associateWith { animation ->
                    readAnimation(current, target, animation).also { scale(it, setting) }
                })
                PresetSetting.TalkBack -> TalkBackPoint(
                    readSecure(current, target, "enabled_accessibility_services"),
                    readSecure(current, target, "accessibility_enabled"),
                )
                PresetSetting.Locale -> {
                    val access = localeFactory(current)
                    if (access is DeviceLocaleService) LocalePoint(access.readPreferredLocales(target.serial))
                    else PresetPreviousValue(PresetValue.Locale(access.read(target.serial).current.tag),
                        "Only the previous primary locale can be restored by this connection.")
                }
                PresetSetting.Location -> error("Location is managed by the shared location control.")
            }
        }
    }

    override suspend fun apply(target: DevicePresetTarget, setting: PresetSetting, value: PresetValue) {
        requireAvailable(target)
        require(setting.accepts(value)) { "This preset contains an invalid value." }
        if (setting == PresetSetting.Location) { location.apply(target, value as PresetValue.Location); return }
        withContext(Dispatchers.IO) {
            val current = settings()
            val service = DeviceSettingsService(current, executor)
            when (setting) {
                PresetSetting.DarkMode -> {
                    val enabled = (value as PresetValue.Toggle).enabled
                    service.setDarkMode(target.serial, enabled)
                }
                PresetSetting.FontScale -> {
                    val requested = (value as PresetValue.Scale).value
                    if (requested in textSizeOptions) service.setFontScale(target.serial, requested)
                    else writeSetting(current, target, setting, requested.toString())
                    check(scale(readSetting(current, target, setting), setting) == requested) { "The device did not apply this scale." }
                }
                PresetSetting.Animations -> {
                    val requested = (value as PresetValue.Scale).value
                    if (requested in animationScaleOptions) service.setAnimationScale(target.serial, requested)
                    else AnimationScaleSetting.entries.forEach { writeAnimation(current, target, it, requested.toString()) }
                    check(AnimationScaleSetting.entries.all { scale(readAnimation(current, target, it), setting) == requested }) {
                        "The device did not apply this scale."
                    }
                }
                PresetSetting.TalkBack -> {
                    val enabled = (value as PresetValue.Toggle).enabled
                    service.setTalkBack(target.serial, enabled)
                    check(service.readTalkBackEnabled(target.serial) == enabled) { "The device did not apply the TalkBack setting." }
                }
                PresetSetting.Locale -> {
                    val requested = DeviceLocale((value as PresetValue.Locale).tag)
                    check(localeFactory(current).apply(target.serial, requested) == requested) { "The device did not apply this locale." }
                }
                PresetSetting.Location -> Unit
            }
        }
    }

    override suspend fun restore(target: DevicePresetTarget, setting: PresetSetting, previous: PresetRestorePoint) {
        requireAvailable(target)
        if (setting == PresetSetting.Location) { location.restore(target, previous); return }
        withContext(Dispatchers.IO) {
            val current = settings()
            when (setting) {
                PresetSetting.DarkMode -> {
                    val mode = (previous as NightModePoint).mode
                    shell(current, target, "cmd", "uimode", "night", mode)
                    check(readNightMode(current, target) == mode) { "The device did not restore its previous appearance mode." }
                }
                PresetSetting.FontScale -> {
                    val raw = (previous as ScalePoint).raw
                    writeSetting(current, target, setting, raw)
                    check(readSetting(current, target, setting) == raw) { "The device did not restore its previous scale." }
                }
                PresetSetting.Animations -> (previous as AnimationsPoint).raw.forEach { (animation, raw) ->
                    writeAnimation(current, target, animation, raw)
                    check(readAnimation(current, target, animation) == raw) { "The device did not restore its previous scale." }
                }
                PresetSetting.TalkBack -> {
                    val point = previous as TalkBackPoint
                    writeSecure(current, target, "enabled_accessibility_services", point.services)
                    writeSecure(current, target, "accessibility_enabled", point.enabled)
                    check(readSecure(current, target, "enabled_accessibility_services") == point.services &&
                        readSecure(current, target, "accessibility_enabled") == point.enabled) { "The device did not restore its previous accessibility settings." }
                }
                PresetSetting.Locale -> {
                    val access = localeFactory(current)
                    if (previous is LocalePoint) {
                        check(access is DeviceLocaleService) { "This connection cannot restore the full preferred-language list." }
                        check(access.applyPreferredLocales(target.serial, previous.locales) == previous.locales) {
                            "The device did not restore its previous locales."
                        }
                    } else {
                        val requested = DeviceLocale(((previous as PresetPreviousValue).value as PresetValue.Locale).tag)
                        check(access.apply(target.serial, requested) == requested) { "The device did not restore its previous locale." }
                    }
                }
                PresetSetting.Location -> Unit
            }
        }
    }

    private fun requireAvailable(target: DevicePresetTarget) {
        check(targetAvailable(target)) { "This device is disconnected." }
    }

    private fun readNightMode(settings: AppSettings, target: DevicePresetTarget): String =
        Regex("Night mode:\\s*(auto|yes|no|custom)\\b").find(shell(settings, target, "cmd", "uimode", "night"))
            ?.groupValues?.get(1) ?: error("Could not read the previous appearance mode.")

    private fun scale(raw: String?, setting: PresetSetting): Float = (raw?.toFloatOrNull() ?: if (raw == null) 1f else null)
        ?.takeIf { it.isFinite() && if (setting == PresetSetting.FontScale) it > 0f else it >= 0f }
        ?: error("Could not read the previous scale.")

    private fun readSetting(settings: AppSettings, target: DevicePresetTarget, setting: PresetSetting): String? =
        shell(settings, target, "settings", "--user", "current", "get", "system", "font_scale").trim().takeUnless { it == "null" }

    private fun writeSetting(settings: AppSettings, target: DevicePresetTarget, setting: PresetSetting, value: String?) {
        if (value != null) scale(value, setting)
        shell(settings, target, "settings", "--user", "current", if (value == null) "delete" else "put", "system", "font_scale",
            *listOfNotNull(value).toTypedArray())
    }

    private fun readAnimation(settings: AppSettings, target: DevicePresetTarget, animation: AnimationScaleSetting): String? =
        shell(settings, target, "settings", "get", "global", animation.key).trim().takeUnless { it == "null" }

    private fun writeAnimation(settings: AppSettings, target: DevicePresetTarget, animation: AnimationScaleSetting, value: String?) {
        if (value != null) scale(value, PresetSetting.Animations)
        shell(settings, target, "settings", if (value == null) "delete" else "put", "global", animation.key,
            *listOfNotNull(value).toTypedArray())
    }

    private fun readSecure(settings: AppSettings, target: DevicePresetTarget, key: String): String? =
        shell(settings, target, "settings", "--user", "current", "get", "secure", key).trim().takeUnless { it == "null" }

    private fun writeSecure(settings: AppSettings, target: DevicePresetTarget, key: String, value: String?) {
        require(value == null || value.matches(Regex("[A-Za-z0-9_./:$-]*"))) { "Could not restore the previous accessibility value." }
        shell(settings, target, "settings", "--user", "current", if (value == null) "delete" else "put", "secure", key,
            *listOfNotNull(value?.let { "'" + it.replace("'", "'\\''") + "'" }).toTypedArray())
    }

    private fun shell(settings: AppSettings, target: DevicePresetTarget, vararg args: String): String {
        requireAvailable(target)
        val android = AndroidService(settings, executor)
        return android.command(android.toolchain.adb, listOf("-s", target.serial, "shell") + args)
    }

    private data class NightModePoint(val mode: String) : PresetRestorePoint
    private data class ScalePoint(val raw: String?) : PresetRestorePoint
    private data class AnimationsPoint(val raw: Map<AnimationScaleSetting, String?>) : PresetRestorePoint
    private data class TalkBackPoint(val services: String?, val enabled: String?) : PresetRestorePoint
    private data class LocalePoint(val locales: List<DeviceLocale>) : PresetRestorePoint
}
