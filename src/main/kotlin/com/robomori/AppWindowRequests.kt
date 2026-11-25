package com.robomori

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

/**
 * Lets the command line ask the app to show an emulator in its own window. Each request is a file named after the
 * emulator, in Robomori's settings folder, which the app picks up and deletes. A request made while the app is closed
 * waits there until it starts.
 *
 * The app holds a lock on [lockFile] while it runs, which the command line checks to know whether to start it. The
 * system releases the lock when the app exits, also when it crashes.
 */
internal open class AppWindowRequests(
    private val folder: File = File(HostPlatform().configDirectory, "open-requests"),
    private val lockFile: File = File(HostPlatform().configDirectory, "app.lock"),
) {
    private var held: Pair<FileChannel, FileLock>? = null

    open fun request(emulator: String) {
        folder.mkdirs()
        File(folder, emulator).writeText("")
    }

    fun pending(): Boolean = folder.listFiles().orEmpty().any(File::isFile)

    /** The emulators asked for since the last call, oldest first. */
    fun take(): List<String> = folder.listFiles().orEmpty().filter(File::isFile).sortedBy(File::lastModified)
        .filter(File::delete).map(File::getName)

    /** Called by the app once it starts. */
    fun holdLock() {
        if (held != null) return
        lockFile.parentFile.mkdirs()
        val channel = RandomAccessFile(lockFile, "rw").channel
        val lock = runCatching { channel.tryLock() }.getOrNull() ?: return channel.close()
        held = channel to lock
    }

    fun releaseLock() {
        held?.let { (channel, lock) -> lock.release(); channel.close() }
        held = null
    }

    open fun appRunning(): Boolean {
        if (!lockFile.isFile) return false
        return RandomAccessFile(lockFile, "rw").channel.use { channel ->
            try { channel.tryLock()?.release() == null } catch (_: OverlappingFileLockException) { true }
        }
    }

    /** Starts the installed app. Returns false when there's no app to start, such as in a development run. */
    open fun launchApp(os: HostOs = HostOs.detect()): Boolean {
        val command = when (os) {
            HostOs.MAC -> appBundle()?.let { listOf("/usr/bin/open", it.path) }
            HostOs.LINUX -> System.getenv("APPIMAGE")?.takeIf(String::isNotBlank)?.let { listOf(it) }
            // The command line runs robomori-cli.exe; the app is the launcher next to it.
            HostOs.WINDOWS -> System.getProperty("jpackage.app-path")?.let { File(File(it).parentFile, "$APP_NAME.exe") }
                ?.takeIf(File::isFile)?.let { listOf(it.path) }
        } ?: return false
        ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        return true
    }
}
