package com.robomori

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

internal class DevicePresetStore(
    private val file: File,
) {
    @Synchronized
    fun load(): List<DevicePreset> {
        if (!file.exists()) return emptyList()
        try {
            val options = LoaderOptions().apply {
                isAllowDuplicateKeys = false
                maxAliasesForCollections = 0
                codePointLimit = 1024 * 1024
            }
            val document = Yaml(SafeConstructor(options)).load<Any>(file.readText()) as? Map<*, *>
                ?: error("Invalid preset file.")
            require(document["version"] == 1) { "Unsupported preset file version." }
            val presets = document["presets"] as? List<*> ?: error("Missing presets.")
            return presets.map(::decode).also(::validate)
        } catch (failure: Exception) {
            throw IllegalStateException("Could not read your presets. The saved file has not been changed.", failure)
        }
    }

    @Synchronized
    fun save(presets: List<DevicePreset>, replaceExisting: Boolean = true) {
        validate(presets)
        val document = linkedMapOf("version" to 1, "presets" to presets.map(::encode))
        val destination = file.absoluteFile
        Files.createDirectories(destination.parentFile.toPath())
        val temporary = Files.createTempFile(destination.parentFile.toPath(), "device-presets-", ".tmp")
        try {
            val options = DumperOptions().apply { defaultFlowStyle = DumperOptions.FlowStyle.BLOCK; isPrettyFlow = true }
            Files.writeString(temporary, Yaml(options).dump(document))
            if (!replaceExisting) Files.move(temporary, destination.toPath())
            else try {
                Files.move(temporary, destination.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    @Synchronized
    fun upsert(preset: DevicePreset): List<DevicePreset> = upsertAll(listOf(preset))

    @Synchronized
    fun upsertAll(presets: List<DevicePreset>): List<DevicePreset> {
        validate(presets)
        val existing = load()
        val updated = existing.toMutableList()
        presets.forEach { preset ->
            val index = updated.indexOfFirst { it.id == preset.id }
            if (index >= 0) updated[index] = preset else updated.add(preset)
        }
        save(updated)
        return updated
    }

    @Synchronized
    fun delete(id: String): List<DevicePreset> = load().filterNot { it.id == id }.also(::save)

    private fun validate(presets: List<DevicePreset>) {
        require(presets.map { it.id }.distinct().size == presets.size) { "Preset identifiers must be unique." }
        require(presets.map { it.name.lowercase(java.util.Locale.ROOT) }.distinct().size == presets.size) {
            "A preset with that name already exists. Choose another name."
        }
    }

    private fun encode(preset: DevicePreset): Map<String, Any?> = linkedMapOf(
        "id" to preset.id,
        "name" to preset.name,
        "values" to preset.values.entries.associate { (setting, value) ->
            setting.key to when (value) {
                is PresetValue.Toggle -> value.enabled
                is PresetValue.Scale -> value.value
                is PresetValue.Locale -> value.tag
                is PresetValue.Location -> value.coordinates?.let {
                    linkedMapOf("latitude" to it.latitude, "longitude" to it.longitude)
                }
            }
        },
    )

    /**
     * Presets saved before animations became one scale hold the three Android scales separately. They load as one
     * Animations scale: the animator duration scale when present, since it drives most in-app animations.
     */
    private fun legacyAnimations(values: Map<*, *>): Map<*, *> {
        val legacy = listOf("animatorDuration", "windowAnimation", "transitionAnimation")
        if (legacy.none(values::containsKey)) return values
        val scale = legacy.firstNotNullOf { values[it] }
        return values.filterKeys { it !in legacy } + ("animations" to scale)
    }

    private fun decode(element: Any?): DevicePreset {
        val item = element as? Map<*, *> ?: error("Invalid preset.")
        val encodedValues = legacyAnimations(item["values"] as? Map<*, *> ?: error("Missing preset settings."))
        val values = encodedValues.map { (key, encoded) ->
            val setting = requireNotNull(PresetSetting.fromKey(key as? String ?: error("Invalid setting name."))) { "Unknown preset setting: $key" }
            val value = when (setting) {
                PresetSetting.DarkMode, PresetSetting.TalkBack -> PresetValue.Toggle(encoded as? Boolean ?: error("Invalid toggle."))
                PresetSetting.FontScale, PresetSetting.Animations ->
                    PresetValue.Scale((encoded as? Number ?: error("Invalid scale.")).toFloat())
                PresetSetting.Locale -> PresetValue.Locale(encoded as? String ?: error("Invalid locale."))
                PresetSetting.Location -> PresetValue.Location(if (encoded == null) null else (encoded as? Map<*, *> ?: error("Invalid location.")).let {
                    DeviceCoordinates((it["latitude"] as? Number ?: error("Invalid latitude.")).toDouble(),
                        (it["longitude"] as? Number ?: error("Invalid longitude.")).toDouble())
                })
            }
            setting to value
        }.toMap()
        return DevicePreset(item["name"] as? String ?: error("Missing preset name."), values,
            item["id"] as? String ?: error("Missing preset identifier."))
    }
}
