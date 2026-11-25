package com.robomori

import java.io.File
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class DevicePresetDirectoryTest {
    @TempDir lateinit var directory: File

    @Test fun `lists only preset files in alphabetical order`() {
        File(directory, "Zulu.robomoripreset").writeText("")
        File(directory, "Arabic.robomoripreset").writeText("")
        File(directory, "notes.yml").writeText("")
        File(directory, "nested.robomoripreset").mkdir()

        assertEquals(listOf("Arabic", "Zulu"), DevicePresetDirectory(directory).files().map { it.nameWithoutExtension })
    }

    @Test fun `reads directory changes on the next listing`() {
        val presets = DevicePresetDirectory(directory)
        val file = File(directory, "Travel.robomoripreset")
        assertEquals(emptyList(), presets.files())
        file.writeText("")
        assertEquals(listOf(file), presets.files())
        file.delete()
        assertEquals(emptyList(), presets.files())
    }

    @Test fun `creating a preset writes a loadable file in the designated folder`() {
        val folder = File(directory, "presets")
        val preset = sample("Large text")

        val file = DevicePresetDirectory(folder).create(preset)

        assertEquals(File(folder, "Large text.robomoripreset"), file)
        assertEquals(listOf(preset), DevicePresetStore(file).load())
    }

    @Test fun `creating a duplicate name preserves the original file`() {
        val presets = DevicePresetDirectory(directory)
        val file = presets.create(sample("Travel"))
        val original = file.readText()

        assertFailsWith<IllegalArgumentException> { presets.create(sample("TRAVEL")) }

        assertEquals(original, file.readText())
    }

    @Test fun `preset names cannot escape the designated folder`() {
        val presets = DevicePresetDirectory(directory)

        val file = presets.create(sample("../Travel"))

        assertEquals(directory.canonicalFile, file.parentFile.canonicalFile)
    }

    @Test fun `updating a preset replaces its settings and preserves its identity`() {
        val presets = DevicePresetDirectory(directory)
        val original = sample("Travel")
        val file = presets.create(original)
        val updatedValues: Map<PresetSetting, PresetValue> = mapOf(PresetSetting.DarkMode to PresetValue.Toggle(true))

        presets.update(file, updatedValues)

        assertEquals(listOf(original.copy(values = updatedValues)), DevicePresetStore(file).load())
        assertEquals(listOf(file), presets.files())
    }

    @Test fun `updating a missing preset does not recreate it`() {
        val presets = DevicePresetDirectory(directory)
        val file = presets.create(sample("Travel"))
        file.delete()

        assertFailsWith<IllegalArgumentException> { presets.update(file, sample("Current").values) }

        assertFalse(file.exists())
    }

    private fun sample(name: String) = DevicePreset(name, mapOf(PresetSetting.FontScale to PresetValue.Scale(1.5f)))
}
