package com.robomori

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.Properties

/** Which columns rows show before the message; without the tag, rows are compact. */
internal data class LogColumns(val time: Boolean = true, val pid: Boolean = false, val tag: Boolean = true)

/**
 * How the user likes the log, kept between runs: the tags they hid, the columns, whether filters highlight rather than
 * hide, and the filters they used, newest first, with the [starred] ones kept on top.
 */
internal data class LogcatSettings(
    val ignoredTags: Set<String> = emptySet(),
    val columns: LogColumns = LogColumns(),
    val highlight: Boolean = false,
    val history: List<String> = emptyList(),
    val starred: List<String> = emptyList(),
)

/** How many recent filters are remembered. */
internal const val MaxQueryHistory = 20

internal interface LogcatSettingsStore {
    fun load(): LogcatSettings
    fun save(settings: LogcatSettings)
}

internal class FileLogcatSettingsStore(private val file: File = File(HostPlatform().configDirectory, "logcat.properties")) : LogcatSettingsStore {
    override fun load(): LogcatSettings {
        val properties = Properties().apply { if (file.isFile) file.inputStream().use(::load) }
        // Lists are kept as numbered keys, since tags and filters can hold any character a separator could be.
        fun list(name: String) = generateSequence(0) { it + 1 }.map { properties.getProperty("$name.$it") }.takeWhile { it != null }
            .filterNotNull().toList()
        fun flag(name: String, default: Boolean) = properties.getProperty(name)?.toBooleanStrictOrNull() ?: default
        val defaults = LogColumns()
        return LogcatSettings(
            ignoredTags = list("ignoredTag").toSet(),
            columns = LogColumns(flag("column.time", defaults.time), flag("column.pid", defaults.pid), flag("column.tag", defaults.tag)),
            highlight = flag("highlight", false),
            history = list("history").take(MaxQueryHistory),
            starred = list("starred"),
        )
    }

    override fun save(settings: LogcatSettings) {
        val properties = Properties().apply {
            fun list(name: String, values: Collection<String>) = values.forEachIndexed { index, value -> setProperty("$name.$index", value) }
            list("ignoredTag", settings.ignoredTags.sorted())
            list("history", settings.history)
            list("starred", settings.starred)
            setProperty("column.time", settings.columns.time.toString())
            setProperty("column.pid", settings.columns.pid.toString())
            setProperty("column.tag", settings.columns.tag.toString())
            setProperty("highlight", settings.highlight.toString())
        }
        file.parentFile.mkdirs()
        val temporary = File.createTempFile("logcat-", ".tmp", file.parentFile)
        try {
            temporary.outputStream().use { properties.store(it, null) }
            Files.move(temporary.toPath(), file.toPath(), REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
}

/**
 * [history] with [query] added on top. A query that extends the newest one replaces it, so typing a filter out with
 * pauses keeps only the finished one.
 */
internal fun rememberQuery(history: List<String>, query: String): List<String> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return history
    val rest = history.filter { it != trimmed }.let { if (it.firstOrNull()?.let(trimmed::startsWith) == true) it.drop(1) else it }
    return (listOf(trimmed) + rest).take(MaxQueryHistory)
}
