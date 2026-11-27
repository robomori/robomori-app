package com.robomori

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Always-on logs for support: Robomori's own failures in [APP_LOG], and each emulator's output in a file of its own.
 * People send them with Export Logs, so a problem on their computer can be read without reproducing it there.
 */
class AppLogs(
    val folder: File = HostPlatform().logDirectory,
    private val now: () -> LocalDateTime = LocalDateTime::now,
    private val keptProcessLogs: Int = 20,
    private val maxAppLogBytes: Long = 1_000_000,
) {
    /** Starts a log for one run of [command], named after [label], keeping only the newest [keptProcessLogs]. */
    fun processLog(label: String, command: List<String>): File {
        folder.mkdirs()
        val safeLabel = label.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val file = File(folder, "$safeLabel-${now().format(fileTime)}.log")
        file.writeText("${now().format(lineTime)} ${command.joinToString(" ")}\n")
        folder.listFiles().orEmpty().filter { it.isFile && it.name != APP_LOG && it.name != OLD_APP_LOG && it.extension == "log" }
            .sortedByDescending { it.lastModified() }.drop(keptProcessLogs).forEach { it.delete() }
        return file
    }

    /** Adds [message] and the stack trace of [failure] to Robomori's own log, moving a full log aside first. */
    @Synchronized fun record(message: String, failure: Throwable? = null) {
        runCatching {
            folder.mkdirs()
            val log = File(folder, APP_LOG)
            if (log.length() > maxAppLogBytes) log.renameTo(File(folder, OLD_APP_LOG).also { it.delete() })
            log.appendText(buildString {
                appendLine("${now().format(lineTime)} $message")
                failure?.let { append(it.stackTraceToString()) }
            })
        }
    }

    /** Zips every log, plus [diagnostics] about this computer, into [target]. */
    fun export(target: File, diagnostics: String) {
        val logs = folder.listFiles().orEmpty().filter(File::isFile).sortedBy(File::getName)
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("diagnostics.txt"))
            zip.write(diagnostics.toByteArray())
            zip.closeEntry()
            logs.forEach { log ->
                zip.putNextEntry(ZipEntry(log.name))
                log.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    companion object {
        const val APP_LOG = "robomori.log"
        private const val OLD_APP_LOG = "robomori.old.log"
        private val fileTime = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS")
        private val lineTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    }
}
