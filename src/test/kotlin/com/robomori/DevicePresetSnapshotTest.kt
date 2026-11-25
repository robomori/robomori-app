package com.robomori

import org.junit.jupiter.api.Test
import kotlin.test.*

class DevicePresetSnapshotTest {
    private val settings = DeviceSettings(false, AnimationScaleSetting.entries.associateWith { 1f }, 1.5f, true, false)

    @Test fun `snapshot uses the settings already displayed by the device controls`() {
        val location = PresetValue.Location(DeviceCoordinates(35.68, 139.69))

        val values = settings.presetValues(DeviceType.PHONE, DeviceLocale("ja-JP"), location)

        assertEquals(PresetValue.Scale(1.5f), values[PresetSetting.FontScale])
        assertEquals(PresetValue.Locale("ja-JP"), values[PresetSetting.Locale])
        assertEquals(location, values[PresetSetting.Location])
    }

    @Test fun `snapshot excludes controls that are unavailable`() {
        val values = settings.copy(talkBackInstalled = false).presetValues(DeviceType.TV, null, null)

        assertEquals(setOf(PresetSetting.FontScale, PresetSetting.Animations), values.keys)
    }

    @Test fun `loading a partial preset preserves other displayed settings`() {
        val updated = settings.withPresetValues(mapOf(PresetSetting.DarkMode to PresetValue.Toggle(true)))

        assertEquals(settings.copy(darkMode = true), updated)
    }
}
