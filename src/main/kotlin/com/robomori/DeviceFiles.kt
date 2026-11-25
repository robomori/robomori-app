package com.robomori

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import org.jetbrains.skia.Image as SkiaImage
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.key.Key
import java.awt.Desktop
import java.io.File
import kotlin.math.roundToInt
import java.util.Base64
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Runs shell commands on a device, and streams a command's output into a file or a file into its input. */
internal interface DeviceFileTransport {
    fun shell(serial: String, command: String): String
    fun pull(serial: String, command: String, destination: File)
    fun push(serial: String, command: String, source: File)
    /** As [shell], handing [onLine] each line of its output as it arrives. */
    fun shellLines(serial: String, command: String, onLine: (String) -> Unit) = shell(serial, command).lineSequence().forEach(onLine)
    /** As [pull], telling [onProgress] how many bytes have arrived so far. */
    fun pull(serial: String, command: String, destination: File, onProgress: (Long) -> Unit) = pull(serial, command, destination)
    /** As [push], telling [onProgress] how many bytes have been sent so far. */
    fun push(serial: String, command: String, source: File, onProgress: (Long) -> Unit) = push(serial, command, source)
}

internal class AdbFileTransport(private val settings: AppSettings, executor: CommandExecutor = ProcessExecutor()) : DeviceFileTransport {
    private val android = AndroidService(settings, executor)

    override fun shell(serial: String, command: String) = android.command(android.toolchain.adb, listOf("-s", serial, "shell", command))

    override fun shellLines(serial: String, command: String, onLine: (String) -> Unit) {
        val process = ProcessBuilder(adb(serial, "shell", command)).redirectErrorStream(true).start()
        try {
            process.inputStream.bufferedReader().useLines { lines -> lines.forEach { if (Thread.interrupted()) throw InterruptedException(); onLine(it) } }
            process.waitFor()
        } finally { if (process.isAlive) process.destroyForcibly() }
    }

    override fun pull(serial: String, command: String, destination: File) =
        stream(ProcessBuilder(adb(serial, "exec-out", command)).redirectOutput(destination))

    override fun push(serial: String, command: String, source: File) =
        stream(ProcessBuilder(adb(serial, "exec-in", command)).redirectInput(source).redirectOutput(ProcessBuilder.Redirect.DISCARD))

    override fun pull(serial: String, command: String, destination: File, onProgress: (Long) -> Unit) =
        stream(ProcessBuilder(adb(serial, "exec-out", command)).redirectOutput(destination)) { onProgress(destination.length()) }

    // Sends the file itself rather than redirecting it, to count what's gone.
    override fun push(serial: String, command: String, source: File, onProgress: (Long) -> Unit) {
        val process = ProcessBuilder(adb(serial, "exec-in", command)).redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        try {
            var sent = 0L
            process.outputStream.use { input ->
                source.inputStream().use { file ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        if (Thread.interrupted()) throw InterruptedException()
                        val read = file.read(buffer).takeIf { it >= 0 } ?: break
                        input.write(buffer, 0, read)
                        sent += read
                        onProgress(sent)
                    }
                }
            }
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) { }
            check(process.exitValue() == 0) { "The device stopped the transfer. Try again." }
        } finally { if (process.isAlive) process.destroyForcibly() }
    }

    private fun adb(serial: String, vararg args: String) = listOf(Toolchain(settings).adb.path, "-s", serial) + args

    private fun stream(builder: ProcessBuilder, onTick: () -> Unit = {}) {
        val process = builder.redirectError(ProcessBuilder.Redirect.DISCARD).start()
        try {
            // Waits in steps, so cancelling the transfer stops it, and its progress can be told.
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) onTick()
            check(process.exitValue() == 0) { "The device stopped the transfer. Try again." }
        } finally { if (process.isAlive) process.destroyForcibly() }
    }
}

/**
 * Where browsing starts: the device's shared storage, or an app's own files, reached with `run-as` for a debuggable app
 * and through root for others.
 */
internal sealed interface FileRoot {
    val path: String
    data object Storage : FileRoot { override val path = "/" }
    data class AppData(val app: InstalledApp) : FileRoot { override val path = "." }
    /** Apps' own folders on a device that allows root, such as a Google APIs emulator: every app's, release builds too. */
    data object Superuser : FileRoot { override val path = "/" }
}

internal enum class DeviceFileKind { Folder, File }

internal data class DeviceFile(val name: String, val path: String, val kind: DeviceFileKind, val size: Long, val modified: Instant) {
    val isDatabase get() = kind == DeviceFileKind.File && databaseExtensions.any { name.endsWith(it, ignoreCase = true) }
    /** Named with a leading dot, which Android and other Unix systems treat as hidden. */
    val isHidden get() = name.startsWith('.')
    val isVideo get() = kind == DeviceFileKind.File && videoExtensions.any { name.endsWith(it, ignoreCase = true) }
    val isImage get() = kind == DeviceFileKind.File && imageExtensions.any { name.endsWith(it, ignoreCase = true) }
}

private val databaseExtensions = listOf(".db", ".sqlite", ".sqlite3", ".db3")
private val videoExtensions = listOf(".mp4", ".m4v", ".3gp", ".webm", ".mkv", ".mov")
private val imageExtensions = listOf(".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp")

/** [bytes] of an image, scaled to fit a square of [size] pixels with its shape kept, or null when it isn't an image. */
internal fun thumbnailOf(bytes: ByteArray, size: Int): ImageBitmap? = runCatching {
    val image = SkiaImage.makeFromEncoded(bytes)
    val scale = minOf(1f, size.toFloat() / maxOf(image.width, image.height))
    val width = maxOf(1, (image.width * scale).roundToInt())
    val height = maxOf(1, (image.height * scale).roundToInt())
    val surface = Surface.makeRasterN32Premul(width, height)
    surface.canvas.drawImageRect(image, Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
        Rect.makeWH(width.toFloat(), height.toFloat()), SamplingMode.MITCHELL, null, true)
    surface.makeImageSnapshot().toComposeImageBitmap()
}.getOrNull()

/** The path of [name] in [folder], which may be the device's root. */
internal fun childPath(folder: String, name: String) = if (folder.endsWith('/')) "$folder$name" else "$folder/$name"

/** [text] quoted for a device's shell. */
internal fun quoteShell(text: String) = "'" + text.replace("'", "'\\''") + "'"

/**
 * [script] run in [root]: inside the app's own files for an app, which `run-as` reaches for a debuggable app, and root
 * for any other, on a device that allows it.
 */
internal fun inRoot(root: FileRoot, script: String) = when (root) {
    FileRoot.Storage -> script
    is FileRoot.AppData -> if (root.app.debuggable) "run-as ${root.app.packageName} sh -c ${quoteShell(script)}"
        else "su 0 sh -c ${quoteShell("cd ${quoteShell(appHome(root.app))} && $script")}"
    FileRoot.Superuser -> "su 0 sh -c ${quoteShell(script)}"
}

/**
 * [script] run in [app]'s own folder as the app: through `run-as` for a debuggable app, and otherwise through root as
 * the app's own user, so what it makes is the app's. Files it makes are the app's alone, as the app's own are.
 */
internal fun asApp(app: InstalledApp, script: String): String {
    if (app.debuggable) return inRoot(FileRoot.AppData(app), script)
    val uid = requireNotNull(app.uid) { "Robomori couldn't tell which user ${app.name} runs as." }
    return "su $uid sh -c ${quoteShell("umask 077; cd ${quoteShell(appHome(app))} && $script")}"
}

private fun appHome(app: InstalledApp) = childPath(AppsFolder, app.packageName)

/**
 * Gives [target] the owner and security label of [reference], and its permissions too with [mode], so a file made or
 * changed through root in an app's folder stays the app's to read and write: SELinux keeps an app out of files labelled
 * for another app, or for none.
 */
internal fun sameAttributesAs(reference: String, target: String, mode: Boolean): String {
    val from = quoteShell(reference)
    val to = quoteShell(target)
    return "chown $(stat -c %u:%g $from) $to && " + (if (mode) "chmod $(stat -c %a $from) $to && " else "") +
        "chcon $(stat -c %C $from) $to"
}

/**
 * Hands [path], made as root in an app's own folder, to that app, with the security label its folder's files have, so
 * the app can still read and change it.
 */
internal fun ownedByApp(path: String): String {
    val packageName = appPackageOf(path) ?: return ""
    val home = quoteShell(childPath(AppsFolder, packageName))
    return "; chown -R $(stat -c '%u:%g' $home) ${quoteShell(path)}; restorecon -R ${quoteShell(path)} 2>/dev/null; true"
}

/** Where a copy to [target] is written first, beside it under a hidden name, before it's moved into place. */
internal fun partialOf(target: String) = childPath(parentOf(target), ".${target.substringAfterLast('/')}.robomori")

/**
 * Moves [partial] over [target], giving it [target]'s permissions, owner and SELinux label first, so the file stays as
 * readable and writable for its app and the system as it was. Owner and label change only where the shell may change
 * them, such as as root.
 */
internal fun keepingAttributes(partial: String, target: String): String {
    val p = quoteShell(partial)
    val t = quoteShell(target)
    return "if [ -e $t ]; then chmod $(stat -c %a $t) $p; chown $(stat -c %u:%g $t) $p 2>/dev/null; " +
        "chcon $(stat -c %C $t) $p 2>/dev/null; fi; mv -f $p $t && echo ROBOMORI_DONE"
}

/** The largest file the Files page edits as text: bigger ones are rarely written by hand, and slow to edit in a field. */
internal const val EditableTextBytes = 2L * 1024 * 1024

/** [bytes] as text, when they're valid UTF-8 with no NUL characters, which text files don't have; null otherwise. */
internal fun textOf(bytes: ByteArray): String? {
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
    val text = runCatching { decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString() }.getOrNull() ?: return null
    return text.takeIf { '\u0000' !in it }
}

/** What reading a file to edit found. */
internal sealed interface TextFileRead {
    /** Its [text], and its change stamp from just before it was read. */
    data class Text(val text: String, val stamp: Set<String>) : TextFileRead
    data class TooLarge(val size: Long) : TextFileRead
    data object NotText : TextFileRead
}

/** What saving an edited file did. */
internal sealed interface TextFileWrite {
    /** Saved, with the file's new change stamp. */
    data class Written(val stamp: Set<String>) : TextFileWrite
    /** Not saved: the file changed on the device since it was opened. */
    data object Changed : TextFileWrite
}

internal class DeviceFilesService(
    private val transport: DeviceFileTransport,
    private val helper: () -> DeviceHelperJar = { DeviceHelperJar.bundled },
) {
    /** Whether the device lets the shell become root, as Google APIs emulators do, to open every app's own files. */
    fun hasRoot(serial: String): Boolean = runCatching { transport.shell(serial, "su 0 id") }.getOrDefault("").contains("uid=0")

    /**
     * The files in [folder]. With [onPart], the files read so far are handed over a few times a second while the device
     * lists them, so a huge folder fills in as it's read rather than appearing all at once.
     */
    fun list(serial: String, root: FileRoot, folder: String, onPart: ((List<DeviceFile>) -> Unit)? = null): List<DeviceFile> {
        val script = "cd ${quoteShell("$folder/")} && { find . -mindepth 1 -maxdepth 1 -exec stat -L -c '%F|%s|%Y|%n' {} + 2>/dev/null; echo ROBOMORI_DONE; }"
        val found = mutableListOf<DeviceFile>()
        val other = StringBuilder()
        var done = false
        var handed = System.nanoTime()
        runCatching {
            transport.shellLines(serial, inRoot(root, script)) { line ->
                if (line.trim() == "ROBOMORI_DONE") done = true
                else parseFileLine(line, folder)?.let { found += it } ?: other.appendLine(line)
                if (onPart != null && System.nanoTime() - handed > PartEveryNanos) {
                    handed = System.nanoTime()
                    onPart(found.sortedForListing())
                }
            }
        }.onFailure { if (it is InterruptedException) throw it }
        check(done) {
            if (root is FileRoot.AppData && "not debuggable" in other) "${root.app.name} can't be browsed: it isn't debuggable."
            else "Could not open this folder."
        }
        return found.sortedForListing()
    }

    /**
     * The files and folders under [folder], at any depth, whose names hold [term], ignoring case, as Finder's search
     * finds them. They fill in through [onPart] as the device finds them, up to [limit] of them. Searching from the
     * root leaves out the system's own pseudo-folders, such as /proc, whose files aren't files.
     */
    fun search(serial: String, root: FileRoot, folder: String, term: String, limit: Int = SearchLimit,
               onPart: (List<DeviceFile>) -> Unit = {}): List<DeviceFile> {
        val pattern = quoteShell("*" + term.replace(Regex("""([\\*?\[\]])"""), "\\\\$1") + "*")
        val prune = if (folder == "/") "\\( -path ./proc -o -path ./sys -o -path ./dev -o -path ./acct -o -path ./config \\) -prune -o " else ""
        val script = "cd ${quoteShell("$folder/")} && { find . $prune-iname $pattern -exec stat -L -c '%F|%s|%Y|%n' {} + 2>/dev/null | " +
            "head -n $limit; echo ROBOMORI_DONE; }"
        val found = mutableListOf<DeviceFile>()
        var handed = System.nanoTime()
        runCatching {
            transport.shellLines(serial, inRoot(root, script)) { line ->
                parseFoundLine(line, folder)?.let { found += it }
                if (System.nanoTime() - handed > PartEveryNanos) {
                    handed = System.nanoTime()
                    onPart(found.toList())
                }
            }
        }.onFailure { if (it is InterruptedException) throw it }
        return found
    }

    /** Copies [file] to [destination] on this computer. */
    fun save(serial: String, root: FileRoot, file: DeviceFile, destination: File, onProgress: (done: Long, total: Long?) -> Unit = { _, _ -> }) {
        val size = size(serial, root, file.path)
        val temporary = File(destination.parentFile, ".${destination.name}.robomori")
        try {
            transport.pull(serial, inRoot(root, "cat ${quoteShell(file.path)}"), temporary) { onProgress(it, size) }
            check(temporary.length() == size) { "Could not copy ${file.name} from the device. Try again." }
            check(temporary.renameTo(destination) || (destination.delete() && temporary.renameTo(destination))) {
                "Could not save ${destination.name}."
            }
        } finally { temporary.delete() }
    }

    /**
     * Copies [folder], with everything in it, into [destination] on this computer, where it keeps its name. The device
     * streams it as one tar archive, which this computer's own tar unpacks.
     */
    fun saveFolder(serial: String, root: FileRoot, folder: DeviceFile, destination: File, onProgress: (done: Long, total: Long?) -> Unit = { _, _ -> }) {
        val parent = folder.path.substringBeforeLast('/').ifEmpty { "/" }
        val archive = File.createTempFile("robomori-folder", ".tar")
        try {
            transport.pull(serial, inRoot(root, "tar -cf - -C ${quoteShell(parent)} ${quoteShell(folder.name)}"), archive) { onProgress(it, null) }
            check(archive.length() > 0) { "Could not copy ${folder.name} from the device. Try again." }
            destination.mkdirs()
            val unpack = ProcessBuilder("tar", "-xf", archive.absolutePath, "-C", destination.absolutePath)
                .redirectErrorStream(true).start()
            val output = unpack.inputStream.bufferedReader().readText()
            check(unpack.waitFor() == 0 && File(destination, folder.name).isDirectory) {
                output.lineSequence().map(String::trim).lastOrNull(String::isNotEmpty) ?: "Could not unpack ${folder.name}."
            }
        } finally { archive.delete() }
    }

    /** Copies [source] from this computer into [folder], replacing a file of the same name. */
    fun upload(serial: String, root: FileRoot, source: File, folder: String, name: String = source.name,
               onProgress: (done: Long, total: Long?) -> Unit = { _, _ -> }) =
        pushTo(serial, root, source, childPath(folder, name)) { onProgress(it, source.length()) }

    /**
     * Square previews of the images and videos at [paths], [size] pixels wide, made on the device so only a few
     * kilobytes each cross the cable: an encoded image, or null for one that can't be read.
     */
    fun thumbnails(serial: String, paths: List<String>, size: Int): List<ByteArray?> {
        if (paths.isEmpty()) return emptyList()
        val remote = stagedHelper(serial, "thumbs")
        val output = transport.shell(serial, "CLASSPATH=$remote app_process / com.robomori.device.ThumbnailHelper $size " +
            paths.joinToString(" ") { quoteShell(it) })
        val found = output.lineSequence().mapNotNull { line ->
            val fields = line.trimEnd('\r').split('\t')
            if (fields.size < 2 || fields[0] != "ROBOMORI_THUMB") return@mapNotNull null
            val encoded = fields.getOrNull(2).orEmpty()
            fields[1].toIntOrNull()?.let { it to encoded.takeIf(String::isNotEmpty)?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() } }
        }.toMap()
        return paths.indices.map { found[it] }
    }

    /**
     * [file]'s text, to edit, with its change stamp from before it was read, or why it can't be edited: it's larger than
     * [EditableTextBytes], or isn't UTF-8 text.
     */
    fun readText(serial: String, root: FileRoot, file: DeviceFile): TextFileRead {
        val stamp = changeStamps(serial, root, listOf(file.path))
        val size = size(serial, root, file.path)
        if (size > EditableTextBytes) return TextFileRead.TooLarge(size)
        return textOf(read(serial, root, file))?.let { TextFileRead.Text(it, stamp) } ?: TextFileRead.NotText
    }

    /**
     * Replaces the file at [path] with [text], unless it changed on the device since it had the stamp [opened], when
     * [overwrite] is needed. The new file keeps the old one's permissions, owner and security label, where [root] may set
     * them, and replaces it all at once, so an app never reads half of it.
     */
    fun writeText(serial: String, root: FileRoot, path: String, text: String, opened: Set<String>, overwrite: Boolean): TextFileWrite {
        if (!overwrite && changeStamps(serial, root, listOf(path)) != opened) return TextFileWrite.Changed
        // Finds out at once when the folder can't be written, such as a read-only system one, and says why.
        val partial = partialOf(path)
        val ready = transport.shell(serial, inRoot(root, ": > ${quoteShell(partial)} && echo ROBOMORI_READY"))
        check("ROBOMORI_READY" in ready) { ready.lineSequence().map(String::trim).lastOrNull(String::isNotEmpty) ?: "Could not write ${path.substringAfterLast('/')}." }
        val local = File.createTempFile("robomori-edit", null)
        try {
            local.writeText(text)
            pushTo(serial, root, local, path, keepAttributes = true)
        } finally { local.delete() }
        return TextFileWrite.Written(changeStamps(serial, root, listOf(path)))
    }

    /**
     * Robomori's device helper in /data/local/tmp as `robomori-[name]-<build>.jar`, pushed once per device and build,
     * where the shell and, through root, any app's user can run it. Read-only, as Android runs code for an app's user
     * only from files that can't be changed.
     */
    fun stagedHelper(serial: String, name: String): String {
        val jar = helper()
        val remote = "/data/local/tmp/robomori-$name-${jar.version}.jar"
        if (transport.shell(serial, deviceFileMatches(remote, jar.file.length())).trim() != "ROBOMORI_PRESENT") {
            transport.shell(serial, "rm -f /data/local/tmp/robomori-$name-*.jar")
            pushTo(serial, FileRoot.Storage, jar.file, remote)
            transport.shell(serial, "chmod 444 ${quoteShell(remote)}")
        }
        return remote
    }

    /** Copies [source] to [target] on the device, whole: under a temporary name first, then moved into place. */
    private fun pushTo(serial: String, root: FileRoot, source: File, target: String, keepAttributes: Boolean = false,
                       onProgress: (Long) -> Unit = {}) {
        val partial = partialOf(target)
        try {
            transport.push(serial, inRoot(root, "cat > ${quoteShell(partial)}"), source, onProgress)
        } catch (stopped: Exception) {
            // A cancelled or failed copy leaves nothing half-written behind.
            runCatching { transport.shell(serial, inRoot(root, "rm -f ${quoteShell(partial)}")) }
            throw stopped
        }
        // adb returns before the device has written everything it was sent.
        val written = (1..100).any { attempt ->
            if (attempt > 1) Thread.sleep(100)
            runCatching { size(serial, root, partial) }.getOrNull() == source.length()
        }
        if (!written) {
            runCatching { transport.shell(serial, inRoot(root, "rm -f ${quoteShell(partial)}")) }
            error("Could not copy ${target.substringAfterLast('/')} to the device. Try again.")
        }
        if (keepAttributes) {
            val output = transport.shell(serial, inRoot(root, keepingAttributes(partial, target)))
            check("ROBOMORI_DONE" in output) {
                output.lineSequence().map(String::trim).lastOrNull(String::isNotEmpty) ?: "Could not write ${target.substringAfterLast('/')}."
            }
        } else transport.shell(serial, inRoot(root, "mv -f ${quoteShell(partial)} ${quoteShell(target)}" + (if (root == FileRoot.Superuser) ownedByApp(target) else "")))
    }

    /**
     * A line for each of [paths] that exists, with its size and when it last changed, to the nanosecond: it changes
     * whenever the file is written, even twice within a second.
     */
    fun changeStamps(serial: String, root: FileRoot, paths: List<String>): Set<String> =
        transport.shell(serial, inRoot(root, "stat -L -c '%n|%s|%y' ${paths.joinToString(" ") { quoteShell(it) }} 2>/dev/null; true"))
            .lineSequence().map(String::trim).filterTo(mutableSetOf()) { line -> line.count { it == '|' } >= 2 }

    /**
     * A fingerprint of [folder]'s listing: each entry's kind, size, name and when it last changed, to the nanosecond,
     * hashed on the device so a few bytes cross the cable rather than the listing. It differs whenever the listing
     * would. Null when the folder can't be read.
     */
    fun folderStamp(serial: String, root: FileRoot, folder: String): String? {
        val script = "cd ${quoteShell("$folder/")} && find . -mindepth 1 -maxdepth 1 -exec stat -L -c '%F|%s|%y|%n' {} + 2>/dev/null | md5sum"
        val output = runCatching { transport.shell(serial, inRoot(root, script)) }.getOrElse { if (it is InterruptedException) throw it; return null }
        return output.lineSequence().mapNotNull { Md5Line.find(it.trim())?.value }.lastOrNull()
    }

    /**
     * Asks Android to add [paths] in shared storage to its media index, folders with everything in them, so photos and
     * videos copied in show up in Photos and over USB. Android 11 and later index what the shell writes there by
     * themselves; Android 10 doesn't. The media provider's own scan comes first, and the older broadcast stands in where
     * the shell can't call it.
     */
    fun scanMedia(serial: String, paths: List<String>) {
        if (paths.isEmpty()) return
        transport.shell(serial, paths.joinToString("; ", postfix = "; true") { path ->
            "content call --uri content://media/external/file --method scan_file --arg ${quoteShell(path)} 2>/dev/null | grep -q Result || " +
                "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d ${quoteShell(fileUri(path))} >/dev/null 2>&1"
        })
    }

    /** Runs [script] in [app]'s own folder, as the app, and returns what it printed. */
    fun shellAsApp(serial: String, app: InstalledApp, script: String): String = transport.shell(serial, asApp(app, script))

    /**
     * Gives those of [paths] in [app]'s own folder that exist the owner and security label of the file at [reference]:
     * files a release build's user makes through root lack the label that lets the app itself open them.
     */
    fun matchAppFiles(serial: String, app: InstalledApp, reference: String, paths: List<String>) {
        if (app.debuggable || paths.isEmpty()) return
        transport.shell(serial, inRoot(FileRoot.AppData(app), paths.joinToString("; ", postfix = "; true") { path ->
            "if [ -e ${quoteShell(path)} ]; then ${sameAttributesAs(reference, path, mode = false)}; fi"
        }))
    }

    /**
     * Writes [source] to [path] in [app]'s own folder, as the app, readable but not writable: Android loads code only
     * from files that can't be changed.
     */
    fun installAsApp(serial: String, app: InstalledApp, source: File, path: String) {
        val partial = quoteShell("$path.robomori")
        // Streamed in on its own, as uploads are: the push ends with the copy, so what follows it runs separately.
        transport.push(serial, inRoot(FileRoot.AppData(app), "cat > $partial"), source)
        transport.shell(serial, inRoot(FileRoot.AppData(app), "chmod 444 $partial && mv -f $partial ${quoteShell(path)}"))
    }

    /**
     * Replaces the file at [path] in [app]'s own folder with [source], as the app, all at once so the app never reads half
     * of it. Written through root, the new file first takes the old one's owner, permissions and security label.
     */
    fun writeAsApp(serial: String, app: InstalledApp, source: File, path: String) {
        val root = FileRoot.AppData(app)
        val partial = "$path.robomori"
        transport.push(serial, inRoot(root, "cat > ${quoteShell(partial)}"), source)
        if (app.debuggable) {
            transport.shell(serial, inRoot(root, "mv -f ${quoteShell(partial)} ${quoteShell(path)}"))
            return
        }
        val output = transport.shell(serial, inRoot(root,
            "${sameAttributesAs(path, partial, mode = true)} && mv -f ${quoteShell(partial)} ${quoteShell(path)} && echo ROBOMORI_DONE"))
        if ("ROBOMORI_DONE" !in output) {
            runCatching { transport.shell(serial, inRoot(root, "rm -f ${quoteShell(partial)}")) }
            error(output.lineSequence().map(String::trim).lastOrNull(String::isNotEmpty) ?: "Could not write ${path.substringAfterLast('/')}.")
        }
    }

    /** [app]'s own folder in /data/data, with when it last changed, read as the app since only it can. */
    fun appFolder(serial: String, app: InstalledApp): DeviceFile {
        val path = childPath(AppsFolder, app.packageName)
        val fields = runCatching { transport.shell(serial, inRoot(FileRoot.AppData(app), "stat -L -c '%s|%Y' ${quoteShell(path)}")) }
            .getOrDefault("").trim().split('|')
        return DeviceFile(app.packageName, path, DeviceFileKind.Folder, fields.getOrNull(0)?.toLongOrNull() ?: 0,
            Instant.ofEpochSecond(fields.getOrNull(1)?.toLongOrNull() ?: 0))
    }

    /** Gives [file] the name [name] in its folder, unless something there has that name already. */
    fun rename(serial: String, root: FileRoot, file: DeviceFile, name: String) {
        val target = childPath(file.path.substringBeforeLast('/').ifEmpty { "/" }, name)
        val output = transport.shell(serial, inRoot(root,
            "if [ -e ${quoteShell(target)} ]; then echo ROBOMORI_EXISTS; else mv ${quoteShell(file.path)} ${quoteShell(target)} && echo ROBOMORI_DONE; fi"))
        check("ROBOMORI_EXISTS" !in output) { "Something named $name is already in this folder." }
        check("ROBOMORI_DONE" in output) { output.lineSequence().map(String::trim).lastOrNull(String::isNotEmpty) ?: "Could not rename ${file.name}." }
    }

    /** Copies [file], with everything in it for a folder, into [folder] as [name], unless something there has that name. */
    fun copy(serial: String, root: FileRoot, file: DeviceFile, folder: String, name: String, replace: Boolean = false) {
        val target = childPath(folder, name)
        val output = transport.shell(serial, inRoot(root, (if (replace) "rm -rf ${quoteShell(target)}; " else "") +
            "if [ -e ${quoteShell(target)} ]; then echo ROBOMORI_EXISTS; else cp -r ${quoteShell(file.path)} ${quoteShell(target)} && echo ROBOMORI_DONE; fi" +
            (if (root == FileRoot.Superuser) ownedByApp(target) else "")))
        check("ROBOMORI_EXISTS" !in output) { "Something named $name is already in this folder." }
        check("ROBOMORI_DONE" in output) { output.lineSequence().map(String::trim).lastOrNull(String::isNotEmpty) ?: "Could not copy ${file.name}." }
    }

    /** Moves [from] to [to] on the device, unless something is at [to] already, which [replace] removes first. */
    fun move(serial: String, root: FileRoot, from: String, to: String, replace: Boolean = false) {
        val name = to.substringAfterLast('/')
        val output = transport.shell(serial, inRoot(root, (if (replace) "rm -rf ${quoteShell(to)}; " else "") +
            "if [ -e ${quoteShell(to)} ]; then echo ROBOMORI_EXISTS; else mv ${quoteShell(from)} ${quoteShell(to)} && echo ROBOMORI_DONE; fi" +
            (if (root == FileRoot.Superuser) ownedByApp(to) else "")))
        check("ROBOMORI_EXISTS" !in output) { "Something named $name is already in that folder." }
        check("ROBOMORI_DONE" in output) { output.lineSequence().map(String::trim).lastOrNull(String::isNotEmpty) ?: "Could not move $name." }
    }

    /** Removes what's at [path]: an empty folder only, unless [contents], so undoing never loses anything put in since. */
    fun remove(serial: String, root: FileRoot, path: String, contents: Boolean) {
        val output = transport.shell(serial, inRoot(root, (if (contents) "rm -rf" else "rmdir") + " ${quoteShell(path)} && echo ROBOMORI_DONE"))
        check("ROBOMORI_DONE" in output) { output.lineSequence().map(String::trim).lastOrNull(String::isNotEmpty) ?: "Could not remove ${path.substringAfterLast('/')}." }
    }

    /** Makes a folder named [name] in [folder], unless something there has that name already. */
    fun createFolder(serial: String, root: FileRoot, folder: String, name: String) {
        val target = childPath(folder, name)
        val output = transport.shell(serial, inRoot(root,
            "if [ -e ${quoteShell(target)} ]; then echo ROBOMORI_EXISTS; else mkdir ${quoteShell(target)} && echo ROBOMORI_DONE; fi" +
            (if (root == FileRoot.Superuser) ownedByApp(target) else "")))
        check("ROBOMORI_EXISTS" !in output) { "Something named $name is already in this folder." }
        check("ROBOMORI_DONE" in output) { output.lineSequence().map(String::trim).lastOrNull(String::isNotEmpty) ?: "Could not make $name." }
    }

    fun delete(serial: String, root: FileRoot, file: DeviceFile) {
        val output = transport.shell(serial, inRoot(root, "rm -rf ${quoteShell(file.path)} && echo ROBOMORI_DONE"))
        check("ROBOMORI_DONE" in output) { output.lineSequence().map(String::trim).lastOrNull(String::isNotEmpty) ?: "Could not delete ${file.name}." }
    }

    /** [file]'s contents, for files small enough to hold in memory. */
    fun read(serial: String, root: FileRoot, file: DeviceFile): ByteArray {
        val local = File.createTempFile("robomori-file", null)
        try {
            save(serial, root, file, local)
            return local.readBytes()
        } finally { local.delete() }
    }

    fun size(serial: String, root: FileRoot, path: String): Long =
        transport.shell(serial, inRoot(root, "stat -L -c %s ${quoteShell(path)}")).trim().toLongOrNull()
            ?: error("Could not read the file on the device.")
}

/** A folder on the way to the open one; the root has no [name] of its own. */
internal data class FolderCrumb(val name: String?, val path: String)

internal fun folderCrumbs(root: FileRoot, folder: String): List<FolderCrumb> {
    val below = folder.removePrefix(root.path).split('/').filter(String::isNotEmpty)
    return below.runningFold(FolderCrumb(null, root.path)) { parent, name -> FolderCrumb(name, childPath(parent.path, name)) }
}

/** Reads `stat -c '%F|%s|%Y|%n'` lines of the entries of [folder]: folders first, then by name. */
internal fun parseFileListing(output: String, folder: String): List<DeviceFile> =
    output.lineSequence().mapNotNull { parseFileLine(it, folder) }.toList().sortedForListing()

/** One line of `stat -c '%F|%s|%Y|%n'` in [folder], or null for one that isn't a file or folder. */
internal fun parseFileLine(line: String, folder: String): DeviceFile? {
    val fields = line.trimEnd('\r').split('|', limit = 4)
    if (fields.size < 4) return null
    val name = fields[3].removePrefix("./").takeIf { it.isNotEmpty() && '/' !in it } ?: return null
    val kind = when {
        fields[0] == "directory" -> DeviceFileKind.Folder
        fields[0].startsWith("regular") -> DeviceFileKind.File
        else -> return null
    }
    return DeviceFile(name, childPath(folder, name), kind, fields[1].toLongOrNull() ?: 0, Instant.ofEpochSecond(fields[2].toLongOrNull() ?: 0))
}

/** One line of `stat -c '%F|%s|%Y|%n'` for a file [folder]'s `find` found, at any depth below it. */
internal fun parseFoundLine(line: String, folder: String): DeviceFile? {
    val fields = line.trimEnd('\r').split('|', limit = 4)
    if (fields.size < 4) return null
    val relative = fields[3].removePrefix("./").trimEnd('/').takeIf { it.isNotEmpty() && it != "." } ?: return null
    val kind = when {
        fields[0] == "directory" -> DeviceFileKind.Folder
        fields[0].startsWith("regular") -> DeviceFileKind.File
        else -> return null
    }
    return DeviceFile(relative.substringAfterLast('/'), childPath(folder, relative), kind, fields[1].toLongOrNull() ?: 0,
        Instant.ofEpochSecond(fields[2].toLongOrNull() ?: 0))
}

/** How many files a search shows at most, so a name found everywhere doesn't flood the list. */
internal const val SearchLimit = 2000

/** Folders first, then by name. */
private fun List<DeviceFile>.sortedForListing() =
    sortedWith(compareBy<DeviceFile> { it.kind != DeviceFileKind.Folder }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })

/** How often a folder being read hands over the files read so far. */
private const val PartEveryNanos = 150_000_000L

/** Where Android keeps each app's own folder, which only the app, or `run-as` for a debuggable one, can open. */
internal const val AppsFolder = "/data/data"

/** Where the device keeps the files people work with, its shared storage, under each of the names it goes by. */
private val SharedStorage = listOf("/sdcard", "/storage/emulated/0", "/storage/self/primary")

/** Whether [path] is in the device's shared storage, where Android indexes photos, videos and other media. */
internal fun inSharedStorage(path: String) = SharedStorage.any { path == it || path.startsWith("$it/") }

/** A `file://` URI for [path] on the device, with spaces and other such characters escaped. */
internal fun fileUri(path: String): String = java.net.URI("file", "", path, null).toASCIIString()

private val Md5Line = Regex("^[0-9a-f]{32}\\b")

/** The device paths of [files], one per line, as Copy Path puts them on the clipboard. */
internal fun pathsText(files: List<DeviceFile>) = files.joinToString("\n") { it.path }

/**
 * Whether the App data page opens [file]: one of its app's databases, a SharedPreferences file, or a DataStore
 * preferences file, where that page looks for them. A database's journals open with it, and other files beside the
 * databases, such as backups, aren't databases, so neither is offered.
 */
internal fun opensInAppData(file: DeviceFile): Boolean {
    if (file.kind != DeviceFileKind.File) return false
    val packageName = appPackageOf(file.path) ?: return false
    val inApp = file.path.removePrefix(childPath(AppsFolder, packageName) + "/")
    return when (inApp.substringBeforeLast('/', "")) {
        "databases" -> file.size > 0 && listOf("-wal", "-shm", "-journal").none { file.name.endsWith(it) } &&
            (file.isDatabase || '.' !in file.name)
        "shared_prefs" -> file.name.endsWith(".xml")
        "files/datastore" -> file.name.endsWith(".preferences_pb")
        else -> false
    }
}

/** The app whose own folder [path] is in, if it is. */
internal fun appPackageOf(path: String): String? =
    path.removePrefix("$AppsFolder/").takeIf { it != path }?.substringBefore('/')?.takeIf(String::isNotEmpty)

/** A folder adb's shell user can't list but can pass through, such as /data on the way to /data/local/tmp. */
private fun passage(name: String, path: String) = DeviceFile(name, path, DeviceFileKind.Folder, 0, Instant.EPOCH)

/** A device file opened on this computer: where its copy is, and when the copy was last in step with the device. */
internal class OpenedCopy(val file: DeviceFile, val local: File, @Volatile var seen: Long)

/** An opened file saved on this computer since, waiting to be uploaded back or discarded. */
internal data class LocalEdit(val file: DeviceFile, val local: File, val size: Long, val modified: Instant)

/** A name a file can take: not empty, without a slash, and not `.` or `..`. */
internal fun isValidFileName(name: String) = name.isNotBlank() && '/' !in name && name != "." && name != ".."

/**
 * Opens [file] in [app]: an app bundle on macOS, a program run with the file on Linux. Without an app, shows Windows' own
 * Open With dialog, where people pick one themselves.
 */
internal fun openWithApp(file: File, app: File?) {
    val command = when {
        app == null -> listOf("rundll32.exe", "shell32.dll,OpenAs_RunDLL", file.path)
        HostOs.detect() == HostOs.MAC -> listOf("open", "-a", app.path, file.path)
        else -> listOf(app.path, file.path)
    }
    ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
}

/** Stands for the `..` row in the selection, which otherwise holds files' paths; no file has this path. */
internal const val ParentRowName = ".."

internal enum class FileShortcut { Back, Forward, Up, Open, Previous, Next, ToggleHidden, Search, Delete, Copy, Paste, Cut, MoveHere, Undo, SelectAll, QuickLook }

/**
 * The file browser keys of the platform: Finder's on macOS (⌘[ ⌘] ⌘↑ ⌘↓ ⌘O ⌘⇧. ⌘F ⌘⌫), and Explorer's and Linux file
 * managers' elsewhere (Alt+← Alt+→ Alt+↑ Backspace Enter, Ctrl+H Ctrl+F Delete). The arrow keys move the selection on both.
 */
internal fun fileShortcut(key: Key, meta: Boolean, ctrl: Boolean, alt: Boolean, shift: Boolean, mac: Boolean): FileShortcut? {
    if (!meta && !ctrl && !alt) when (key) {
        Key.DirectionUp -> return FileShortcut.Previous
        Key.DirectionDown -> return FileShortcut.Next
        Key.Spacebar -> return FileShortcut.QuickLook
    }
    // ⌘⌥V moves what was copied, as in Finder.
    if (mac && meta && alt && !ctrl && !shift && key == Key.V) return FileShortcut.MoveHere
    return if (mac) when {
        !meta || ctrl || alt -> null
        shift -> FileShortcut.ToggleHidden.takeIf { key == Key.Period }
        else -> when (key) {
            Key.LeftBracket -> FileShortcut.Back
            Key.RightBracket -> FileShortcut.Forward
            Key.DirectionUp -> FileShortcut.Up
            Key.DirectionDown, Key.O -> FileShortcut.Open
            Key.F -> FileShortcut.Search
            Key.C -> FileShortcut.Copy
            Key.V -> FileShortcut.Paste
            Key.Z -> FileShortcut.Undo
            Key.A -> FileShortcut.SelectAll
            Key.Backspace, Key.Delete -> FileShortcut.Delete
            else -> null
        }
    } else when {
        alt && !ctrl && !shift -> when (key) {
            Key.DirectionLeft -> FileShortcut.Back
            Key.DirectionRight -> FileShortcut.Forward
            Key.DirectionUp -> FileShortcut.Up
            else -> null
        }
        ctrl && !alt && !shift -> when (key) {
            Key.H -> FileShortcut.ToggleHidden
            Key.F -> FileShortcut.Search
            Key.C -> FileShortcut.Copy
            Key.X -> FileShortcut.Cut
            Key.V -> FileShortcut.Paste
            Key.Z -> FileShortcut.Undo
            Key.A -> FileShortcut.SelectAll
            else -> null
        }
        ctrl || alt || meta -> null
        key == Key.Backspace -> FileShortcut.Back
        key == Key.Delete -> FileShortcut.Delete
        key == Key.Enter || key == Key.NumPadEnter -> FileShortcut.Open
        else -> null
    }
}

/** How an action names the files it works on: one by its name, several by their count. */
private val List<DeviceFile>.title get() = singleOrNull()?.name ?: "$size items"

internal enum class FileColumn { Name, Modified, Size, Kind }

/** How the open folder is ordered: by one column, either way. Folders always come first. */
internal data class FileSort(val column: FileColumn = FileColumn.Name, val ascending: Boolean = true)

internal enum class FileType { Folder, Database, Image, File }

internal val DeviceFile.type get() = when {
    kind == DeviceFileKind.Folder -> FileType.Folder
    isDatabase -> FileType.Database
    isImage -> FileType.Image
    else -> FileType.File
}

internal fun List<DeviceFile>.sortedBy(sort: FileSort): List<DeviceFile> {
    val byName = compareBy(String.CASE_INSENSITIVE_ORDER, DeviceFile::name)
    val byColumn = when (sort.column) {
        FileColumn.Name -> byName
        FileColumn.Modified -> compareBy<DeviceFile> { it.modified }.then(byName)
        FileColumn.Size -> compareBy<DeviceFile> { it.size }.then(byName)
        FileColumn.Kind -> compareBy<DeviceFile> { it.type }.then(byName)
    }
    return sortedWith(compareBy<DeviceFile> { it.kind != DeviceFileKind.Folder }.then(if (sort.ascending) byColumn else byColumn.reversed()))
}

/** Each column's share of the table's width, so they all grow and shrink with it. */
private const val NewFolderName = "untitled folder"

/** How many changes ⌘Z can step back through. */
private const val MaxUndos = 50

/** The folder [path] is in. */
internal fun parentOf(path: String) = path.substringBeforeLast('/').ifEmpty { "/" }

/** Tells how far a copy has got: the file under way, the bytes done, and its size when known. */
private typealias Report = (file: String, done: Long, total: Long?) -> Unit

/** The actions after which the open folder is read again. */
private val ChangesFolder = setOf(FileAction.Upload, FileAction.Rename, FileAction.Delete, FileAction.CreateFolder,
    FileAction.Paste, FileAction.Move, FileAction.Undo)


/** How many folders' files are remembered, to show at once when going back to one. */
private const val RememberedFolders = 64

private val DefaultColumnShares = mapOf(FileColumn.Name to .52f, FileColumn.Modified to .22f, FileColumn.Size to .11f, FileColumn.Kind to .15f)
internal val MinColumnWidth = 48.dp

private const val ThumbnailPixels = 48
// Bigger images take too long to copy over just for a preview.
private const val ThumbnailMaxBytes = 20L * 1024 * 1024

internal sealed interface FolderSnapshot {
    data object Loading : FolderSnapshot
    data class Loaded(val files: List<DeviceFile>) : FolderSnapshot
    data class Failed(val cause: Throwable) : FolderSnapshot
}

internal enum class FileAction { Open, Save, Upload, Rename, Delete, CreateFolder, Paste, Move, Undo }

internal sealed interface FileActionState {
    data object Idle : FileActionState
    /** [files] are the names of the files in the open folder it works on. */
    data class Running(val action: FileAction, val name: String, val files: Set<String> = emptySet(),
                       val progress: TransferProgress? = null) : FileActionState
    data class Failed(val action: FileAction, val name: String, val cause: Throwable) : FileActionState
}

/** How far a copy to or from the device has got: [done] of [file]'s [total] bytes, when its size is known. */
internal data class TransferProgress(val file: String, val done: Long, val total: Long?)

/** What to do with files going into a folder that has some of their names already. */
internal enum class ConflictChoice { Replace, KeepBoth, Skip }

/** Files going into [folder] whose [names] it has already, waiting for a [ConflictChoice]. */
internal data class NameConflict(val folder: String, val names: List<String>)

/** Browsing one device's files: the place being looked at, its contents, and the file action under way. */
internal class DeviceFilesState(
    private val service: DeviceFilesService,
    val serial: String,
    private val scope: CoroutineScope,
    /** Shows a file copied to this computer in the app that opens its kind. */
    private val openLocally: (File) -> Unit = { Desktop.getDesktop().open(it) },
    /** Shows a file copied to this computer in the app at the second path, or with none, asks Windows which app to use. */
    private val openLocallyWith: (File, File?) -> Unit = ::openWithApp,
    /** The device's debuggable apps, whose own folders in /data/data can be opened with `run-as`. */
    private val debuggableApps: (String) -> List<InstalledApp> = { emptyList() },
    /** Previews files copied to this computer: macOS's Quick Look panel, or the app that opens their kind elsewhere. */
    private val previewer: FilePreviewer = SystemPreviewer,
    /** Where browsing starts: the folder, history and scroll positions this device's page last had, if any. */
    startPlace: FilesPlace? = null,
) {
    /** The whole device, from its root, which only shows what adb's shell user can read. */
    val root: FileRoot = FileRoot.Storage
    var apps by mutableStateOf<List<InstalledApp>>(emptyList()); private set

    suspend fun loadApps() {
        apps = runCatching { runInterruptible(Dispatchers.IO) { debuggableApps(serial) } }.getOrDefault(apps)
    }

    /** Opens [packageName]'s own folder, where it keeps its preferences, databases and files. */
    fun showApp(packageName: String) = go(childPath(AppsFolder, packageName))

    /** The app whose own folder [file] is, for showing its icon. */
    fun appFor(file: DeviceFile): InstalledApp? =
        if (file.path.substringBeforeLast('/') == AppsFolder) apps.firstOrNull { it.packageName == file.name } else null

    /**
     * Whether [file] can be opened on the App data page: a database or preferences file of any app on a device that
     * allows root, as that page then reads every app's files, and of a debuggable app otherwise.
     */
    fun canOpenInAppData(file: DeviceFile) = opensInAppData(file) && (rooted == true || apps.any { it.packageName == appPackageOf(file.path) })

    /** How [path] is reached: through its app's `run-as` inside an app's own folder, and directly everywhere else. */
    private fun rootFor(path: String): FileRoot {
        val packageName = appPackageOf(path) ?: return root
        if (rooted == true) return FileRoot.Superuser
        return FileRoot.AppData(apps.firstOrNull { it.packageName == packageName } ?: InstalledApp(packageName, packageName, null, null, null))
    }

    /** The folder's files: listed by the device, or for the folders adb's shell user can't list, the ones it can open. */
    private fun listFolder(folder: String, onPart: ((List<DeviceFile>) -> Unit)? = null): List<DeviceFile> {
        return when (folder) {
            "/data" -> listOf(passage("data", AppsFolder), passage("local", "/data/local"))
            "/data/local" -> listOf(passage("tmp", "/data/local/tmp"))
            // With root every app's folder opens, release builds too; without it, debuggable apps' only.
            AppsFolder -> if (rooted == true) service.list(serial, FileRoot.Superuser, folder).also { if (apps.isEmpty()) apps = debuggableApps(serial) }
                else apps.ifEmpty { debuggableApps(serial).also { apps = it } }.map { service.appFolder(serial, it) }
            else -> service.list(serial, rootFor(folder), folder, onPart)
        }
    }

    /** Whether the device allows root, found out when the first folder is read; null until then. */
    @Volatile private var rooted: Boolean? = null
    // Opens on shared storage, where people's and apps' files are.
    var folder by mutableStateOf(startPlace?.folder ?: "/sdcard"); private set
    var snapshot by mutableStateOf<FolderSnapshot>(FolderSnapshot.Loading); private set
    /** The names of the selected files in the open folder. */
    var selection by mutableStateOf<Set<String>>(emptySet()); private set
    private var anchor: String? = null
    // The file the arrow keys last moved to; Shift with them extends the selection from the anchor to it.
    private var cursor: String? = null
    /** The file waiting for the user to choose its new name. */
    var renaming by mutableStateOf<DeviceFile?>(null); private set
    /** The files waiting for the user to confirm deleting them. */
    var confirmingDelete by mutableStateOf<List<DeviceFile>?>(null); private set
    var action by mutableStateOf<FileActionState>(FileActionState.Idle); private set
    var sort by mutableStateOf(FileSort()); private set
    var showHidden by mutableStateOf(true); private set
    /** How wide each column after the name is; the name takes the rest. */
    var columnShares by mutableStateOf(DefaultColumnShares); private set
    /** The columns from left to right; the name always comes first. */
    var columnOrder by mutableStateOf(FileColumn.entries.toList()); private set

    fun reorderColumns(order: List<FileColumn>) { if (order.firstOrNull() == FileColumn.Name && order.toSet() == FileColumn.entries.toSet()) columnOrder = order }
    /** Small previews of images, by the version of the file they show. A null one couldn't be read. */
    private val thumbnails = mutableStateMapOf<DeviceFile, ImageBitmap?>()
    private val thumbnailLoads = Semaphore(3)
    private var loading: Job? = null

    val atRoot get() = folder == root.path

    /** Goes into a folder, or copies a file to a temporary folder on this computer and opens it there. */
    fun open(file: DeviceFile) {
        if (file.kind == DeviceFileKind.Folder) go(file.path) else openAll(listOf(file))
    }

    /** Opens [targets]: a folder alone is gone into, otherwise each file is opened on this computer. */
    fun openAll(targets: List<DeviceFile>) {
        targets.singleOrNull()?.takeIf { it.kind == DeviceFileKind.Folder }?.let { return go(it.path) }
        val opened = targets.filter { it.kind == DeviceFileKind.File }.ifEmpty { return }
        run(FileAction.Open, opened) { report ->
            for (file in opened) openLocally(copyToComputer(file, report))
        }
    }

    /** Whether files are being dragged out of the page, so dropping them back on it doesn't upload copies of them. */
    var draggingOut by mutableStateOf(false)

    /**
     * Copies [targets] to this computer for dragging into another app, folders with everything in them, each in a folder
     * of its own so it keeps its name.
     */
    fun copiesForDrag(targets: List<DeviceFile>): List<File> = targets.map { file ->
        val holder = Files.createTempDirectory("robomori-drag").toFile()
        File(holder, file.name).also {
            if (file.kind == DeviceFileKind.Folder) service.saveFolder(serial, rootFor(file.path), file, holder)
            else service.save(serial, rootFor(file.path), file, it)
        }
    }

    // A folder of its own keeps the file's name, so the app that opens it shows that name.
    private fun copyToComputer(file: DeviceFile, report: Report = { _, _, _ -> }): File =
        File(Files.createTempDirectory("robomori-open").toFile(), file.name).also {
            service.save(serial, rootFor(file.path), file, it) { done, total -> report(file.name, done, total) }
            openedCopies[it] = OpenedCopy(file, it, it.lastModified())
        }

    /** The files opened on this computer, watched so that edits saved to them can be uploaded back. */
    private val openedCopies = ConcurrentHashMap<File, OpenedCopy>()

    /** The opened files saved since they were opened or last uploaded, newest first. */
    var edits by mutableStateOf<List<LocalEdit>>(emptyList()); private set

    /** Looks for opened files saved since, which the page offers to upload. */
    fun checkEdits() {
        val found = openedCopies.values.mapNotNull { copy ->
            val modified = copy.local.lastModified()
            if (copy.local.isFile && modified > copy.seen) LocalEdit(copy.file, copy.local, copy.local.length(), Instant.ofEpochMilli(modified)) else null
        }.sortedByDescending { it.modified }
        if (found != edits) edits = found
    }

    /**
     * Copies [edit] over the file it was opened from on the device. It stops being offered at once; if the upload fails,
     * it's offered again.
     */
    fun uploadEdit(edit: LocalEdit) {
        val copy = openedCopies[edit.local] ?: return
        if (action is FileActionState.Running) return
        val folder = edit.file.path.substringBeforeLast('/').ifEmpty { root.path }
        val before = copy.seen
        copy.seen = edit.local.lastModified()
        edits = edits - edit
        run(FileAction.Upload, listOf(edit.file)) {
            try {
                service.upload(serial, rootFor(edit.file.path), edit.local, folder)
                indexMedia(listOf(edit.file.path))
            } catch (failure: Exception) {
                copy.seen = before
                throw failure
            }
        }
    }

    /** Leaves the device's file as it is, and stops offering [edit]. */
    fun discardEdit(edit: LocalEdit) {
        openedCopies.remove(edit.local)
        edits = edits - edit
    }

    /** Opens the folder above, with the one just left selected, as Finder does, so the keys carry on from it. */
    fun up() {
        if (atRoot) return
        val left = folder
        go(folder.substringBeforeLast('/').ifEmpty { root.path })
        if (left.substringAfterLast('/').isNotEmpty()) {
            selection = setOf(left)
            anchor = left
            cursor = left
        }
    }

    /** The folders from the root down to the open one, to jump back to any of them. */
    val crumbs get() = folderCrumbs(root, folder)

    fun goTo(crumb: FolderCrumb) { if (crumb.path != folder) go(crumb.path) }

    /** Selects [file] alone, or adds or removes it with [toggle], or the files from the last one clicked with [extend]. */
    fun select(file: DeviceFile, toggle: Boolean = false, extend: Boolean = false) {
        val order = shownFiles.map { it.path }
        val from = order.indexOf(anchor)
        val to = order.indexOf(file.path)
        selection = when {
            extend && from >= 0 && to >= 0 -> {
                val range = order.subList(minOf(from, to), maxOf(from, to) + 1).toSet()
                if (toggle) selection + range else range
            }
            toggle -> if (file.path in selection) selection - file.path else selection + file.path
            else -> setOf(file.path)
        }
        if (!extend || from < 0) anchor = file.path
        cursor = file.path
    }

    /** The files a context menu opened on [file] acts on: the selection when [file] is part of it, otherwise [file] alone. */
    fun contextTargets(file: DeviceFile): List<DeviceFile> {
        if (file.path !in selection) select(file)
        return shownFiles.filter { it.path in selection }
    }

    /** The open folder's files as listed: in the chosen order, leaving out hidden ones unless they're shown. */
    /**
     * What's searched for by name, as Finder's search does: across the whole of where the open folder is, its storage or
     * its app's own folder, not just the folder. Empty shows the open folder's files.
     */
    var query by mutableStateOf(""); private set
    /** The files found for [query], filling in as the device finds them; null while not searching. */
    var found by mutableStateOf<FolderSnapshot?>(null); private set
    private var searching: Job? = null
    val isSearching get() = query.isNotBlank()

    /** Whether the list starts with the `..` row: in a folder below the root, and not while showing what a search found. */
    val showsParentRow get() = !atRoot && !isSearching

    /** The files listed: the open folder's, or what a search found, in the chosen order, hidden ones only when shown. */
    val shownFiles get() = (if (isSearching) found else snapshot).let { it as? FolderSnapshot.Loaded }?.files.orEmpty()
        .filter { showHidden || !hidden(it) }.sortedBy(sort)

    /** Where a search looks: the open app's own folder, the shared storage, or else the whole device. */
    val searchFolder: String get() {
        appPackageOf(folder)?.let { return childPath(AppsFolder, it) }
        return SharedStorage.firstOrNull { folder == it || folder.startsWith("$it/") } ?: root.path
    }

    // Found by a search, a file is hidden when it's in a hidden folder too, as in Finder.
    private fun hidden(file: DeviceFile) =
        if (!isSearching) file.isHidden else file.path.removePrefix(searchFolder).split('/').any { it.startsWith('.') }

    fun search(text: String) {
        if (text == query) return
        query = text
        searching?.cancel()
        selection = emptySet()
        anchor = null
        cursor = null
        if (text.isBlank()) { found = null; return }
        found = FolderSnapshot.Loading
        val where = searchFolder
        val term = text.trim()
        searching = scope.launch {
            // Typing on waits a moment, so a search runs for the name being typed rather than each letter of it.
            delay(250)
            found = try {
                FolderSnapshot.Loaded(runInterruptible(Dispatchers.IO) {
                    service.search(serial, rootFor(where), where, term) { part -> if (query.trim() == term) found = FolderSnapshot.Loaded(part) }
                })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                FolderSnapshot.Failed(failure)
            }
        }
    }

    /** Shows or hides files whose names start with a dot. Hidden files can't stay selected. */
    fun toggleHidden() {
        showHidden = !showHidden
        if (!showHidden) selection = selection.filterTo(mutableSetOf()) { !it.substringAfterLast('/').startsWith('.') || it == ParentRowName }
    }

    fun thumbnail(file: DeviceFile): ImageBitmap? = thumbnails[file]

    /** Reads a preview of [file], an image, unless there is one already. */
    suspend fun loadThumbnail(file: DeviceFile) {
        if (!(file.isImage || file.isVideo) || file in thumbnails) return
        // The device makes previews of what the shell can read, the rows on screen together, as starting it takes time.
        if (rootFor(file.path) is FileRoot.Storage) {
            pendingThumbnails += file
            if (thumbnailBatch?.isActive != true) thumbnailBatch = scope.launch { loadPendingThumbnails() }
            return
        }
        // Inside an app's own folder, small images are copied over whole instead.
        if (!file.isImage || file.size > ThumbnailMaxBytes) return
        val root = rootFor(file.path)
        val preview = thumbnailLoads.withPermit {
            runCatching { runInterruptible(Dispatchers.IO) { thumbnailOf(service.read(serial, root, file), ThumbnailPixels) } }.getOrNull()
        }
        thumbnails[file] = preview
    }

    private val pendingThumbnails = LinkedHashSet<DeviceFile>()
    private var thumbnailBatch: Job? = null

    private suspend fun loadPendingThumbnails() {
        while (pendingThumbnails.isNotEmpty()) {
            delay(50) // Lets the other rows on screen ask too.
            val batch = pendingThumbnails.take(24)
            pendingThumbnails.removeAll(batch.toSet())
            val previews = runCatching {
                runInterruptible(Dispatchers.IO) { service.thumbnails(serial, batch.map { it.path }, ThumbnailPixels) }
            }.getOrNull()
            batch.forEachIndexed { index, file -> thumbnails[file] = previews?.getOrNull(index)?.let { thumbnailOf(it, ThumbnailPixels) } }
        }
    }

    /** Takes [widths] for the columns after the name, as dragging the line between two of them sets them. */
    fun resizeColumns(shares: Map<FileColumn, Float>) {
        columnShares = shares
    }

    /** Orders by [column], or the other way when it already is. */
    fun sortBy(column: FileColumn) {
        sort = if (sort.column == column) sort.copy(ascending = !sort.ascending) else FileSort(column)
    }

    fun refresh() { load(keepShowing = true) }

    /**
     * Reads the open folder again when it changed on the device since it was read, so changes apps make show up. Only a
     * fingerprint of it is read to tell, and it's left alone while it's being read, changed by an action or renamed in,
     * or while a search shows other files, so the list doesn't change under what's being done with it.
     */
    fun poll() {
        if (loading?.isActive == true || checking?.isActive == true || action is FileActionState.Running || renaming != null ||
            renameWhenListed != null || isSearching) return
        val folder = folder
        checking = scope.launch {
            val stamp = runCatching { runInterruptible(Dispatchers.IO) { stampOf(folder) } }.getOrNull()
            if (folder == this@DeviceFilesState.folder && loading?.isActive != true && (stamp == null || stamp != stamps[folder])) refresh()
        }
    }

    private var checking: Job? = null

    /** The fingerprint of each folder's listing as it was read, to tell when it has changed since. */
    private val stamps = ConcurrentHashMap<String, String>()

    /**
     * [folder]'s fingerprint, or null when there's none to tell changes by, and it's read again each time. Finds out
     * first whether the device allows root, which decides how the folder is reached.
     */
    private fun stampOf(folder: String): String? {
        if (rooted == null) rooted = service.hasRoot(serial)
        return when (folder) {
            "/data", "/data/local" -> folder
            AppsFolder -> if (rooted == true) service.folderStamp(serial, FileRoot.Superuser, folder) else apps.joinToString { it.packageName }
            else -> service.folderStamp(serial, rootFor(folder), folder)
        }
    }

    // The folders visited before and after the open one, for going back and forward.
    private val backStack = ArrayDeque(startPlace?.back.orEmpty())
    private val forwardStack = ArrayDeque(startPlace?.forward.orEmpty())
    /** Where each folder was scrolled to, by folder. */
    private val scrolls = mutableStateMapOf<String, FilesScroll>().apply { putAll(startPlace?.scrolls.orEmpty()) }

    /** Where [folder] was last scrolled to. */
    fun scrollOf(folder: String): FilesScroll = scrolls[folder] ?: FilesScroll(0, 0)

    fun rememberScroll(folder: String, scroll: FilesScroll) {
        if (scrolls[folder] == scroll) return
        scrolls.remove(folder)
        scrolls[folder] = scroll
        while (scrolls.size > RememberedScrolls) scrolls.remove(scrolls.keys.first())
    }

    /** Where the page is, to pick up from after the device restarts or Robomori quits; changes as browsing goes on. */
    val place: FilesPlace get() = FilesPlace(folder, backStack.toList(), forwardStack.toList(), scrolls.toMap())

    /** Returns to the folder open before this one. */
    fun back() {
        val previous = backStack.removeLastOrNull() ?: return
        forwardStack.addLast(folder)
        show(previous)
    }

    /** Returns to the folder left with [back]. */
    fun forward() {
        val next = forwardStack.removeLastOrNull() ?: return
        backStack.addLast(folder)
        show(next)
    }

    private fun go(folder: String) {
        if (folder == this.folder) return
        backStack.addLast(this.folder)
        forwardStack.clear()
        show(folder)
    }

    private fun show(folder: String) {
        // Previews still waiting are for the folder left; the new one's rows ask for theirs.
        pendingThumbnails.clear()
        // Opening a folder, found or not, leaves the search for its files.
        searching?.cancel()
        query = ""
        found = null
        closePreview()
        this.folder = folder
        selection = emptySet()
        anchor = null
        cursor = null
        // A folder opened before shows its files at once and is read again underneath; another starts empty. Either way
        // the table never shows one folder's files under another's name.
        snapshot = listings[folder]?.let(FolderSnapshot::Loaded) ?: FolderSnapshot.Loading
        load(keepShowing = true)
    }

    /** The files of the folders read lately, by folder, to show at once when one is opened again. */
    private val listings = object : LinkedHashMap<String, List<DeviceFile>>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<DeviceFile>>?) = size > RememberedFolders
    }

    /**
     * Selects the row [by] rows from the last one clicked or moved to, or the first or last row when none is. The `..` row
     * comes first below the root. [extend] keeps the files from the anchor selected, like Shift with the arrow keys.
     * Returns the row moved to.
     */
    fun moveSelection(by: Int, extend: Boolean = false): Int? {
        val rows = (if (showsParentRow) listOf(ParentRowName) else emptyList()) + shownFiles.map { it.path }
        if (rows.isEmpty()) return null
        val current = rows.indexOf(cursor)
        val next = if (current < 0) (if (by > 0) 0 else rows.lastIndex) else (current + by).coerceIn(0, rows.lastIndex)
        val name = rows[next]
        val from = rows.indexOf(anchor)
        selection = when {
            name == ParentRowName -> setOf(ParentRowName)
            extend && from >= 0 -> rows.subList(minOf(from, next), maxOf(from, next) + 1).filterTo(mutableSetOf()) { it != ParentRowName }
            else -> setOf(name)
        }
        if (!extend || from < 0 || name == ParentRowName) anchor = name
        cursor = name
        return next
    }

    /** Selects the `..` row, as clicking it does. */
    fun selectParent() {
        selection = setOf(ParentRowName)
        anchor = ParentRowName
        cursor = ParentRowName
    }

    /** Copies [file] to this computer and opens it in [app], or with none, in the app picked in Windows' Open With dialog. */
    fun openWith(file: DeviceFile, app: File?) {
        if (file.kind != DeviceFileKind.File) return
        run(FileAction.Open, listOf(file)) { openLocallyWith(copyToComputer(file), app) }
    }

    fun requestRename(file: DeviceFile) { if (action !is FileActionState.Running) renaming = file }

    fun dismissRename() { renaming = null }

    /** The files and folders copied, to paste into a folder on this device. */
    var copied by mutableStateOf<List<DeviceFile>>(emptyList()); private set

    /** Copies of [copied] on this computer, for other apps to paste; pasting them back here copies on the device instead. */
    var copiedToComputer by mutableStateOf<List<File>>(emptyList()); private set

    /**
     * Copies [targets] to paste into another folder on the device, and copies them to this computer too, handing the
     * copies to [onCopiedToComputer] for the clipboard, so Finder and other apps can paste them.
     */
    fun copy(targets: List<DeviceFile>, onCopiedToComputer: (List<File>) -> Unit = {}) {
        if (targets.isEmpty()) return
        copied = targets
        copiedToComputer = emptyList()
        // In the background, apart from the page's actions, so pasting on the device needn't wait for it.
        copyingToComputer?.cancel()
        copyingToComputer = scope.launch {
            val local = runCatching { runInterruptible(Dispatchers.IO) { copiesForDrag(targets) } }.getOrNull() ?: return@launch
            copiedToComputer = local
            onCopiedToComputer(local)
        }
    }

    private var copyingToComputer: Job? = null

    /**
     * Copies what was copied into the open folder, each under a name of its own, as Finder does: "notes copy.txt" when
     * "notes.txt" is there already. An app's own files and the device's other files can't be pasted into each other.
     */
    fun paste(moving: Boolean = cut) {
        val sources = copied.ifEmpty { return }
        if (moving) {
            moveInto(sources, folder)
            copied = emptyList()
            cut = false
            return
        }
        val folder = folder
        val root = rootFor(folder)
        val here = sources.all { parentOf(it.path) == folder }
        withConflicts(sources.map { it.name }, folder, sources.filter { it.kind == DeviceFileKind.Folder }.mapTo(mutableSetOf()) { it.name },
            keepBoth = here) { placements ->
            run(FileAction.Paste, sources) {
                val made = mutableListOf<String>()
                val pasted = mutableListOf<String>()
                for ((source, placement) in sources.zip(placements)) {
                    placement ?: continue
                    check(rootFor(source.path) == root) { "Robomori can only paste within one app's own files, or within the device's other files." }
                    service.copy(serial, root, source, folder, placement.name, replace = placement.replaced)
                    if (!placement.replaced) made += childPath(folder, placement.name)
                    pasted += childPath(folder, placement.name)
                }
                remember(Undo(root, removals = made.map { it to true }))
                indexMedia(pasted)
            }
        }
    }

    /** Whether the files copied move when pasted, as after Cut, or ⌘⌥V on macOS. */
    var cut by mutableStateOf(false); private set

    /** Marks [targets] to move to the folder they're pasted in, as Cut does. */
    fun cut(targets: List<DeviceFile>) {
        if (targets.isEmpty()) return
        copied = targets
        cut = true
    }

    /**
     * Moves [targets] into [destination] on the device, such as when they're dropped on a folder. One can't go into itself
     * or a folder inside it, and ones there already stay put. Names it has already are asked about first.
     */
    fun moveInto(targets: List<DeviceFile>, destination: String) {
        val moving = targets.filter { parentOf(it.path) != destination && it.path != destination && !destination.startsWith(it.path + "/") }
            .ifEmpty { return }
        val root = rootFor(destination)
        withConflicts(moving.map { it.name }, destination, moving.filter { it.kind == DeviceFileKind.Folder }.mapTo(mutableSetOf()) { it.name }) { placements ->
            run(FileAction.Move, moving) {
                val back = mutableListOf<Pair<String, String>>()
                val moved = mutableListOf<String>()
                for ((file, placement) in moving.zip(placements)) {
                    placement ?: continue
                    check(rootFor(file.path) == root) { "Robomori can only move within one app's own files, or within the device's other files." }
                    val to = childPath(destination, placement.name)
                    service.move(serial, root, file.path, to, replace = placement.replaced)
                    if (!placement.replaced) back += to to file.path
                    moved += listOf(to, file.path)
                }
                remember(Undo(root, moves = back.asReversed()))
                // Where they left is scanned too, so the index lets go of them there.
                indexMedia(moved)
            }
        }
    }

    /** The files being dragged out of the page, which dropping on one of its folders moves there. */
    var dragged by mutableStateOf<List<DeviceFile>>(emptyList())

    /** What undoing the last change puts back: [moves] from where things are now to where they were, then [removals]. */
    private data class Undo(val root: FileRoot, val moves: List<Pair<String, String>> = emptyList(),
                            val removals: List<Pair<String, Boolean>> = emptyList())

    private val undos = ArrayDeque<Undo>()

    private fun remember(undo: Undo) {
        if (undo.moves.isEmpty() && undo.removals.isEmpty()) return
        synchronized(undos) {
            undos.addLast(undo)
            if (undos.size > MaxUndos) undos.removeFirst()
        }
    }

    /**
     * Undoes the last rename, move, new folder, paste or upload, as ⌘Z does in Finder. A new folder is only removed while
     * it's still empty, so nothing put in it since is lost. Deleting can't be undone.
     */
    fun undo() {
        if (action is FileActionState.Running) return
        val undo = synchronized(undos) { undos.removeLastOrNull() } ?: return
        run(FileAction.Undo, "the last change") {
            for ((from, to) in undo.moves) service.move(serial, undo.root, from, to)
            for ((path, contents) in undo.removals) service.remove(serial, undo.root, path, contents)
        }
    }

    /** Selects the files at [paths], such as the rows a selection box crosses. */
    fun selectPaths(paths: List<String>) {
        selection = paths.toSet()
        anchor = paths.firstOrNull()
        cursor = paths.lastOrNull()
    }

    /** Selects every file listed, as ⌘A does. */
    fun selectAll() {
        val paths = shownFiles.map { it.path }.ifEmpty { return }
        selection = paths.toSet()
        anchor = paths.first()
        cursor = paths.last()
    }

    /** Whether the selected files are being previewed, as Space shows them in Finder. */
    var previewing by mutableStateOf(false); private set
    private var following: Job? = null
    /** The copies made for previewing, by the file they're of, so going back to one shows it at once. */
    private val previewCopies = ConcurrentHashMap<String, File>()

    /** Previews the selected files, or stops, as Space does in Finder. */
    fun toggleQuickLook() = if (previewing) closePreview() else openPreview()

    /**
     * Previews the selected files, and keeps previewing whatever's selected after, as Finder's Quick Look follows the
     * selection. A file still being copied when the selection moves on is left for the next one.
     */
    private fun openPreview() {
        if (shownFiles.none { it.path in selection }) return
        previewing = true
        // Each opening is a session of its own, so the panel saying an earlier one closed, which can arrive after a quick
        // reopen, leaves this one be.
        val session = ++previewSession
        following?.cancel()
        following = scope.launch {
            var opening = true
            snapshotFlow { shownFiles.filter { it.path in selection } }.distinctUntilChanged().collectLatest { selected ->
                if (selected.isEmpty()) return@collectLatest
                val copies = runCatching { runInterruptible(Dispatchers.IO) { selected.map(::previewCopy) } }.getOrNull()
                    ?: return@collectLatest
                previewer.show(copies, opening, ::onPreviewKey) { if (previewing && session == previewSession) stopFollowing() }
                opening = false
                // The files either side are copied while this one shows, so the arrow keys move on at once.
                val at = shownFiles.indexOfFirst { it.name == selected.last().name }
                runCatching { runInterruptible(Dispatchers.IO) { listOfNotNull(shownFiles.getOrNull(at + 1), shownFiles.getOrNull(at - 1)).forEach(::previewCopy) } }
            }
        }
    }

    private var previewSession = 0

    /** The keys pressed in the preview, which move through the list behind it as they do in Finder. */
    private fun onPreviewKey(key: PreviewKey) {
        when (key) {
            PreviewKey.Up -> moveSelection(-1)
            PreviewKey.Down -> moveSelection(1)
            PreviewKey.Close -> closePreview()
        }
    }

    fun closePreview() {
        if (!previewing) return
        previewSession++
        stopFollowing()
        previewer.close()
    }

    private fun stopFollowing() {
        previewing = false
        following?.cancel()
        following = null
    }

    /** [file] on this computer, to preview: a copy, or for a folder, an empty one of its name, which shows as a folder. */
    private fun previewCopy(file: DeviceFile): File = previewCopies.getOrPut("${file.path}|${file.size}|${file.modified}") {
        val holder = Files.createTempDirectory("robomori-preview").toFile()
        File(holder, file.name).also { copy ->
            if (file.kind == DeviceFileKind.Folder) copy.mkdir() else service.save(serial, rootFor(file.path), file, copy)
        }
    }

    /** The text file being edited on the page, in place of the list, if any. */
    var editor by mutableStateOf<TextFileEditor?>(null); private set

    /**
     * Whether [file] may be edited as text: a file small enough, and not one of the kinds that are never text. Whether
     * it really is text shows once it's read.
     */
    fun canEdit(file: DeviceFile) =
        file.kind == DeviceFileKind.File && file.size <= EditableTextBytes && !file.isImage && !file.isVideo && !file.isDatabase

    /** Opens [file] in the page's text editor. */
    fun edit(file: DeviceFile) {
        if (!canEdit(file) || editor != null) return
        closePreview()
        val root = editRootFor(file.path)
        editor = TextFileEditor(file, scope,
            read = { service.readText(serial, root, file) },
            write = { text, opened, overwrite -> service.writeText(serial, root, file.path, text, opened, overwrite) },
            onClose = { editor = null },
            onSaved = { indexMedia(listOf(file.path)); refresh() },
        ).also { it.load() }
    }

    /**
     * How a file is edited: as its app inside a debuggable app's own folder, as the shell in shared storage, and as root
     * elsewhere on a device that allows it, such as for system files the shell can only read.
     */
    private fun editRootFor(path: String): FileRoot =
        if (rooted == true && appPackageOf(path) == null && !inSharedStorage(path)) FileRoot.Superuser else rootFor(path)

    /** A folder just made, to rename in its row once the folder's files are read again. */
    private var renameWhenListed: String? = null

    /**
     * Makes a folder in the open folder at once, named "untitled folder" or the first such name that's free, and opens its
     * name for editing in its row, as Finder does.
     */
    fun newFolder() {
        val listed = (snapshot as? FolderSnapshot.Loaded)?.files ?: return
        if (action is FileActionState.Running) return
        val name = copyName(NewFolderName, folder = true, taken = listed.mapTo(mutableSetOf()) { it.name }).let {
            // Finder numbers new folders "untitled folder 2", not "untitled folder copy".
            if (it == NewFolderName) it else generateSequence(2) { n -> n + 1 }.map { n -> "$NewFolderName $n" }.first { n -> listed.none { f -> f.name == n } }
        }
        val folder = folder
        renameWhenListed = name
        run(FileAction.CreateFolder, name) {
            service.createFolder(serial, rootFor(folder), folder, name)
            remember(Undo(rootFor(folder), removals = listOf(childPath(folder, name) to false)))
        }
        val made = childPath(folder, name)
        selection = setOf(made)
        anchor = made
        cursor = made
    }

    /** Renames the file waiting for a name, keeping it selected under its new name. */
    fun confirmRename(name: String) {
        val file = renaming ?: return
        val trimmed = name.trim()
        if (!isValidFileName(trimmed)) return
        renaming = null
        if (trimmed == file.name) return
        run(FileAction.Rename, listOf(file)) {
            service.rename(serial, rootFor(file.path), file, trimmed)
            remember(Undo(rootFor(file.path), moves = listOf(childPath(parentOf(file.path), trimmed) to file.path)))
        }
        val renamed = childPath(parentOf(file.path), trimmed)
        selection = setOf(renamed)
        anchor = renamed
        cursor = renamed
    }

    /** Opens the selected files, as double-clicking does. */
    /** Asks to delete the selected files, as the platform's delete key does in a file browser. */
    fun deleteSelection() = requestDelete(shownFiles.filter { it.path in selection })

    fun openSelection() {
        if (ParentRowName in selection) return up()
        openAll(shownFiles.filter { it.path in selection }.ifEmpty { return })
    }

    private fun load(keepShowing: Boolean) {
        loading?.cancel()
        checking?.cancel()
        val folder = folder
        if (!keepShowing) snapshot = FolderSnapshot.Loading
        loading = scope.launch {
            val read = try {
                // A folder shown for the first time fills in as it's read; one shown before keeps its files until then.
                val progressive = snapshot == FolderSnapshot.Loading
                FolderSnapshot.Loaded(runInterruptible(Dispatchers.IO) {
                    // Fingerprinted before it's read, so a change while it's read shows as one the next time it's checked.
                    stampOf(folder).let { if (it != null) stamps[folder] = it else stamps.remove(folder) }
                    listFolder(folder) { part -> if (progressive && this@DeviceFilesState.folder == folder) snapshot = FolderSnapshot.Loaded(part) }
                })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                FolderSnapshot.Failed(failure)
            }
            if (read is FolderSnapshot.Loaded) listings[folder] = read.files
            snapshot = read
            if (read is FolderSnapshot.Loaded) renameWhenListed?.let { name ->
                read.files.firstOrNull { it.name == name }?.let { renaming = it; renameWhenListed = null }
            }
        }
    }

    fun save(file: DeviceFile, destination: File) = run(FileAction.Save, listOf(file)) { report ->
        service.save(serial, rootFor(file.path), file, destination) { done, total -> report(file.name, done, total) }
    }

    /** Copies [targets]' files into [directory] on this computer, under their own names. */
    fun saveAll(targets: List<DeviceFile>, directory: File) {
        val saved = targets.filter { it.kind == DeviceFileKind.File }.ifEmpty { return }
        run(FileAction.Save, saved) { report ->
            for (file in saved) service.save(serial, rootFor(file.path), file, File(directory, file.name)) { done, total -> report(file.name, done, total) }
        }
    }

    /**
     * Copies [sources] from this computer into [destination], the open folder unless dropped on another, leaving out
     * folders. Names it has already are asked about first.
     */
    fun upload(sources: List<File>, destination: String = folder) {
        val uploads = sources.filter(File::isFile).ifEmpty { return }
        val root = rootFor(destination)
        withConflicts(uploads.map { it.name }, destination) { names ->
            val chosen = uploads.zip(names).filter { it.second != null }.ifEmpty { return@withConflicts }
            run(FileAction.Upload, chosen.singleOrNull()?.first?.name ?: "${chosen.size} files") { report ->
                val made = mutableListOf<String>()
                for ((source, name) in chosen) {
                    service.upload(serial, root, source, destination, name!!.name) { done, total -> report(source.name, done, total) }
                    if (!name.replaced) made += childPath(destination, name.name)
                }
                remember(Undo(root, removals = made.map { it to true }))
                indexMedia(chosen.map { (_, name) -> childPath(destination, name!!.name) })
            }
        }
    }

    /**
     * Has Android add the files at [paths] that are in shared storage to its media index, in the background and quietly:
     * Photos showing them is a nicety, never worth an error or a wait.
     */
    private fun indexMedia(paths: List<String>) {
        val shared = paths.filter(::inSharedStorage).ifEmpty { return }
        scope.launch { runCatching { runInterruptible(Dispatchers.IO) { service.scanMedia(serial, shared) } } }
    }

    /** Where a file goes in a folder with its name taken, or not: its [name], and whether it [replaced] what was there. */
    private data class Placement(val name: String, val replaced: Boolean)

    /** A conflict waiting for its choice, and what to do once it's chosen. */
    var conflict by mutableStateOf<NameConflict?>(null); private set
    private var onConflictChosen: ((ConflictChoice) -> Unit)? = null

    /** Goes on with the files waiting on [conflict], as [choice] says, or with none, leaves them all. */
    fun resolveConflict(choice: ConflictChoice?) {
        val proceed = onConflictChosen
        conflict = null
        onConflictChosen = null
        if (choice != null) proceed?.invoke(choice)
    }

    /**
     * Places [names] in [destination]: under their own names where they're free, and for those it has already, asks once
     * whether to replace them, keep both under new names, or skip them. [proceed] gets each name's [Placement], or null
     * for one skipped. Pasting files into the folder they're in keeps both, as Finder's Duplicate does.
     */
    private fun withConflicts(names: List<String>, destination: String, folders: Set<String> = emptySet(), keepBoth: Boolean = false,
                              proceed: (List<Placement?>) -> Unit) {
        if (action is FileActionState.Running) return
        val taken = (if (destination == folder) (snapshot as? FolderSnapshot.Loaded)?.files else listings[destination])
            .orEmpty().mapTo(mutableSetOf()) { it.name }
        fun place(choice: ConflictChoice): List<Placement?> {
            val used = taken.toMutableSet()
            return names.map { name ->
                when {
                    name !in taken -> Placement(name, false).also { used += name }
                    choice == ConflictChoice.Replace -> Placement(name, true)
                    choice == ConflictChoice.KeepBoth -> Placement(copyName(name, name in folders, used), false).also { used += it.name }
                    else -> null
                }
            }
        }
        val clashing = names.filter { it in taken }
        when {
            clashing.isEmpty() -> proceed(place(ConflictChoice.Skip))
            keepBoth -> proceed(place(ConflictChoice.KeepBoth))
            else -> {
                conflict = NameConflict(destination, clashing)
                onConflictChosen = { choice -> proceed(place(choice)) }
            }
        }
    }

    fun requestDelete(targets: List<DeviceFile>) { if (action !is FileActionState.Running && targets.isNotEmpty()) confirmingDelete = targets }

    fun confirmDelete() {
        val targets = confirmingDelete ?: return
        confirmingDelete = null
        run(FileAction.Delete, targets) { for (file in targets) service.delete(serial, rootFor(file.path), file) }
    }

    fun dismissDelete() { confirmingDelete = null }

    fun dismissFailure() { if (action is FileActionState.Failed) action = FileActionState.Idle }

    private fun run(kind: FileAction, targets: List<DeviceFile>, work: (Report) -> Unit) = run(kind, targets.title, targets, work)

    private fun run(kind: FileAction, name: String, targets: List<DeviceFile> = emptyList(), work: (Report) -> Unit) {
        if (action is FileActionState.Running) return
        val started = FileActionState.Running(kind, name, targets.mapTo(mutableSetOf()) { it.name })
        action = started
        var reported = 0L
        // Tells how far a copy has got, a few times a second at most.
        val report: Report = { file, done, total ->
            val now = System.nanoTime()
            if (now - reported > 100_000_000 || done == total) {
                reported = now
                val current = action
                if (current is FileActionState.Running && current.action == kind) action = current.copy(progress = TransferProgress(file, done, total))
            }
        }
        running = scope.launch {
            action = try {
                runInterruptible(Dispatchers.IO) { work(report) }
                FileActionState.Idle
            } catch (cancelled: CancellationException) {
                action = FileActionState.Idle
                refresh()
                throw cancelled
            } catch (failure: Exception) {
                FileActionState.Failed(kind, name, failure)
            }
            if (kind in ChangesFolder) refresh()
        }
    }

    private var running: Job? = null

    /** Stops the action under way, such as a long copy; what it already did stays done. */
    fun cancelAction() {
        if (action is FileActionState.Running) running?.cancel()
    }
}

/**
 * When a file changed, as Finder shows it: "Today, 13:48", "Yesterday, 13:48", or the short date and time, such as
 * "11/17/26, 21:19", in [locale], with a 24-hour clock when [hours24].
 */
internal fun formatModified(time: java.time.Instant, now: java.time.Instant, zone: java.time.ZoneId, locale: java.util.Locale,
                            hours24: Boolean): String {
    val at = time.atZone(zone)
    val today = now.atZone(zone).toLocalDate()
    val clock = java.time.format.DateTimeFormatter.ofPattern(if (hours24) "HH:mm" else "h:mm a", locale).format(at)
    val day = when (at.toLocalDate()) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.SHORT).withLocale(locale).format(at)
    }
    return "$day, $clock"
}

/**
 * Whether times show with a 24-hour clock: the Mac's own setting when it forces one, otherwise the locale's habit.
 * Read once, as asking macOS starts a process.
 */
internal val uses24HourClock: Boolean by lazy {
    val forced = if (System.getProperty("os.name").orEmpty().startsWith("Mac")) runCatching {
        ProcessBuilder("defaults", "read", "-g", "AppleICUForce24HourTime").redirectErrorStream(true).start()
            .inputStream.bufferedReader().readText().trim()
    }.getOrNull() else null
    when (forced) {
        "1" -> true
        "0" -> false
        else -> java.time.format.DateTimeFormatterBuilder.getLocalizedDateTimePattern(null, java.time.format.FormatStyle.SHORT,
            java.time.chrono.IsoChronology.INSTANCE, java.util.Locale.getDefault()).contains('H')
    }
}

/**
 * A name for a copy of [name] that none of [taken] has, as Finder names copies: "notes.txt" itself when it's free, then
 * "notes copy.txt", "notes copy 2.txt", and so on. A folder's name has no extension to keep.
 */
internal fun copyName(name: String, folder: Boolean, taken: Set<String>): String {
    if (name !in taken) return name
    val dot = if (folder) -1 else name.lastIndexOf('.').takeIf { it > 0 } ?: -1
    val (base, extension) = if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
    return generateSequence(1) { it + 1 }.map { if (it == 1) "$base copy$extension" else "$base copy $it$extension" }.first { it !in taken }
}
