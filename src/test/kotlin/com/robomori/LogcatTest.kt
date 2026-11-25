package com.robomori

import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import kotlin.test.*

class LogcatTest {
    @Test fun `long names can wrap at their punctuation, and copy without the breaks`() {
        val name = "com.android.providers.media/MediaServiceV2"
        val wrapped = breakable(name)
        assertEquals("com.\u200Bandroid.\u200Bproviders.\u200Bmedia/\u200BMediaServiceV2", wrapped)
        assertEquals(name, unbreakable(wrapped))
    }

    @Test fun `reads a threadtime line`() {
        val entry = parseLogcatLine("11-20 14:10:01.123  4321  4330 W ActivityManager: Slow operation: took 52ms", 7)
        assertEquals(LogEntry(7, "11-20 14:10:01.123", 4321, LogLevel.Warn, "ActivityManager", "Slow operation: took 52ms"), entry)
    }

    @Test fun `keeps tags with spaces and colons in the message`() {
        val entry = assertNotNull(parseLogcatLine("11-20 14:10:01.123  100  101 I My Tag  : key: value", 0))
        assertEquals("My Tag", entry.tag)
        assertEquals("key: value", entry.message)
    }

    @Test fun `skips lines that are not log entries`() {
        assertNull(parseLogcatLine("--------- beginning of main", 0))
    }

    @Test fun `reads process names by ID`() {
        val processes = parseProcesses("""
              PID NAME
                1 init
             4321 com.example.app
             4400 com.example.app:remote
        """.trimIndent())
        assertEquals(mapOf(1 to "init", 4321 to "com.example.app", 4400 to "com.example.app:remote"), processes)
    }

    @Test fun `an app's other processes belong to it, other packages that share its prefix don't`() {
        assertTrue(belongsTo("com.example.app", "com.example.app"))
        assertTrue(belongsTo("com.example.app:remote", "com.example.app"))
        assertFalse(belongsTo("com.example.application", "com.example.app"))
    }

    @Test fun `starts on the running debuggable app, and picking all apps shows every line`(): Unit = runBlocking {
        val source = FakeLogcatSource(mapOf(10 to "com.example.app", 20 to "com.android.systemui"))
        val logcat = LogcatState(source, "emulator-5554", this, MemoryLogcatSettings())
        val observing = launch { logcat.observe() }
        source.lines.put("11-20 14:10:01.123    10    10 I App: from the app")
        source.lines.put("11-20 14:10:01.124    20    20 D SystemUi: from the system")
        withTimeout(5_000) { while (logcat.entries.size < 2 || logcat.app == null) delay(20) }
        assertEquals("com.example.app", logcat.app)
        assertEquals(listOf("from the app"), logcat.shown.map { (it.line as LogEntry).message })
        logcat.selectApp(null)
        assertEquals(2, logcat.shown.size)
        logcat.selectApp("com.example.app")
        assertEquals(listOf("com.example.app"), logcat.apps.map { it.packageName })
        assertEquals("11-20 14:10:01.123 10 I App: from the app\n", logcat.text())
        val saved = kotlin.io.path.createTempFile(suffix = ".txt").toFile()
        logcat.save(saved)
        withTimeout(5_000) { while (saved.length() == 0L) delay(20) }
        assertEquals(logcat.text(), saved.readText())
        logcat.clearDevice()
        withTimeout(5_000) { while (logcat.entries.isNotEmpty()) delay(20) }
        assertTrue(source.deviceCleared)
        assertNull(logcat.failure)
        observing.cancelAndJoin()
        assertTrue(source.closed)
    }

    @Test fun `levels and search narrow the lines`(): Unit = runBlocking {
        val source = FakeLogcatSource(emptyMap())
        val logcat = LogcatState(source, "emulator-5554", this, MemoryLogcatSettings())
        val observing = launch { logcat.observe() }
        source.lines.put("11-20 14:10:01.123    10    10 D Net: connected")
        source.lines.put("11-20 14:10:01.124    10    10 E Net: request failed")
        source.lines.put("11-20 14:10:01.125    10    10 F Crash: fatal")
        withTimeout(5_000) { while (logcat.entries.size < 3) delay(20) }
        logcat.toggleLevel(LogLevelFilter.Error)
        assertEquals(listOf("connected"), logcat.shown.map { (it.line as LogEntry).message })
        logcat.toggleLevel(LogLevelFilter.Error)
        logcat.search.edit { replace(0, length, "FAIL") }
        assertEquals(listOf("request failed"), logcat.shown.map { (it.line as LogEntry).message })
        logcat.search.edit { replace(0, length, "crash") }
        assertEquals(listOf("fatal"), logcat.shown.map { (it.line as LogEntry).message })
        observing.cancelAndJoin()
    }

    @Test fun `a run of the same line shows once with its count`() {
        fun entry(id: Long, message: String, pid: Int = 1) = LogEntry(id, "t", pid, LogLevel.Info, "Tag", message)
        val rows = collapseRepeats(listOf(entry(0, "a"), entry(1, "a"), entry(2, "a"), entry(3, "b"), entry(4, "a"), entry(5, "a", pid = 2)))
        assertEquals(listOf(0L to 3, 3L to 1, 4L to 1, 5L to 1), rows.map { it.id to it.count })
    }

    @Test fun `reads process starts and deaths from the system's lines`() {
        fun am(message: String) = LogEntry(0, "11-20 14:10:01.123", 500, LogLevel.Info, "ActivityManager", message)
        assertEquals(ProcessMarker(0, "11-20 14:10:01.123", 4321, "com.example.app", true),
            processMarker(am("Start proc 4321:com.example.app/u0a233 for next-top-activity {com.example.app/.Main}")))
        assertEquals(ProcessMarker(0, "11-20 14:10:01.123", 4321, "com.example.app", false),
            processMarker(am("Process com.example.app (pid 4321) has died: fg  TOP")))
        assertEquals(ProcessMarker(0, "11-20 14:10:01.123", 4400, "com.example.app:remote", false),
            processMarker(am("Killing 4400:com.example.app:remote/u0a233 (adj 0): stop com.example.app")))
        assertNull(processMarker(am("Displayed com.example.app/.Main: +300ms")))
        assertNull(processMarker(LogEntry(0, "t", 1, LogLevel.Info, "Other", "Start proc 1:x/u0")))
    }

    @Test fun `an app's process starting and ending shows when it's picked`(): Unit = runBlocking {
        val source = FakeLogcatSource(emptyMap())
        val logcat = LogcatState(source, "emulator-5554", this, MemoryLogcatSettings())
        val observing = launch { logcat.observe() }
        source.lines.put("11-20 14:10:01.100   500   510 I ActivityManager: Start proc 4321:com.example.app/u0a233 for activity")
        source.lines.put("11-20 14:10:01.200  4321  4321 I App: hello")
        source.lines.put("11-20 14:10:02.000   500   510 I ActivityManager: Process com.example.app (pid 4321) has died: fg  TOP")
        withTimeout(5_000) { while (logcat.entries.size < 5) delay(20) }
        logcat.selectApp("com.example.app")
        assertEquals(listOf("started", "hello", "ended"), logcat.shown.map {
            when (val line = it.line) {
                is ProcessMarker -> if (line.started) "started" else "ended"
                is LogEntry -> line.message
            }
        })
        logcat.selectApp(null)
        assertTrue(logcat.shown.none { it.line is ProcessMarker })
        observing.cancelAndJoin()
    }

    @Test fun `an app's lines keep showing after it restarts with a new process ID, even one another process had`(): Unit = runBlocking {
        val source = FakeLogcatSource(emptyMap())
        val logcat = LogcatState(source, "emulator-5554", this, MemoryLogcatSettings())
        val observing = launch { logcat.observe() }
        listOf(
            "11-20 14:10:00.000   500   510 I ActivityManager: Start proc 100:com.other.daemon/u0a9 for service",
            "11-20 14:10:00.100   100   100 I Daemon: from the daemon",
            "11-20 14:10:01.000   500   510 I ActivityManager: Start proc 4321:com.example.app/u0a233 for next-top-activity",
            "11-20 14:10:01.100  4321  4321 I App: first run",
            // A force stop: the system says so twice.
            "11-20 14:10:02.000   500   510 I ActivityManager: Killing 4321:com.example.app/u0a233 (adj 0): stop com.example.app",
            "11-20 14:10:02.010   500   510 I ActivityManager: Process com.example.app (pid 4321) has died: fg  TOP",
            "11-20 14:10:02.500   500   510 I ActivityManager: Process com.other.daemon (pid 100) has died: svc",
            // Devices count process IDs only to 32768, so the app can come back with the daemon's.
            "11-20 14:10:03.000   100   100 I App: second run, before the system says it started",
            "11-20 14:10:03.010   500   510 I ActivityManager: Start proc 100:com.example.app/u0a233 for next-top-activity",
            "11-20 14:10:03.100   100   100 I App: second run",
        ).forEach(source.lines::put)
        withTimeout(5_000) { while (logcat.entries.count { it is LogEntry } < 10) delay(20) }
        logcat.selectApp("com.example.app")
        assertEquals(listOf("Process 4321 started", "first run", "Process 4321 ended", "second run, before the system says it started",
            "Process 100 started", "second run"), logcat.shown.map {
            when (val line = it.line) {
                is ProcessMarker -> "Process ${line.pid} ${if (line.started) "started" else "ended"}"
                is LogEntry -> line.message
            }
        })
        logcat.selectApp(null)
        logcat.search.edit { replace(0, length, "process:daemon") }
        assertEquals(listOf("from the daemon"), logcat.shown.map { (it.line as LogEntry).message })
        observing.cancelAndJoin()
    }

    @Test fun `a process ID keeps each name it had, from the line it began`() {
        val names = ProcessNames().named(mapOf(10 to "a"), lineId = 5)
        assertEquals("a", names.nameOf(10, 0))
        // Seen under another name without its end: the lines until then stay the first one's.
        val renamed = names.named(mapOf(10 to "b"), lineId = 20)
        assertEquals("a", renamed.nameOf(10, 19))
        assertEquals("b", renamed.nameOf(10, 20))
        assertEquals(mapOf(10 to "b"), renamed.current)
        // After its end, everything it wrote is the next one's.
        val ended = renamed.ended(10, lineId = 30)
        assertTrue(ended.hasEnded(10))
        val reused = ended.named(10, "c", lineId = 40)
        assertFalse(reused.hasEnded(10))
        assertEquals("b", reused.nameOf(10, 30))
        assertEquals("c", reused.nameOf(10, 31))
        assertNull(reused.nameOf(11, 0))
    }
}

class StackFrameLinksTest {
    @Test fun `reads a frame's class, file and line, and where it is in the message`() {
        val message = "\tat com.example.app.Main\$onCreate\$1.invoke(Main.kt:12)"
        val frame = assertNotNull(stackFrame(message))
        assertEquals(StackFrame("com.example.app.Main\$onCreate\$1", "Main.kt", 12, 4..message.lastIndex), frame)
        assertEquals("com/example/app", frame.packagePath)
        assertEquals("Main.kt:12", frame.location)
        assertEquals("Foo.java", stackFrame("at com.example.Foo.<init>(Foo.java:3)")?.file)
    }

    @Test fun `frames without a source file and line, and other lines, aren't links`() {
        assertNull(stackFrame("at android.os.Looper.loop(Native Method)"))
        assertNull(stackFrame("at a.b.c(SourceFile:12)"))
        assertNull(stackFrame("at com.example.Foo.bar(Unknown Source:4)"))
        assertNull(stackFrame("Caused by: java.io.IOException: at com.example.Foo.bar(Foo.kt:1)"))
    }

    @Test fun `finds a frame's file in its package's folders, skipping build output`() {
        val project = kotlin.io.path.createTempDirectory().toFile()
        fun file(path: String, text: String = "") = File(project, path).apply { parentFile.mkdirs(); writeText(text) }
        file("app/build/generated/com/example/app/Main.kt")
        val main = file("app/src/main/kotlin/com/example/app/Main.kt")
        file("lib/src/main/kotlin/com/example/lib/Main.kt")
        val frame = assertNotNull(stackFrame("at com.example.app.MainKt.main(Main.kt:3)"))
        assertEquals(main.canonicalPath, findSource(project, frame)?.canonicalPath)
        // Kotlin files needn't sit in their package's folders, so the one declaring the package will do, never another.
        file("app/src/main/kotlin/other/Util.kt", "package com.other\n")
        val util = file("app/src/main/kotlin/Util.kt", "// Helpers\n\npackage com.example\n\nfun f() {}\n")
        assertEquals(util.canonicalPath, findSource(project, assertNotNull(stackFrame("at com.example.UtilKt.f(Util.kt:1)")))?.canonicalPath)
        assertNull(findSource(project, assertNotNull(stackFrame("at org.elsewhere.UtilKt.f(Util.kt:1)"))))
        assertNull(findSource(project, assertNotNull(stackFrame("at com.example.Missing.f(Missing.kt:1)"))))
    }

    @Test fun `reads the projects an IDE has open, then the ones it used last`() {
        val home = kotlin.io.path.createTempDirectory().toFile()
        listOf("open", "recent", "old").forEach { File(home, "projects/$it").mkdirs() }
        fun entry(name: String, activated: Long, open: Boolean = false) = """
            <entry key="${'$'}USER_HOME${'$'}/projects/$name"><value>
              <RecentProjectMetaInfo frameTitle="$name"${if (open) " opened=\"true\"" else ""}>
                <option name="activationTimestamp" value="$activated" />
              </RecentProjectMetaInfo>
            </value></entry>"""
        fun xml(vararg entries: String) = File.createTempFile("recentProjects", ".xml").apply {
            writeText("""<application><component name="RecentProjectsManager"><option name="additionalInfo"><map>
                ${entries.joinToString("")}</map></option></component></application>""")
        }
        assertEquals(listOf(File(home, "projects/open"), File(home, "projects/recent")),
            openProjects(xml(entry("recent", 3), entry("open", 1, open = true), entry("gone", 4, open = true)), home))
        assertEquals(listOf("recent", "old"), openProjects(xml(entry("old", 1), entry("recent", 2)), home).map { it.name })
        assertEquals(emptyList(), openProjects(File(home, "missing.xml"), home))
    }

    @Test fun `knows a running Android Studio by its launcher, with its latest settings`() {
        val home = kotlin.io.path.createTempDirectory().toFile()
        val older = File(home, "Library/Application Support/Google/AndroidStudio2025.3/options").apply { mkdirs() }
        val newer = File(home, "Library/Application Support/Google/AndroidStudio2026.1/options").apply { mkdirs() }
        File(older, "recentProjects.xml").apply { writeText("") }.setLastModified(1_000)
        File(newer, "recentProjects.xml").apply { writeText("") }.setLastModified(2_000)
        val mac = HostPlatform(HostOs.MAC, home, emptyMap(), null)
        val studio = File("/Applications/Android Studio.app/Contents/MacOS/studio")
        assertEquals(RunningIde(studio, listOf(newer.parentFile)), runningIde(studio, mac))
        assertNull(runningIde(File("/Applications/Safari.app/Contents/MacOS/Safari"), mac))
    }

    @Test fun `an IDE gets the file at its line, and without one, the location is copied and why it didn't open is kept`(): Unit = runBlocking {
        val frame = assertNotNull(stackFrame("at com.example.app.Main.onCreate(Main.kt:12)"))
        val opener = FakeSourceOpener()
        val copied = mutableListOf<String>()
        val logcat = LogcatState(FakeLogcatSource(emptyMap()), "emulator-5554", this, MemoryLogcatSettings(),
            sourceOpener = opener, copy = { copied += it })
        logcat.openSource(frame)
        withTimeout(5_000) { while (opener.opened.isEmpty()) delay(20) }
        assertEquals(listOf(frame), opener.opened)
        assertNull(logcat.unopenedSource)
        assertEquals(emptyList(), copied)

        opener.result = SourceOpenResult.NotFound
        logcat.openSource(frame)
        withTimeout(5_000) { while (logcat.unopenedSource == null) delay(20) }
        assertEquals(UnopenedSource.NotInProjects(frame), logcat.unopenedSource)
        assertEquals(listOf("Main.kt:12"), copied)

        opener.result = SourceOpenResult.NoIdeRunning
        logcat.openSource(frame)
        withTimeout(5_000) { while (logcat.unopenedSource !is UnopenedSource.NoIde) delay(20) }
        logcat.dismissUnopenedSource()
        assertNull(logcat.unopenedSource)
    }

    @Test fun `the opener hands the file to the running IDE's launcher with its line`() {
        val home = kotlin.io.path.createTempDirectory().toFile()
        val source = File(home, "projects/app/src/main/kotlin/com/example/app/Main.kt").apply { parentFile.mkdirs(); writeText("") }
        val config = File(home, "config").apply { File(this, "options").mkdirs() }
        File(config, "options/recentProjects.xml").writeText("""<application><component name="RecentProjectsManager"><option name="additionalInfo"><map>
            <entry key="${'$'}USER_HOME${'$'}/projects/app"><value><RecentProjectMetaInfo opened="true" /></value></entry>
            </map></option></component></application>""")
        val launched = mutableListOf<List<String>>()
        val studio = RunningIde(File("/Applications/Android Studio.app/Contents/MacOS/studio"), listOf(config))
        val frame = assertNotNull(stackFrame("at com.example.app.Main.onCreate(Main.kt:12)"))
        val platform = HostPlatform(HostOs.MAC, home, emptyMap(), null)
        assertEquals(SourceOpenResult.Opened, IdeSourceOpener(platform, { listOf(studio) }, { launched += it }).open(frame))
        assertEquals(listOf(listOf(studio.launcher.path, "--line", "12", source.path)), launched)
        assertEquals(SourceOpenResult.NotFound, IdeSourceOpener(platform, { listOf(studio) }, { launched += it })
            .open(assertNotNull(stackFrame("at android.app.Activity.performCreate(Activity.java:9)"))))
        assertEquals(SourceOpenResult.NoIdeRunning, IdeSourceOpener(platform, { emptyList() }, { launched += it }).open(frame))
    }
}

private class FakeSourceOpener : SourceOpener {
    @Volatile var result = SourceOpenResult.Opened
    val opened = java.util.Collections.synchronizedList(mutableListOf<StackFrame>())
    override fun open(frame: StackFrame) = result.also { opened += frame }
}

class LogcatSelectionTest {
    @Test fun `clicking selects a line, clicking it again deselects it, and modifier keys add lines`(): Unit = runBlocking {
        val source = FakeLogcatSource(emptyMap())
        val logcat = LogcatState(source, "emulator-5554", this, MemoryLogcatSettings())
        val observing = launch { logcat.observe() }
        repeat(4) { source.lines.put("11-20 14:10:01.12$it    10    10 I App: line $it") }
        withTimeout(5_000) { while (logcat.entries.size < 4) delay(20) }
        val (a, b, c, d) = logcat.entries.map { it.id }
        logcat.selectLine(b)
        assertEquals(setOf(b), logcat.selectedLines)
        logcat.selectLine(b)
        assertEquals(emptySet(), logcat.selectedLines)
        logcat.selectLine(a)
        logcat.selectLine(c, extend = true)
        assertEquals(setOf(a, b, c), logcat.selectedLines)
        logcat.selectLine(b, toggle = true)
        assertEquals(setOf(a, c), logcat.selectedLines)
        assertEquals("11-20 14:10:01.120 10 I App: line 0\n11-20 14:10:01.122 10 I App: line 2\n", logcat.selectedText())
        // A menu on a selected line acts on the selection; on another line, on that line alone.
        logcat.selectForMenu(c)
        assertEquals(setOf(a, c), logcat.selectedLines)
        logcat.selectForMenu(d)
        assertEquals(setOf(d), logcat.selectedLines)
        logcat.clear()
        assertEquals(emptySet(), logcat.selectedLines)
        observing.cancelAndJoin()
    }
}

class LogQueryTest {
    private fun entry(message: String, tag: String = "Net", level: LogLevel = LogLevel.Info, pid: Int = 10, time: String = "11-20 14:10:01.123", id: Long = 0) =
        LogEntry(id, time, pid, level, tag, message)

    private fun LogQuery.matches(entry: LogEntry) = matches(entry, QueryContext(processes = ProcessNames().named(mapOf(10 to "com.example.app"), 0)))

    @Test fun `reads keys, negation, regex and quoted values into terms`() {
        assertEquals(LogQuery(listOf(
            QueryTerm.Text(QueryField.Tag, TextMatch.Contains("My Tag")),
            QueryTerm.Text(QueryField.Message, TextMatch.Pattern("time(d|out)"), negated = true),
            QueryTerm.Level(LogLevel.Warn),
            QueryTerm.Text(QueryField.Process, TextMatch.Contains("com.example")),
            QueryTerm.Age(kotlin.time.Duration.parse("5m")),
            QueryTerm.Is(LineKind.Crash),
            QueryTerm.Text(QueryField.Any, TextMatch.Contains("request failed")),
        )), parseLogQuery("""tag:"My Tag" -message~:time(d|out) level:W process:com.example age:5m is:crash request failed"""))
    }

    @Test fun `free text matches the tag or the message, ignoring case, and a quoted phrase stands apart`() {
        val query = parseLogQuery("FAIL")
        assertTrue(query.matches(entry("request failed")))
        assertTrue(query.matches(entry("ok", tag = "Failover")))
        assertFalse(query.matches(entry("ok")))
        assertEquals(listOf(QueryTerm.Text(QueryField.Any, TextMatch.Contains("a b")), QueryTerm.Text(QueryField.Any, TextMatch.Contains("c"))),
            parseLogQuery("\"a b\" c").terms)
    }

    @Test fun `terms of the same key are alternatives, different keys must all match, and negated ones must not`() {
        val query = parseLogQuery("tag:Net tag:Http level:E")
        assertTrue(query.matches(entry("x", tag = "Net", level = LogLevel.Error)))
        assertTrue(query.matches(entry("x", tag = "Http", level = LogLevel.Fatal)))
        assertFalse(query.matches(entry("x", tag = "Net", level = LogLevel.Warn)))
        assertFalse(query.matches(entry("x", tag = "Db", level = LogLevel.Error)))
        val excluding = parseLogQuery("-tag:Net -tag:Http")
        assertFalse(excluding.matches(entry("x", tag = "Net")))
        assertFalse(excluding.matches(entry("x", tag = "Http")))
        assertTrue(excluding.matches(entry("x", tag = "Db")))
    }

    @Test fun `a level matches it and the levels above it, by letter or name`() {
        assertEquals(listOf(QueryTerm.Level(LogLevel.Warn)), parseLogQuery("level:warning").terms)
        val query = parseLogQuery("level:w")
        assertTrue(query.matches(entry("x", level = LogLevel.Warn)))
        assertTrue(query.matches(entry("x", level = LogLevel.Error)))
        assertFalse(query.matches(entry("x", level = LogLevel.Info)))
    }

    @Test fun `process matches the name of the line's process`() {
        assertTrue(parseLogQuery("process:example").matches(entry("x", pid = 10)))
        assertFalse(parseLogQuery("process:example").matches(entry("x", pid = 11)))
    }

    @Test fun `age keeps lines logged within it`() {
        val context = QueryContext(now = java.time.LocalDateTime.of(2026, 11, 20, 14, 10, 30))
        val query = parseLogQuery("age:30s")
        assertTrue(query.matches(entry("x", time = "11-20 14:10:01.000"), context))
        assertFalse(query.matches(entry("x", time = "11-20 14:09:59.999"), context))
        assertTrue(parseLogQuery("age:1h").matches(entry("x", time = "11-20 13:10:31.000"), context))
    }

    @Test fun `a line's time without a year is from this year, or last year when this one would be ahead`() {
        val january = java.time.LocalDateTime.of(2027, 1, 2, 9, 0)
        assertEquals(java.time.LocalDateTime.of(2026, 12, 31, 23, 59, 59, 500_000_000), lineTime("12-31 23:59:59.500", january))
        assertEquals(java.time.LocalDateTime.of(2027, 1, 2, 8, 0), lineTime("01-02 08:00:00.000", january))
        assertNull(lineTime("t", january))
    }

    @Test fun `an invalid regex matches nothing and is reported, without throwing`() {
        val query = parseLogQuery("message~:ab( tag:Net")
        assertEquals(listOf<QueryProblem>(QueryProblem.InvalidRegex("ab(")), query.problems)
        assertFalse(query.matches(entry("ab(", tag = "Net")))
    }

    @Test fun `values it can't read are reported and left out`() {
        val query = parseLogQuery("level:loud age:soon is:slow \"open")
        assertEquals(listOf(QueryProblem.UnclosedQuote, QueryProblem.UnknownLevel("loud"), QueryProblem.InvalidAge("soon"),
            QueryProblem.UnknownKind("slow")), query.problems)
        assertEquals(listOf(QueryTerm.Text(QueryField.Any, TextMatch.Contains("open"))), query.terms)
    }

    @Test fun `a key still being typed filters nothing, and unknown keys are text`() {
        assertTrue(parseLogQuery("tag:").isEmpty)
        assertEquals(listOf(QueryTerm.Text(QueryField.Any, TextMatch.Contains("http://example.com"))), parseLogQuery("http://example.com").terms)
    }

    @Test fun `highlights where text terms match, in the tag and the message`() {
        val highlights = parseLogQuery("net tag:Ne message~:fail\\w+ -message:x level:E").highlights(entry("Network failed, net down", tag = "Net"))
        assertEquals(listOf(0..1, 0..2), highlights.tag.sortedBy { it.last })
        assertEquals(listOf(0..2, 8..13, 16..18), highlights.message.sortedBy { it.first })
    }
}

class LineKindsTest {
    private var nextId = 0L
    private fun line(pid: Int, level: Char, tag: String, message: String) =
        assertNotNull(parseLogcatLine("11-20 14:10:01.123 $pid $pid $level $tag: $message", nextId++))

    @Test fun `a Java crash runs from FATAL EXCEPTION through its process's AndroidRuntime lines, past other processes' lines`() {
        val lines = listOf(
            line(10, 'I', "App", "before"),
            line(10, 'E', "AndroidRuntime", "FATAL EXCEPTION: main"),
            line(20, 'D', "Other", "in between"),
            line(10, 'E', "AndroidRuntime", "Process: com.example.app, PID: 10"),
            line(10, 'E', "AndroidRuntime", "java.lang.IllegalStateException: boom"),
            line(10, 'E', "AndroidRuntime", "\tat com.example.Main.onCreate(Main.kt:12)"),
            line(10, 'E', "AndroidRuntime", "Caused by: java.io.IOException"),
            line(10, 'I', "Process", "Sending signal. PID: 10 SIG: 9"),
            line(10, 'E', "AndroidRuntime", "later, unrelated"),
        )
        val kinds = classifyLines(lines)
        assertEquals(listOf(listOf(1L, 3, 4, 5, 6)), kinds.crashes)
        assertEquals(setOf(5L, 6L), kinds.stacktraces)
    }

    @Test fun `a native crash is crash_dump's tombstone`() {
        val lines = listOf(
            line(30, 'F', "DEBUG", "*** *** *** *** *** *** *** *** *** *** *** *** *** *** *** ***"),
            line(30, 'F', "DEBUG", "pid: 10, tid: 10, name: main  >>> com.example.app <<<"),
            line(30, 'F', "DEBUG", "      #00 pc 000000000004e0a4  /apex/com.android.runtime/lib64/bionic/libc.so (abort+164)"),
            line(30, 'I', "crash_dump64", "done"),
        )
        val kinds = classifyLines(lines)
        assertEquals(listOf(listOf(0L, 1, 2)), kinds.crashes)
        assertEquals(setOf(2L), kinds.stacktraces)
    }
}

class LogcatSettingsTest {
    @Test fun `settings survive a save and load`() {
        val file = kotlin.io.path.createTempFile(suffix = ".properties").toFile()
        val settings = LogcatSettings(setOf("chatty", "a=b: c"), LogColumns(time = false, pid = true, tag = false), highlight = true,
            history = listOf("tag:Net", "is:crash"), starred = listOf("level:E"))
        FileLogcatSettingsStore(file).save(settings)
        assertEquals(settings, FileLogcatSettingsStore(file).load())
        assertEquals(LogcatSettings(), FileLogcatSettingsStore(File(file.parentFile, "missing-${file.name}")).load())
    }

    @Test fun `recent filters keep the newest first, once each, replace one they extend, and stop at twenty`() {
        assertEquals(listOf("b", "a"), rememberQuery(listOf("a", "b"), " b "))
        assertEquals(listOf("tag:Activity", "x"), rememberQuery(listOf("tag:Act", "x"), "tag:Activity"))
        assertEquals(listOf("a"), rememberQuery(listOf("a"), "  "))
        assertEquals(MaxQueryHistory, rememberQuery((1..MaxQueryHistory).map { "q$it" }, "new").size)
    }
}

class LogcatStateFiltersTest {
    private suspend fun CoroutineScope.logcat(vararg lines: String, store: LogcatSettingsStore = MemoryLogcatSettings(),
                                              block: suspend (LogcatState) -> Unit) {
        val source = FakeLogcatSource(emptyMap())
        val logcat = LogcatState(source, "emulator-5554", this, store)
        val observing = launch { logcat.observe() }
        lines.forEach(source.lines::put)
        withTimeout(5_000) { while (logcat.entries.size < lines.size) delay(20) }
        block(logcat)
        observing.cancelAndJoin()
    }

    private fun LogcatState.messages() = shown.map { (it.line as LogEntry).message }

    @Test fun `hidden tags are left out before the other filters, and kept`(): Unit = runBlocking {
        val store = MemoryLogcatSettings()
        logcat("11-20 14:10:01.100    10    10 I chatty: noise", "11-20 14:10:01.200    10    10 I App: hello", store = store) { logcat ->
            logcat.hideTag("chatty")
            assertEquals(listOf("hello"), logcat.messages())
            logcat.search.edit { replace(0, length, "tag:chatty") }
            assertEquals(emptyList(), logcat.messages())
            logcat.showTag("chatty")
            assertEquals(listOf("noise"), logcat.messages())
            logcat.hideTag("chatty")
        }
        eventually { store.settings.ignoredTags == setOf("chatty") }
        assertEquals(setOf("chatty"), LogcatState(FakeLogcatSource(emptyMap()), "e", this, store).settings.ignoredTags)
    }

    @Test fun `highlighting shows every line and marks the matches`(): Unit = runBlocking {
        logcat("11-20 14:10:01.100    10    10 I Net: connected", "11-20 14:10:01.200    10    10 E Net: request failed") { logcat ->
            logcat.search.edit { replace(0, length, "fail") }
            assertEquals(listOf("request failed"), logcat.messages())
            assertNull(logcat.highlights(logcat.shown.single().line as LogEntry))
            logcat.setHighlight(true)
            assertEquals(listOf("connected", "request failed"), logcat.messages())
            assertEquals(Highlights(emptyList(), listOf(8..11)), logcat.highlights(logcat.shown[1].line as LogEntry))
        }
    }

    @Test fun `counts the crashes shown and goes from one to the next`(): Unit = runBlocking {
        logcat(
            "11-20 14:10:01.100    10    10 I App: start",
            "11-20 14:10:01.200    10    10 E AndroidRuntime: FATAL EXCEPTION: main",
            "11-20 14:10:01.300    10    10 E AndroidRuntime: java.lang.RuntimeException: first",
            "11-20 14:10:02.100    11    11 I App: again",
            "11-20 14:10:02.200    11    11 E AndroidRuntime: FATAL EXCEPTION: main",
            "11-20 14:10:02.300    11    11 E AndroidRuntime: java.lang.RuntimeException: second",
        ) { logcat ->
            assertEquals(listOf(1, 4), logcat.crashRows)
            assertEquals(1, logcat.goToCrash(forward = true, firstVisible = 0))
            assertEquals(setOf(logcat.shown[1].id), logcat.selectedLines)
            assertEquals(4, logcat.goToCrash(forward = true, firstVisible = 0))
            assertNull(logcat.goToCrash(forward = true, firstVisible = 0))
            assertEquals(1, logcat.goToCrash(forward = false, firstVisible = 0))
            logcat.search.edit { replace(0, length, "is:crash") }
            assertEquals(listOf("FATAL EXCEPTION: main", "java.lang.RuntimeException: first", "FATAL EXCEPTION: main",
                "java.lang.RuntimeException: second"), logcat.messages())
            logcat.search.edit { replace(0, length, "-message:first") }
            assertEquals(listOf(1, 3), logcat.crashRows)
        }
    }

    @Test fun `copies only the messages of the selected lines`(): Unit = runBlocking {
        logcat("11-20 14:10:01.100    10    10 I App: one", "11-20 14:10:01.200    10    10 W Net: two") { logcat ->
            val (a, b) = logcat.shown.map { it.id }
            logcat.selectLine(a)
            logcat.selectLine(b, extend = true)
            assertEquals("one\ntwo\n", logcat.selectedMessages())
            assertEquals(listOf("App", "Net"), logcat.selectedTags())
        }
    }

    @Test fun `picked and starred filters are remembered, starred ones apart`(): Unit = runBlocking {
        val store = MemoryLogcatSettings()
        logcat(store = store) { logcat ->
            logcat.applySearch("tag:Net")
            assertEquals("tag:Net", logcat.search.text.toString())
            logcat.applySearch("is:crash")
            logcat.setStarred("tag:Net", true)
            logcat.setStarred("level:E", true)
            logcat.setStarred("level:E", false)
        }
        eventually { store.settings.starred == listOf("tag:Net") }
        assertEquals(listOf("is:crash", "tag:Net"), store.settings.history)
    }

    @Test fun `a saved log shows in place of the device's until closed`(): Unit = runBlocking {
        val file = kotlin.io.path.createTempFile(suffix = ".logcat").toFile()
        file.writeText("""
            --------- beginning of main
            11-19 09:00:00.000   500   510 I ActivityManager: Start proc 4321:com.example.app/u0a233 for activity
            11-19 09:00:01.000  4321  4321 I App: from the file
        """.trimIndent())
        logcat("11-20 14:10:01.100    10    10 I App: live") { logcat ->
            logcat.open(file)
            withTimeout(5_000) { while (logcat.imported == null) delay(20) }
            assertEquals(file.name, logcat.imported?.name)
            assertEquals(mapOf(4321 to "com.example.app"), logcat.imported?.processes?.current)
            assertEquals(listOf("Start proc 4321:com.example.app/u0a233 for activity", "from the file"), logcat.messages())
            logcat.search.edit { replace(0, length, "process:example") }
            assertEquals(listOf("from the file"), logcat.messages())
            logcat.search.edit { replace(0, length, "") }
            logcat.closeImport()
            assertEquals(listOf("live"), logcat.messages())
        }
    }

    @Test fun `columns are kept`(): Unit = runBlocking {
        val store = MemoryLogcatSettings()
        logcat(store = store) { it.setColumns(LogColumns(time = false, pid = true, tag = false)) }
        eventually { store.settings.columns == LogColumns(time = false, pid = true, tag = false) }
    }

    /** Waits for [check], since settings are saved off the caller's thread. */
    private suspend fun eventually(check: () -> Boolean) = withTimeout(5_000) { while (!check()) delay(20) }
}

private class MemoryLogcatSettings : LogcatSettingsStore {
    @Volatile var settings = LogcatSettings()
    override fun load() = settings
    override fun save(settings: LogcatSettings) { this.settings = settings }
}

private class FakeLogcatSource(private val processes: Map<Int, String>) : LogcatSource {
    val lines = LinkedBlockingQueue<String>()
    @Volatile var closed = false

    override fun open(serial: String) = object : LogcatStream {
        override val lines = generateSequence { this@FakeLogcatSource.lines.take().takeUnless { it == END } }
        override fun close() { closed = true; this@FakeLogcatSource.lines.put(END) }
    }

    override fun processes(serial: String) = processes
    override fun apps(serial: String) = listOf(InstalledApp("com.example.app", "Example", null, null, null))
    var deviceCleared = false
    override fun clearDevice(serial: String) { deviceCleared = true }

    private companion object { const val END = "\u0000end" }

}
