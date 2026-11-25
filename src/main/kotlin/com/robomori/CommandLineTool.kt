package com.robomori

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import kotlin.io.path.invariantSeparatorsPathString
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/** The command people type to use Robomori from a terminal. */
internal const val COMMAND_NAME = "robomori"

/**
 * Installs the `robomori` command. The command is a small script in Robomori's settings folder that runs the installed
 * app with [CLI_ARGUMENT], so it always runs the app's current version and code update. The app rewrites the script
 * whenever it starts, in case it moved. The script is put on the PATH once: through a link in `/usr/local/bin` on
 * macOS (which asks for an administrator's password), a link in `~/.local/bin` on Linux, and its folder in the
 * user's PATH on Windows.
 *
 * [executable] is the program the script runs: null when this isn't an installed app, such as a development run.
 */
internal class CommandLineTool(
    private val platform: HostPlatform = HostPlatform(),
    private val executable: File? = installedExecutable(platform.os),
    private val system: SystemCommands = SystemCommands(),
    /** Where macOS links the command: on the PATH of every terminal by default. */
    private val macLinkDirectory: File = File("/usr/local/bin"),
) {
    val script: File get() = File(platform.configDirectory, "bin/" + if (platform.os == HostOs.WINDOWS) "$COMMAND_NAME.cmd" else COMMAND_NAME)

    /** Where the command is put on the PATH: a link on macOS and Linux, the script's folder on Windows. */
    val location: File get() = when (platform.os) {
        HostOs.MAC -> File(macLinkDirectory, COMMAND_NAME)
        HostOs.LINUX -> File(platform.home, ".local/bin/$COMMAND_NAME")
        HostOs.WINDOWS -> script.parentFile
    }

    val available: Boolean get() = executable != null

    val installed: Boolean get() = script.isFile && when (platform.os) {
        HostOs.WINDOWS -> system.userPath().any { File(it) == location }
        else -> isOurLink(location)
    }

    fun install() {
        val target = checkNotNull(executable) { "The command is available once Robomori is installed." }
        writeScript(target)
        when (platform.os) {
            HostOs.WINDOWS -> system.addToUserPath(location)
            else -> {
                // On Intel Macs, `brew install robomori/tap/robomori` links its own command at the same place.
                check(!isHomebrewLink(location)) { "Homebrew already installed the $COMMAND_NAME command." }
                check(isOurLink(location) || !Files.exists(location.toPath(), NOFOLLOW_LINKS)) {
                    "Another $COMMAND_NAME command is already at ${location.path}. Remove it, then try again."
                }
                if (canWrite(location.parentFile)) {
                    location.parentFile.mkdirs()
                    Files.deleteIfExists(location.toPath())
                    Files.createSymbolicLink(location.toPath(), script.toPath())
                } else system.asAdministrator("mkdir -p ${quote(location.parent)} && ln -sfn ${quote(script.path)} ${quote(location.path)}")
            }
        }
    }

    fun uninstall() {
        when (platform.os) {
            HostOs.WINDOWS -> system.removeFromUserPath(location)
            else -> if (isOurLink(location)) {
                if (canWrite(location.parentFile)) Files.delete(location.toPath())
                else system.asAdministrator("rm -f ${quote(location.path)}")
            }
        }
        script.delete()
    }

    /** Points an installed command at this copy of the app, so it keeps working after the app moves. */
    fun refresh() {
        val target = executable ?: return
        if (installed) writeScript(target)
    }

    private fun writeScript(target: File) {
        val text = when (platform.os) {
            HostOs.WINDOWS -> "@echo off\r\n\"${target.path}\" $CLI_ARGUMENT %*\r\n"
            else -> "#!/bin/sh\n# Runs Robomori from the command line. Robomori keeps this file up to date.\nexec ${quote(target.path)} $CLI_ARGUMENT \"\$@\"\n"
        }
        if (script.isFile && script.readText() == text) return
        script.parentFile.mkdirs()
        script.writeText(text)
        script.setExecutable(true)
    }

    private fun isOurLink(link: File) = Files.isSymbolicLink(link.toPath()) &&
        runCatching { link.toPath().parent.resolve(Files.readSymbolicLink(link.toPath())) == script.toPath() }.getOrDefault(false)

    private fun isHomebrewLink(link: File) = Files.isSymbolicLink(link.toPath()) &&
        runCatching { "/Caskroom/$COMMAND_NAME/" in Files.readSymbolicLink(link.toPath()).invariantSeparatorsPathString }.getOrDefault(false)

    /** Whether [directory], or the nearest folder that exists above it, can be written without an administrator. */
    private fun canWrite(directory: File): Boolean =
        generateSequence(directory) { it.parentFile }.firstOrNull { it.exists() }?.canWrite() == true

    private fun quote(text: String) = "'" + text.replace("'", "'\\''") + "'"

    companion object {
        /** The installed app's own launcher. Windows has a separate one for the command line that can print to a terminal. */
        fun installedExecutable(os: HostOs, bundle: File? = appBundle(), appImage: String? = System.getenv("APPIMAGE"),
                                appPath: String? = System.getProperty("jpackage.app-path")): File? = when (os) {
            HostOs.MAC -> bundle?.takeIf { installedForUpdates(os, bundle = it) }?.let { File(it, "Contents/MacOS/$APP_NAME") }
            HostOs.LINUX -> appImage?.takeIf(String::isNotBlank)?.let(::File)
            HostOs.WINDOWS -> appPath?.takeIf { it.endsWith(".exe", true) }?.let { File(File(it).parentFile, "$COMMAND_NAME-cli.exe") }
        }?.takeIf(File::isFile)
    }
}

/** The system changes that need more than file access: an administrator's approval, or the Windows user PATH. */
internal open class SystemCommands {
    /** Runs [script] in a shell as an administrator, after macOS asks for a password. */
    open fun asAdministrator(script: String) {
        val escaped = script.replace("\\", "\\\\").replace("\"", "\\\"")
        val result = run(listOf("/usr/bin/osascript", "-e", "do shell script \"$escaped\" with administrator privileges"))
        if (result.exitCode != 0) {
            if ("-128" in result.output) throw PasswordPromptCancelled()
            error("Could not install the command: ${result.output.trim().ifBlank { "osascript exited with ${result.exitCode}" }}")
        }
    }

    // The user's PATH is read and written in the registry as is, so entries like %USERPROFILE%\bin keep expanding.
    open fun userPath(): List<String> = powershell("$ENVIRONMENT_KEY.GetValue('Path', '', 'DoNotExpandEnvironmentNames')")
        .trim().split(';').filter(String::isNotBlank)

    open fun addToUserPath(directory: File) = setUserPath(userPath().filterNot { File(it) == directory } + directory.path)

    open fun removeFromUserPath(directory: File) = setUserPath(userPath().filterNot { File(it) == directory })

    // Setting any user variable through .NET tells open programs the environment changed, so new terminals see it.
    private fun setUserPath(entries: List<String>) {
        powershell("$ENVIRONMENT_KEY.SetValue('Path', \$env:ROBOMORI_PATH, 'ExpandString'); " +
            "[Environment]::SetEnvironmentVariable('RobomoriPathChanged', '1', 'User'); " +
            "[Environment]::SetEnvironmentVariable('RobomoriPathChanged', \$null, 'User')",
            mapOf("ROBOMORI_PATH" to entries.joinToString(";")))
    }

    private fun powershell(command: String, environment: Map<String, String> = emptyMap()): String {
        val result = run(listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command), environment)
        check(result.exitCode == 0) { "Could not update your PATH: ${result.output.trim()}" }
        return result.output
    }

    class Result(val exitCode: Int, val output: String)

    private companion object {
        const val ENVIRONMENT_KEY = "[Microsoft.Win32.Registry]::CurrentUser.OpenSubKey('Environment', \$true)"
    }

    private fun run(command: List<String>, environment: Map<String, String> = emptyMap()): Result {
        val process = ProcessBuilder(command).redirectErrorStream(true).apply { environment().putAll(environment) }.start()
        process.outputStream.close()
        val output = process.inputStream.bufferedReader().readText()
        return Result(process.waitFor(), output)
    }
}

/** Closing macOS's password prompt: nothing changed, and there's nothing to report. */
internal class PasswordPromptCancelled : Exception("Cancelled.")

/** The CLI & Agents settings page's view of [tool]. */
internal class CommandLineToolState(private val scope: CoroutineScope, private val tool: CommandLineTool = CommandLineTool()) {
    var installed by mutableStateOf(false); private set
    var working by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    val available get() = tool.available
    val location get() = tool.location

    fun load() = scope.launch {
        installed = withContext(Dispatchers.IO) { runCatching { tool.installed }.getOrDefault(false) }
    }

    /** Keeps an installed command pointing at this copy of the app. */
    fun refresh() = scope.launch(Dispatchers.IO) { runCatching { tool.refresh() } }

    fun install() = change(tool::install)

    fun uninstall() = change(tool::uninstall)

    private fun change(action: () -> Unit) {
        if (working) return
        working = true; error = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { action() }
            } catch (_: PasswordPromptCancelled) {
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = e.message ?: "Could not change the command."
            } finally {
                installed = withContext(Dispatchers.IO) { runCatching { tool.installed }.getOrDefault(false) }
                working = false
            }
        }
    }
}
