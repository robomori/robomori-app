package com.robomori

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** How many queries each database's history keeps. */
internal const val RememberedQueries = 30

/** [history] with [sql] first, once, keeping the newest [RememberedQueries]. */
internal fun withQuery(history: List<String>, sql: String): List<String> =
    (listOf(sql) + history.filter { it != sql }).take(RememberedQueries)

/**
 * The queries last run on each database, newest first, by app and database, kept in a file so they're there after
 * Robomori quits. Every device's page writes to the one file, so each change reads it again first.
 */
internal class QueryHistoryStore(private val file: File = File(HostPlatform().configDirectory, "query-history.json")) {
    fun load(database: String): List<String> = synchronized(lock) { read()[database].orEmpty() }

    /** Puts [sql] first in [database]'s history. */
    fun record(database: String, sql: String) = synchronized(lock) {
        val histories = read().toMutableMap()
        histories[database] = withQuery(histories[database].orEmpty(), sql)
        val json = JsonObject(histories.mapValues { (_, queries) -> JsonArray(queries.map(::JsonPrimitive)) })
        file.parentFile.mkdirs()
        val temporary = File.createTempFile("query-history-", ".tmp", file.parentFile)
        try {
            temporary.writeText(json.toString())
            Files.move(temporary.toPath(), file.toPath(), REPLACE_EXISTING)
        } finally { temporary.delete() }
    }

    private fun read(): Map<String, List<String>> = runCatching {
        if (!file.isFile) return emptyMap()
        Json.parseToJsonElement(file.readText()).jsonObject.mapValues { (_, queries) -> queries.jsonArray.map { it.jsonPrimitive.content } }
    }.getOrDefault(emptyMap())

    private companion object { val lock = Any() }
}
