package com.robomori

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.*
import java.util.Properties
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

val userHome: File get() = File(System.getProperty("user.home"))
private const val enableWifiOnFirstBoot = "robomori.enableWifiOnFirstBoot"
private const val disableLockScreenOnFirstBoot = "robomori.disableLockScreenOnFirstBoot"
// Ends each microphone check in watchMicrophoneUse's output.
private const val CheckEnd = "--robomori-check--"

data class AppSettings(
    val sdk: String, val avds: String,
    val adb: String = "", val emulator: String = "",
    val sdkmanager: String = "", val avdmanager: String = "",
)
class SettingsStore(private val file: File = File(HostPlatform().configDirectory, "settings.properties")) {
    fun load(): AppSettings {
        val p = Properties().apply { if (file.isFile) file.inputStream().use(::load) }
        val sdk = p.getProperty("sdk") ?: HostPlatform().defaultSdk.path
        val avds = p.getProperty("avds") ?: defaultAvdHome()
        return AppSettings(sdk, avds, p.getProperty("adb", ""), p.getProperty("emulator", ""),
            p.getProperty("sdkmanager", ""), p.getProperty("avdmanager", ""))
    }
    /** When the settings were last saved, by the app or the command line; 0 before the first save. */
    fun savedAt(): Long = file.lastModified()
    /** The settings that were chosen, not left at their defaults. */
    fun chosen(): Set<String> = properties().let { p -> p.stringPropertyNames().filter { p.getProperty(it).isNotBlank() }.toSet() }

    private fun properties() = Properties().apply { if (file.isFile) file.inputStream().use(::load) }

    /** Saves [settings], leaving each of [defaults] out so it follows its default. */
    fun save(settings: AppSettings, defaults: Set<String>) {
        save(settings)
        if (defaults.isEmpty()) return
        val kept = properties().apply { defaults.forEach(::remove) }
        val temp = File.createTempFile("settings-", ".tmp", file.parentFile)
        try {
            temp.outputStream().use { kept.store(it, "Robomori") }
            Files.move(temp.toPath(), file.toPath(), REPLACE_EXISTING, ATOMIC_MOVE)
        } finally { temp.delete() }
    }

    fun save(settings: AppSettings) {
        file.parentFile.mkdirs()
        val temp = File.createTempFile("settings-", ".tmp", file.parentFile)
        try {
            temp.outputStream().use { Properties().apply {
                setProperty("sdk", settings.sdk); setProperty("avds", settings.avds)
                setProperty("adb", settings.adb); setProperty("emulator", settings.emulator)
                setProperty("sdkmanager", settings.sdkmanager); setProperty("avdmanager", settings.avdmanager)
            }.store(it, "Robomori") }
            Files.move(temp.toPath(), file.toPath(), REPLACE_EXISTING, ATOMIC_MOVE)
        } finally { temp.delete() }
    }
}
fun defaultAvdHome(): String = HostPlatform().defaultAvds.path
fun expandPath(value: String): File {
    val path = value.trim()
    return when {
        path == "~" -> userHome
        path.startsWith("~/") || path.startsWith("~\\") -> File(userHome, path.drop(2))
        else -> File(path)
    }.absoluteFile
}
fun readIni(file: File): Map<String, String> = if (!file.isFile) emptyMap() else file.readLines().mapNotNull {
    val i = it.indexOf('='); if (i < 0) null else it.take(i).trim() to it.drop(i + 1).trim()
}.toMap()
fun writeIni(file: File, values: Map<String, String>) {
    file.writeText(values.entries.joinToString("\n", postfix = "\n") { "${it.key}=${it.value}" })
}

data class Toolchain(val root: File, private val settings: AppSettings? = null) {
    constructor(settings: AppSettings) : this(expandPath(settings.sdk), settings)
    private val host = HostPlatform()
    private fun tool(name: String): File {
        val dirs = File(root, "cmdline-tools").listFiles()?.filter(File::isDirectory).orEmpty()
        return (listOf(File(root, "cmdline-tools/latest/bin/$name")) + dirs.sortedByDescending { it.name }.map { File(it, "bin/$name") })
            .firstOrNull { it.isFile && it.canExecute() } ?: File(root, "cmdline-tools/latest/bin/$name")
    }
    private fun override(path: String?, fallback: () -> File) = path?.takeIf(String::isNotBlank)?.let(::expandPath) ?: fallback()
    val sdkmanager get() = override(settings?.sdkmanager) { tool(host.script("sdkmanager")) }
    val avdmanager get() = override(settings?.avdmanager) { tool(host.script("avdmanager")) }
    val emulator get() = override(settings?.emulator) { File(root, "emulator/${host.executable("emulator")}") }
    val adb get() = override(settings?.adb) { File(root, "platform-tools/${host.executable("adb")}") }
    val tools get() = linkedMapOf("sdkmanager" to sdkmanager, "avdmanager" to avdmanager, "emulator" to emulator, "adb" to adb)
    val ready get() = tools.values.all { it.isFile && it.canExecute() }
    fun hasTools(vararg names: String) = names.all { tools.getValue(it).let { file -> file.isFile && file.canExecute() } }
    fun requireTools(vararg names: String) {
        val missing = names.filterNot { hasTools(it) }
        require(missing.isEmpty()) { "Choose executable files for ${missing.joinToString(", ")} in Settings → SDK Locations." }
    }
}

internal class AndroidCommandException(val output: String, val exitCode: Int) :
    IllegalStateException(androidCommandError(output, exitCode))

fun androidCommandError(output: String, exitCode: Int): String {
    if (output.contains("No space left on device", ignoreCase = true) ||
        output.contains("not enough space", ignoreCase = true)) {
        return "Not enough disk space to unpack the Android image. Free space on the drive containing your Android SDK, or choose an SDK on another drive in Settings, then retry."
    }
    val lines = output.replace('\r', '\n').lines().map(String::trim).filter { it.isNotEmpty() && !it.startsWith("[") }
    val failure = lines.lastOrNull { it.contains("error", true) || it.contains("failed", true) || it.contains("exception", true) }
    return (failure ?: lines.takeLast(3).joinToString("\n")).take(1000)
        .ifBlank { "Android command exited with $exitCode." }
}

/** [streamed] output goes only to the output callback, for commands that run for long; the result is then empty. */
/** [log] names the file a detached command's output is kept in; without one, its output is discarded. */
data class Command(val executable: File, val args: List<String>, val env: Map<String, String> = emptyMap(), val input: String? = null, val detached: Boolean = false,
                   val streamed: Boolean = false, val log: String? = null)
fun interface CommandExecutor { fun run(command: Command, cancelled: AtomicBoolean, output: (String) -> Unit): String }
class ProcessExecutor(private val logs: AppLogs = AppLogs()) : CommandExecutor {
    override fun run(command: Command, cancelled: AtomicBoolean, output: (String) -> Unit): String {
        // cmd.exe treats semicolons as batch argument separators. Quote every
        // argument, including SDK package IDs that contain no spaces.
        val arguments = if (HostOs.detect() == HostOs.WINDOWS && command.executable.extension.lowercase() in listOf("bat", "cmd")) {
            command.args.map {
                require(it.none { char -> char == '"' || char == '\r' || char == '\n' }) { "Invalid SDK command argument." }
                "\"$it\""
            }
        } else command.args
        val builder = ProcessBuilder(listOf(command.executable.path) + arguments).redirectErrorStream(true)
        builder.environment().putAll(command.env)
        if (command.detached) {
            // A log that can't be written must not stop the process from starting.
            val log = command.log?.let { runCatching { logs.processLog(it, listOf(command.executable.path) + arguments) }.getOrNull() }
            builder.redirectOutput(log?.let(ProcessBuilder.Redirect::appendTo) ?: ProcessBuilder.Redirect.DISCARD).start().outputStream.close()
            return ""
        }
        val process = builder.start()
        val text = StringBuilder()
        val reader = thread(isDaemon = true, name = "android-output") {
            process.inputStream.bufferedReader().use { stream ->
                val chars = CharArray(2048)
                while (true) {
                    val n = stream.read(chars)
                    if (n < 0) break
                    val chunk = String(chars, 0, n)
                    if (!command.streamed) synchronized(text) { text.append(chunk) }
                    output(chunk)
                }
            }
        }
        try {
            process.outputStream.bufferedWriter().use { if (command.input != null) it.write(command.input) }
            while (!process.waitFor(100, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                if (cancelled.get() || Thread.currentThread().isInterrupted) {
                    stopProcessTree(process)
                    throw CancellationException("Operation cancelled.")
                }
            }
            reader.join()
            val result = synchronized(text) { text.toString() }
            if (process.exitValue() != 0) throw AndroidCommandException(result, process.exitValue())
            return result
        } finally { if (process.isAlive) stopProcessTree(process) }
    }

    private fun stopProcessTree(process: Process) {
        // On Windows sdkmanager.bat starts a Java child. Killing only cmd.exe
        // leaves the SDK operation running and holding its files open.
        val children = process.descendants().use { it.toList() }.asReversed()
        children.forEach { it.destroy() }
        process.destroy()
        try { process.waitFor(1, java.util.concurrent.TimeUnit.SECONDS) }
        finally {
            children.filter { it.isAlive }.forEach { it.destroyForcibly() }
            if (process.isAlive) process.destroyForcibly()
        }
    }
}

private fun imageTag(config: Map<String, String>): String = config["tag.id"]?.takeIf(String::isNotBlank)
    ?: config["image.sysdir.1"].orEmpty().replace('\\', '/').split('/').getOrNull(2).orEmpty()

private fun unsupportedPlatform(config: Map<String, String>): Boolean {
    val name = config["hw.device.name"].orEmpty().lowercase()
    val tag = imageTag(config).lowercase()
    return listOf("automotive", "desktop", "xr").any { it in name || it in tag }
}

enum class DeviceType(val label: String) {
    PHONE("Phone"), FOLDABLE("Foldable"), TABLET("Tablet"),
    WEAR("Wear OS"), TV("Android TV");
    val supportsRotation get() = this == PHONE || this == FOLDABLE || this == TABLET
    fun accepts(tag: String): Boolean = when (this) {
        WEAR -> tag.contains("wear")
        TV -> tag.contains("tv")
        else -> !listOf("wear", "tv", "automotive", "xr", "desktop").any(tag::contains)
    }
    companion object {
        fun from(config: Map<String, String>): DeviceType {
            require(!unsupportedPlatform(config)) { "This emulator platform is not supported." }
            val name = config["hw.device.name"].orEmpty()
            return when {
                name.startsWith("wear") || name.contains("watch") || config["tag.id"].orEmpty().contains("wear") -> WEAR
                name.contains("fold") -> FOLDABLE
                // A 1080p TV is wider than the tablet heuristic.
                name.startsWith("tv") || imageTag(config).contains("tv") -> TV
                name.contains("tablet") || (config["hw.lcd.width"]?.toIntOrNull() ?: 0) >= 1600 -> TABLET
                else -> PHONE
            }
        }
    }
}
interface DeviceTarget {
    val name: String
    val displayName: String
    val type: DeviceType
}
data class Device(override val name: String, val directory: File, val config: Map<String, String>, val bytes: Long) : DeviceTarget {
    override val type get() = DeviceType.from(config)
    val api get() = Regex("android-([^/;\\\\]+)").find(config["image.sysdir.1"].orEmpty())?.groupValues?.get(1)
        ?: config["target"]?.removePrefix("android-") ?: "—"
    val platformTitle get() = when (type) {
        DeviceType.WEAR -> wearOsTitle(api)
        DeviceType.TV -> androidTvTitle(api)
        else -> androidFamilyTitle(api)
    }
    override val displayName get() = config["avd.ini.displayname"]?.takeIf(String::isNotBlank) ?: name.replace('_', ' ')
    val missingStorage get() = !directory.isDirectory || directory.listFiles().orEmpty().any { Files.isSymbolicLink(it.toPath()) && !it.exists() }
    val needsInitialSetup get() = config[enableWifiOnFirstBoot] == "yes" || config[disableLockScreenOnFirstBoot] == "yes"
}

data class SystemImage(val path: String, val description: String, val installed: Boolean) {
    val api get() = path.split(';').getOrElse(1) { "" }.removePrefix("android-")
    val tag get() = path.split(';').getOrElse(2) { "" }
    val abi get() = path.split(';').getOrElse(3) { "" }
    val services get() = when { tag.contains("playstore") || tag.contains("google_tv") || tag.startsWith("google-tv") || tag == "android-tv" -> "Google Play"; tag.contains("google") -> "Google APIs"; else -> "None" }
    val platformTitle get() = when {
        tag.contains("wear") -> wearOsTitle(api)
        tag.endsWith("-tv") || tag.startsWith("google-tv") -> androidTvTitle(api)
        else -> androidFamilyTitle(api)
    }
    val title get() = "$platformTitle · $services · ${if (abi == "arm64-v8a") "arm64" else abi}"
}

fun SystemImage.directory(sdkRoot: File): File = File(sdkRoot, path.replace(';', '/'))

fun SystemImage.sizeOnDisk(sdkRoot: File): Long? {
    val directory = directory(sdkRoot)
    if (!directory.isDirectory) return null
    return directory.walkTopDown()
        .onEnter { !Files.isSymbolicLink(it.toPath()) }
        .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }
        .sumOf { it.length() }
}

fun Device.usesImage(image: SystemImage, sdkRoot: File): Boolean {
    val path = config["image.sysdir.1"] ?: return false
    val configured = File(path)
    val directory = if (configured.isAbsolute) configured else File(sdkRoot, path)
    return directory.canonicalFile == image.directory(sdkRoot).canonicalFile
}

/** Marks what is installed by what is on disk now, which a cached catalog can't know. */
fun withInstalled(catalog: List<SystemImage>, installed: List<SystemImage>): List<SystemImage> {
    val paths = installed.mapTo(HashSet()) { it.path }
    val known = catalog.mapTo(HashSet()) { it.path }
    return (catalog.map { it.copy(installed = it.path in paths) } + installed.filter { it.path !in known }).sortedByRelease()
}

private fun List<SystemImage>.sortedByRelease() =
    sortedWith(compareByDescending<SystemImage> { Regex("[0-9.]+").find(it.api)?.value?.toDoubleOrNull() ?: 0.0 }.thenBy { it.path })

fun parseImages(text: String): List<SystemImage> {
    var installed = false
    val images = linkedMapOf<String, SystemImage>()
    text.lineSequence().forEach { line ->
        when {
            line.trim().startsWith("Installed packages:") -> installed = true
            line.trim().startsWith("Available Packages:", true) -> installed = false
            else -> {
                val parts = line.trim().split('|').map(String::trim)
                if (parts.size >= 3 && Regex("system-images;[^;]+;[^;]+;[^;\\s]+").matches(parts[0])) {
                    val image = SystemImage(parts[0], parts[2], installed)
                    if (images[image.path]?.installed != true) images[image.path] = image
                }
            }
        }
    }
    return images.values.toList().sortedByRelease()
}
data class DeviceProfile(val id: String, val name: String)
fun parseProfiles(text: String): List<DeviceProfile> = Regex("id:.*?\\\"([^\\\"]+)\\\"\\s*\\n\\s*Name: ([^\\n]+)").findAll(text).map { DeviceProfile(it.groupValues[1], it.groupValues[2].trim()) }.toList()
data class CreateSpec(val name: String, val image: SystemImage, val profile: String, val ram: Int? = null, val storage: Int = 16, val sdCard: Int? = null, val frame: Boolean = true,
    val hardware: HardwareProfile? = null, val displayName: String = name)

class AndroidService(val settings: AppSettings, private val executor: CommandExecutor = ProcessExecutor()) {
    val toolchain = Toolchain(settings)
    val avdRoot = expandPath(settings.avds)
    fun command(executable: File, args: List<String>, cancelled: AtomicBoolean = AtomicBoolean(), input: String? = null, detached: Boolean = false, environment: Map<String, String> = emptyMap(), streamed: Boolean = false, log: String? = null, output: (String) -> Unit = {}): String {
        val arguments = if (settings.sdkmanager.isNotBlank() && executable == toolchain.sdkmanager)
            args + "--sdk_root=${toolchain.root.path}" else args
        val toolEnvironment = if (settings.avdmanager.isNotBlank() && executable == toolchain.avdmanager) {
            // avdmanager derives its SDK from this JVM property, ignoring ANDROID_HOME.
            // Its launcher evaluates AVDMANAGER_OPTS after the default JVM options.
            val option = "-Dcom.android.sdkmanager.toolsdir=${File(toolchain.root, "cmdline-tools/latest").path}"
            val quoted = if (HostOs.detect() == HostOs.WINDOWS) "\"$option\"" else "'${option.replace("'", "'\\''")}'"
            mapOf("AVDMANAGER_OPTS" to listOfNotNull(System.getenv("AVDMANAGER_OPTS"), quoted).joinToString(" "))
        } else emptyMap()
        return executor.run(Command(executable, arguments, mapOf("ANDROID_AVD_HOME" to avdRoot.path, "ANDROID_HOME" to toolchain.root.path, "ANDROID_SDK_ROOT" to toolchain.root.path) + toolEnvironment + environment, input, detached, streamed, log), cancelled, output)
    }
    // Skips hidden files, such as the ._Name.ini metadata macOS writes next to each file on exFAT and other non-Apple drives.
    fun devices(): List<Device> = avdRoot.listFiles().orEmpty().filter { it.extension == "ini" && !it.name.startsWith(".") }.sortedBy { it.name.lowercase() }.mapNotNull { ini ->
        val name = ini.nameWithoutExtension
        val directory = readIni(ini)["path"]?.let(::File) ?: File(avdRoot, "$name.avd")
        val config = readIni(File(directory, "config.ini"))
        if (unsupportedPlatform(config)) return@mapNotNull null
        val bytes = if (directory.isDirectory) directory.walkTopDown().onEnter { !Files.isSymbolicLink(it.toPath()) }.filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }.sumOf { it.length() } else 0L
        Device(name, directory, config, bytes)
    }
    fun images(cancelled: AtomicBoolean = AtomicBoolean(), output: (String) -> Unit = {}) = parseImages(catalog(cancelled, output))
    /** sdkmanager's package list, fetched from Google's repository. */
    fun catalog(cancelled: AtomicBoolean = AtomicBoolean(), output: (String) -> Unit = {}): String {
        toolchain.requireTools("sdkmanager")
        return command(toolchain.sdkmanager, listOf("--list"), cancelled, output = output)
    }
    fun installedImages(): List<SystemImage> {
        require(toolchain.root.isDirectory) { "Android SDK storage is unavailable. Connect its drive or choose another SDK in Settings." }
        val root = File(toolchain.root, "system-images")
        return root.listFiles().orEmpty().flatMap { version ->
            version.listFiles().orEmpty().flatMap { tag ->
                tag.listFiles().orEmpty().mapNotNull { abi ->
                    val properties = File(abi, "source.properties")
                    if (!properties.isFile || !File(abi, "system.img").isFile) return@mapNotNull null
                    val metadata = Properties().apply { properties.inputStream().use(::load) }
                    SystemImage("system-images;${version.name};${tag.name};${abi.name}",
                        metadata.getProperty("Pkg.Desc", "Android system image"), installed = true)
                }
            }
        }
    }
    fun profiles() = parseProfiles(command(toolchain.avdmanager, listOf("list", "device")))
    fun running(): Map<String, String> {
        if (!toolchain.adb.canExecute()) return emptyMap()
        val result = linkedMapOf<String, String>()
        val serials = command(toolchain.adb, listOf("devices")).lines().filter { it.startsWith("emulator-") && it.endsWith("\tdevice") }.map { it.substringBefore('\t') }
        serials.forEach { serial ->
            val name = command(toolchain.adb, listOf("-s", serial, "emu", "avd", "name")).lines().firstOrNull { it.isNotBlank() && it != "OK" }
            if (name != null) result[name.trim()] = serial
        }
        return result
    }
    fun bootCompleted(serial: String): Boolean = command(toolchain.adb, listOf("-s", serial, "shell", "getprop", "sys.boot_completed")).trim() == "1"
    fun configureInitialSettings(device: Device, serial: String) {
        val file = File(device.directory, "config.ini")
        var config = readIni(file)
        if (config[enableWifiOnFirstBoot] == "yes") {
            command(toolchain.adb, listOf("-s", serial, "shell", "cmd", "wifi", "set-wifi-enabled", "enabled"))
            config = config - enableWifiOnFirstBoot
            writeIni(file, config)
        }
        if (config[disableLockScreenOnFirstBoot] == "yes") {
            // Generic emulator images can ignore the fold-specific stay-awake setting.
            command(toolchain.adb, listOf("-s", serial, "shell", "locksettings", "set-disabled", "true"))
            writeIni(file, config - disableLockScreenOnFirstBoot)
        }
    }
    fun displayRotation(serial: String): Int {
        val dump = command(toolchain.adb, listOf("-s", serial, "shell", "dumpsys", "input"))
        return inputDisplayRotation(dump)
            ?: error("Could not read the emulator orientation. Try rotating again once Android has started.")
    }
    fun launch(device: Device, coldBoot: Boolean = false, wipeData: Boolean = false, media: EmulatorMedia = EmulatorMedia(),
               graphics: EmulatorGraphics = EmulatorGraphics.Hardware) {
        require(!unsupportedPlatform(device.config)) { "This emulator platform is not supported." }
        toolchain.requireTools("emulator", "adb"); requireAvdStorage()
        require(!device.missingStorage) { "${device.displayName} has unavailable storage. Connect its drive before launching." }
        val grpcPort = java.net.ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress()).use { it.localPort }
        val ram = lightenRam(device)
        val frameUpdated = updateFrame(device)
        // The chosen graphics override the emulator's own setting for this run, leaving its configuration as it is.
        val args = mutableListOf("-avd", device.name, "-no-window", "-gpu", graphics.gpuMode, "-grpc", grpcPort.toString(), "-grpc-use-token")
        if (coldBoot || wipeData || ram.changed || frameUpdated) args += "-no-snapshot-load"
        // Without -lowram the emulator raises newer system images to at least 4 GB.
        if (ram.mib in 1 until 4096) args += "-lowram"
        if (wipeData) args += "-wipe-data"
        val skin = device.config["skin.path"]?.let(::File)
        if (device.config["showDeviceFrame"] != "no" && skin?.isDirectory == true) args += listOf("-skindir", skin.parent, "-skin", skin.name)
        args += hostMediaArgs(device.config, media, hostCameras())
        command(toolchain.emulator, args, detached = true, log = "emulator-${device.name}")
        if (media.microphone != null) startMicrophoneForwarder()
    }
    /** Starts the background process that feeds the chosen microphone to emulators. It ends itself once idle. */
    fun startMicrophoneForwarder() {
        val forwarder = microphoneForwarderCommand() ?: return
        command(File(forwarder.first()), forwarder.drop(1), detached = true)
    }
    /**
     * Calls [onCrash] each time a WebView on the emulator crashes because its hardware graphics lack OpenGL ES 3.1, until
     * [cancelled]. Reads only Chromium's fatal log lines, from now on.
     */
    fun watchWebViewGraphicsCrashes(serial: String, cancelled: AtomicBoolean, onCrash: () -> Unit) {
        val pending = StringBuilder()
        command(toolchain.adb, listOf("-s", serial, "logcat", "-T", "1", "-s", "chromium:F"), cancelled, streamed = true) { chunk ->
            pending.append(chunk)
            while (true) {
                val end = pending.indexOf("\n").takeIf { it >= 0 } ?: break
                if (isWebViewGraphicsCrash(pending.substring(0, end))) onCrash()
                pending.delete(0, end + 1)
            }
        }
    }
    private class LaunchRam(val mib: Int, val changed: Boolean)
    /** Lowers a Robomori emulator made with more RAM than its profile now uses. Its snapshot holds the old RAM, so it boots cold once. */
    private fun lightenRam(device: Device): LaunchRam {
        val configured = device.config["hw.ramSize"]?.removeSuffix("M")?.toIntOrNull() ?: 0
        val profile = HardwareProfiles.all.find { it.id == device.config["robomori.profile"] } ?: return LaunchRam(configured, false)
        if (configured <= profile.ramMiB) return LaunchRam(configured, false)
        val file = File(device.directory, "config.ini")
        writeIni(file, readIni(file) + ("hw.ramSize" to profile.ramMiB.toString()))
        return LaunchRam(profile.ramMiB, true)
    }
    /**
     * Brings a Robomori emulator's frame up to date with this version, such as the foldable's, which made it start
     * turned sideways. The emulator sets its orientation as it boots, so it boots cold once.
     */
    private fun updateFrame(device: Device): Boolean {
        val profile = HardwareProfiles.all.find { it.id == device.config["robomori.profile"] } ?: return false
        if (!profile.frameOutdated(device.directory)) return false
        profile.installFrame(device.directory)
        return true
    }
    /** The computer's cameras, as the emulator numbers them right now. */
    fun hostCameras(): List<HostCamera> = runCatching { parseHostCameras(command(toolchain.emulator, listOf("-webcam-list"))) }.getOrDefault(emptyList())
    fun stop(serial: String) { command(toolchain.adb, listOf("-s", serial, "emu", "kill")) }
    /** Whether an app on the emulator has a camera open. */
    fun cameraInUse(serial: String): Boolean =
        cameraClients(command(toolchain.adb, listOf("-s", serial, "shell", "dumpsys media.camera | sed -n '/Active Camera Clients/,/^]/p'"))).isNotEmpty()
    /**
     * Tells [onCheck], about ten times a second until [cancelled], whether an app on the emulator records from its
     * microphone. A single adb shell keeps checking, so frequent checks start no new processes.
     */
    fun watchMicrophoneUse(serial: String, cancelled: AtomicBoolean, onCheck: (Boolean) -> Unit) {
        val pending = StringBuilder()
        val loop = "while :; do dumpsys media.audio_policy | grep -E 'State: |Source: [0-9]'; echo $CheckEnd; sleep 0.1; done"
        command(toolchain.adb, listOf("-s", serial, "shell", loop), cancelled, streamed = true) { chunk ->
            pending.append(chunk)
            while (true) {
                val end = pending.indexOf(CheckEnd).takeIf { it >= 0 } ?: break
                onCheck(recordingClients(pending.substring(0, end)) > 0)
                pending.delete(0, end + CheckEnd.length)
            }
        }
    }
    /** Stops every emulator adb lists, including ones that are still booting. */
    fun stopAll() {
        if (!toolchain.adb.canExecute()) return
        command(toolchain.adb, listOf("devices")).lines().filter { it.startsWith("emulator-") }.map { it.substringBefore('\t') }
            .forEach { serial -> runCatching { stop(serial) } }
    }
    fun restart(serial: String) { command(toolchain.adb, listOf("-s", serial, "reboot")) }
    fun requireAvdStorage() { require(avdRoot.isDirectory) { "Emulator storage is unavailable at ${avdRoot.path}. Connect its drive or choose another folder in Settings." } }
    fun validateName(name: String) {
        require(Regex("[A-Za-z0-9._-]+").matches(name)) { "Use letters, numbers, dots, underscores, or dashes in the name." }
        require(!isReservedDeviceName(name)) { "This name is reserved by Windows. Choose a different name." }
        require(!File(avdRoot, "$name.ini").exists() && !Files.exists(File(avdRoot, "$name.avd").toPath(), NOFOLLOW_LINKS)) { "An emulator named $name already exists." }
    }
    private fun resolveCreationName(spec: CreateSpec): CreateSpec {
        val issue = creationNameError(spec.displayName, devices().map(Device::displayName))
        if (issue != null) throw InvalidCreationName(issue)
        // Unregistered directories and broken links also occupy storage names.
        val occupied = avdRoot.listFiles().orEmpty().filter { it.extension in listOf("ini", "avd") }.map(File::nameWithoutExtension)
        return spec.copy(name = availableAvdName(spec.displayName, occupied))
    }

    fun deleteImage(image: SystemImage) {
        toolchain.requireTools("sdkmanager")
        val users = devices().filter { it.usesImage(image, toolchain.root) }
        require(users.isEmpty()) { "This Android version is used by ${users.joinToString { it.displayName }}. Delete those emulators before deleting this version." }
        command(toolchain.sdkmanager, listOf("--uninstall", image.path))
    }

    fun installDownloadedImage(image: SystemImage, archive: DownloadedImage, cancelled: AtomicBoolean, output: (String) -> Unit) {
        toolchain.requireTools("sdkmanager")
        val sdkClasspath = File(toolchain.sdkmanager.canonicalFile.parentFile.parentFile, "lib/sdkmanager-classpath.jar")
        require(sdkClasspath.isFile) { "The Android SDK command-line tools are incomplete. Reinstall them and try again." }
        val helper = File(SdkImageInstaller::class.java.protectionDomain.codeSource.location.toURI())
        val java = File(System.getProperty("java.home"), "bin/${HostPlatform().executable("java")}")
        command(java, listOf("-cp", helper.path, SdkImageInstaller::class.java.name, toolchain.root.path,
            image.path, archive.uri, archive.file.path, sdkClasspath.path), cancelled,
            input = "y\n".repeat(32), environment = mapOf("TERM" to "xterm"), output = output)
    }

    fun installImage(image: SystemImage, cancelled: AtomicBoolean, output: (String) -> Unit) {
        if (!image.installed) toolchain.requireTools("sdkmanager")
        if (cancelled.get()) throw CancellationException("Download cancelled.")
        val images = File(toolchain.root, "system-images")
        require(!Files.isSymbolicLink(images.toPath()) || images.isDirectory) { "System image storage points to an unavailable drive. Connect it or select an SDK with local storage in Settings." }
        if (!images.exists()) check(images.mkdir()) { "Could not create system image storage at ${images.path}." }
        require(images.isDirectory && images.canWrite()) { "System image storage is not writable: ${images.path}" }
        if (!image.installed) {
            output("Installing ${image.description}\n")
            // sdkmanager suppresses progress when launched with TERM=dumb (including Gradle).
            command(toolchain.sdkmanager, listOf("--install", image.path), cancelled, input = "y\n".repeat(32), environment = mapOf("TERM" to "xterm"), output = output)
        } else output("Using installed Android image. No download needed.\n")
    }

    fun create(request: CreateSpec, cancelled: AtomicBoolean, output: (String) -> Unit): CreateSpec {
        var spec = request
        require(!unsupportedPlatform(mapOf("hw.device.name" to spec.profile, "tag.id" to spec.image.tag))) {
            "This emulator platform is not supported."
        }
        toolchain.requireTools("avdmanager")
        if (spec.hardware == null) validateName(spec.name)
        else require(creationNameError(spec.displayName) == null) { "Enter a valid emulator name." }
        spec.hardware?.let { hardware ->
            require(spec.profile == hardware.id && spec.ram == hardware.ramMiB && spec.storage == hardware.storageGiB && spec.sdCard == null && spec.frame == (hardware.frame != null)) { "Built-in emulator settings are fixed by the profile." }
            require(spec.image in creationImages(hardware.type, listOf(spec.image))) { "This emulator needs a compatible host-native system image." }
        }
        if (avdRoot.path == defaultAvdHome() && !avdRoot.exists() && avdRoot.toPath().startsWith(userHome.toPath())) avdRoot.mkdirs()
        requireAvdStorage()
        installImage(spec.image, cancelled, output)
        if (cancelled.get()) throw CancellationException("Creation cancelled.")
        // Resolve immediately before creation: the UI's device list can be stale,
        // and a long image download may outlive changes made by another SDK tool.
        if (spec.hardware != null) spec = resolveCreationName(spec)
        validateName(spec.name)
        val args = mutableListOf("create", "avd", "-n", spec.name, "-k", spec.image.path, "-p", File(avdRoot, "${spec.name}.avd").path)
        // Bundled profiles are self-contained and need no matching SDK device definition.
        if (spec.hardware == null) args += listOf("-d", spec.profile)
        spec.sdCard?.let { args += listOf("-c", "${it * 1024}M") }
        command(toolchain.avdmanager, args, cancelled, input = "no\n", output = output)
        val iniFile = File(avdRoot, "${spec.name}.ini")
        val dir = readIni(iniFile)["path"]?.let(::File) ?: File(avdRoot, "${spec.name}.avd")
        val config = readIni(File(dir, "config.ini")).toMutableMap()
        config["avd.ini.displayname"] = spec.displayName
        config["disk.dataPartition.size"] = "${spec.storage}G"
        config["showDeviceFrame"] = if (spec.frame) "yes" else "no"
        config["hw.gpu.enabled"] = "yes"
        config["hw.gpu.mode"] = GpuMode
        config["hw.keyboard"] = "yes"
        spec.ram?.let { config["hw.ramSize"] = it.toString() }
        val skin = File(toolchain.root, "skins/${spec.profile}")
        if (spec.frame && skin.isDirectory) { config["skin.name"] = spec.profile; config["skin.path"] = skin.path; config["skin.dynamic"] = "yes" }
        spec.hardware?.let { profile ->
            config.putAll(mapOf(
                "robomori.profile" to profile.id,
                "hw.device.name" to profile.id.replace('-', '_'),
                "hw.device.manufacturer" to "Google",
                "hw.lcd.width" to profile.display.width.toString(),
                "hw.lcd.height" to profile.display.height.toString(),
                "hw.lcd.density" to profile.display.density.toString(),
                "hw.initialOrientation" to if (profile.display.width > profile.display.height) "landscape" else "portrait",
                "hw.mainKeys" to "no",
                "hw.dPad" to "no",
                "hw.trackBall" to "no",
                "hw.gps" to "yes",
                "hw.accelerometer" to "yes",
                "hw.sensors.orientation" to "yes",
                "hw.camera.back" to "virtualscene",
                "hw.camera.front" to "emulated",
                // Some platform images include the Play Store even without a Play Store tag.
                "PlayStore.enabled" to (spec.image.services == "Google Play" || profile.type == DeviceType.WEAR ||
                    profile.type == DeviceType.TV).toString(),
            ))
            profile.installFrame(dir)?.let { bundledSkin ->
                config.putAll(mapOf("skin.name" to bundledSkin.name, "skin.path" to bundledSkin.path, "skin.dynamic" to "yes"))
            }
            // Do not inherit a generic profile's identity hash.
            config.remove("hw.device.hash2")
            profile.folded?.let { folded ->
                config[disableLockScreenOnFirstBoot] = "yes"
                // One hinge down the middle of the inner screen; closing it shows the cover screen region.
                config.putAll(mapOf(
                    "hw.sensor.hinge" to "yes",
                    "hw.sensor.hinge.count" to "1",
                    "hw.sensor.hinge.type" to "1",
                    "hw.sensor.hinge.sub_type" to "1",
                    "hw.sensor.hinge.ranges" to "0-180",
                    "hw.sensor.hinge.defaults" to "180",
                    "hw.sensor.hinge.areas" to "${profile.display.width / 2}-0-0-${profile.display.height}",
                    "hw.sensor.hinge.fold_to_displayRegion.0.1_at_posture" to "1",
                    "hw.sensor.posture_list" to "1, 2, 3",
                    "hw.sensor.hinge_angles_posture_definitions" to "0-30, 30-150, 150-180",
                    "hw.displayRegion.0.1.xOffset" to "0",
                    "hw.displayRegion.0.1.yOffset" to "0",
                    "hw.displayRegion.0.1.width" to folded.width.toString(),
                    "hw.displayRegion.0.1.height" to folded.height.toString(),
                ))
            }
            if (profile.type == DeviceType.TV) {
                // TVs are driven by a remote: no touchscreen, motion sensors or cameras.
                config.putAll(mapOf(
                    "hw.screen" to "no-touch",
                    "hw.dPad" to "yes",
                    "hw.accelerometer" to "no",
                    "hw.sensors.orientation" to "no",
                    "hw.gps" to "no",
                    "hw.camera.back" to "none",
                    "hw.camera.front" to "none",
                ))
            }
            if (profile.type == DeviceType.WEAR) {
                config.putAll(mapOf(
                    "hw.lcd.circular" to "true",
                    "hw.rotaryInput" to "yes",
                    "hw.camera.back" to "none",
                    "hw.camera.front" to "none",
                ))
                // Wear OS reads the round screen shape from this boot property.
                File(dir, "boot.prop").writeText("ro.emulator.circular=true\n")
            }
        }
        if (spec.profile.startsWith("tv_") || spec.profile.contains("landscape")) config["hw.initialOrientation"] = "landscape"
        if (spec.image.tag.contains("tv")) config[enableWifiOnFirstBoot] = "yes"
        writeIni(File(dir, "config.ini"), config)
        return spec
    }
    fun delete(device: Device) { toolchain.requireTools("avdmanager"); requireAvdStorage(); command(toolchain.avdmanager, listOf("delete", "avd", "-n", device.name)) }
    fun duplicate(device: Device): String {
        require(!unsupportedPlatform(device.config)) { "This emulator platform is not supported." }
        requireAvdStorage(); require(!device.missingStorage) { "Connect the emulator's drive before duplicating." }
        var name = device.name + "_Copy"; var n = 2
        while (File(avdRoot, "$name.ini").exists() || File(avdRoot, "$name.avd").exists()) name = device.name + "_Copy_${n++}"
        validateName(name)
        val destination = File(avdRoot, "$name.avd")
        try {
            device.directory.walkTopDown().forEach { file ->
                require(!Files.isSymbolicLink(file.toPath())) { "This emulator contains linked storage. Copy it to a regular folder before duplicating." }
                val relative = file.relativeTo(device.directory).path
                if (file.isDirectory) File(destination, relative).mkdirs() else Files.copy(file.toPath(), File(destination, relative).toPath())
            }
            normalize(destination, device.directory.path, name)
            val config = File(destination, "config.ini")
            writeIni(config, readIni(config) + mapOf("hw.gpu.enabled" to "yes", "hw.gpu.mode" to GpuMode, "hw.keyboard" to "yes"))
            writeIni(File(avdRoot, "$name.ini"), readIni(File(avdRoot, "${device.name}.ini")).minus("path.rel") + ("path" to destination.path))
            return name
        } catch (e: Exception) { destination.deleteRecursively(); File(avdRoot, "$name.ini").delete(); throw e }
    }
    fun rename(device: Device, name: String) {
        requireAvdStorage(); validateName(name)
        val destination = File(avdRoot, "$name.avd")
        val oldIni = File(avdRoot, "${device.name}.ini")
        val newIni = File(avdRoot, "$name.ini")
        val original = oldIni.readText()
        Files.move(device.directory.toPath(), destination.toPath())
        try {
            normalize(destination, device.directory.path, name)
            writeIni(newIni, readIni(oldIni).minus("path.rel") + ("path" to destination.path))
            check(oldIni.delete()) { "Could not remove the old emulator definition." }
        } catch (e: Exception) {
            if (destination.exists()) { normalize(destination, destination.path, device.name, device.directory.path); Files.move(destination.toPath(), device.directory.toPath()) }
            oldIni.writeText(original); newIni.delete(); throw e
        }
    }
    private fun normalize(directory: File, oldPath: String, name: String, targetPath: String = directory.path) {
        directory.walkTopDown().filter { it.isFile && !Files.isSymbolicLink(it.toPath()) && it.extension in listOf("ini", "txt", "conf") }.forEach {
            it.writeText(it.readText().replace(oldPath, targetPath))
        }
        val config = File(directory, "config.ini")
        writeIni(config, readIni(config) + ("avd.ini.displayname" to name) + ("AvdId" to name))
        listOf("hardware-qemu.ini", "multiinstance.lock", "read-snapshot.txt", "emu-launch-params.txt", "tmpAdbCmds").forEach { File(directory, it).deleteRecursively() }
    }
    fun diagnostics() = buildString {
        appendLine("$APP_NAME $APP_DISPLAY_VERSION · Kotlin ${KotlinVersion.CURRENT}")
        appendLine("${System.getProperty("os.name")} ${System.getProperty("os.version")} · ${System.getProperty("os.arch")}")
        appendLine("SDK: ${toolchain.root}\nAVDs: $avdRoot")
        toolchain.tools.forEach { (name, file) -> appendLine("$name: ${if(file.canExecute()) "available" else "missing"} ($file)") }
        appendLine("System images: ${File(toolchain.root, "system-images").canonicalPath}")
        appendLine("Emulators: ${devices().joinToString { it.name }}")
    }
}

internal fun inputDisplayRotation(dump: String): Int? =
    Regex("Viewport INTERNAL: displayId=0,.*?orientation=([0-3])(?:,|\\s)")
        .find(dump)?.groupValues?.get(1)?.toInt()
        ?: Regex("SurfaceOrientation: ([0-3])").find(dump)?.groupValues?.get(1)?.toInt()
fun formatSize(bytes: Long): String = when { bytes > 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0); bytes > 1_000_000 -> "%.0f MB".format(bytes / 1_000_000.0); else -> "${bytes / 1000} KB" }

/** The graphics new emulators start with: the computer's GPU. */
internal val GpuMode = EmulatorGraphics.Hardware.gpuMode

/**
 * How emulators draw. [Hardware] uses the computer's GPU and is much faster. [Software] draws on the CPU: slow, but it
 * avoids an Android emulator bug where WebViews crash on Android 17 and newer on a Mac, whose GPU path stops at OpenGL ES 3.0.
 */
enum class EmulatorGraphics(val gpuMode: String) {
    Hardware("host"), Software("swiftshader_indirect");
}

/** Chromium's fatal line when a WebView can't run on the emulator's hardware graphics, as on Android 17 on a Mac. */
internal fun isWebViewGraphicsCrash(line: String): Boolean =
    "gl_version_info" in line || "Chrome runs only on top of OpenGL ES" in line

/** Reads and saves the graphics emulators start with. Hardware unless Software was chosen. */
class EmulatorGraphicsStore(private val file: File = File(HostPlatform().configDirectory, "emulator-graphics.properties")) {
    fun load(): EmulatorGraphics = runCatching {
        val value = Properties().apply { if (file.isFile) file.inputStream().use(::load) }.getProperty("graphics")
        EmulatorGraphics.entries.firstOrNull { it.name == value }
    }.getOrNull() ?: EmulatorGraphics.Hardware

    fun save(graphics: EmulatorGraphics) {
        file.parentFile.mkdirs()
        val temp = File.createTempFile("emulator-graphics-", ".tmp", file.parentFile)
        try {
            temp.outputStream().use { Properties().apply { setProperty("graphics", graphics.name) }.store(it, null) }
            Files.move(temp.toPath(), file.toPath(), REPLACE_EXISTING, ATOMIC_MOVE)
        } finally { temp.delete() }
    }
}
