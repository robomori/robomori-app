package com.robomori

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/** The part of a line a text term looks in; [Any] is free text, which looks in the tag and the message. */
internal enum class QueryField { Any, Tag, Message, Process }

/** Lines that belong to something bigger than themselves, which `is:` picks out. */
internal enum class LineKind { Crash, Stacktrace }

/** How a text term matches: by containing [Contains.text], ignoring case, or by a regex found anywhere in the text. */
internal sealed interface TextMatch {
    fun matches(text: String): Boolean
    /** Where the text matches, for highlighting. */
    fun find(text: String): List<IntRange>

    data class Contains(val text: String) : TextMatch {
        override fun matches(text: String) = text.contains(this.text, ignoreCase = true)
        override fun find(text: String): List<IntRange> = buildList {
            var from = text.indexOf(this@Contains.text, ignoreCase = true)
            while (from >= 0) {
                add(from until from + this@Contains.text.length)
                from = text.indexOf(this@Contains.text, from + this@Contains.text.length, ignoreCase = true)
            }
        }
    }

    /** A regex, ignoring case like plain text does. Built once here rather than per line, and compared by its [pattern]. */
    class Pattern(val pattern: String) : TextMatch {
        private val regex = Regex(pattern, RegexOption.IGNORE_CASE)
        override fun matches(text: String) = regex.containsMatchIn(text)
        override fun find(text: String) = regex.findAll(text).map { it.range }.filterNot { it.isEmpty() }.toList()
        override fun equals(other: Any?) = other is Pattern && other.pattern == pattern
        override fun hashCode() = pattern.hashCode()
        override fun toString() = "Pattern($pattern)"
    }

    /** A regex that doesn't compile; it matches nothing rather than failing the whole query. */
    data class Invalid(val pattern: String) : TextMatch {
        override fun matches(text: String) = false
        override fun find(text: String) = emptyList<IntRange>()
    }
}

/** One condition of a [LogQuery]. Terms with the same [key] are alternatives; a [negated] one must not match. */
internal sealed interface QueryTerm {
    val negated: Boolean
    val key: String

    data class Text(val field: QueryField, val match: TextMatch, override val negated: Boolean = false) : QueryTerm {
        override val key get() = field.name
    }

    /** Lines at [level] or above. */
    data class Level(val level: LogLevel, override val negated: Boolean = false) : QueryTerm {
        override val key get() = "level"
    }

    /** Lines logged within [duration] of now. */
    data class Age(val duration: Duration, override val negated: Boolean = false) : QueryTerm {
        override val key get() = "age"
    }

    data class Is(val kind: LineKind, override val negated: Boolean = false) : QueryTerm {
        override val key get() = "is"
    }
}

/** Why part of a query was left out or can't match, so the panel can say so without stopping the rest from working. */
internal sealed interface QueryProblem {
    data class InvalidRegex(val pattern: String) : QueryProblem
    data class UnknownLevel(val value: String) : QueryProblem
    data class InvalidAge(val value: String) : QueryProblem
    data class UnknownKind(val value: String) : QueryProblem
    data object UnclosedQuote : QueryProblem
}

/** What a query needs to know about the lines beyond their own text. */
internal class QueryContext(
    val processes: ProcessNames = ProcessNames(),
    val kinds: LineKinds = LineKinds(),
    val now: LocalDateTime = LocalDateTime.now(),
)

/** Where a query's text terms match in a line, for highlighting them. */
internal data class Highlights(val tag: List<IntRange>, val message: List<IntRange>)

/**
 * A filter in the language of Android Studio's Logcat. Free text and `tag:`, `message:` and `process:` look for text,
 * `level:`, `age:` and `is:` for the rest. Terms of the same key are alternatives and different keys must all match,
 * while every negated term must fail, so `-tag:A -tag:B` leaves out both.
 */
internal data class LogQuery(val terms: List<QueryTerm> = emptyList(), val problems: List<QueryProblem> = emptyList()) {
    val isEmpty get() = terms.isEmpty()

    fun matches(entry: LogEntry, context: QueryContext): Boolean {
        val (excluded, required) = terms.partition { it.negated }
        if (excluded.any { it.matches(entry, context) }) return false
        return required.groupBy { it.key }.values.all { group -> group.any { it.matches(entry, context) } }
    }

    fun highlights(entry: LogEntry): Highlights {
        val text = terms.filterIsInstance<QueryTerm.Text>().filter { !it.negated }
        fun ranges(field: QueryField, value: String) =
            text.filter { it.field == field || it.field == QueryField.Any }.flatMap { it.match.find(value) }
        return Highlights(ranges(QueryField.Tag, entry.tag), ranges(QueryField.Message, entry.message))
    }

    private fun QueryTerm.matches(entry: LogEntry, context: QueryContext): Boolean = when (this) {
        is QueryTerm.Text -> when (field) {
            QueryField.Any -> match.matches(entry.tag) || match.matches(entry.message)
            QueryField.Tag -> match.matches(entry.tag)
            QueryField.Message -> match.matches(entry.message)
            QueryField.Process -> context.processes.nameOf(entry.pid, entry.id)?.let(match::matches) == true
        }
        is QueryTerm.Level -> entry.level >= level
        is QueryTerm.Age -> lineTime(entry.time, context.now)?.let { !it.isBefore(context.now.minus(duration.toJavaDuration())) } == true
        is QueryTerm.Is -> entry.id in when (kind) {
            LineKind.Crash -> context.kinds.crashLines
            LineKind.Stacktrace -> context.kinds.stacktraces
        }
    }
}

private val keyedTerm = Regex("""^(-?)(tag|message|msg|level|process|age|is)(~?):""", RegexOption.IGNORE_CASE)

/** Reads a query such as `tag:Net -level:D message~:"time(d|out)"`; parts it can't read are reported, not thrown. */
internal fun parseLogQuery(query: String): LogQuery {
    val problems = mutableListOf<QueryProblem>()
    val terms = mutableListOf<QueryTerm>()
    val words = mutableListOf<String>()
    fun endText() {
        if (words.isNotEmpty()) terms += QueryTerm.Text(QueryField.Any, TextMatch.Contains(words.joinToString(" ")))
        words.clear()
    }
    val (tokens, unclosed) = tokenize(query)
    if (unclosed) problems += QueryProblem.UnclosedQuote
    for (token in tokens) {
        val key = keyedTerm.find(token)
        if (key == null) {
            // Plain words in a row are one phrase, so "request failed" finds that phrase; quotes keep a phrase apart.
            if (token.first() == '"' || token.first() == '\'') { endText(); words += unquote(token); endText() }
            else words += token
            continue
        }
        endText()
        val (negation, name, regex) = key.destructured
        val negated = negation.isNotEmpty()
        val value = unquote(token.substring(key.range.last + 1))
        // A key with nothing after it yet, while it's being typed, filters nothing.
        if (value.isEmpty()) continue
        val field = when (name.lowercase()) {
            "tag" -> QueryField.Tag
            "message", "msg" -> QueryField.Message
            "process" -> QueryField.Process
            else -> null
        }
        when {
            field != null -> {
                val match = if (regex.isEmpty()) TextMatch.Contains(value)
                    else runCatching { TextMatch.Pattern(value) }.getOrElse {
                        problems += QueryProblem.InvalidRegex(value)
                        TextMatch.Invalid(value)
                    }
                terms += QueryTerm.Text(field, match, negated)
            }
            name.equals("level", true) -> parseLevel(value)?.let { terms += QueryTerm.Level(it, negated) }
                ?: run { problems += QueryProblem.UnknownLevel(value) }
            name.equals("age", true) -> parseAge(value)?.let { terms += QueryTerm.Age(it, negated) }
                ?: run { problems += QueryProblem.InvalidAge(value) }
            else -> when (value.lowercase()) {
                "crash" -> terms += QueryTerm.Is(LineKind.Crash, negated)
                "stacktrace" -> terms += QueryTerm.Is(LineKind.Stacktrace, negated)
                else -> problems += QueryProblem.UnknownKind(value)
            }
        }
    }
    endText()
    return LogQuery(terms, problems)
}

/** Splits [query] at spaces outside quotes, keeping the quotes; also says whether a quote was left open. */
private fun tokenize(query: String): Pair<List<String>, Boolean> {
    val tokens = mutableListOf<String>()
    val current = StringBuilder()
    var quote: Char? = null
    for (char in query) {
        when {
            quote != null -> { current.append(char); if (char == quote) quote = null }
            char == '"' || char == '\'' -> { current.append(char); quote = char }
            char.isWhitespace() -> { if (current.isNotEmpty()) tokens += current.toString(); current.clear() }
            else -> current.append(char)
        }
    }
    if (current.isNotEmpty()) tokens += current.toString()
    return tokens to (quote != null)
}

private fun unquote(value: String): String {
    val quote = value.firstOrNull()?.takeIf { it == '"' || it == '\'' } ?: return value
    return value.drop(1).removeSuffix(quote.toString())
}

/** A level by its letter or name, such as `W`, `warn` or `warning`. */
private fun parseLevel(value: String): LogLevel? {
    if (value.length == 1) return LogLevel.of(value.single().uppercaseChar())
    val name = value.lowercase().let { if (it == "warning") "warn" else it }
    return LogLevel.entries.firstOrNull { it.name.lowercase() == name }
}

private val ageValue = Regex("""^(\d+)([smhd])$""", RegexOption.IGNORE_CASE)

/** An age such as `30s`, `5m`, `1h` or `2d`. */
private fun parseAge(value: String): Duration? {
    val (amount, unit) = ageValue.matchEntire(value)?.destructured ?: return null
    val count = amount.toLongOrNull() ?: return null
    return when (unit.lowercase()) {
        "s" -> count.seconds
        "m" -> count.minutes
        "h" -> count.hours
        else -> count.days
    }
}

private val threadtimeFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS")

/**
 * When a line was logged, from its `MM-dd HH:mm:ss.SSS` time, which has no year: the year of [now], or the one before
 * when that would put it in the future, as for December's lines read in January.
 */
internal fun lineTime(time: String, now: LocalDateTime): LocalDateTime? {
    fun at(year: Int) = runCatching { LocalDateTime.parse("$year-$time", threadtimeFormat) }.getOrNull()
    val thisYear = at(now.year) ?: return null
    return if (thisYear.isAfter(now.plusDays(1))) at(now.year - 1) else thisYear
}

/** Which lines are part of a crash, each crash's lines in order, and which lines are stack frames. */
internal data class LineKinds(val crashes: List<List<Long>> = emptyList(), val stacktraces: Set<Long> = emptySet()) {
    val crashLines: Set<Long> = crashes.flatMapTo(HashSet()) { it }
}

private val javaFrame = Regex("""^at [^\s(]+\(.*\)$""")
private val nativeFrame = Regex("""^#\d+ pc [0-9a-fA-F]+ """)

/**
 * Whether [message], without its indent, is part of a stack trace: a Java frame, a cause, or a native frame as
 * tombstones print them. Every line is checked each time lines arrive, so most are ruled out by their first letters.
 */
private fun isStackLine(message: String) = when {
    message.startsWith("at ") -> javaFrame.matches(message)
    message.startsWith("#") -> nativeFrame.containsMatchIn(message)
    else -> message.startsWith("Caused by: ") || message.startsWith("Suppressed: ") ||
        (message.startsWith("... ") && message.endsWith(" more"))
}

/**
 * Finds the crashes in [lines]: a Java crash, from AndroidRuntime's "FATAL EXCEPTION" through the lines its process
 * logs under that tag after it, and a native one, from crash_dump's `*** *** ***` through its DEBUG lines. Other
 * processes' lines can come in between, so each crash follows its own process until it logs under another tag.
 */
internal fun classifyLines(lines: List<LogLine>): LineKinds {
    class Open(val tag: String, val ids: MutableList<Long>)
    val crashes = mutableListOf<MutableList<Long>>()
    val open = HashMap<Int, Open>()
    val stacktraces = HashSet<Long>()
    for (line in lines) {
        if (line !is LogEntry) continue
        val message = line.message.trimStart()
        if (isStackLine(message)) stacktraces += line.id
        val starts = (line.tag == "AndroidRuntime" && message.startsWith("FATAL EXCEPTION")) ||
            (line.tag == "DEBUG" && message.startsWith("*** *** ***"))
        val current = open[line.pid]
        when {
            starts -> mutableListOf(line.id).also { crashes += it; open[line.pid] = Open(line.tag, it) }
            current != null && current.tag == line.tag -> current.ids += line.id
            current != null -> open.remove(line.pid)
        }
    }
    return LineKinds(crashes, stacktraces)
}
