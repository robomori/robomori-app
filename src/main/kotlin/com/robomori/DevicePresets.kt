package com.robomori

import java.util.UUID

internal sealed class PresetSetting(val key: String) {
    data object DarkMode : PresetSetting("darkMode")
    data object FontScale : PresetSetting("fontScale")
    data object TalkBack : PresetSetting("talkBack")
    /** All three Android animation scales, set together. */
    data object Animations : PresetSetting("animations")
    data object Locale : PresetSetting("locale")
    data object Location : PresetSetting("location")

    fun accepts(value: PresetValue): Boolean = when (this) {
        DarkMode, TalkBack -> value is PresetValue.Toggle
        FontScale -> value is PresetValue.Scale && value.value > 0f
        Animations -> value is PresetValue.Scale
        Locale -> value is PresetValue.Locale
        Location -> value is PresetValue.Location
    }

    companion object {
        val all: List<PresetSetting> get() = listOf(DarkMode, FontScale, TalkBack, Animations, Locale, Location)
        fun fromKey(key: String): PresetSetting? = all.firstOrNull { it.key == key }
    }
}

internal sealed interface PresetValue {
    data class Toggle(val enabled: Boolean) : PresetValue
    data class Scale(val value: Float) : PresetValue {
        init { require(value.isFinite() && value >= 0f) { "Choose a valid scale." } }
    }
    data class Locale(val tag: String) : PresetValue {
        init { require(DeviceLocale.parse(tag)?.tag == tag) { "Choose a valid locale." } }
    }
    /** Null is an explicit None selection. An absent map entry leaves location unchanged. */
    data class Location(val coordinates: DeviceCoordinates?) : PresetValue
}

internal data class DevicePreset(
    val name: String,
    val values: Map<PresetSetting, PresetValue>,
    val id: String = UUID.randomUUID().toString(),
) {
    init {
        require(id.isNotBlank()) { "A preset needs an identifier." }
        require(name.isNotBlank() && name == name.trim() && name.length <= 80) {
            "Enter a preset name of 1 to 80 characters."
        }
        require(values.isNotEmpty()) { "Choose at least one setting." }
        require(values.all { (setting, value) -> setting.accepts(value) }) { "A preset contains an invalid setting." }
    }
}

internal data class DevicePresetTarget(val identity: String, val serial: String) {
    init { require(identity.isNotBlank() && serial.isNotBlank()) { "Select a connected device." } }
}

internal fun DeviceSettings.presetValues(type: DeviceType, locale: DeviceLocale?, location: PresetValue.Location?): Map<PresetSetting, PresetValue> = buildMap {
    if (type != DeviceType.TV && type != DeviceType.WEAR) put(PresetSetting.DarkMode, PresetValue.Toggle(darkMode))
    put(PresetSetting.FontScale, PresetValue.Scale(fontScale))
    if (talkBackInstalled) put(PresetSetting.TalkBack, PresetValue.Toggle(talkBackEnabled))
    put(PresetSetting.Animations, PresetValue.Scale(animationScale ?: animationScales.getValue(AnimationScaleSetting.ANIMATOR)))
    if (locale != null) put(PresetSetting.Locale, PresetValue.Locale(locale.tag))
    if (location != null) put(PresetSetting.Location, location)
}

internal fun DeviceSettings.withPresetValues(values: Map<PresetSetting, PresetValue>): DeviceSettings = copy(
    darkMode = (values[PresetSetting.DarkMode] as? PresetValue.Toggle)?.enabled ?: darkMode,
    fontScale = (values[PresetSetting.FontScale] as? PresetValue.Scale)?.value ?: fontScale,
    talkBackEnabled = (values[PresetSetting.TalkBack] as? PresetValue.Toggle)?.enabled ?: talkBackEnabled,
    animationScales = (values[PresetSetting.Animations] as? PresetValue.Scale)?.value
        ?.let { scale -> animationScales.mapValues { scale } } ?: animationScales,
)

internal sealed interface PresetSupport {
    data class Supported(val restoreLimit: String? = null) : PresetSupport
    data class Unsupported(val reason: String) : PresetSupport
}

/** Adapter-owned points preserve values such as automatic theme mode, not just its visible result. */
internal interface PresetRestorePoint {
    val limitation: String? get() = null
}

internal data class PresetPreviousValue(
    val value: PresetValue,
    override val limitation: String? = null,
) : PresetRestorePoint

internal interface DevicePresetAccess {
    suspend fun support(target: DevicePresetTarget, setting: PresetSetting, value: PresetValue): PresetSupport
    suspend fun capture(target: DevicePresetTarget, setting: PresetSetting): PresetRestorePoint
    suspend fun apply(target: DevicePresetTarget, setting: PresetSetting, value: PresetValue)
    suspend fun restore(target: DevicePresetTarget, setting: PresetSetting, previous: PresetRestorePoint)
}

internal data class PresetPlannedChange(val setting: PresetSetting, val value: PresetValue, val support: PresetSupport)
internal data class PresetPlan(val target: DevicePresetTarget, val preset: DevicePreset, val changes: List<PresetPlannedChange>)

internal sealed interface PresetSettingOutcome {
    data object Applied : PresetSettingOutcome
    data object Restored : PresetSettingOutcome
    data class Failed(val reason: String) : PresetSettingOutcome
    data class Skipped(val reason: String) : PresetSettingOutcome
}

internal data class PresetSettingResult(val setting: PresetSetting, val outcome: PresetSettingOutcome)
internal data class PresetOperationResult(val results: List<PresetSettingResult>, val locked: Boolean = false) {
    val successful: Boolean get() = !locked && results.isNotEmpty() && results.all {
        it.outcome == PresetSettingOutcome.Applied || it.outcome == PresetSettingOutcome.Restored
    }
}

internal data class PresetRestoreSummary(
    val settings: Set<PresetSetting>,
    val limitations: Map<PresetSetting, String>,
    val available: Boolean = true,
)
