package com.robomori

import org.w3c.dom.Element
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Where an app keeps a set of preferences: Android's SharedPreferences, Jetpack DataStore's Preferences, or a typed
 * Proto DataStore, whose fields have only numbers in the file and are named by the app's own schema.
 */
internal enum class PreferenceKind { SharedPreferences, DataStore, ProtoDataStore }

internal data class PreferenceFile(val file: DeviceFile, val kind: PreferenceKind) {
    /** Its name as the app's code calls it, without the file's extension. */
    val name get() = file.name.removeSuffix(".xml").removeSuffix(".preferences_pb")
}

internal enum class PreferenceType { String, Int, Long, Float, Double, Boolean, StringSet, Bytes }

/**
 * One preference: its [key], [type], and its value as it reads, null for a SharedPreferences null. A set of strings
 * keeps them in [strings], and bytes in [bytes], since neither is typed as text.
 */
internal data class Preference(
    val key: String,
    val type: PreferenceType,
    val value: String?,
    val strings: List<String> = emptyList(),
    val bytes: ByteArray? = null,
) {
    /** Whether its value can be typed: every type but a set of strings or bytes. */
    val editable get() = type != PreferenceType.StringSet && type != PreferenceType.Bytes

    /** This preference set to [text], read as its type; throws [IllegalArgumentException] for text its type can't hold. */
    fun withValue(text: String): Preference {
        val value = when (type) {
            PreferenceType.Int -> text.trim().toIntOrNull()?.toString() ?: throw IllegalArgumentException("$key takes a whole number.")
            PreferenceType.Long -> text.trim().toLongOrNull()?.toString() ?: throw IllegalArgumentException("$key takes a whole number.")
            PreferenceType.Float -> text.trim().toFloatOrNull()?.toString() ?: throw IllegalArgumentException("$key takes a number.")
            PreferenceType.Double -> text.trim().toDoubleOrNull()?.toString() ?: throw IllegalArgumentException("$key takes a number.")
            PreferenceType.Boolean -> text.trim().lowercase().takeIf { it == "true" || it == "false" }
                ?: throw IllegalArgumentException("$key takes true or false.")
            PreferenceType.String -> text
            PreferenceType.StringSet, PreferenceType.Bytes -> throw IllegalArgumentException("$key can't be typed.")
        }
        return copy(value = value)
    }

    override fun equals(other: Any?) = other is Preference && key == other.key && type == other.type && value == other.value &&
        strings == other.strings && (bytes ?: ByteArray(0)).contentEquals(other.bytes ?: ByteArray(0))

    override fun hashCode() = key.hashCode() * 31 + value.hashCode()
}

/** The columns a preferences file is shown in, as Xcode shows a property list: each key has a type of its own. */
internal val PreferenceColumns = listOf("Key", "Type", "Value")

/** How a preference's value reads in its cell: a set as its strings, bytes by their size. */
internal fun Preference.shown(): String? = when (type) {
    PreferenceType.StringSet -> strings.joinToString(", ", "[", "]")
    PreferenceType.Bytes -> blobLabel(bytes?.size ?: 0)
    else -> value
}

internal sealed interface PreferencesLoad {
    data object Loading : PreferencesLoad
    data class Ready(val entries: List<Preference>) : PreferencesLoad
    /** A Proto DataStore file of [size] bytes, which only the app's schema can read. */
    data class NeedsSchema(val size: Long) : PreferencesLoad
    data class Failed(val message: String) : PreferencesLoad
}

/** Reads a SharedPreferences file, as Android writes them: a `map` of elements named by type, each with a `name`. */
internal fun parseSharedPreferences(xml: ByteArray): List<Preference> {
    if (xml.isEmpty()) return emptyList()
    val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.inputStream())
    val map = document.documentElement
    return map.childNodes.let { nodes -> (0 until nodes.length).mapNotNull { nodes.item(it) as? Element } }.mapNotNull { element ->
        val key = element.getAttribute("name")
        val attribute = element.getAttribute("value")
        when (element.tagName) {
            "string" -> Preference(key, PreferenceType.String, element.textContent)
            "null" -> Preference(key, PreferenceType.String, null)
            "int" -> Preference(key, PreferenceType.Int, attribute)
            "long" -> Preference(key, PreferenceType.Long, attribute)
            "float" -> Preference(key, PreferenceType.Float, attribute)
            "boolean" -> Preference(key, PreferenceType.Boolean, attribute)
            "set" -> {
                val strings = element.getElementsByTagName("string").let { nodes -> (0 until nodes.length).map { nodes.item(it).textContent } }
                Preference(key, PreferenceType.StringSet, null, strings = strings)
            }
            else -> null
        }
    }
}

/** Writes [entries] as a SharedPreferences file, in the form Android itself writes. */
internal fun writeSharedPreferences(entries: List<Preference>): ByteArray = buildString {
    fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    append("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n")
    for (entry in entries) {
        val name = escape(entry.key)
        when (entry.type) {
            PreferenceType.String -> if (entry.value == null) append("    <null name=\"$name\" />\n")
                else append("    <string name=\"$name\">${escape(entry.value)}</string>\n")
            PreferenceType.StringSet -> {
                append("    <set name=\"$name\">\n")
                entry.strings.forEach { append("        <string>${escape(it)}</string>\n") }
                append("    </set>\n")
            }
            PreferenceType.Int, PreferenceType.Long, PreferenceType.Float, PreferenceType.Boolean ->
                append("    <${entry.type.name.lowercase()} name=\"$name\" value=\"${escape(entry.value.orEmpty())}\" />\n")
            // SharedPreferences has neither; a file never holds them.
            PreferenceType.Double, PreferenceType.Bytes -> {}
        }
    }
    append("</map>\n")
}.toByteArray()

/**
 * Reads a DataStore Preferences file: protocol buffers, a `PreferenceMap` of entries, each a key and a `Value` that holds
 * one of a boolean (1), float (2), int (3), long (4), string (5), set of strings (6), double (7) or bytes (8).
 */
internal fun parseDataStore(bytes: ByteArray): List<Preference> {
    val entries = mutableListOf<Preference>()
    ProtoReader(bytes).forEachField { field, reader ->
        if (field != 1) return@forEachField reader.skip()
        var key = ""
        var preference: Preference? = null
        ProtoReader(reader.bytes()).forEachField { entryField, entry ->
            when (entryField) {
                1 -> key = entry.bytes().decodeToString()
                2 -> ProtoReader(entry.bytes()).forEachField { valueField, value ->
                    preference = when (valueField) {
                        1 -> Preference("", PreferenceType.Boolean, (value.varint() != 0L).toString())
                        2 -> Preference("", PreferenceType.Float, Float.fromBits(value.fixed32()).toString())
                        3 -> Preference("", PreferenceType.Int, value.varint().toInt().toString())
                        4 -> Preference("", PreferenceType.Long, value.varint().toString())
                        5 -> Preference("", PreferenceType.String, value.bytes().decodeToString())
                        6 -> {
                            val strings = mutableListOf<String>()
                            ProtoReader(value.bytes()).forEachField { setField, set -> if (setField == 1) strings += set.bytes().decodeToString() else set.skip() }
                            Preference("", PreferenceType.StringSet, null, strings = strings)
                        }
                        7 -> Preference("", PreferenceType.Double, Double.fromBits(value.fixed64()).toString())
                        8 -> value.bytes().let { Preference("", PreferenceType.Bytes, null, bytes = it) }
                        else -> { value.skip(); preference }
                    }
                }
                else -> entry.skip()
            }
        }
        preference?.let { entries += it.copy(key = key) }
    }
    return entries
}

/** Writes [entries] as a DataStore Preferences file. */
internal fun writeDataStore(entries: List<Preference>): ByteArray {
    val map = ProtoWriter()
    for (entry in entries) {
        val value = ProtoWriter()
        when (entry.type) {
            PreferenceType.Boolean -> value.varint(1, if (entry.value == "true") 1 else 0)
            PreferenceType.Float -> value.fixed32(2, entry.value.orEmpty().toFloat().toRawBits())
            PreferenceType.Int -> value.varint(3, entry.value.orEmpty().toInt().toLong())
            PreferenceType.Long -> value.varint(4, entry.value.orEmpty().toLong())
            PreferenceType.String -> value.bytes(5, entry.value.orEmpty().toByteArray())
            PreferenceType.StringSet -> value.bytes(6, ProtoWriter().apply { entry.strings.forEach { bytes(1, it.toByteArray()) } }.toByteArray())
            PreferenceType.Double -> value.fixed64(7, entry.value.orEmpty().toDouble().toRawBits())
            PreferenceType.Bytes -> value.bytes(8, entry.bytes ?: ByteArray(0))
        }
        map.bytes(1, ProtoWriter().apply { bytes(1, entry.key.toByteArray()); bytes(2, value.toByteArray()) }.toByteArray())
    }
    return map.toByteArray()
}

/** Reads the fields of one protocol buffers message, just enough for DataStore's. */
private class ProtoReader(private val data: ByteArray) {
    private var at = 0
    private var wireType = 0

    fun forEachField(onField: (field: Int, reader: ProtoReader) -> Unit) {
        while (at < data.size) {
            val tag = varint()
            wireType = (tag and 7).toInt()
            onField((tag ushr 3).toInt(), this)
        }
    }

    fun varint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val byte = data[at++].toInt()
            result = result or ((byte and 0x7F).toLong() shl shift)
            if (byte and 0x80 == 0) return result
            shift += 7
        }
    }

    fun fixed32(): Int = ByteBuffer.wrap(data, at, 4).order(ByteOrder.LITTLE_ENDIAN).int.also { at += 4 }
    fun fixed64(): Long = ByteBuffer.wrap(data, at, 8).order(ByteOrder.LITTLE_ENDIAN).long.also { at += 8 }
    fun bytes(): ByteArray { val size = varint().toInt(); return data.copyOfRange(at, at + size).also { at += size } }

    fun skip() {
        when (wireType) {
            0 -> varint()
            1 -> at += 8
            2 -> at += varint().toInt()
            5 -> at += 4
            else -> error("This DataStore file isn't one Robomori can read.")
        }
    }
}

private class ProtoWriter {
    private val out = ByteArrayOutputStream()

    private fun raw(value: Long) {
        var rest = value
        while (rest and 0x7FL.inv() != 0L) { out.write(((rest and 0x7F) or 0x80).toInt()); rest = rest ushr 7 }
        out.write(rest.toInt())
    }

    fun varint(field: Int, value: Long) { raw((field shl 3).toLong()); raw(value) }
    fun fixed32(field: Int, value: Int) { raw((field shl 3 or 5).toLong()); out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()) }
    fun fixed64(field: Int, value: Long) { raw((field shl 3 or 1).toLong()); out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()) }
    fun bytes(field: Int, value: ByteArray) { raw((field shl 3 or 2).toLong()); raw(value.size.toLong()); out.write(value) }
    fun toByteArray(): ByteArray = out.toByteArray()
}

/** Reads and writes the preferences files of a device's apps, as the app. */
internal class AppPreferencesService(private val files: DeviceFilesService) {
    /** [app]'s SharedPreferences files, then its DataStore ones, Preferences and Proto alike, each by name. */
    fun files(serial: String, app: InstalledApp): List<PreferenceFile> {
        fun listed(folder: String) = runCatching { files.list(serial, FileRoot.AppData(app), folder) }.getOrDefault(emptyList())
            .filter { it.kind == DeviceFileKind.File }
            .sortedBy { it.name.lowercase() }
        val dataStores = listed("./files/datastore")
            // DataStore writes a new file under a `.tmp` name, then moves it over the old one.
            .filter { !it.name.endsWith(".tmp") }
            .map { PreferenceFile(it, if (it.name.endsWith(".preferences_pb")) PreferenceKind.DataStore else PreferenceKind.ProtoDataStore) }
        return listed("./shared_prefs").filter { it.name.endsWith(".xml") }.map { PreferenceFile(it, PreferenceKind.SharedPreferences) } +
            dataStores
    }

    /** Copies [file] as it is on the device to [destination] on this computer. */
    fun saveCopy(serial: String, app: InstalledApp, file: PreferenceFile, destination: File) =
        files.save(serial, FileRoot.AppData(app), file.file, destination)

    /** What [file] is on the device now; it differs from an earlier one once the app has written it since. */
    fun version(serial: String, app: InstalledApp, file: PreferenceFile): Set<String> =
        files.changeStamps(serial, FileRoot.AppData(app), listOf(file.file.path))

    fun read(serial: String, app: InstalledApp, file: PreferenceFile): List<Preference> {
        val local = Files.createTempFile("robomori-preferences", null).toFile()
        try {
            files.save(serial, FileRoot.AppData(app), file.file, local)
            val bytes = local.readBytes()
            return when (file.kind) {
                PreferenceKind.SharedPreferences -> parseSharedPreferences(bytes)
                PreferenceKind.DataStore -> parseDataStore(bytes)
                PreferenceKind.ProtoDataStore -> error("A Proto DataStore can only be read with the app's schema.")
            }
        } finally { local.delete() }
    }

    /**
     * Replaces [file] on the device with [entries]. A SharedPreferences backup beside it goes, as Android would read the
     * backup in its place.
     */
    fun write(serial: String, app: InstalledApp, file: PreferenceFile, entries: List<Preference>) {
        val local: File = Files.createTempFile("robomori-preferences", null).toFile()
        try {
            local.writeBytes(when (file.kind) {
                PreferenceKind.SharedPreferences -> writeSharedPreferences(entries)
                PreferenceKind.DataStore -> writeDataStore(entries)
                PreferenceKind.ProtoDataStore -> error("A Proto DataStore can only be written with the app's schema.")
            })
            files.writeAsApp(serial, app, local, file.file.path)
            if (file.kind == PreferenceKind.SharedPreferences) files.shellAsApp(serial, app, "rm -f ${quoteShell(file.file.path + ".bak")}")
        } finally { local.delete() }
    }
}
