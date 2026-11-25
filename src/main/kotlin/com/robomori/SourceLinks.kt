package com.robomori

import org.w3c.dom.Element
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import javax.xml.parsers.DocumentBuilderFactory

/**
 * A frame of a Java or Kotlin stack trace that names its source, such as `at com.example.app.Main.onCreate(Main.kt:12)`,
 * found at [range] in a log message.
 */
internal data class StackFrame(val className: String, val file: String, val line: Int, val range: IntRange) {
    /** The folders the file sits in by its package, such as `com/example/app`. */
    val packagePath get() = className.substringBeforeLast('.', "").replace('.', '/')
    /** Where the frame points, as IDEs take it in "Go to file". */
    val location get() = "$file:$line"
}

private val frameLine = Regex("""at ([\w$]+(?:\.[\w$<>-]+)+)\(([\w$-]+\.(?:kt|java)):(\d+)\)""")

/** The stack frame [message] is, when it names a Kotlin or Java file and a line; frames without them can't be opened. */
internal fun stackFrame(message: String): StackFrame? {
    if (!message.trimStart().startsWith("at ")) return null
    val match = frameLine.find(message) ?: return null
    val (method, file, line) = match.destructured
    return StackFrame(method.substringBeforeLast('.'), file, line.toIntOrNull() ?: return null,
        match.groups[1]!!.range.first..match.range.last)
}

internal enum class SourceOpenResult { Opened, NoIdeRunning, NotFound }

/** Opens a stack frame's source in the user's IDE. */
internal interface SourceOpener {
    fun open(frame: StackFrame): SourceOpenResult
}

/** Android Studio or IntelliJ IDEA, running, with the [launcher] that hands it files to open and its [configs] folders. */
internal data class RunningIde(val launcher: File, val configs: List<File>)

/**
 * Opens frames in the Android Studio or IntelliJ IDEA that's running, in the file of that name and package within a
 * project it has open. IDEs no longer answer requests to open a file over HTTP, and their `jetbrains://` links need the
 * project's name, so the open projects are read from the IDE's own list, and the file handed to its launcher with
 * `--line`, which passes it on to the running IDE.
 */
internal class IdeSourceOpener(
    private val platform: HostPlatform = HostPlatform(),
    private val findIdes: () -> List<RunningIde> = { runningIdes(platform) },
    private val launch: (List<String>) -> Unit = { ProcessBuilder(it).redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD).start() },
) : SourceOpener {
    override fun open(frame: StackFrame): SourceOpenResult {
        val ides = findIdes()
        if (ides.isEmpty()) return SourceOpenResult.NoIdeRunning
        for (ide in ides) {
            val projects = ide.configs.flatMap { openProjects(File(it, "options/recentProjects.xml"), platform.home) }
            val file = projects.firstNotNullOfOrNull { findSource(it, frame) } ?: continue
            launch(listOf(ide.launcher.path, "--line", frame.line.toString(), file.path))
            return SourceOpenResult.Opened
        }
        return SourceOpenResult.NotFound
    }
}

/**
 * The projects to look in, by an IDE's `recentProjects.xml`: those marked open, then the others used last, most recent
 * first, since the IDE saves the list only now and then, so the marks can be out of date.
 */
internal fun openProjects(recentProjects: File, home: File): List<File> {
    if (!recentProjects.isFile) return emptyList()
    val document = runCatching { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(recentProjects) }.getOrNull()
        ?: return emptyList()
    class Project(val dir: File, val open: Boolean, val activated: Long)
    val entries = document.getElementsByTagName("entry")
    val projects = (0 until entries.length).mapNotNull { index ->
        val entry = entries.item(index) as Element
        val info = entry.getElementsByTagName("RecentProjectMetaInfo").item(0) as? Element ?: return@mapNotNull null
        val options = info.getElementsByTagName("option")
        val activated = (0 until options.length).map { options.item(it) as Element }
            .firstOrNull { it.getAttribute("name") == "activationTimestamp" }?.getAttribute("value")?.toLongOrNull() ?: 0
        val dir = File(entry.getAttribute("key").replace("\$USER_HOME\$", home.path))
        Project(dir, info.getAttribute("opened") == "true", activated)
    }.filter { it.dir.isDirectory }.sortedByDescending { it.activated }
    val (open, others) = projects.partition { it.open }
    return (open + others.take(RecentProjectsSearched)).map { it.dir }
}

/** How many projects that aren't marked open are looked in, besides those that are. */
private const val RecentProjectsSearched = 10

/** Folders that hold no sources of the project's own, skipped while looking for a file. */
private val skippedFolders = setOf("build", "out", "node_modules", "Pods", "DerivedData")

/**
 * The file [frame] is in within [project]: the one of its name in its package's folders, or, as Kotlin files needn't
 * sit in their package's folders, the only one of its name.
 */
internal fun findSource(project: File, frame: StackFrame): File? {
    val named = mutableListOf<Path>()
    runCatching {
        Files.walkFileTree(project.toPath(), object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                val name = dir.fileName?.toString().orEmpty()
                val skip = dir != project.toPath() && (name.startsWith(".") || name in skippedFolders)
                return if (skip) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (file.fileName.toString() == frame.file) named.add(file)
                return FileVisitResult.CONTINUE
            }
            override fun visitFileFailed(file: Path, exc: IOException) = FileVisitResult.CONTINUE
        })
    }
    val inPackage = named.filter { it.parent.toString().replace(File.separatorChar, '/').endsWith("/${frame.packagePath}") }
    // Never a file of the same name in another package, such as another project's MainActivity.kt.
    return (inPackage.firstOrNull() ?: named.firstOrNull { declaredPackage(it.toFile()) == frame.packagePath.replace('/', '.') })
        ?.toFile()
}

/** The package [file] declares, from its `package` line; "" when it has none. */
private fun declaredPackage(file: File): String? = runCatching {
    file.useLines { lines ->
        lines.map(String::trim).firstOrNull { it.startsWith("package ") || it.startsWith("import ") || it.startsWith("class ") }
            ?.takeIf { it.startsWith("package ") }?.removePrefix("package ")?.removeSuffix(";")?.trim()?.trim('`') ?: ""
    }
}.getOrNull()

/** The Android Studio and IntelliJ IDEA processes running, Android Studio first, as Android apps' code is most often open there. */
internal fun runningIdes(platform: HostPlatform): List<RunningIde> = ProcessHandle.allProcesses().toList()
    .mapNotNull { process -> process.info().command().orElse(null)?.let { runningIde(File(it), platform) } }
    .distinctBy { it.launcher }
    .sortedBy { if (it.launcher.name.startsWith("studio")) 0 else 1 }

/**
 * The IDE a process running [command] is, if it's Android Studio or IntelliJ IDEA: on a Mac its launcher is the
 * process, elsewhere the process is the IDE's own Java, beside the launcher's `bin` folder.
 */
internal fun runningIde(command: File, platform: HostPlatform): RunningIde? {
    val launcher = when (platform.os) {
        HostOs.MAC -> command.takeIf { it.parentFile?.name == "MacOS" }
        HostOs.WINDOWS -> command.takeIf { it.parentFile?.name == "bin" }
            ?: command.parentFile?.parentFile?.parentFile?.takeIf { command.parentFile.parentFile.name == "jbr" }
                ?.let { home -> listOf("studio64.exe", "idea64.exe").map { File(home, "bin/$it") }.firstOrNull(File::isFile) }
        HostOs.LINUX -> command.parentFile?.parentFile?.parentFile?.takeIf { command.parentFile.parentFile.name == "jbr" }
            ?.let { home -> listOf("studio", "studio.sh", "idea", "idea.sh").map { File(home, "bin/$it") }.firstOrNull(File::isFile) }
    } ?: return null
    val name = launcher.name.lowercase()
    val (vendor, prefixes) = when {
        name.startsWith("studio") -> "Google" to listOf("AndroidStudio")
        name.startsWith("idea") -> "JetBrains" to listOf("IntelliJIdea", "IdeaIC")
        else -> return null
    }
    val root = when (platform.os) {
        HostOs.MAC -> File(platform.home, "Library/Application Support/$vendor")
        HostOs.WINDOWS -> File(platform.env("APPDATA")?.let(::File) ?: File(platform.home, "AppData/Roaming"), vendor)
        HostOs.LINUX -> File(platform.env("XDG_CONFIG_HOME")?.let(::File) ?: File(platform.home, ".config"), vendor)
    }
    // Every version keeps its own folder; the one used last is the running one's.
    val configs = root.listFiles { file -> file.isDirectory && prefixes.any(file.name::startsWith) }.orEmpty()
        .sortedByDescending { File(it, "options/recentProjects.xml").lastModified() }
    return RunningIde(launcher, configs.take(1))
}
