package com.robomori

import java.io.File
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class DevicePresetStoreTest {
    @TempDir lateinit var directory: File

    @Test fun `presets round trip every supported value`() {
        val preset = DevicePreset("Arabic accessibility", mapOf(
            PresetSetting.DarkMode to PresetValue.Toggle(true),
            PresetSetting.FontScale to PresetValue.Scale(1.5f),
            PresetSetting.TalkBack to PresetValue.Toggle(false),
            PresetSetting.Animations to PresetValue.Scale(0f),
            PresetSetting.Locale to PresetValue.Locale("ar-EG"),
            PresetSetting.Location to PresetValue.Location(DeviceCoordinates(30.05, 31.24)),
        ))
        val store = DevicePresetStore(File(directory, "presets.json"))

        store.save(listOf(preset))

        assertEquals(listOf(preset), DevicePresetStore(File(directory, "presets.json")).load())
    }

    @Test fun `a preset saved with three animation scales loads as one Animations scale`() {
        val file = File(directory, "Old.robomoripreset").apply { writeText("""
            version: 1
            presets:
              - id: old
                name: Old
                values:
                  windowAnimation: 1.0
                  transitionAnimation: 1.0
                  animatorDuration: 0.0
        """.trimIndent()) }

        assertEquals<Map<PresetSetting, PresetValue>>(mapOf(PresetSetting.Animations to PresetValue.Scale(0f)), DevicePresetStore(file).load().single().values)
    }

    @Test fun `loads a handwritten YAML preset`() {
        val file = File(directory, "Travel.robomoripreset").apply { writeText("""
            version: 1
            presets:
              - id: travel
                name: Travel
                values:
                  darkMode: false
                  fontScale: 1.5
                  locale: ar-EG
                  location:
                    latitude: 30.05
                    longitude: 31.24
        """.trimIndent()) }

        val preset = DevicePresetStore(file).load().single()

        assertEquals(DevicePreset("Travel", mapOf(
            PresetSetting.DarkMode to PresetValue.Toggle(false),
            PresetSetting.FontScale to PresetValue.Scale(1.5f),
            PresetSetting.Locale to PresetValue.Locale("ar-EG"),
            PresetSetting.Location to PresetValue.Location(DeviceCoordinates(30.05, 31.24)),
        ), "travel"), preset)
    }

    @Test fun `rejects duplicate YAML keys`() {
        val file = File(directory, "broken.robomoripreset").apply { writeText("""
            version: 1
            presets:
              - id: broken
                name: Broken
                values:
                  darkMode: true
                  darkMode: false
        """.trimIndent()) }

        assertFailsWith<IllegalStateException> { DevicePresetStore(file).load() }
    }

    @Test fun `creating a file never overwrites an existing destination`() {
        val file = File(directory, "Travel.robomoripreset").apply { writeText("existing preset") }

        assertFailsWith<java.nio.file.FileAlreadyExistsException> {
            DevicePresetStore(file).save(listOf(sample("Travel")), replaceExisting = false)
        }

        assertEquals("existing preset", file.readText())
    }

    @Test fun `explicit none location remains distinct from an excluded location`() {
        val none = DevicePreset("No location", mapOf(PresetSetting.Location to PresetValue.Location(null)))
        val excluded = DevicePreset("Large text", mapOf(PresetSetting.FontScale to PresetValue.Scale(1.5f)))
        val store = DevicePresetStore(File(directory, "presets.json"))
        store.save(listOf(none, excluded))

        val restored = store.load()

        assertEquals(PresetValue.Location(null), restored[0].values[PresetSetting.Location])
        assertFalse(PresetSetting.Location in restored[1].values)
    }

    @Test fun `renaming a preset preserves its identity and values`() {
        val store = DevicePresetStore(File(directory, "presets.json"))
        val preset = sample("Original")
        store.upsert(preset)

        store.upsert(preset.copy(name = "Renamed"))

        assertEquals(listOf(preset.copy(name = "Renamed")), store.load())
    }

    @Test fun `deleting a preset leaves the other saved presets unchanged`() {
        val store = DevicePresetStore(File(directory, "presets.json"))
        val first = sample("First")
        val second = sample("Second")
        store.save(listOf(first, second))

        store.delete(first.id)

        assertEquals(listOf(second), store.load())
    }

    @Test fun `duplicate names do not replace the existing saved file`() {
        val file = File(directory, "presets.json")
        val store = DevicePresetStore(file)
        store.save(listOf(sample("Travel")))
        val original = file.readText()

        assertFailsWith<IllegalArgumentException> { store.upsert(sample("TRAVEL")) }

        assertEquals(original, file.readText())
    }

    @Test fun `loading a preset file preserves unrelated saved presets`() {
        val store = DevicePresetStore(File(directory, "library.json"))
        val existing = sample("Existing")
        val imported = sample("Imported")
        val file = File(directory, "shared.json")
        store.save(listOf(existing))
        DevicePresetStore(file).save(listOf(imported))

        store.upsertAll(DevicePresetStore(file).load())

        assertEquals(listOf(existing, imported), store.load())
    }

    @Test fun `reloading a preset file updates its saved settings without duplicating it`() {
        val store = DevicePresetStore(File(directory, "library.json"))
        val preset = sample("Travel")
        store.save(listOf(preset))
        val updated = preset.copy(values = mapOf(PresetSetting.FontScale to PresetValue.Scale(1.5f)))

        store.upsertAll(listOf(updated))

        assertEquals(listOf(updated), store.load())
    }

    @Test fun `a conflicting imported name leaves the entire saved library unchanged`() {
        val file = File(directory, "library.json")
        val store = DevicePresetStore(file)
        store.save(listOf(sample("Travel")))
        val original = file.readText()

        assertFailsWith<IllegalArgumentException> {
            store.upsertAll(listOf(sample("New"), sample("TRAVEL")))
        }

        assertEquals(original, file.readText())
    }

    @Test fun `invalid imported data leaves the saved library unchanged`() {
        val file = File(directory, "library.json")
        val store = DevicePresetStore(file)
        store.save(listOf(sample("Existing")))
        val original = file.readText()
        val imported = File(directory, "broken.json").apply { writeText("not a preset") }

        assertFailsWith<IllegalStateException> { store.upsertAll(DevicePresetStore(imported).load()) }

        assertEquals(original, file.readText())
    }

    @Test fun `corrupt data is reported without overwriting it`() {
        val file = File(directory, "presets.json").apply { writeText("not json") }
        val store = DevicePresetStore(file)

        assertFailsWith<IllegalStateException> { store.upsert(sample("New")) }

        assertEquals("not json", file.readText())
    }

    @Test fun `unsupported file versions are not silently replaced`() {
        val file = File(directory, "presets.json").apply { writeText("{\"version\":2,\"presets\":[]}") }

        assertFailsWith<IllegalStateException> { DevicePresetStore(file).load() }

        assertEquals("{\"version\":2,\"presets\":[]}", file.readText())
    }

    @Test fun `saving creates missing parent directories`() {
        val file = File(directory, "nested/config/presets.json")
        val preset = sample("Saved")

        DevicePresetStore(file).save(listOf(preset))

        assertEquals(listOf(preset), DevicePresetStore(file).load())
    }

    @Test fun `preset values reject a scale assigned to a toggle`() {
        assertFailsWith<IllegalArgumentException> {
            DevicePreset("Invalid", mapOf(PresetSetting.DarkMode to PresetValue.Scale(1f)))
        }
    }

    @Test fun `preset requires at least one included setting`() {
        assertFailsWith<IllegalArgumentException> { DevicePreset("Empty", emptyMap()) }
    }

    private fun sample(name: String) = DevicePreset(name, mapOf(PresetSetting.DarkMode to PresetValue.Toggle(true)))
}
