package com.robomori

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppDataTest {
    @TempDir lateinit var directory: File
    private val pantry = InstalledApp("com.example.pantry", "Pantry", "1.0", "1", null)

    @Test fun `reads and writes SharedPreferences as Android does`() {
        val xml = """
            <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
            <map>
                <string name="user">Ada &amp; co</string>
                <int name="launches" value="3" />
                <long name="since" value="1700000000000" />
                <float name="scale" value="1.5" />
                <boolean name="onboarded" value="true" />
                <set name="tags"><string>a</string><string>b</string></set>
                <null name="token" />
            </map>
        """.trimIndent().toByteArray()
        val entries = parseSharedPreferences(xml)
        assertEquals(listOf(
            Preference("user", PreferenceType.String, "Ada & co"),
            Preference("launches", PreferenceType.Int, "3"),
            Preference("since", PreferenceType.Long, "1700000000000"),
            Preference("scale", PreferenceType.Float, "1.5"),
            Preference("onboarded", PreferenceType.Boolean, "true"),
            Preference("tags", PreferenceType.StringSet, null, strings = listOf("a", "b")),
            Preference("token", PreferenceType.String, null),
        ), entries)
        assertEquals(entries, parseSharedPreferences(writeSharedPreferences(entries)))
    }

    @Test fun `reads and writes DataStore preferences`() {
        val entries = listOf(
            Preference("dark", PreferenceType.Boolean, "true"),
            Preference("ratio", PreferenceType.Float, "0.25"),
            Preference("count", PreferenceType.Int, "-7"),
            Preference("since", PreferenceType.Long, "1700000000000"),
            Preference("name", PreferenceType.String, "Ada"),
            Preference("tags", PreferenceType.StringSet, null, strings = listOf("x", "y")),
            Preference("pi", PreferenceType.Double, "3.14159"),
            Preference("blob", PreferenceType.Bytes, null, bytes = byteArrayOf(1, 2, 3)),
        )
        assertEquals(entries, parseDataStore(writeDataStore(entries)))
    }

    @Test fun `a preference takes only what its type holds`() {
        val launches = Preference("launches", PreferenceType.Int, "3")
        assertEquals("12", launches.withValue(" 12 ").value)
        assertEquals("launches takes a whole number.", runCatching { launches.withValue("many") }.exceptionOrNull()?.message)
        assertEquals("false", Preference("on", PreferenceType.Boolean, "true").withValue("FALSE").value)
    }

    @Test fun `an added row holds what was typed, and an empty value of its type where one is needed`() {
        val table = DatabaseTable("items", view = false, rows = 0, columns = listOf(
            DatabaseColumn("id", "INTEGER", primaryKey = true),
            DatabaseColumn("name", "TEXT", notNull = true),
            DatabaseColumn("amount", "INTEGER", notNull = true),
            DatabaseColumn("added", "INTEGER", notNull = true, hasDefault = true),
            DatabaseColumn("note", "TEXT"),
        ))
        assertEquals("INSERT INTO \"items\" (\"name\", \"amount\") VALUES ('', 0)", insertRowSql(table))
        assertEquals("INSERT INTO \"items\" (\"note\", \"name\", \"amount\") VALUES ('fresh', '', 0)", insertRowSql(table, mapOf("note" to "fresh")))
        assertEquals("INSERT INTO \"notes\" DEFAULT VALUES", insertRowSql(DatabaseTable("notes", view = false, rows = 0,
            columns = listOf(DatabaseColumn("note", "TEXT")))))
    }

    @Test fun `rows copy as CSV, quoting what needs it`() {
        assertEquals("name,note\r\nMilk,\"2, cold\"\r\n\"Say \"\"hi\"\"\",\r\n",
            toCsv(listOf("name", "note"), listOf(listOf("Milk", "2, cold"), listOf("Say \"hi\"", null))))
    }

    @Test fun `knows an encrypted database by its first bytes`() {
        val plain = File(directory, "plain.db")
        DriverManager.getConnection("jdbc:sqlite:${plain.absolutePath}").use { it.createStatement().use { s -> s.executeUpdate("CREATE TABLE t(a)") } }
        assertTrue(isSqliteFile(plain))
        assertFalse(isSqliteFile(File(directory, "cipher.db").apply { writeBytes(ByteArray(64) { (it * 37).toByte() }) }))
    }

    private val onDevice by lazy { File(directory, "device.db") }

    private fun sql(statement: String) = DriverManager.getConnection("jdbc:sqlite:${onDevice.absolutePath}").use { connection ->
        connection.createStatement().use { it.executeUpdate(statement) }
    }

    /** Runs [statements] as the device helper does: in one transaction, all or none, counting the rows they changed. */
    private fun script(statements: List<String>): String = DriverManager.getConnection("jdbc:sqlite:${onDevice.absolutePath}").use { connection ->
        connection.autoCommit = false
        try {
            var changed = 0
            connection.createStatement().use { statement -> for (sql in statements) if (!statement.execute(sql)) changed += maxOf(statement.updateCount, 0) }
            connection.commit()
            "ROBOMORI_CHANGED\t$changed"
        } catch (failure: java.sql.SQLException) {
            connection.rollback()
            "ROBOMORI_ERROR\t${failure.message}"
        }
    }

    /** A device holding [onDevice] as an app's one database, [dataStores] by name in its `files/datastore` folder, and [sharedPrefs] in `shared_prefs`. */
    private fun device(dataStores: Map<String, ByteArray> = emptyMap(), sharedPrefs: Map<String, ByteArray> = emptyMap()) = object : DeviceFileTransport {
        val files = dataStores + sharedPrefs
        var modified = 0
        override fun shell(serial: String, command: String): String = when {
            "DatabaseHelper" in command -> {
                val encoded = command.substringAfter("DatabaseHelper ").substringAfter(' ').substringBefore(';').trim().trimEnd('\'').split(' ')
                script(encoded.map { String(java.util.Base64.getDecoder().decode(it)) }).also { modified++ }
            }
            "%y" in command -> "./databases/app.db|${onDevice.length()}|$modified"
            "find ." in command && "datastore" in command ->
                dataStores.entries.joinToString("") { (name, bytes) -> "regular file|${bytes.size}|0|./$name\n" } + "ROBOMORI_DONE"
            "find ." in command && "shared_prefs" in command ->
                sharedPrefs.entries.joinToString("") { (name, bytes) -> "regular file|${bytes.size}|0|./$name\n" } + "ROBOMORI_DONE"
            "find ." in command && "databases" in command ->
                (if (onDevice.exists()) "regular file|${onDevice.length()}|0|./app.db\n" else "") + "ROBOMORI_DONE"
            "find ." in command -> "ROBOMORI_DONE"
            "stat -L -c %s" in command -> "${files.entries.firstOrNull { it.key in command }?.value?.size ?: onDevice.length()}"
            else -> ""
        }
        override fun pull(serial: String, command: String, destination: File) {
            val proto = files.entries.firstOrNull { it.key in command }
            if (proto != null) destination.writeBytes(proto.value) else onDevice.copyTo(destination, overwrite = true)
        }
        override fun push(serial: String, command: String, source: File) {}
    }

    private fun CoroutineScope.state(device: DeviceFileTransport = device(), history: QueryHistoryStore? = null, live: LiveAppData? = null,
                                     installedApps: InstalledAppsService = InstalledAppsService(FakeAndroid(directory).settings)): AppDatabasesState {
        val helper = { DeviceHelperJar(File(directory, "h.jar"), "t") }
        val files = DeviceFilesService(device, helper)
        return AppDatabasesState(AppDatabasesService(files, helper), installedApps, "s", this, AppPreferencesService(files), history, live)
    }

    private val release = InstalledApp("com.example.release", "Release", "1.0", "1", null, debuggable = false, uid = 10233)

    /**
     * [device] on a phone that allows root, recording what's run: it has Robomori's helper in /data/local/tmp once it's
     * been pushed there, and moves a file into place when asked to.
     */
    private class RootDevice(private val device: DeviceFileTransport) : DeviceFileTransport {
        val commands = java.util.Collections.synchronizedList(mutableListOf<String>())
        val pushes = java.util.Collections.synchronizedList(mutableListOf<String>())
        private var staged = false

        override fun shell(serial: String, command: String): String {
            commands += command
            return when {
                command == "su 0 id" -> "uid=0(root) gid=0(root) context=u:r:su:s0"
                "ROBOMORI_PRESENT" in command -> if (staged) "ROBOMORI_PRESENT" else "ROBOMORI_MISSING"
                // The helper as copied, which is empty here.
                "stat -L -c %s" in command && "robomori-database" in command -> "0"
                command.startsWith("chmod 444") -> { staged = true; "" }
                "echo ROBOMORI_DONE" in command && "mv -f" in command -> "ROBOMORI_DONE"
                else -> device.shell(serial, command)
            }
        }
        override fun pull(serial: String, command: String, destination: File) { commands += command; device.pull(serial, command, destination) }
        override fun push(serial: String, command: String, source: File) { pushes += command }
    }

    @Test fun `opens a release build's database and preferences through root, and changes them as the app's own user`() = runBlocking {
        sql("CREATE TABLE items(id INTEGER PRIMARY KEY, name TEXT NOT NULL)")
        sql("INSERT INTO items(name) VALUES ('Milk')")
        val device = RootDevice(device(sharedPrefs = mapOf("settings.xml" to writeSharedPreferences(listOf(Preference("name", PreferenceType.String, "Ada"))))))
        val live = FakeLiveAppData { LiveEdit.NeedsRestart }
        val databases = state(device, live = live)
        databases.chooseApp(release)
        suspend fun names(): List<String?> = databases.done().let { result -> result.rows.map { it[2] } }
        withTimeout(5000) { while (names() != listOf("Milk")) delay(10) }
        assertTrue(device.commands.filter { "find ." in it }.all { it.startsWith("su 0 sh -c 'cd '\\''/data/data/com.example.release'\\'' && ") },
            "Its folder is read through root.")

        databases.editCell(databases.done().rows.single(), "name", "Oat milk")
        withTimeout(5000) { while (names() != listOf("Oat milk") || databases.databaseEdit != LiveEdit.NeedsRestart) delay(10) }
        val helper = device.commands.single { "DatabaseHelper" in it }
        assertTrue(helper.startsWith("su 10233 sh -c 'umask 077; cd '\\''/data/data/com.example.release'\\'' && " +
            "CLASSPATH=/data/local/tmp/robomori-database-t.jar app_process / com.robomori.device.DatabaseHelper "), helper)
        assertTrue(device.commands.any { it == "chmod 444 '/data/local/tmp/robomori-database-t.jar'" }, "The helper is read-only, as Android runs only such code.")
        val labels = device.commands.single { "chcon" in it }
        assertTrue(labels.startsWith("su 0 sh -c") && "chcon \$(stat -c %C '\\''./databases/app.db'\\'') '\\''./databases/app.db-wal'\\''" in labels, labels)

        databases.choosePreferences(databases.preferenceFiles.single())
        withTimeout(5000) { while (databases.preferences !is PreferencesLoad.Ready) delay(10) }
        databases.editPreference("name", "Grace")
        withTimeout(5000) { while (databases.preferencesEdit != LiveEdit.NeedsRestart || databases.action != DataAction.None) delay(10) }
        val upload = device.pushes.single { "settings.xml" in it }
        assertTrue(upload.startsWith("su 0 sh -c 'cd '\\''/data/data/com.example.release'\\'' && cat > "), upload)
        val write = device.commands.single { "mv -f" in it && "settings.xml" in it }
        for (kept in listOf("chown \$(stat -c %u:%g", "chmod \$(stat -c %a", "chcon \$(stat -c %C")) {
            assertTrue("$kept '\\''./shared_prefs/settings.xml'\\'') '\\''./shared_prefs/settings.xml.robomori'\\''" in write, write)
        }
        assertTrue(device.commands.any { it.startsWith("su 10233 sh -c") && "settings.xml.bak" in it })
        assertTrue(device.commands.none { it.startsWith("run-as") }, "run-as refuses a release build.")
        databases.close()
    }

    @Test fun `lists every app on a device that allows root, the system's only when asked`() = runBlocking {
        val scripts = mutableListOf<String>()
        val installed = InstalledAppsService(AppSettings("/sdk", "/avds"), CommandExecutor { command, _, _ ->
            scripts += command.args.last()
            listOf(
                "ROBOMORI_APP\tcom.android.settings\tSettings\t17\t1\t\t1000\t0\t1",
                "ROBOMORI_APP\tcom.example.release\tRelease\t1.0\t1\t\t10233\t0\t0",
                "ROBOMORI_APP\tcom.example.pantry\tPantry\t1.0\t1\t\t10234\t1\t0",
                "ROBOMORI_DONE",
            ).joinToString("\n")
        }, helper = { DeviceHelperJar(File(directory, "h.jar"), "t") })
        val databases = state(RootDevice(device()), installedApps = installed)
        databases.loadApps()
        assertTrue(scripts.single().contains("com.robomori.device.AppsHelper all"), scripts.single())
        assertEquals(listOf("Pantry", "Release", "Settings"), databases.apps.orEmpty().map { it.name })
        assertEquals(pantry.packageName, databases.app?.packageName, "A debug build opens first, as the app being worked on.")
        assertEquals(listOf("Pantry", "Release"), databases.shownApps.map { it.name })
        databases.toggleSystemApps()
        assertEquals(listOf("Pantry", "Release", "Settings"), databases.shownApps.map { it.name })
        databases.close()
    }

    private suspend fun AppDatabasesState.done(): QueryResult = withTimeout(5000) {
        while (result !is QueryState.Done) delay(10)
        (result as QueryState.Done).result
    }

    @Test fun `searches a table, writes through a query, adds and deletes rows, and saves a copy`() = runBlocking {
        sql("CREATE TABLE items(id INTEGER PRIMARY KEY, name TEXT NOT NULL)")
        sql("INSERT INTO items(name) VALUES ('Milk'), ('Eggs'), ('Oat milk')")
        val live = FakeLiveAppData()
        val databases = state(live = live)
        suspend fun names(): List<String?> = withTimeout(5000) {
            while (databases.result !is QueryState.Done) delay(10)
            val result = (databases.result as QueryState.Done).result
            databases.shownRows(result).map { result.rows[it][2] }
        }
        suspend fun until(expected: List<String?>) = withTimeout(5000) { while (names() != expected) delay(10) }

        databases.chooseApp(pantry)
        until(listOf("Milk", "Eggs", "Oat milk"))

        databases.search("milk")
        until(listOf("Milk", "Oat milk"))
        databases.search("")
        until(listOf("Milk", "Eggs", "Oat milk"))

        databases.addRow(mapOf("name" to "Bread"))
        until(listOf("Milk", "Eggs", "Oat milk", "Bread"))
        assertEquals(listOf(listOf("items")), live.tables, "The running app hears which table changed.")

        val eggs = (databases.result as QueryState.Done).result.rows.first { it[2] == "Eggs" }
        databases.requestDeleteRows(listOf(eggs))
        assertEquals(PendingDelete.Rows("items", listOf(eggs)), databases.pendingDelete, "Nothing is deleted before it's confirmed.")
        assertEquals(listOf("Milk", "Eggs", "Oat milk", "Bread"), names())
        databases.confirmDelete()
        until(listOf("Milk", "Oat milk", "Bread"))

        databases.writeQuery()
        databases.query.edit { replace(0, length, "DELETE FROM items WHERE name = 'Bread'") }
        databases.run()
        withTimeout(5000) { while ((databases.result as? QueryState.Done)?.result?.changed != 1) delay(10) }
        withTimeout(5000) { while (live.tables.size < 3) delay(10) }
        assertEquals(listOf("items"), live.tables.last(), "A query may change any table, so the app hears of them all.")
        databases.chooseTable("items")
        until(listOf("Milk", "Oat milk"))

        val copy = File(directory, "copy.db")
        databases.saveCopy(copy)
        withTimeout(5000) { while (databases.action !is DataAction.Saved) delay(10) }
        DatabaseSnapshot(copy).use { assertEquals(2L, it.tables().single().rows) }
        databases.close()
    }

    @Test fun `splits statements at semicolons outside quotes, comments and trigger bodies`() {
        assertEquals(listOf("SELECT 1", "SELECT 'a;b'", "SELECT \"x;\"\"y\"", "SELECT [c;d]"),
            splitStatements("SELECT 1; SELECT 'a;b';SELECT \"x;\"\"y\"\n;SELECT [c;d];"))
        assertEquals(listOf("SELECT 1 -- one; two", "/* three; */ SELECT 'it''s; here'"),
            splitStatements("SELECT 1 -- one; two\n; /* three; */ SELECT 'it''s; here'; -- trailing; comment"))
        val trigger = "CREATE TEMP TRIGGER stamp AFTER INSERT ON items BEGIN " +
            "UPDATE items SET note = CASE WHEN new.name = '' THEN 'empty' ELSE 'named' END WHERE id = new.id; " +
            "DELETE FROM log; END"
        assertEquals(listOf(trigger, "INSERT INTO items(name) VALUES ('x')"), splitStatements("$trigger; INSERT INTO items(name) VALUES ('x');"))
        assertEquals(listOf("BEGIN", "DELETE FROM items", "END"), splitStatements("BEGIN; DELETE FROM items; END;"))
        assertEquals(emptyList(), splitStatements(" ; -- nothing\n /* at all */ "))
    }

    @Test fun `reads a table's columns, indexes and the statements that create it`() {
        sql("CREATE TABLE items(id INTEGER PRIMARY KEY, name TEXT NOT NULL DEFAULT '', code TEXT UNIQUE, added INTEGER DEFAULT CURRENT_TIMESTAMP)")
        sql("CREATE INDEX items_by_name ON items(name, lower(code))")
        sql("CREATE TRIGGER items_named AFTER INSERT ON items BEGIN SELECT 1; END")
        DatabaseSnapshot(onDevice).use { snapshot ->
            val structure = snapshot.structure("items")
            assertEquals(listOf(
                DatabaseColumn("id", "INTEGER", primaryKey = true),
                DatabaseColumn("name", "TEXT", notNull = true, hasDefault = true, default = "''"),
                DatabaseColumn("code", "TEXT"),
                DatabaseColumn("added", "INTEGER", hasDefault = true, default = "CURRENT_TIMESTAMP"),
            ), structure.columns)
            assertEquals(setOf(
                DatabaseIndex("items_by_name", unique = false, columns = listOf("name", null)),
                DatabaseIndex("sqlite_autoindex_items_1", unique = true, columns = listOf("code")),
            ), structure.indexes.toSet())
            val statements = structure.sql.split("\n\n")
            assertEquals(3, statements.size)
            assertTrue(statements[0].startsWith("CREATE TABLE items"))
            assertTrue(statements[1].startsWith("CREATE INDEX items_by_name"))
            assertTrue(statements[2].startsWith("CREATE TRIGGER items_named"))
        }
    }

    @Test fun `switches a table between its rows and its structure`() = runBlocking {
        sql("CREATE TABLE items(id INTEGER PRIMARY KEY, name TEXT)")
        sql("CREATE TABLE shelves(label TEXT NOT NULL)")
        val databases = state()
        databases.chooseApp(pantry)
        withTimeout(5000) { while (databases.table != "items") delay(10) }
        databases.showTableView(TableView.Structure)
        withTimeout(5000) { while (databases.structure !is StructureLoad.Ready) delay(10) }
        assertEquals(listOf("id", "name"), (databases.structure as StructureLoad.Ready).structure.columns.map { it.name })
        assertEquals(StructureColumns, databases.structureLayout.order)

        // Another table opens showing its structure too.
        databases.chooseTable("shelves")
        withTimeout(5000) { while ((databases.structure as? StructureLoad.Ready)?.structure?.columns?.map { it.name } != listOf("label")) delay(10) }
        assertEquals(TableView.Structure, databases.tableView)
        databases.close()
    }

    @Test fun `runs several statements, reading on the copy, and writing all of them or none on the device`() = runBlocking {
        sql("CREATE TABLE items(id INTEGER PRIMARY KEY, name TEXT NOT NULL)")
        sql("INSERT INTO items(name) VALUES ('Milk'), ('Eggs')")
        val databases = state()
        databases.chooseApp(pantry)
        withTimeout(5000) { while (databases.table == null) delay(10) }
        databases.writeQuery()

        databases.query.edit { replace(0, length, "SELECT count(*) AS n FROM items; SELECT name FROM items ORDER BY name") }
        databases.run()
        assertEquals(listOf(listOf<String?>("Eggs"), listOf("Milk")), databases.done().rows, "Reads show the last statement's rows.")

        databases.query.edit { replace(0, length, "INSERT INTO items(name) VALUES ('Bread'); UPDATE items SET name = upper(name); SELECT name FROM items ORDER BY name") }
        databases.run()
        // The device runs them all; the new copy then shows the rows the last one reads.
        withTimeout(5000) { while (databases.done().rows != listOf(listOf<String?>("BREAD"), listOf("EGGS"), listOf("MILK"))) delay(10) }

        databases.query.edit { replace(0, length, "DELETE FROM items; INSERT INTO items(name) VALUES (NULL)") }
        databases.run()
        withTimeout(5000) { while (databases.result !is QueryState.Failed) delay(10) }
        assertEquals(3L, DatabaseSnapshot(onDevice).use { it.tables().single().rows }, "A failing statement undoes the ones before it.")

        databases.query.edit { replace(0, length, "DELETE FROM items WHERE name = 'BREAD'; DELETE FROM items WHERE name = 'EGGS'") }
        databases.run()
        withTimeout(5000) { while ((databases.result as? QueryState.Done)?.result?.changed != 2) delay(10) }
        databases.close()
    }

    @Test fun `remembers the queries run on each database, newest first, across restarts`() = runBlocking {
        sql("CREATE TABLE items(id INTEGER PRIMARY KEY, name TEXT)")
        val file = File(directory, "config/query-history.json")
        val databases = state(history = QueryHistoryStore(file))
        databases.chooseApp(pantry)
        withTimeout(5000) { while (databases.table == null) delay(10) }
        databases.writeQuery()
        for (sql in listOf("SELECT 1", "SELECT 2", "SELECT 1")) {
            databases.query.edit { replace(0, length, sql) }
            databases.run()
            databases.done()
        }
        assertEquals(listOf("SELECT 1", "SELECT 2"), databases.history)

        databases.query.edit { replace(0, length, "SELECT 3") }
        databases.previousQuery()
        assertEquals("SELECT 1", databases.query.text.toString())
        databases.previousQuery()
        databases.previousQuery()
        assertEquals("SELECT 2", databases.query.text.toString(), "Stepping back stops at the oldest.")
        databases.nextQuery()
        databases.nextQuery()
        assertEquals("SELECT 3", databases.query.text.toString(), "Stepping forward ends at what was being written.")
        databases.close()

        withTimeout(5000) { while (QueryHistoryStore(file).load("com.example.pantry/databases/app.db") != listOf("SELECT 1", "SELECT 2")) delay(10) }
        val again = state(history = QueryHistoryStore(file))
        again.chooseApp(pantry)
        withTimeout(5000) { while (again.history != listOf("SELECT 1", "SELECT 2")) delay(10) }
        again.close()
    }

    @Test fun `a history keeps the newest distinct queries`() {
        val history = (1..40).fold(emptyList<String>()) { history, n -> withQuery(history, "SELECT $n") }
        assertEquals(RememberedQueries, history.size)
        assertEquals("SELECT 40", history.first())
        assertEquals(listOf("SELECT 35", "SELECT 40", "SELECT 39"), withQuery(history, "SELECT 35").take(3))
    }

    @Test fun `a Proto DataStore shows its size and saves a copy of its bytes`() = runBlocking {
        val bytes = byteArrayOf(8, 1, 18, 3, 65, 100, 97)
        val databases = state(device(mapOf("settings.pb" to bytes, "ui.preferences_pb" to writeDataStore(emptyList()), "settings.pb.tmp" to bytes)))
        databases.chooseApp(pantry)
        withTimeout(5000) { while (databases.preferences !is PreferencesLoad.NeedsSchema) delay(10) }
        assertEquals(listOf("settings.pb" to PreferenceKind.ProtoDataStore, "ui" to PreferenceKind.DataStore),
            databases.preferenceFiles.map { it.name to it.kind })
        assertEquals(PreferencesLoad.NeedsSchema(bytes.size.toLong()), databases.preferences)

        val copy = File(directory, "settings.pb")
        databases.savePreferencesCopy(copy)
        withTimeout(5000) { while (databases.action !is DataAction.Saved) delay(10) }
        assertTrue(bytes.contentEquals(copy.readBytes()))
        databases.close()
    }
}
