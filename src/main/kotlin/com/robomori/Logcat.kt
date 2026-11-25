package com.robomori

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import java.io.Closeable
import java.io.File
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

internal enum class LogLevel(val letter: Char) {
    Verbose('V'), Debug('D'), Info('I'), Warn('W'), Error('E'), Fatal('F'), Assert('A');

    companion object {
        fun of(letter: Char) = entries.firstOrNull { it.letter == letter }
    }
}

/** A row of the view: [line], standing for [count] lines in a row that repeat it. Its [id] is the first one's. */
internal data class ShownLine(val line: LogLine, val count: Int = 1) {
    val id get() = line.id
}

/** Folds each run of the same line from the same process into one [ShownLine] with its count. */
internal fun collapseRepeats(lines: List<LogLine>): List<ShownLine> {
    val rows = ArrayList<ShownLine>(lines.size)
    for (line in lines) {
        val last = rows.lastOrNull()?.line
        if (line is LogEntry && last is LogEntry && line.pid == last.pid && line.level == last.level &&
            line.tag == last.tag && line.message == last.message) {
            rows[rows.lastIndex] = rows.last().copy(count = rows.last().count + 1)
        } else rows += ShownLine(line)
    }
    return rows
}

/** The levels each filter chip shows; E covers the levels above it too, which are rare. */
internal enum class LogLevelFilter(val levels: Set<LogLevel>) {
    Verbose(setOf(LogLevel.Verbose)), Debug(setOf(LogLevel.Debug)), Info(setOf(LogLevel.Info)), Warn(setOf(LogLevel.Warn)),
    Error(setOf(LogLevel.Error, LogLevel.Fatal, LogLevel.Assert)),
}

/** A row of the log view. [id] tells rows apart in lists, since the same message can repeat. */
internal sealed interface LogLine {
    val id: Long
    /** The row as text, for copying and saving. */
    val text: String
}

/** One line of logcat. */
internal data class LogEntry(override val id: Long, val time: String, val pid: Int, val level: LogLevel, val tag: String,
                             val message: String) : LogLine {
    override val text get() = "$time $pid ${level.letter} $tag: $message"
}

/** An app's process starting or ending, as the system logged it. */
internal data class ProcessMarker(override val id: Long, val time: String, val pid: Int, val process: String,
                                  val started: Boolean) : LogLine {
    override val text get() = "$time ---- Process $pid ${if (started) "started" else "ended"}: $process"
}

private val processStarted = listOf(
    Regex("""^Start proc (\d+):([^\s/]+)/"""),
    // Before Android 8, the name came first.
    Regex("""^Start proc ([^\s:]+(?::[^\s]+)?) for .*?pid=(\d+)"""),
)
private val processEnded = listOf(
    Regex("""^Process ([^\s]+) \(pid (\d+)\) has died"""),
    Regex("""^Killing (\d+):([^\s/]+)/"""),
)

/** The process start or end that [entry] reports, when it's the system saying one did; its [ProcessMarker.id] is [entry]'s. */
internal fun processMarker(entry: LogEntry): ProcessMarker? {
    if (entry.tag != "ActivityManager") return null
    fun marker(regexes: List<Regex>, started: Boolean) = regexes.firstNotNullOfOrNull { regex ->
        val (first, second) = regex.find(entry.message)?.destructured ?: return@firstNotNullOfOrNull null
        val (pid, name) = if (first.all(Char::isDigit)) first to second else second to first
        ProcessMarker(entry.id, entry.time, pid.toInt(), name, started)
    }
    return marker(processStarted, true) ?: marker(processEnded, false)
}

private val threadtimeLine = Regex("""^(\d\d-\d\d \d\d:\d\d:\d\d\.\d{3})\s+(\d+)\s+\d+\s+([VDIWEFA])\s+(.*?)\s*: ?(.*)$""")

/** Reads a line of `logcat -v threadtime`; null for lines that aren't log entries, such as `--------- beginning of main`. */
internal fun parseLogcatLine(line: String, id: Long): LogEntry? {
    val match = threadtimeLine.matchEntire(line.trimEnd('\r')) ?: return null
    val (time, pid, level, tag, message) = match.destructured
    return LogEntry(id, time, pid.toInt(), LogLevel.of(level.single()) ?: return null, tag, message)
}

/** Reads `ps -A -o PID,NAME` into each process's ID and name; apps' processes are named after their package. */
internal fun parseProcesses(output: String): Map<Int, String> = output.lineSequence().mapNotNull { line ->
    val fields = line.trim().split(Regex("\\s+"))
    val pid = fields.firstOrNull()?.toIntOrNull() ?: return@mapNotNull null
    fields.getOrNull(1)?.let { pid to it }
}.toMap()

/**
 * Which process wrote each line. Apps are matched to their lines by name, so an app that restarts with a new process ID
 * keeps its lines. An ID can come back for another process once its own has ended, as devices count only to 32768, so
 * each ID keeps every name it had, each from the line where it began; lines from before the first name known get it.
 */
internal class ProcessNames private constructor(private val names: Map<Int, List<Named>>, private val ends: Map<Int, Long>) {
    constructor() : this(emptyMap(), emptyMap())

    /** [name] for the lines from [from] on. */
    private data class Named(val from: Long, val name: String)

    /** The name of [pid] when it wrote the line [lineId]. */
    fun nameOf(pid: Int, lineId: Long): String? {
        val named = names[pid] ?: return null
        return (named.lastOrNull { it.from <= lineId } ?: named.first()).name
    }

    /** Each process ID's latest name. */
    val current: Map<Int, String> get() = names.mapValues { it.value.last().name }

    /** Whether [pid]'s latest process has ended, so another line saying so repeats it. */
    fun hasEnded(pid: Int): Boolean {
        val end = ends[pid] ?: return false
        return names[pid]?.last()?.let { it.from <= end } ?: true
    }

    /**
     * [pid] is [name], as of the line [lineId], such as the system saying it started it or a later look at the
     * processes. When the process before it ended, everything [pid] wrote since is the new one's.
     */
    fun named(pid: Int, name: String, lineId: Long): ProcessNames {
        val named = names[pid].orEmpty()
        if (named.lastOrNull()?.name == name) return this
        val from = if (named.isNotEmpty() && hasEnded(pid)) ends.getValue(pid) + 1 else lineId
        return ProcessNames(names + (pid to named + Named(from, name)), ends)
    }

    /** [pid] ended with the line [lineId]. */
    fun ended(pid: Int, lineId: Long) = ProcessNames(names, ends + (pid to lineId))

    /** [processes] as one look at the device's processes, taken as of the line [lineId]. */
    fun named(processes: Map<Int, String>, lineId: Long) = processes.entries.fold(this) { names, (pid, name) -> names.named(pid, name, lineId) }
}

/** Whether a process named [process] belongs to the app [packageName], including its other processes such as `app:remote`. */
internal fun belongsTo(process: String, packageName: String) = process == packageName || process.startsWith("$packageName:")

/** A device's log as it's written; [close] stops it and ends [lines]. */
internal interface LogcatStream : Closeable {
    val lines: Sequence<String>
}

internal interface LogcatSource {
    /** Follows [serial]'s log, starting with its recent lines. */
    fun open(serial: String): LogcatStream
    /** The running processes on [serial], by ID. */
    fun processes(serial: String): Map<Int, String>
    /** The apps the user can pick from. */
    fun apps(serial: String): List<InstalledApp>
    /** Empties [serial]'s log buffer, so earlier lines are gone from the device too. */
    fun clearDevice(serial: String)
}

internal class AdbLogcatSource(private val settings: AppSettings, private val apps: InstalledAppsService) : LogcatSource {
    private val adb get() = AndroidService(settings).toolchain.adb.absolutePath

    override fun open(serial: String): LogcatStream {
        val process = ProcessBuilder(adb, "-s", serial, "logcat", "-v", "threadtime", "-T", "$RecentLines")
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        return object : LogcatStream {
            override val lines = process.inputStream.bufferedReader().lineSequence()
            override fun close() { process.destroy() }
        }
    }

    override fun processes(serial: String): Map<Int, String> {
        val process = ProcessBuilder(adb, "-s", serial, "shell", "ps -A -o PID,NAME").redirectErrorStream(true).start()
        try {
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0) { "Could not read the device's processes." }
            return parseProcesses(output)
        } finally { if (process.isAlive) process.destroyForcibly() }
    }

    override fun apps(serial: String) = apps.debuggableApps(serial)

    override fun clearDevice(serial: String) {
        val process = ProcessBuilder(adb, "-s", serial, "logcat", "-c").redirectErrorStream(true).start()
        try {
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0) {
                output.trim().ifEmpty { "Could not clear the device's logs." }
            }
        } finally { if (process.isAlive) process.destroyForcibly() }
    }
}

/** How many earlier lines a new log view starts with. */
private const val RecentLines = 1000
/** How many lines the view keeps; older ones drop off the top. */
internal const val MaxLogLines = 20_000

internal enum class LogcatFailure { ClearDevice, Save, Open }

/** A log saved earlier and opened to read, by its file's [name]; it doesn't change, and [processes] come from its own lines. */
internal data class ImportedLog(val name: String, val lines: List<LogLine>, val processes: ProcessNames)

/**
 * [entries] as rows, each followed by the process start or end it reports, with [processes] updated by them; [nextId]
 * gives each marker an ID of its own. A process ending is shown once, though the system can say so twice, as
 * "Killing" and then "has died".
 */
private fun withMarkers(entries: List<LogEntry>, processes: ProcessNames, nextId: () -> Long): Pair<List<LogLine>, ProcessNames> {
    var names = processes
    val rows = ArrayList<LogLine>(entries.size)
    for (entry in entries) {
        rows += entry
        val marker = processMarker(entry) ?: continue
        names = when {
            marker.started -> names.named(marker.pid, marker.process, entry.id)
            names.hasEnded(marker.pid) -> continue
            else -> names.ended(marker.pid, entry.id)
        }
        rows += marker.copy(id = nextId())
    }
    return rows to names
}

/** Reads a saved `logcat -v threadtime` log, skipping lines that aren't entries. */
internal fun readSavedLog(name: String, text: String): ImportedLog {
    var id = 0L
    val entries = text.lineSequence().mapNotNull { parseLogcatLine(it, id++) }.toList()
    val (rows, processes) = withMarkers(entries, ProcessNames()) { id++ }
    return ImportedLog(name, rows, processes)
}

/** Why a stack frame's file didn't open in the IDE. */
internal sealed interface UnopenedSource {
    val frame: StackFrame
    /** Neither Android Studio nor IntelliJ IDEA is running. */
    data class NoIde(override val frame: StackFrame) : UnopenedSource
    /** No project open in the IDE has the file. */
    data class NotInProjects(override val frame: StackFrame) : UnopenedSource
}

internal fun copyToClipboard(text: String) =
    java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(text), null)

internal sealed interface LogcatStatus {
    data object Connecting : LogcatStatus
    data object Streaming : LogcatStatus
    /** The device stopped sending its log, for example because it was disconnected. */
    data object Ended : LogcatStatus
    data class Failed(val cause: Throwable) : LogcatStatus
}

/** One device's log, from the first time it's shown until the device is deselected, and how it's narrowed down. */
internal class LogcatState(
    private val source: LogcatSource,
    val serial: String,
    private val scope: CoroutineScope,
    private val settingsStore: LogcatSettingsStore,
    private val now: () -> LocalDateTime = LocalDateTime::now,
    private val sourceOpener: SourceOpener = IdeSourceOpener(),
    private val copy: (String) -> Unit = ::copyToClipboard,
) {
    var status by mutableStateOf<LogcatStatus>(LogcatStatus.Connecting); private set
    var entries by mutableStateOf<List<LogLine>>(emptyList()); private set
    /** A saved log being read instead of the device's, until [closeImport]. */
    var imported by mutableStateOf<ImportedLog?>(null); private set
    var settings by mutableStateOf(runCatching { settingsStore.load() }.getOrDefault(LogcatSettings())); private set
    /** The apps to narrow the log to, by name. */
    var apps by mutableStateOf<List<InstalledApp>>(emptyList()); private set
    /** The app whose lines show, by package; null shows every line. */
    var app by mutableStateOf<String?>(null); private set
    /** Whether [app] was picked by the user, or left to start on the app that's running. */
    private var appChosen = false
    /** The level chips that are off. */
    var hiddenLevels by mutableStateOf<Set<LogLevelFilter>>(emptySet()); private set
    /** The filter, in the query language of [parseLogQuery]. */
    val search = TextFieldState()
    /** Whether the view stays where it is as new lines come in. */
    var paused by mutableStateOf(false)
    private var processes by mutableStateOf(ProcessNames())
    private var nextId = 0L

    /** [search] as a query, with what couldn't be read in it. */
    val query by derivedStateOf { parseLogQuery(search.text.toString()) }

    /** The lines being read: the saved log that's open, or the device's. */
    private val lines get() = imported?.lines ?: entries
    private val knownProcesses get() = imported?.processes ?: processes

    /** Which lines are crashes and stack frames, found in all the lines, since a crash's lines only make sense together. */
    val kinds by derivedStateOf { classifyLines(lines) }

    /**
     * The lines that pass the filters: tags the user hid are left out first; then [app]'s lines and its processes
     * starting and ending, or every line without the markers, at the levels that are on and, unless highlighting,
     * matching [query]. A saved log shows every app's lines, as its processes may not be known.
     */
    val filtered by derivedStateOf {
        val selected = app.takeIf { imported == null }
        val levels = LogLevelFilter.entries.filter { it !in hiddenLevels }.flatMapTo(mutableSetOf()) { it.levels }
        val ignored = settings.ignoredTags
        val narrowing = query.takeUnless { settings.highlight || it.isEmpty }
        val processes = knownProcesses
        val context = QueryContext(processes, kinds, now())
        lines.filter { line ->
            when (line) {
                is ProcessMarker -> selected != null && belongsTo(line.process, selected)
                is LogEntry -> line.tag !in ignored && line.level in levels &&
                    (selected == null || processes.nameOf(line.pid, line.id)?.let { belongsTo(it, selected) } == true) &&
                    (narrowing == null || narrowing.matches(line, context))
            }
        }
    }

    /** The rows to show: [filtered], with each run of the same line repeating as one row with its count. */
    val shown by derivedStateOf { collapseRepeats(filtered) }

    /** Where [query]'s text matches in [entry], while highlighting rather than filtering; null when there's nothing to mark. */
    fun highlights(entry: LogEntry): Highlights? = query.takeIf { settings.highlight && !it.isEmpty }?.highlights(entry)

    /** The rows, by index in [shown], where each crash that shows starts. */
    val crashRows by derivedStateOf {
        val shownIds = filtered.mapTo(HashSet()) { it.id }
        val starts = kinds.crashes.mapNotNullTo(HashSet()) { crash -> crash.firstOrNull { it in shownIds } }
        shown.indices.filter { shown[it].id in starts }
    }

    /**
     * Selects the start of the next crash, or with [forward] false the one before, counting from the line selected, or
     * else from [firstVisible], the first row in view. Returns its row to scroll to; null when there's none that way.
     */
    fun goToCrash(forward: Boolean, firstVisible: Int): Int? {
        val selected = selectedLines.singleOrNull()?.let { id -> shown.indexOfFirst { it.id == id } }?.takeIf { it >= 0 }
        val from = selected ?: if (forward) firstVisible - 1 else firstVisible
        val row = (if (forward) crashRows.firstOrNull { it > from } else crashRows.lastOrNull { it < from }) ?: return null
        val id = shown[row].id
        selectedLines = setOf(id)
        anchor = id
        return row
    }

    private fun updateSettings(change: (LogcatSettings) -> LogcatSettings) {
        settings = change(settings)
        // Saves whatever is newest when its turn comes, so saves that overlap can't leave an older one on disk.
        scope.launch(Dispatchers.IO) { runCatching { synchronized(settingsStore) { settingsStore.save(settings) } } }
    }

    /** Leaves out every line with [tag], until it's shown again. */
    fun hideTag(tag: String) = updateSettings { it.copy(ignoredTags = it.ignoredTags + tag) }
    fun showTag(tag: String) = updateSettings { it.copy(ignoredTags = it.ignoredTags - tag) }
    fun showAllTags() = updateSettings { it.copy(ignoredTags = emptySet()) }

    fun setColumns(columns: LogColumns) = updateSettings { it.copy(columns = columns) }

    /** Whether lines that don't match the filter still show, with the matches marked in those that do. */
    fun setHighlight(highlight: Boolean) = updateSettings { it.copy(highlight = highlight) }

    /** Adds the filter being typed to the recent ones. */
    fun rememberSearch() {
        val text = search.text.toString()
        if (text.isNotBlank()) updateSettings { it.copy(history = rememberQuery(it.history, text)) }
    }

    /** Filters by [query], such as one picked from the recent or starred ones. */
    fun applySearch(query: String) {
        search.edit { replace(0, length, query) }
        rememberSearch()
    }

    /** Keeps [query] on top of the recent filters, however many come after it, or lets it go again. */
    fun setStarred(query: String, starred: Boolean) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        updateSettings { it.copy(starred = if (starred) (it.starred - trimmed) + trimmed else it.starred - trimmed) }
    }

    fun selectApp(packageName: String?) {
        app = packageName
        appChosen = true
    }

    fun toggleLevel(level: LogLevelFilter) {
        hiddenLevels = if (level in hiddenLevels) hiddenLevels - level else hiddenLevels + level
    }

    /** Until the user picks, narrows the log to the debuggable app started most recently, when one is running. */
    private fun pickDefaultApp() {
        if (appChosen) return
        val packages = apps.mapTo(mutableSetOf()) { it.packageName }
        val newest = processes.current.entries.filter { (_, name) -> name in packages }.maxByOrNull { it.key } ?: return
        app = newest.value
        appChosen = true
    }

    /** The last copy, save or clear that failed, to show until the next one. */
    var failure by mutableStateOf<LogcatFailure?>(null); private set

    /** The selected lines, by [LogEntry.id]. */
    var selectedLines by mutableStateOf<Set<Long>>(emptySet()); private set
    private var anchor: Long? = null

    /**
     * Selects the line [id] alone, or adds or removes it with [toggle], or the lines from the last one clicked with
     * [extend]. Clicking the only selected line again deselects it.
     */
    fun selectLine(id: Long, toggle: Boolean = false, extend: Boolean = false) {
        val order = shown.map { it.id }
        val from = order.indexOf(anchor)
        val to = order.indexOf(id)
        selectedLines = when {
            extend && from >= 0 && to >= 0 -> order.subList(minOf(from, to), maxOf(from, to) + 1).toSet()
            toggle -> if (id in selectedLines) selectedLines - id else selectedLines + id
            selectedLines == setOf(id) -> emptySet()
            else -> setOf(id)
        }
        if (!extend || from < 0) anchor = id
    }

    /** Selects the line [id] a context menu opened on, unless it's already part of the selection the menu acts on. */
    fun selectForMenu(id: Long) {
        if (id !in selectedLines) { selectedLines = setOf(id); anchor = id }
    }

    /** The selected rows as logcat prints them, each repeated line once. */
    fun selectedText() = shown.filter { it.id in selectedLines }.joinToString("\n", postfix = "\n") { it.line.text }

    /** Only the messages of the selected lines, each repeated line once. */
    fun selectedMessages() = shown.filter { it.id in selectedLines }.mapNotNull { (it.line as? LogEntry)?.message }
        .joinToString("\n", postfix = "\n")

    /** The tags of the selected lines, in the order they show. */
    fun selectedTags() = shown.filter { it.id in selectedLines }.mapNotNull { (it.line as? LogEntry)?.tag }.distinct()

    /** Empties the view: the saved log that's open, or the device's lines so far. */
    fun clear() {
        imported?.let { imported = it.copy(lines = emptyList()) } ?: run { entries = emptyList() }
        selectedLines = emptySet()
    }

    /** Shows the saved log in [file] in place of the device's, which keeps being read meanwhile. */
    fun open(file: File) = run(LogcatFailure.Open) {
        val log = withContext(Dispatchers.IO) { readSavedLog(file.name, file.readText()) }
        selectedLines = emptySet()
        imported = log
    }

    /** Goes back from a saved log to the device's. */
    fun closeImport() {
        imported = null
        selectedLines = emptySet()
    }

    /** Clears the device's lines in the view and in its log buffer. */
    fun clearDevice() = run(LogcatFailure.ClearDevice) {
        runInterruptible(Dispatchers.IO) { source.clearDevice(serial) }
        entries = emptyList()
        selectedLines = emptySet()
    }

    /** The lines that pass the filters as logcat prints them, repeats included. */
    fun text() = filtered.joinToString("\n", postfix = "\n") { it.text }

    /** Saves the shown lines to [file]. */
    fun save(file: File) {
        val text = text()
        run(LogcatFailure.Save) { withContext(Dispatchers.IO) { file.writeText(text) } }
    }

    private fun run(step: LogcatFailure, block: suspend () -> Unit) {
        failure = null
        scope.launch {
            try { block() } catch (e: Exception) {
                if (e is CancellationException) throw e
                failure = step
            }
        }
    }

    fun dismissFailure() { failure = null }

    /** The stack frame whose file couldn't be opened last, and why; its location was copied instead. */
    var unopenedSource by mutableStateOf<UnopenedSource?>(null); private set

    /** Opens [frame]'s file at its line in the IDE, or else copies where it points, to find it by hand. */
    fun openSource(frame: StackFrame) {
        unopenedSource = null
        scope.launch {
            val result = runCatching { runInterruptible(Dispatchers.IO) { sourceOpener.open(frame) } }
                .getOrDefault(SourceOpenResult.NotFound)
            unopenedSource = when (result) {
                SourceOpenResult.Opened -> return@launch
                SourceOpenResult.NoIdeRunning -> UnopenedSource.NoIde(frame)
                SourceOpenResult.NotFound -> UnopenedSource.NotInProjects(frame)
            }
            runCatching { copy(frame.location) }
        }
    }

    fun dismissUnopenedSource() { unopenedSource = null }

    /**
     * Follows the log until cancelled, adding lines in batches so a busy device doesn't redraw the view for each one.
     * Process names are read every few seconds, so lines from apps that start later are matched to them too.
     */
    suspend fun observe() = coroutineScope {
        status = LogcatStatus.Connecting
        // A filter left alone for a moment is one the user settled on, so it joins the recent ones.
        launch {
            snapshotFlow { search.text.toString() }.drop(1).collectLatest {
                delay(2000)
                rememberSearch()
            }
        }
        launch {
            apps = runCatching { runInterruptible(Dispatchers.IO) { source.apps(serial) } }.getOrDefault(apps)
            pickDefaultApp()
        }
        launch {
            while (isActive) {
                runCatching { runInterruptible(Dispatchers.IO) { source.processes(serial) } }
                    .onSuccess { processes = processes.named(it, nextId); pickDefaultApp() }
                delay(2000)
            }
        }
        val pending = mutableListOf<String>()
        val stream = try { withContext(Dispatchers.IO) { source.open(serial) } } catch (e: Exception) {
            if (e is CancellationException) throw e
            status = LogcatStatus.Failed(e)
            coroutineContext.cancelChildren()
            return@coroutineScope
        }
        val flusher = launch {
            while (true) {
                delay(100)
                flush(pending)
            }
        }
        try {
            status = LogcatStatus.Streaming
            // Reading blocks, so closing the stream, not cancelling, is what stops it. A closed stream ends the read
            // with an error, which only means it's over.
            launch(Dispatchers.IO) {
                runCatching { stream.lines.forEach { line -> synchronized(pending) { pending += line } } }
            }.join()
            flush(pending)
            status = LogcatStatus.Ended
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            status = LogcatStatus.Failed(e)
        } finally {
            stream.close()
            flusher.cancel()
            coroutineContext.cancelChildren()
        }
    }

    private fun flush(pending: MutableList<String>) {
        val lines = synchronized(pending) { pending.toList().also { pending.clear() } }
        if (lines.isEmpty()) return
        val added = lines.mapNotNull { parseLogcatLine(it, nextId++) }
        if (added.isEmpty()) return
        // The system's own lines say when an app's process starts or ends, sooner than reading the processes again.
        val (rows, names) = withMarkers(added, processes) { nextId++ }
        processes = names
        entries = (entries + rows).takeLast(MaxLogLines)
    }
}
