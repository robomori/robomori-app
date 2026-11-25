package com.robomori

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** Where a device's Files page was scrolled in a folder: the first row in view, and how far into it. */
internal data class FilesScroll(val index: Int, val offset: Int)

/** Where a device's Files page was: the open [folder], the folders [back] and [forward] go to, and each folder's scroll. */
internal data class FilesPlace(
    val folder: String,
    val back: List<String> = emptyList(),
    val forward: List<String> = emptyList(),
    val scrolls: Map<String, FilesScroll> = emptyMap(),
)

/** Each device's [FilesPlace], by its name, kept in a file so the Files page picks up where it was after Robomori quits. */
internal class FilesPlacesStore(private val file: File = File(HostPlatform().configDirectory, "files-places.json")) {
    fun load(): Map<String, FilesPlace> = runCatching {
        if (!file.isFile) return emptyMap()
        Json.parseToJsonElement(file.readText()).jsonObject.mapValues { (_, value) ->
            val place = value.jsonObject
            FilesPlace(
                folder = place.getValue("folder").jsonPrimitive.content,
                back = place["back"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
                forward = place["forward"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
                scrolls = place["scrolls"]?.jsonObject?.mapValues { (_, scroll) ->
                    val (index, offset) = scroll.jsonArray.map { it.jsonPrimitive.int }
                    FilesScroll(index, offset)
                }.orEmpty(),
            )
        }
    }.getOrDefault(emptyMap())

    fun save(places: Map<String, FilesPlace>) {
        val json = buildJsonObject {
            for ((device, place) in places) put(device, buildJsonObject {
                put("folder", place.folder)
                put("back", JsonArray(place.back.map(::JsonPrimitive)))
                put("forward", JsonArray(place.forward.map(::JsonPrimitive)))
                put("scrolls", JsonObject(place.scrolls.mapValues { (_, scroll) ->
                    buildJsonArray { add(JsonPrimitive(scroll.index)); add(JsonPrimitive(scroll.offset)) }
                }))
            })
        }
        file.parentFile.mkdirs()
        val temporary = File.createTempFile("files-places-", ".tmp", file.parentFile)
        try {
            temporary.writeText(json.toString())
            Files.move(temporary.toPath(), file.toPath(), REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
}

/** How many folders' scroll positions a device keeps, so the file stays small. */
internal const val RememberedScrolls = 100
