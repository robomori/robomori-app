package com.robomori

import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A device's files as the Files page reaches them: shared storage directly, and an app's own folder under
 * `/data/data/<package>` through its `run-as` for a debug build, or through root on a device that allows it.
 */
private class CliFiles(private val cli: Cli, val serial: String) {
    val service = DeviceFilesService(AdbFileTransport(cli.settings, cli.executor))
    val rooted by lazy { service.hasRoot(serial) }
    private val apps by lazy { InstalledAppsService(cli.settings, cli.executor).appsWithData(serial, everyApp = rooted) }

    /** The installed app [packageName], whose own files Robomori can open. */
    fun app(packageName: String): InstalledApp = apps.firstOrNull { it.packageName == packageName } ?: throw IllegalStateException(
        if (rooted) "$packageName isn't installed on this device."
        else "Robomori can't open $packageName's own files: it isn't installed, or isn't a debug build. On emulators without " +
            "Google Play, every app's files open.")

    fun rootFor(path: String): FileRoot {
        val packageName = appPackageOf(path) ?: return FileRoot.Storage
        return if (rooted) FileRoot.Superuser else FileRoot.AppData(app(packageName))
    }

    fun list(folder: String): List<DeviceFile> = when (folder.trimEnd('/').ifEmpty { "/" }) {
        // Without root, the apps folder can't be listed; the apps whose own files open stand in for it.
        AppsFolder -> if (rooted) service.list(serial, FileRoot.Superuser, AppsFolder) else apps.map { service.appFolder(serial, it) }
        else -> service.list(serial, rootFor(folder), folder.trimEnd('/').ifEmpty { "/" })
    }

    /** The file or folder at [path], or a failure naming it. */
    fun at(path: String): DeviceFile {
        val clean = path.trimEnd('/').ifEmpty { "/" }
        val parent = clean.substringBeforeLast('/').ifEmpty { "/" }
        return runCatching { list(parent) }.getOrNull()?.firstOrNull { it.path == clean || it.name == clean.substringAfterLast('/') }
            ?: throw IllegalStateException("There's nothing at $clean on the device.")
    }
}

private val listTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

private fun DeviceFile.toJson() = buildJsonObject {
    put("name", name); put("path", path); put("folder", kind == DeviceFileKind.Folder); put("size", size); put("modified", modified.toString())
}

/** `robomori files list [<path>]`: a folder's files, shared storage by default. */
internal fun Cli.fileList(options: Options) {
    options.expect(0..1, values = setOf("device"))
    val files = CliFiles(this, target(options).serial!!)
    val folder = options.positional.firstOrNull() ?: "/sdcard"
    val listed = files.list(folder).sortedWith(compareBy<DeviceFile> { it.kind != DeviceFileKind.Folder }.thenBy { it.name.lowercase() })
    if (jsonOutput) return printJson(buildJsonArray { listed.forEach { add(it.toJson()) } })
    if (listed.isEmpty()) return out.println("$folder is empty.")
    for (file in listed) {
        val name = if (file.kind == DeviceFileKind.Folder) "${file.name}/" else file.name
        val size = if (file.kind == DeviceFileKind.Folder) "" else "${file.size}"
        out.println("${listTime.format(file.modified)}  ${size.padStart(10)}  $name")
    }
}

/** `robomori files read <path>`: a text file's contents, such as an app's SharedPreferences XML. */
internal fun Cli.fileRead(options: Options) {
    options.expect(1..1, values = setOf("device"), what = "file's path on the device")
    val files = CliFiles(this, target(options).serial!!)
    val path = options.positional.single()
    val file = files.at(path)
    if (file.kind == DeviceFileKind.Folder) throw UsageException("$path is a folder. List it with 'robomori files list $path'.")
    when (val read = files.service.readText(files.serial, files.rootFor(path), file)) {
        is TextFileRead.Text -> if (jsonOutput) printJson(buildJsonObject { put("path", file.path); put("text", read.text) }) else out.print(read.text)
        is TextFileRead.TooLarge -> throw IllegalStateException("$path is too large to show. Copy it to this computer with 'robomori files pull $path'.")
        TextFileRead.NotText -> throw IllegalStateException("$path isn't text. Copy it to this computer with 'robomori files pull $path'.")
    }
}

/** `robomori files pull <path> [-o <local>]`: copies a file or folder from the device to this computer. */
internal fun Cli.filePull(options: Options) {
    options.expect(1..1, values = setOf("device", "output"), what = "file's path on the device")
    val files = CliFiles(this, target(options).serial!!)
    val path = options.positional.single()
    val file = files.at(path)
    val destination = File(options.value("output") ?: file.name).absoluteFile
    destination.parentFile?.mkdirs()
    if (file.kind == DeviceFileKind.Folder) files.service.saveFolder(files.serial, files.rootFor(path), file, destination)
    else files.service.save(files.serial, files.rootFor(path), file, destination)
    if (jsonOutput) printJson(buildJsonObject { put("path", destination.path) }) else out.println(destination.path)
}

/** `robomori files push <local> <folder>`: copies a file from this computer into a folder on the device. */
internal fun Cli.filePush(options: Options) {
    options.expect(2..2, values = setOf("device"), what = "file on this computer and the device folder to copy it to")
    val (localPath, folder) = options.positional
    val source = File(localPath).absoluteFile
    if (!source.isFile) throw UsageException("No file at $localPath. Push one file at a time.")
    val files = CliFiles(this, target(options).serial!!)
    val clean = folder.trimEnd('/').ifEmpty { "/" }
    files.service.upload(files.serial, files.rootFor(clean), source, clean)
    val path = childPath(clean, source.name)
    if (jsonOutput) printJson(buildJsonObject { put("path", path) }) else out.println(path)
}

/** `robomori files delete <path>`: deletes a file, or a folder and everything in it. */
internal fun Cli.fileDelete(options: Options) {
    options.expect(1..1, values = setOf("device"), what = "file's path on the device")
    val files = CliFiles(this, target(options).serial!!)
    val path = options.positional.single()
    val file = files.at(path)
    files.service.delete(files.serial, files.rootFor(path), file)
    if (jsonOutput) printJson(buildJsonObject { put("deleted", file.path) }) else out.println("Deleted ${file.path}")
}

/** `robomori data list <package>`: an app's databases with their tables, and its preference files. */
internal fun Cli.dataList(options: Options) {
    options.expect(1..1, values = setOf("device"), what = "app's package, such as com.example.app")
    val files = CliFiles(this, target(options).serial!!)
    val app = files.app(options.positional.single())
    val databases = AppDatabasesService(files.service)
    val listed = databases.databases(files.serial, app).map { database ->
        database to runCatching { withSnapshot(databases, files, app, database) { it.tables() } }
    }
    val preferences = AppPreferencesService(files.service).files(files.serial, app)
    if (jsonOutput) return printJson(buildJsonObject {
        putJsonArray("databases") {
            for ((database, tables) in listed) addJsonObject {
                put("name", database.name)
                tables.onSuccess { found -> putJsonArray("tables") { found.forEach { table -> addJsonObject { put("name", table.name); table.rows?.let { put("rows", it) } } } } }
                tables.onFailure { put("error", it.message ?: "Couldn't read it.") }
            }
        }
        putJsonArray("preferences") { preferences.forEach { addJsonObject { put("name", it.file.name); put("kind", it.kind.name) } } }
    })
    if (listed.isEmpty() && preferences.isEmpty()) return out.println("${app.packageName} hasn't saved any databases or preferences yet.")
    if (listed.isNotEmpty()) out.println("Databases:")
    for ((database, tables) in listed) {
        out.println("  ${database.name}")
        tables.onSuccess { found -> found.forEach { out.println("    ${it.name}" + (it.rows?.let { rows -> "  ($rows rows)" } ?: "  (view)")) } }
        tables.onFailure { out.println("    ${it.message ?: "Couldn't read it."}") }
    }
    if (preferences.isNotEmpty()) out.println("Preferences:")
    for (file in preferences) out.println("  ${file.file.name}  ${file.kind.label}")
}

/**
 * `robomori data query <package> <database> <sql>`: runs SQL on an app's database. Reading statements run on a copy, so
 * the app isn't disturbed; ones that change it run on the device, as the app, all or none.
 */
internal fun Cli.dataQuery(options: Options) {
    options.expect(3..3, values = setOf("device", "limit"), what = "app's package, database and SQL")
    val (packageName, databaseName, sql) = options.positional
    val limit = options.value("limit")?.let { it.toIntOrNull()?.takeIf { n -> n > 0 } ?: throw UsageException("Give --limit as a number of rows.") } ?: 100
    val files = CliFiles(this, target(options).serial!!)
    val app = files.app(packageName)
    val databases = AppDatabasesService(files.service)
    val database = databases.databases(files.serial, app).firstOrNull { it.name == databaseName } ?: throw IllegalStateException(
        "$packageName has no database $databaseName. See its databases with 'robomori data list $packageName'.")
    val statements = splitStatements(sql).ifEmpty { throw UsageException("Give the SQL to run.") }
    val result = try {
        withSnapshot(databases, files, app, database) { it.query(statements, limit) }
    } catch (_: WritingStatementException) {
        val changed = databases.execute(files.serial, app, database, statements)
        return if (jsonOutput) printJson(buildJsonObject { put("changed", changed) }) else out.println("Changed $changed rows.")
    }
    if (jsonOutput) return printJson(buildJsonObject {
        putJsonArray("columns") { result.columns.forEach { add(it) } }
        putJsonArray("rows") { result.rows.forEach { row -> addJsonArray { row.forEach { add(it) } } } }
        put("more", result.more)
    })
    if (result.columns.isEmpty()) return out.println("Done.")
    out.println(result.columns.joinToString("\t"))
    for (row in result.rows) out.println(row.joinToString("\t") { it ?: "NULL" })
    if (result.more) progress("Showing the first $limit rows. Use --limit for more.")
}

/** `robomori data prefs <package> <file>`: an app's SharedPreferences or DataStore preferences, one per line. */
internal fun Cli.dataPrefs(options: Options) {
    options.expect(2..2, values = setOf("device"), what = "app's package and preferences file")
    val (packageName, name) = options.positional
    val files = CliFiles(this, target(options).serial!!)
    val app = files.app(packageName)
    val service = AppPreferencesService(files.service)
    val file = service.files(files.serial, app).firstOrNull { it.file.name == name || it.name == name } ?: throw IllegalStateException(
        "$packageName has no preferences file $name. See its files with 'robomori data list $packageName'.")
    if (file.kind == PreferenceKind.ProtoDataStore) throw IllegalStateException(
        "${file.file.name} is a Proto DataStore, which only the app's own schema can read. Copy it with 'robomori files pull ${file.file.path}'.")
    val entries = service.read(files.serial, app, file)
    if (jsonOutput) return printJson(buildJsonArray {
        entries.forEach { addJsonObject { put("key", it.key); put("type", it.type.name); put("value", it.shown()) } }
    })
    if (entries.isEmpty()) return out.println("${file.file.name} is empty.")
    for (entry in entries) out.println("${entry.key}\t${entry.type.name}\t${entry.shown() ?: "null"}")
}

private val PreferenceKind.label get() = when (this) {
    PreferenceKind.SharedPreferences -> "SharedPreferences"
    PreferenceKind.DataStore -> "DataStore"
    PreferenceKind.ProtoDataStore -> "Proto DataStore"
}

/** Copies [database] off the device and opens the copy for reading, as the App data page does. */
private fun <T> withSnapshot(databases: AppDatabasesService, files: CliFiles, app: InstalledApp, database: DeviceFile,
                             read: (DatabaseSnapshot) -> T): T {
    val directory = Files.createTempDirectory("robomori-database").toFile()
    try {
        return DatabaseSnapshot(databases.snapshot(files.serial, app, database, directory)).use(read)
    } finally {
        directory.deleteRecursively()
    }
}
