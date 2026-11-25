package com.robomori

import java.io.File

internal class DevicePresetDirectory(val directory: File = File(HostPlatform().configDirectory, "presets")) {
    fun files(): List<File> {
        if (!directory.exists()) return emptyList()
        return checkNotNull(directory.listFiles()) { "Could not read the presets folder." }
            .filter { it.isFile && it.extension.equals("robomoripreset", ignoreCase = true) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.nameWithoutExtension })
    }

    @Synchronized
    fun create(preset: DevicePreset): File {
        val filename = preset.name.replace(Regex("[<>:\"/\\\\|?*\\p{Cntrl}]"), "-").trimEnd('.', ' ')
        require(filename.isNotBlank()) { "Choose another preset name." }
        require(!Regex("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?").matches(filename)) {
            "Choose another preset name."
        }
        val file = File(directory, "$filename.robomoripreset")
        require(files().none { it.name.equals(file.name, ignoreCase = true) }) {
            "A preset with that name already exists. Choose another name."
        }
        DevicePresetStore(file).save(listOf(preset), replaceExisting = false)
        return file
    }

    @Synchronized
    fun update(file: File, values: Map<PresetSetting, PresetValue>) {
        require(file.parentFile.canonicalFile == directory.canonicalFile && file.isFile) {
            "This preset is no longer available. Save a new preset instead."
        }
        val store = DevicePresetStore(file)
        val presets = store.load()
        require(presets.size == 1) { "This preset file is invalid. Save a new preset instead." }
        val preset = presets.single()
        store.save(listOf(preset.copy(values = values)))
    }
}
