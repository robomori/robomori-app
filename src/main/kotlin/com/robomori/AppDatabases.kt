package com.robomori

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import org.sqlite.SQLiteErrorCode
import org.sqlite.SQLiteException
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager

/** A column of a table, as its schema declares it. */
internal data class DatabaseColumn(
    val name: String,
    val type: String = "",
    val notNull: Boolean = false,
    val hasDefault: Boolean = false,
    val primaryKey: Boolean = false,
    /** Its default as the schema writes it, such as `0` or `CURRENT_TIMESTAMP`; null when it has none. */
    val default: String? = null,
) {
    /** An INTEGER PRIMARY KEY is the rowid under another name: never NULL, and numbered by SQLite when a row is added. */
    val isRowId get() = primaryKey && type.equals("INTEGER", ignoreCase = true)
    val refusesNull get() = notNull || isRowId
}

/** A table or view of a database, with its row count for tables. */
internal data class DatabaseTable(
    val name: String,
    val view: Boolean,
    val rows: Long?,
    /** Whether its rows can be changed one cell at a time: a table whose rows have rowids, as all but `WITHOUT ROWID` ones do. */
    val editable: Boolean = !view,
    val columns: List<DatabaseColumn> = emptyList(),
) {
    /** The columns that can't be set to NULL. */
    val notNull: Set<String> get() = columns.filter { it.refusesNull }.mapTo(mutableSetOf()) { it.name }
}

/** An index of a table: the [columns] it orders rows by, an expression's as null, and whether their values are [unique]. */
internal data class DatabaseIndex(val name: String, val unique: Boolean, val columns: List<String?>)

/** How a table is built: its columns, its indexes, and the statements that create it, the table's then its indexes' and triggers'. */
internal data class TableStructure(val columns: List<DatabaseColumn>, val indexes: List<DatabaseIndex>, val sql: String)

/** What the pane shows of a table: its rows, or how it's built. */
internal enum class TableView { Data, Structure }

/** The hidden first column of a table's rows as they're browsed: each row's rowid, which an edit changes it by. */
internal const val RowIdColumn = "robomori_rowid"

/** How a cell holding bytes reads, as they can't be shown or typed. */
internal fun blobLabel(size: Int) = "BLOB · $size bytes"

/** The column rows are sorted by, and which way. */
internal data class DatabaseSort(val column: String, val ascending: Boolean)

/** A deletion waiting to be confirmed: rows of the open table, or keys of the open preferences. */
internal sealed interface PendingDelete {
    data class Rows(val table: String, val rows: List<List<String?>>) : PendingDelete
    data class Keys(val file: String, val keys: List<String>) : PendingDelete
}

/** How the last change made from Robomori went: an edit, an added or deleted row, or a copy saved to this computer. */
internal sealed interface DataAction {
    data object None : DataAction
    data object Working : DataAction
    data class Saved(val file: File) : DataAction
    data class Failed(val message: String) : DataAction
}

/**
 * What a query returned: up to a limit of its rows, with [more] when it had others. A null cell is SQL's NULL; [blobs]
 * holds the row and column of each cell of bytes, shown by its size. A statement that changes the database returns no
 * rows, and how many it [changed].
 */
internal data class QueryResult(
    val columns: List<String>,
    val rows: List<List<String?>>,
    val more: Boolean,
    val changed: Int? = null,
    val blobs: Set<Pair<Int, Int>> = emptySet(),
)

/** The first bytes of every SQLite database file. An encrypted one, such as SQLCipher's, starts with random bytes instead. */
private val SqliteHeader = "SQLite format 3\u0000".toByteArray()

internal fun isSqliteFile(file: File): Boolean = file.inputStream().use { input ->
    val header = ByteArray(SqliteHeader.size)
    input.readNBytes(header, 0, header.size) == header.size && header.contentEquals(SqliteHeader)
}

/** Thrown for a database whose file isn't readable SQLite, as an encrypted one isn't without its key. */
internal class EncryptedDatabaseException : Exception("This database is encrypted.")

/** Thrown for a statement that would change the database, which the copy here can't take. */
internal class WritingStatementException : Exception()

/** A copy of a database taken from the device, opened for reading only. */
internal class DatabaseSnapshot(file: File) : Closeable {
    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").apply {
        // Changes would only reach this copy, never the app, so none are allowed.
        createStatement().use { it.execute("PRAGMA query_only = ON") }
    }

    fun tables(): List<DatabaseTable> {
        val found = connection.createStatement().use { statement ->
            statement.executeQuery("SELECT name, type, sql FROM sqlite_master WHERE type IN ('table', 'view') AND name NOT LIKE 'sqlite_%' ORDER BY name COLLATE NOCASE")
                .use { rows -> buildList { while (rows.next()) add(Triple(rows.getString(1), rows.getString(2) == "view", rows.getString(3).orEmpty())) } }
        }
        return found.map { (name, view, sql) ->
            val count = if (view) null else runCatching {
                connection.createStatement().use { it.executeQuery("SELECT count(*) FROM ${quoteIdentifier(name)}").use { rows -> rows.next(); rows.getLong(1) } }
            }.getOrNull()
            val columns = runCatching { columns(name) }.getOrDefault(emptyList())
            DatabaseTable(name, view, count, editable = !view && !sql.contains(Regex("WITHOUT\\s+ROWID", RegexOption.IGNORE_CASE)),
                columns = columns)
        }
    }

    private fun columns(table: String): List<DatabaseColumn> = connection.createStatement().use { statement ->
        statement.executeQuery("PRAGMA table_info(${quoteIdentifier(table)})").use { rows ->
            buildList {
                while (rows.next()) add(DatabaseColumn(rows.getString("name"), rows.getString("type").orEmpty(),
                    notNull = rows.getInt("notnull") == 1, hasDefault = rows.getString("dflt_value") != null,
                    primaryKey = rows.getInt("pk") > 0, default = rows.getString("dflt_value")))
            }
        }
    }

    /** How [table] is built, as its schema says. */
    fun structure(table: String): TableStructure {
        val indexes = connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA index_list(${quoteIdentifier(table)})").use { rows ->
                buildList { while (rows.next()) add(rows.getString("name") to (rows.getInt("unique") == 1)) }
            }
        }.map { (name, unique) ->
            val columns = connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA index_info(${quoteIdentifier(name)})").use { rows ->
                    buildList { while (rows.next()) add(rows.getString("name")) }
                }
            }
            DatabaseIndex(name, unique, columns)
        }
        // The table's own statement first. Indexes SQLite makes for UNIQUE and PRIMARY KEY constraints have none.
        val sql = connection.prepareStatement("SELECT sql FROM sqlite_master WHERE tbl_name = ? AND sql IS NOT NULL " +
            "ORDER BY CASE type WHEN 'index' THEN 1 WHEN 'trigger' THEN 2 ELSE 0 END, name").use { statement ->
            statement.setString(1, table)
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1).trim().removeSuffix(";") + ";") } }
        }
        return TableStructure(columns(table), indexes, sql.joinToString("\n\n"))
    }

    /**
     * Runs [statements] one after another and returns what the last one did, keeping up to [limit] of its rows. Throws
     * [WritingStatementException] at the first that would change the database, having changed nothing.
     */
    fun query(statements: List<String>, limit: Int = 500): QueryResult = try {
        statements.dropLast(1).forEach { query(it, limit = 0) }
        query(statements.last(), limit)
    } finally {
        // A transaction the statements began and didn't end would keep the next ones from beginning one.
        runCatching { connection.createStatement().use { it.execute("ROLLBACK") } }
    }

    /** Runs [sql], keeping up to [limit] of its rows; one that would change the database throws [WritingStatementException]. */
    fun query(sql: String, limit: Int = 500): QueryResult = try {
        connection.createStatement().use { statement ->
            if (!statement.execute(sql)) return QueryResult(emptyList(), emptyList(), more = false, changed = statement.updateCount)
            statement.resultSet.use { rows ->
                val meta = rows.metaData
                val columns = (1..meta.columnCount).map(meta::getColumnLabel)
                val kept = mutableListOf<List<String?>>()
                val blobs = mutableSetOf<Pair<Int, Int>>()
                var more = false
                while (rows.next()) {
                    if (kept.size == limit) { more = true; break }
                    kept += columns.indices.map { column ->
                        when (val value = rows.getObject(column + 1)) {
                            null -> null
                            is ByteArray -> { blobs += kept.size to column; blobLabel(value.size) }
                            else -> value.toString()
                        }
                    }
                }
                QueryResult(columns, kept, more, blobs = blobs)
            }
        }
    } catch (failure: SQLiteException) {
        if (failure.resultCode.code and 0xFF == SQLiteErrorCode.SQLITE_READONLY.code) throw WritingStatementException()
        throw failure
    }

    /** Saves this copy, with every change its journal holds, as one database file at [file]. */
    fun saveTo(file: File) {
        file.delete()
        connection.createStatement().use { it.executeUpdate("backup to ${file.absolutePath}") }
    }

    override fun close() = connection.close()
}

internal fun quoteIdentifier(name: String) = "\"" + name.replace("\"", "\"\"") + "\""

/** [value] written into SQL: NULL, or text that SQLite turns into a number in a column of numbers. */
internal fun sqlLiteral(value: String?) = if (value == null) "NULL" else "'" + value.replace("'", "''") + "'"

/**
 * Adds a row to [table] holding [values] by column: its other columns take their defaults, and one that must have a
 * value but has no default gets an empty one of its type, such as 0 or ''.
 */
internal fun insertRowSql(table: DatabaseTable, values: Map<String, String?> = emptyMap()): String {
    val needed = table.columns.filter { it.name !in values && it.notNull && !it.hasDefault && !it.isRowId }
    if (values.isEmpty() && needed.isEmpty()) return "INSERT INTO ${quoteIdentifier(table.name)} DEFAULT VALUES"
    val columns = values.keys.toList() + needed.map { it.name }
    val literals = values.values.map(::sqlLiteral) + needed.map { emptyValueOf(it.type) }
    return "INSERT INTO ${quoteIdentifier(table.name)} (${columns.joinToString { quoteIdentifier(it) }}) VALUES (${literals.joinToString()})"
}

/** An empty value of a column declared [type], by SQLite's rules for which kind of value a type holds. */
private fun emptyValueOf(type: String): String {
    val declared = type.uppercase()
    return when {
        listOf("INT", "REAL", "FLOA", "DOUB", "NUM", "DEC", "BOOL").any { it in declared } -> "0"
        "BLOB" in declared -> "x''"
        else -> "''"
    }
}

/** A `WHERE` clause finding the rows of which any of [columns] holds [text], ignoring case; empty when there's no text. */
internal fun searchClause(columns: List<String>, text: String): String {
    val term = text.trim().ifEmpty { return "" }
    val pattern = sqlLiteral("%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%")
    return " WHERE " + columns.joinToString(" OR ") { "CAST(${quoteIdentifier(it)} AS TEXT) LIKE $pattern ESCAPE '\\'" }
}

/**
 * The statements of [sql], split at each `;` that ends one: not one in quotes, in a comment, or in a trigger's
 * `BEGIN … END` body. Statements of nothing but comments are left out.
 */
internal fun splitStatements(sql: String): List<String> {
    val statements = mutableListOf<String>()
    var start = 0
    var content = false
    // The first words of the statement, to tell a trigger, whose body holds statements of its own. CASE … END inside the
    // body is counted too, as it ends with END as well.
    val leading = mutableListOf<String>()
    var depth = 0
    var at = 0
    fun end(until: Int) {
        if (content) statements += sql.substring(start, until).trim()
        start = until + 1
        content = false
        leading.clear()
        depth = 0
    }
    while (at < sql.length) {
        val char = sql[at]
        when {
            sql.startsWith("--", at) -> at = sql.indexOf('\n', at).let { if (it < 0) sql.length else it }
            sql.startsWith("/*", at) -> at = sql.indexOf("*/", at + 2).let { if (it < 0) sql.length else it + 2 }
            char == '\'' || char == '"' || char == '`' || char == '[' -> {
                val close = if (char == '[') ']' else char
                var next = at + 1
                while (next < sql.length) {
                    if (sql[next] == close) {
                        // A quote doubled inside quotes is the quote itself.
                        if (close != ']' && next + 1 < sql.length && sql[next + 1] == close) { next += 2; continue }
                        break
                    }
                    next++
                }
                content = true
                at = next + 1
            }
            char == ';' && depth == 0 -> { end(at); at++ }
            char.isLetter() || char == '_' -> {
                var next = at
                while (next < sql.length && (sql[next].isLetterOrDigit() || sql[next] == '_' || sql[next] == '$')) next++
                val word = sql.substring(at, next).uppercase()
                if (leading.size < 3) leading += word
                if (leading.first() == "CREATE" && "TRIGGER" in leading) when (word) {
                    "BEGIN" -> depth++
                    "CASE" -> if (depth > 0) depth++
                    "END" -> if (depth > 0) depth--
                }
                content = true
                at = next
            }
            else -> { if (!char.isWhitespace()) content = true; at++ }
        }
    }
    end(sql.length)
    return statements
}

/** [rows] under [columns] as CSV, the way spreadsheets read it; NULL is an empty field. */
internal fun toCsv(columns: List<String>, rows: List<List<String?>>): String {
    fun field(value: String?) = when {
        value == null -> ""
        value.any { it == ',' || it == '"' || it == '\n' || it == '\r' } -> "\"" + value.replace("\"", "\"\"") + "\""
        else -> value
    }
    return (listOf(columns) + rows).joinToString("\r\n") { row -> row.joinToString(",") { field(it) } } + "\r\n"
}

/** Orders cells as SQLite does: NULL first, then numbers by value, then text. */
internal fun compareCells(a: String?, b: String?): Int {
    if (a == null || b == null) return compareValues(a == null, b == null).unaryMinus()
    val x = a.toDoubleOrNull()
    val y = b.toDoubleOrNull()
    return when {
        x != null && y != null -> x.compareTo(y)
        x != null -> -1
        y != null -> 1
        else -> a.compareTo(b, ignoreCase = true)
    }
}

internal class AppDatabasesService(
    private val files: DeviceFilesService,
    private val helper: () -> DeviceHelperJar = { DeviceHelperJar.bundled },
) {
    /** The databases in [app]'s `databases` folder, where Android and Room keep them. */
    fun databases(serial: String, app: InstalledApp): List<DeviceFile> {
        val listing = runCatching { files.list(serial, FileRoot.AppData(app), "./databases") }.getOrElse { return emptyList() }
        val names = listing.mapTo(mutableSetOf()) { it.name }
        return listing.filter { file ->
            file.kind == DeviceFileKind.File && journalSuffixes.none { file.name.endsWith(it) } && file.size > 0 &&
                // Files beside a database, such as Room's backups, aren't databases on their own unless they look like one.
                (file.isDatabase || '.' !in file.name || journalSuffixes.any { "${file.name}$it" in names })
        }
    }

    /**
     * What [database] and its journals are on the device now: each one's size and when it last changed. It differs from
     * an earlier one once the app has written to the database since.
     */
    fun version(serial: String, app: InstalledApp, database: DeviceFile): Set<String> =
        files.changeStamps(serial, FileRoot.AppData(app), (journalSuffixes.map { database.path + it } + database.path))

    fun execute(serial: String, app: InstalledApp, database: DeviceFile, sql: String): Int = execute(serial, app, database, listOf(sql))

    /** Whether the device lets the shell become root, which opens every app's files, release builds' too. */
    fun hasRoot(serial: String): Boolean = files.hasRoot(serial)

    /**
     * Runs [statements] on [database] on the device, as the app, through Android's own SQLite and under the app's locks,
     * in one transaction: all of them, or none when one fails. Returns how many rows they changed. For a debuggable app,
     * Robomori's helper goes into the app's own folder first, once per build, since only code the app owns can open its
     * files; any other app's user runs it through root from /data/local/tmp.
     */
    fun execute(serial: String, app: InstalledApp, database: DeviceFile, statements: List<String>): Int {
        val encoded = statements.joinToString(" ") { java.util.Base64.getEncoder().encodeToString(it.toByteArray()) }
        val output = if (app.debuggable) executeAsDebuggable(serial, app, database, encoded) else executeThroughRoot(serial, app, database, encoded)
        val result = output.lineSequence().firstOrNull { it.startsWith("ROBOMORI_CHANGED\t") || it.startsWith("ROBOMORI_ERROR\t") }
            ?: error("Could not change the database. Try again.")
        check(result.startsWith("ROBOMORI_CHANGED")) {
            // SQLite's own message, without the code Android adds after it, such as "(code 1299 SQLITE_CONSTRAINT_NOTNULL)".
            result.substringAfter('\t').replace(Regex("\\s*\\(code \\d+[^)]*\\)\\s*$"), "").trim().ifEmpty { "Could not change the database." }
        }
        return result.substringAfter('\t').trim().toInt()
    }

    private fun executeAsDebuggable(serial: String, app: InstalledApp, database: DeviceFile, encoded: String): String {
        val jar = helper()
        val remote = jar.inApp
        val run = "if [ -f $remote ]; then CLASSPATH=\"\$PWD/$remote\" app_process / com.robomori.device.DatabaseHelper " +
            "${quoteShell(database.path)} $encoded; else echo ROBOMORI_MISSING; fi"
        val output = files.shellAsApp(serial, app, run)
        if (output.lineSequence().none { it.trim() == "ROBOMORI_MISSING" }) return output
        files.shellAsApp(serial, app, "mkdir -p code_cache && rm -f code_cache/robomori-database-*.jar")
        files.installAsApp(serial, app, jar.file, remote)
        return files.shellAsApp(serial, app, run)
    }

    /**
     * Runs the helper as [app]'s own user through root, leaving the app's folder as it was. Journals it makes, such as a
     * write-ahead log the app had closed, then take the database's security label, without which the app couldn't open them.
     */
    private fun executeThroughRoot(serial: String, app: InstalledApp, database: DeviceFile, encoded: String): String {
        val jar = files.stagedHelper(serial, "database")
        val output = files.shellAsApp(serial, app, "CLASSPATH=$jar app_process / com.robomori.device.DatabaseHelper ${quoteShell(database.path)} $encoded")
        files.matchAppFiles(serial, app, database.path, journalSuffixes.map { database.path + it })
        return output
    }

    /**
     * Copies [database] and the journal beside it, which holds its newest changes, into [directory]. Throws
     * [EncryptedDatabaseException] for one that isn't readable SQLite.
     */
    fun snapshot(serial: String, app: InstalledApp, database: DeviceFile, directory: File): File {
        val root = FileRoot.AppData(app)
        val folder = database.path.substringBeforeLast('/')
        val siblings = files.list(serial, root, folder).associateBy { it.name }
        val local = File(directory, "database.db")
        files.save(serial, root, siblings[database.name] ?: error("${database.name} is no longer on the device."), local)
        if (!isSqliteFile(local)) throw EncryptedDatabaseException()
        for (suffix in journalSuffixes) siblings[database.name + suffix]?.let { files.save(serial, root, it, File(directory, "database.db$suffix")) }
        return local
    }

    private companion object { val journalSuffixes = listOf("-wal", "-shm", "-journal") }
}

internal sealed interface DatabaseLoad {
    data object None : DatabaseLoad
    data object Loading : DatabaseLoad
    data class Ready(val tables: List<DatabaseTable>) : DatabaseLoad
    /** Its file isn't readable without a key, as a SQLCipher database isn't. */
    data object Encrypted : DatabaseLoad
    data class Failed(val cause: Throwable) : DatabaseLoad
}

internal sealed interface StructureLoad {
    data object Loading : StructureLoad
    data class Ready(val structure: TableStructure) : StructureLoad
    data class Failed(val message: String) : StructureLoad
}

/** The columns a table's structure is shown in, and its indexes. */
internal val StructureColumns = listOf("Column", "Type", "Not Null", "Default", "Primary Key")
internal val IndexColumns = listOf("Index", "Unique", "Columns")

internal sealed interface QueryState {
    data object Idle : QueryState
    data object Running : QueryState
    data class Done(val result: QueryResult) : QueryState
    data class Failed(val message: String) : QueryState
}

/** How a table's columns are laid out: their order and the share of the width each takes. Each grid keeps its own. */
internal class GridLayout {
    var order by mutableStateOf<List<String>>(emptyList()); private set
    var shares by mutableStateOf<Map<String, Float>>(emptyMap()); private set

    /** Lays out [columns] side by side in equal shares, unless they're the ones laid out already. */
    fun adopt(columns: List<String>) {
        if (columns.toSet() == order.toSet()) return
        order = columns
        shares = columns.associateWith { 1f }
    }

    fun resize(shares: Map<String, Float>) { this.shares = shares }
    fun reorder(order: List<String>) { this.order = order }
}

/**
 * Browsing what one device's apps keep, debuggable ones' or, on a device that allows root, every app's: their databases,
 * read from a copy taken when one is opened and again whenever the app writes to it, and their preferences. Changes go
 * to the app's own files on the device.
 */
internal class AppDatabasesState(
    private val service: AppDatabasesService,
    private val installedApps: InstalledAppsService,
    val serial: String,
    private val scope: CoroutineScope,
    private val preferencesService: AppPreferencesService? = null,
    private val queryHistory: QueryHistoryStore? = null,
    /** Brings changes into the app while it runs; without it, the app sees them once it restarts. */
    private val live: LiveAppData? = null,
) {
    var apps by mutableStateOf<List<InstalledApp>?>(null); private set
    var app by mutableStateOf<InstalledApp?>(null); private set
    var databases by mutableStateOf<List<DeviceFile>>(emptyList()); private set
    var database by mutableStateOf<DeviceFile?>(null); private set
    var load by mutableStateOf<DatabaseLoad>(DatabaseLoad.None); private set
    var table by mutableStateOf<String?>(null); private set
    var result by mutableStateOf<QueryState>(QueryState.Idle); private set
    /** Whether the last try to follow the app's changes failed, so what's shown may be out of date until one works. */
    var updateFailed by mutableStateOf(false); private set
    var sort by mutableStateOf<DatabaseSort?>(null); private set
    /** Text the rows shown must hold in one of their cells; empty shows them all. */
    var search by mutableStateOf(""); private set
    val layout = GridLayout()
    var action by mutableStateOf<DataAction>(DataAction.None); private set
    /** The deletion asked for, until it's confirmed or dismissed. */
    var pendingDelete by mutableStateOf<PendingDelete?>(null); private set
    val query = TextFieldState()
    /** The queries last run on the open database, newest first. */
    var history by mutableStateOf<List<String>>(emptyList()); private set
    /** Whether the open table shows its rows or how it's built, kept as other tables are opened. */
    var tableView by mutableStateOf(TableView.Data); private set
    var structure by mutableStateOf<StructureLoad>(StructureLoad.Loading); private set
    val structureLayout = GridLayout()
    val indexLayout = GridLayout()

    /** The app's preferences files, and the one open in place of a database, if any. */
    var preferenceFiles by mutableStateOf<List<PreferenceFile>>(emptyList()); private set
    var preferenceFile by mutableStateOf<PreferenceFile?>(null); private set
    var preferences by mutableStateOf<PreferencesLoad>(PreferencesLoad.Loading); private set
    /** Where the last change to the open preferences took effect, until another file is opened or the app restarts. */
    var preferencesEdit by mutableStateOf<LiveEdit?>(null); private set
    val preferencesLayout = GridLayout()

    // One connection, used from one thread at a time.
    private val sqlite = Dispatchers.IO.limitedParallelism(1)
    private var snapshot: DatabaseSnapshot? = null
    private var directory: File? = null
    private var job: Job? = null
    // The open file as it was on the device when it was last read, to tell when the app has written to it since.
    private var copiedVersion: Set<String>? = null
    // How many rows the open table or query shows; more load as the list is scrolled to its end.
    private var rowLimit = PageSize
    // The statements whose rows are shown, run again on each new copy and as more rows load. Only ever run on the copy,
    // so a statement that writes, which then fails there, is never run twice.
    private var shown: List<String>? = null
    // Which query of the history the field holds as ↑ and ↓ step through it, -1 for the one being written, kept in draft.
    private var historyAt = -1
    private var draft = ""

    /** Whether the system's own apps are listed too, after the user's, on a device whose every app can be opened. */
    var showSystemApps by mutableStateOf(false); private set
    /** Where the last change to the open database took effect, until another database is opened or the app restarts. */
    var databaseEdit by mutableStateOf<LiveEdit?>(null); private set
    // Whether the device allows root, which lists every app rather than the debuggable ones; found out with the apps.
    @Volatile private var rooted: Boolean? = null

    /** The apps to choose from: the user's, then the system's when they're shown; the open app always. */
    val shownApps: List<InstalledApp> get() = apps.orEmpty().filter { !it.system || showSystemApps || it == app }

    val hasSystemApps: Boolean get() = apps.orEmpty().any { it.system }

    fun toggleSystemApps() { showSystemApps = !showSystemApps }

    /**
     * Reads the apps whose data can be opened: every app on a device that allows root, otherwise the debuggable ones.
     * Opens a debug build first, as the app being worked on, or else the first of the user's apps.
     */
    suspend fun loadApps() {
        val found = runCatching {
            runInterruptible(Dispatchers.IO) {
                val everyApp = rooted ?: service.hasRoot(serial).also { rooted = it }
                installedApps.appsWithData(serial, everyApp)
            }
        }.getOrDefault(emptyList())
        apps = found
        if (app == null) (found.firstOrNull { it.debuggable && !it.system } ?: found.firstOrNull())?.let(::chooseApp)
    }

    fun chooseApp(app: InstalledApp) {
        this.app = app
        job?.cancel()
        preferenceFile = null
        databaseEdit = null
        job = scope.launch {
            databases = runInterruptible(Dispatchers.IO) { service.databases(serial, app) }
            preferenceFiles = preferencesService?.let { runCatching { runInterruptible(Dispatchers.IO) { it.files(serial, app) } }.getOrNull() }.orEmpty()
            databases.firstOrNull()?.let { open(it) } ?: run {
                closeDatabase()
                preferenceFiles.firstOrNull()?.let(::choosePreferences)
            }
        }
    }

    fun chooseDatabase(database: DeviceFile) {
        preferenceFile = null
        if (database != this.database) databaseEdit = null
        job?.cancel()
        job = scope.launch { open(database) }
    }

    /**
     * Opens [packageName]'s database or preferences file at [path], as the Files page names it in /data/data, such as
     * when it's opened from there. Waits for the apps to be read, as they are once the page shows, then takes over from
     * the app the page chose on its own.
     */
    fun openFile(packageName: String, path: String) {
        val inApp = "./" + path.removePrefix(childPath(AppsFolder, packageName) + "/")
        scope.launch {
            while (apps == null) delay(50)
            val app = apps.orEmpty().firstOrNull { it.packageName == packageName } ?: return@launch
            this@AppDatabasesState.app = app
            preferenceFile = null
            job?.cancel()
            job = scope.launch {
                databases = runInterruptible(Dispatchers.IO) { service.databases(serial, app) }
                preferenceFiles = preferencesService?.let { runCatching { runInterruptible(Dispatchers.IO) { it.files(serial, app) } }.getOrNull() }.orEmpty()
                databases.firstOrNull { it.path == inApp }?.let { return@launch open(it) }
                closeDatabase()
                preferenceFiles.firstOrNull { it.file.path == inApp }?.let(::choosePreferences)
            }
        }
    }

    /** Shows the query editor for the open database in place of a table, starting from that table's query. */
    fun writeQuery() {
        table?.let { query.setTextAndPlaceCursorAtEnd("SELECT * FROM ${quoteIdentifier(it)}") }
        preferenceFile = null
        table = null
        sort = null
        search = ""
        rowLimit = PageSize
        result = QueryState.Idle
        shown = null
        historyAt = -1
    }

    fun chooseTable(name: String) {
        preferenceFile = null
        table = name
        sort = null
        search = ""
        rowLimit = PageSize
        action = DataAction.None
        structure = StructureLoad.Loading
        browse(quietly = false)
        if (tableView == TableView.Structure) readStructure()
    }

    fun showTableView(view: TableView) {
        tableView = view
        if (view == TableView.Structure) readStructure()
    }

    /** Puts [sql], one of the [history], in the query field. */
    fun useQuery(sql: String) {
        query.setTextAndPlaceCursorAtEnd(sql)
        historyAt = -1
    }

    /** Steps back through the [history] in the query field, keeping what was being written to come back to. */
    fun previousQuery() {
        if (historyAt + 1 >= history.size) return
        if (historyAt == -1) draft = query.text.toString()
        historyAt++
        query.setTextAndPlaceCursorAtEnd(history[historyAt])
    }

    /** Steps forward through the [history], back to what was being written. */
    fun nextQuery() {
        if (historyAt < 0) return
        historyAt--
        query.setTextAndPlaceCursorAtEnd(if (historyAt == -1) draft else history[historyAt])
    }

    /** The open table's details, while a table rather than the query editor is shown. */
    val openTable: DatabaseTable? get() = (load as? DatabaseLoad.Ready)?.tables?.firstOrNull { it.name == table }

    /** Whether the rows shown can be changed: a table's, each with the rowid that says which row it is. */
    val editable: Boolean get() = openTable?.editable == true &&
        (result as? QueryState.Done)?.result?.columns?.firstOrNull() == RowIdColumn

    /**
     * Sorts the rows by [column], the other way round when they already are. A table is sorted as a whole by the
     * database, past the rows shown; a query's own rows are sorted where they are.
     */
    fun sortBy(column: String) {
        val current = sort
        sort = DatabaseSort(column, ascending = current?.column != column || !current.ascending)
        if (table != null) browse(quietly = true)
    }

    /** Shows only the rows holding [text]: a table's found by the database, a query's among its own rows. */
    fun search(text: String) {
        if (text == search) return
        search = text
        rowLimit = PageSize
        if (table != null) browse(quietly = true)
    }

    /** The rows of [result] as they're shown: a query's sorted and searched here, a table's already by the database. */
    fun shownRows(result: QueryResult): List<Int> {
        var indices = result.rows.indices.toList()
        if (table != null) return indices
        if (search.isNotBlank()) indices = indices.filter { row -> result.rows[row].any { it?.contains(search.trim(), ignoreCase = true) == true } }
        val by = sort ?: return indices
        val column = result.columns.indexOf(by.column).takeIf { it >= 0 } ?: return indices
        val sorted = indices.sortedWith { a, b -> compareCells(result.rows[a][column], result.rows[b][column]) }
        return if (by.ascending) sorted else sorted.reversed()
    }

    /** Shows more of the rows, once the list reaches the end of those shown and there are others. */
    fun loadMore() {
        val done = result as? QueryState.Done ?: return
        if (!done.result.more) return
        rowLimit += PageSize
        if (table != null) browse(quietly = true) else reread()
    }

    /**
     * Sets [column] of [row] to [value], NULL when it's null, in the app's database on the device. The new value shows
     * at once, and the database is copied again once it's written, so what's shown is what the app now has.
     */
    fun editCell(row: List<String?>, column: String, value: String?) {
        val table = table?.takeIf { editable } ?: return
        val done = result as? QueryState.Done ?: return
        val rowId = rowIdOf(row) ?: return
        val index = done.result.columns.indexOf(column).takeIf { it > 0 } ?: return
        if (row[index] == value) return
        result = QueryState.Done(done.result.copy(rows = done.result.rows.map { shown ->
            if (shown.firstOrNull() == rowId) shown.toMutableList().apply { set(index, value) } else shown
        }))
        write("UPDATE ${quoteIdentifier(table)} SET ${quoteIdentifier(column)} = ${sqlLiteral(value)} WHERE rowid = $rowId", table)
    }

    /** Asks to delete [rows] from the open table, which happens once it's confirmed. */
    fun requestDeleteRows(rows: List<List<String?>>) {
        val table = table?.takeIf { editable } ?: return
        if (rows.isNotEmpty()) pendingDelete = PendingDelete.Rows(table, rows)
    }

    /** Asks to delete the preferences [keys], which happens once it's confirmed. */
    fun requestDeletePreferences(keys: List<String>) {
        val file = preferenceFile ?: return
        if (keys.isNotEmpty()) pendingDelete = PendingDelete.Keys(file.name, keys)
    }

    fun confirmDelete() {
        when (val pending = pendingDelete ?: return) {
            is PendingDelete.Rows -> deleteRows(pending.rows)
            is PendingDelete.Keys -> deletePreferences(pending.keys)
        }
        pendingDelete = null
    }

    fun dismissDelete() { pendingDelete = null }

    /** Deletes [rows] from the open table, in the app's database on the device, in one statement. */
    fun deleteRows(rows: List<List<String?>>) {
        val table = table?.takeIf { editable } ?: return
        val done = result as? QueryState.Done ?: return
        val rowIds = rows.mapNotNull(::rowIdOf).toSet().ifEmpty { return }
        result = QueryState.Done(done.result.copy(rows = done.result.rows.filter { it.firstOrNull() !in rowIds }))
        write("DELETE FROM ${quoteIdentifier(table)} WHERE rowid IN (${rowIds.joinToString()})", table)
    }

    /**
     * Adds a row to the open table holding [values] by column, the rest empty or their defaults, in the app's database on
     * the device; it shows once the database is copied again.
     */
    fun addRow(values: Map<String, String?> = emptyMap()) {
        val table = openTable?.takeIf { it.editable } ?: return
        write(insertRowSql(table, values), table.name)
    }

    /** Saves a copy of the open database to [file] on this computer, as one file with every change it holds. */
    fun saveCopy(file: File) {
        val snapshot = snapshot ?: return
        action = DataAction.Working
        scope.launch {
            action = try {
                withContext(sqlite) { snapshot.saveTo(file) }
                DataAction.Saved(file)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                DataAction.Failed(failure.message ?: "Could not save the database.")
            }
        }
    }

    /** Saves the open preferences file to [file] on this computer, as it is on the device. */
    fun savePreferencesCopy(file: File) {
        val app = app ?: return
        val preferences = preferenceFile ?: return
        val service = preferencesService ?: return
        action = DataAction.Working
        scope.launch {
            action = try {
                runInterruptible(Dispatchers.IO) { service.saveCopy(serial, app, preferences, file) }
                DataAction.Saved(file)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                DataAction.Failed(failure.message ?: "Could not save the file.")
            }
        }
    }

    /**
     * Copies the open database or preferences again when the app has written to them since they were read, keeping
     * what's shown on screen until the new copy is ready, so the page follows the app's changes.
     */
    suspend fun followChanges() {
        val app = app ?: return
        preferenceFile?.let { file -> return followPreferences(app, file) }
        val current = database ?: return
        if (job?.isActive == true || load !is DatabaseLoad.Ready) return
        val now = runCatching { runInterruptible(Dispatchers.IO) { service.version(serial, app, current) } }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull()
        updateFailed = now == null
        if (now == null) return
        if (now == copiedVersion || job?.isActive == true || database != current) return
        job = scope.launch { open(current, keepTable = true, quietly = true) }
    }

    /**
     * Runs the statements written, which the history keeps. When they only read, they run on the copy here; when any
     * of them changes the database, all of them run on the device, as the app, and the database is copied again after.
     */
    fun run() {
        val sql = query.text.toString().trim()
        val statements = splitStatements(sql).ifEmpty { return }
        addToHistory(sql)
        shown = null
        read(statements, quietly = false, writes = true)
    }

    private fun addToHistory(sql: String) {
        history = withQuery(history, sql)
        historyAt = -1
        val store = queryHistory ?: return
        val key = historyKey() ?: return
        scope.launch(Dispatchers.IO) { runCatching { store.record(key, sql) } }
    }

    /** The open database, as the history knows it: by its app and its path, the same on every device. */
    private fun historyKey(): String? {
        val app = app ?: return null
        val database = database ?: return null
        return "${app.packageName}/${database.path.removePrefix("./")}"
    }

    /** Runs the statements shown again, on the copy only. */
    private fun reread() { shown?.let { read(it, quietly = true, writes = false) } }

    /**
     * Runs [statements] on the copy here, showing what the last one returns. When one would change the database, all
     * of them run on the device instead when [writes] allows it; otherwise what's shown stays.
     */
    private fun read(statements: List<String>, quietly: Boolean, writes: Boolean) {
        val snapshot = snapshot ?: return
        if (!quietly) result = QueryState.Running
        val limit = rowLimit
        scope.launch {
            result = try {
                QueryState.Done(withContext(sqlite) { snapshot.query(statements, limit) }).also { done ->
                    shown = statements
                    layout.adopt(done.result.columns.filterIndexed { index, column -> !(index == 0 && column == RowIdColumn) })
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (writing: WritingStatementException) {
                if (writes) runOnDevice(statements) else result
            } catch (failure: Exception) {
                QueryState.Failed(failure.message?.substringAfter("] ")?.trim()?.ifEmpty { null } ?: "The query failed.")
            }
        }
    }

    /**
     * Runs [statements], of which one at least changes the database, on the device, then copies the database again. The
     * new copy then shows the last statement's rows, when it reads.
     */
    private suspend fun runOnDevice(statements: List<String>): QueryState {
        val app = app ?: return QueryState.Idle
        val database = database ?: return QueryState.Idle
        return try {
            val changed = runInterruptible(Dispatchers.IO) { service.execute(serial, app, database, statements) }
            shown = listOf(statements.last())
            // Any table may have changed, so the app hears about them all.
            tablesChanged(app, (load as? DatabaseLoad.Ready)?.tables.orEmpty().filter { !it.view }.map { it.name })
            job?.cancel()
            job = scope.launch { open(database, keepTable = true, quietly = true) }
            QueryState.Done(QueryResult(emptyList(), emptyList(), more = false, changed = changed))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            QueryState.Failed(failure.message ?: "The query failed.")
        }
    }

    /**
     * Runs [sql], which changes [table], on the device, then copies the database again whether or not it worked. The app
     * hears that [table] changed once it has.
     */
    private fun write(sql: String, table: String) {
        val app = app ?: return
        val database = database ?: return
        action = DataAction.Working
        scope.launch {
            action = try {
                runInterruptible(Dispatchers.IO) { service.execute(serial, app, database, sql) }
                tablesChanged(app, listOf(table))
                DataAction.None
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                DataAction.Failed(failure.message ?: "Could not change the database.")
            }
            // Copied again either way: the change as the device has it, or what was there back after a failure.
            job?.cancel()
            job = scope.launch { open(database, keepTable = true, quietly = true) }
        }
    }

    /**
     * Tells [app], if it runs, that [tables] changed, so what it shows from them through Room updates at once: Room only
     * notices writes made through itself. A failure leaves the app as it was, showing the change once it reads again.
     */
    private fun tablesChanged(app: InstalledApp, tables: List<String>) {
        val live = live ?: return
        scope.launch {
            runCatching { runInterruptible(Dispatchers.IO) { live.tablesChanged(serial, app, tables) } }
                .onSuccess { if (this@AppDatabasesState.app == app) databaseEdit = it }
                .onFailure { if (it is CancellationException) throw it }
        }
    }

    private fun rowIdOf(row: List<String?>) = row.firstOrNull()?.takeIf { id -> id.isNotEmpty() && id.all { it.isDigit() || it == '-' } }

    /** Runs the open table's query: its rows with their rowids where edits need them, searched and sorted as chosen. */
    private fun browse(quietly: Boolean) {
        val table = openTable ?: return
        val rowIds = if (table.editable) "rowid AS ${quoteIdentifier(RowIdColumn)}, " else ""
        val where = searchClause(table.columns.map { it.name }, search)
        val order = sort?.let { " ORDER BY ${quoteIdentifier(it.column)} ${if (it.ascending) "ASC" else "DESC"}" }.orEmpty()
        val sql = "SELECT $rowIds* FROM ${quoteIdentifier(table.name)}$where$order"
        query.setTextAndPlaceCursorAtEnd(sql)
        read(listOf(sql), quietly, writes = false)
    }

    /** Reads how the open table is built from the copy; [StructureLoad.Loading] shows until it's first read. */
    private fun readStructure() {
        val name = table ?: return
        val snapshot = snapshot ?: return
        scope.launch {
            val read = try {
                StructureLoad.Ready(withContext(sqlite) { snapshot.structure(name) })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                StructureLoad.Failed(failure.message ?: "Could not read the table's structure.")
            }
            if (table != name) return@launch
            structure = read
            structureLayout.adopt(StructureColumns)
            indexLayout.adopt(IndexColumns)
        }
    }

    /** Copies [database] and opens the copy; [quietly] keeps the current copy on screen until the new one is ready. */
    private suspend fun open(database: DeviceFile, keepTable: Boolean = false, quietly: Boolean = false) {
        val app = app ?: return
        this.database = database
        if (!quietly) load = DatabaseLoad.Loading
        if (!keepTable) {
            table = null; result = QueryState.Idle; search = ""; sort = null; rowLimit = PageSize; shown = null; historyAt = -1
            val key = historyKey()
            history = if (key == null || queryHistory == null) emptyList() else runInterruptible(Dispatchers.IO) { queryHistory.load(key) }
        }
        val loaded = try {
            val opened = withContext(sqlite) {
                val folder = Files.createTempDirectory("robomori-database").toFile()
                try {
                    // Read before copying, so a write during the copy shows as a change the next time it's checked.
                    val version = runInterruptible { runCatching { service.version(serial, app, database) }.getOrNull() }
                    val file = runInterruptible { service.snapshot(serial, app, database, folder) }
                    copiedVersion = version
                    val next = DatabaseSnapshot(file)
                    replace(next, folder)
                    next.tables()
                } catch (failure: Throwable) {
                    if (directory != folder) folder.deleteRecursively()
                    throw failure
                }
            }
            DatabaseLoad.Ready(opened)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (encrypted: EncryptedDatabaseException) {
            DatabaseLoad.Encrypted
        } catch (failure: Exception) {
            DatabaseLoad.Failed(failure)
        }
        // A copy that fails while following changes leaves the last one shown, and says so; the next check tries again.
        updateFailed = quietly && loaded is DatabaseLoad.Failed
        if (updateFailed) return
        load = loaded
        val tables = (load as? DatabaseLoad.Ready)?.tables.orEmpty()
        when {
            // The table shown, or the query's rows, are read again from the new copy; a statement that wrote doesn't write again.
            keepTable && table != null -> if (openTable != null) {
                browse(quietly)
                if (tableView == TableView.Structure) readStructure()
            } else { table = null; result = QueryState.Idle }
            keepTable -> reread()
            !keepTable -> tables.firstOrNull { !it.view }?.let { chooseTable(it.name) }
        }
    }

    private suspend fun closeDatabase() {
        database = null
        table = null
        result = QueryState.Idle
        load = DatabaseLoad.None
        withContext(sqlite) { replace(null, null) }
    }

    private fun replace(next: DatabaseSnapshot?, folder: File?) {
        snapshot?.close()
        directory?.deleteRecursively()
        snapshot = next
        directory = folder
    }

    // Preferences

    fun choosePreferences(file: PreferenceFile) {
        preferenceFile = file
        preferencesEdit = null
        search = ""
        sort = null
        action = DataAction.None
        job?.cancel()
        job = scope.launch { readPreferences(file, quietly = false) }
    }

    /** Sets the preference [key] to [value], typed as its type reads it, in the app's file on the device. */
    fun editPreference(key: String, value: String) = changePreferences { entries ->
        entries.map { if (it.key == key) it.withValue(value) else it }
    }

    fun deletePreferences(keys: List<String>) = changePreferences { entries -> entries.filter { it.key !in keys } }

    /** The preferences shown, searched and sorted. */
    fun shownPreferences(entries: List<Preference>): List<Preference> {
        val found = if (search.isBlank()) entries else entries.filter { entry ->
            listOf(entry.key, entry.value.orEmpty()).any { it.contains(search.trim(), ignoreCase = true) }
        }
        val by = sort ?: return found
        val sorted = found.sortedWith { a, b ->
            when (by.column) {
                PreferenceColumns[1] -> a.type.compareTo(b.type)
                PreferenceColumns[2] -> compareCells(a.value, b.value)
                else -> a.key.compareTo(b.key, ignoreCase = true)
            }
        }
        return if (by.ascending) sorted else sorted.reversed()
    }

    fun sortPreferences(column: String) {
        val current = sort
        sort = DatabaseSort(column, ascending = current?.column != column || !current.ascending)
    }

    fun searchPreferences(text: String) { search = text }

    /**
     * Stops and opens the app again, so it reads the preferences and database changes made here that it couldn't take in
     * while running, as it otherwise keeps what it read before.
     */
    fun restartApp() {
        val app = app ?: return
        scope.launch {
            runCatching {
                runInterruptible(Dispatchers.IO) {
                    installedApps.run(serial, app.packageName, AppAction.ForceStop)
                    installedApps.run(serial, app.packageName, AppAction.Launch)
                }
                preferencesEdit = null
                databaseEdit = null
            }.onFailure { if (it is CancellationException) throw it; action = DataAction.Failed(it.message ?: "Could not restart the app.") }
        }
    }

    /**
     * Applies [change] inside the app while it runs, so the app sees it at once, through its own preferences. When the app
     * isn't running, or can't take it in, the file is written instead, which the app reads when it starts.
     */
    private fun changePreferences(change: (List<Preference>) -> List<Preference>) {
        val app = app ?: return
        val file = preferenceFile ?: return
        val service = preferencesService ?: return
        val ready = preferences as? PreferencesLoad.Ready ?: return
        val next = try { change(ready.entries) } catch (invalid: IllegalArgumentException) {
            action = DataAction.Failed(invalid.message ?: "That isn't a valid value."); return
        }
        val edits = preferenceEdits(ready.entries, next)
        preferences = PreferencesLoad.Ready(next)
        action = DataAction.Working
        job?.cancel()
        job = scope.launch {
            action = try {
                val edit = runInterruptible(Dispatchers.IO) {
                    val edit = runCatching { live?.editPreferences(serial, app, file, edits) }
                        .onFailure { if (it is InterruptedException) throw it }.getOrNull() ?: LiveEdit.NeedsRestart
                    if (edit != LiveEdit.Applied) service.write(serial, app, file, next)
                    edit
                }
                preferencesEdit = edit
                DataAction.None
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                DataAction.Failed(failure.message ?: "Could not change the preferences.")
            }
            readPreferences(file, quietly = true)
        }
    }

    private suspend fun followPreferences(app: InstalledApp, file: PreferenceFile) {
        val service = preferencesService ?: return
        if (job?.isActive == true) return
        val now = runCatching { runInterruptible(Dispatchers.IO) { service.version(serial, app, file) } }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull()
        updateFailed = now == null
        if (now == null || now == copiedVersion || job?.isActive == true || preferenceFile != file) return
        job = scope.launch { readPreferences(file, quietly = true) }
    }

    private suspend fun readPreferences(file: PreferenceFile, quietly: Boolean) {
        val app = app ?: return
        val service = preferencesService ?: return
        if (!quietly) preferences = PreferencesLoad.Loading
        val read = if (file.kind == PreferenceKind.ProtoDataStore) PreferencesLoad.NeedsSchema(file.file.size) else try {
            val version = runCatching { runInterruptible(Dispatchers.IO) { service.version(serial, app, file) } }.getOrNull()
            val entries = runInterruptible(Dispatchers.IO) { service.read(serial, app, file) }
            copiedVersion = version
            PreferencesLoad.Ready(entries)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            PreferencesLoad.Failed(failure.message ?: "Could not read the preferences.")
        }
        updateFailed = quietly && read is PreferencesLoad.Failed
        if (updateFailed || preferenceFile != file) return
        preferences = read
        preferencesLayout.adopt(PreferenceColumns)
    }

    /** Lets go of the copy, when the device is no longer shown. */
    fun close() {
        job?.cancel()
        scope.launch(NonCancellable + sqlite) { replace(null, null) }
    }

    private companion object { const val PageSize = 500 }
}
