package com.robomori

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.serialization.json.*
import sun.misc.Signal

/** The hidden command a physical device's location simulation runs as, in the background. */
internal const val LOCATION_KEEPER = "location-keeper"

internal fun Cli.simulateLocation(options: Options) {
    options.expect(1..2, values = setOf("device"), what = "action: set <latitude>,<longitude> or clear")
    val action = options.positional.first()
    val device = when (action) {
        "set" -> {
            val value = options.positional.getOrNull(1)
            val coordinates = value?.split(',')?.takeIf { it.size == 2 }?.let { coordinatesFromInput(it[0], it[1]) }
                ?: throw UsageException("location set takes <latitude>,<longitude>, such as 37.4220,-122.0841.")
            target(options).also { setLocation(it, coordinates) }
        }
        "clear" -> {
            options.expect(1..1, values = setOf("device"))
            target(options).also { clearLocation(it) }
        }
        else -> throw UsageException("Unknown action: $action.${suggestion(action, listOf("set", "clear"))}",
            "Use 'robomori device location set <latitude>,<longitude>' or 'robomori device location clear'.")
    }
    val location = options.positional.getOrNull(1)
    if (jsonOutput) printJson(buildJsonObject { put("device", device.name); put("location", location) })
    else if (location != null) out.println(location)
}

/**
 * Sends [device] a GPS position. An emulator keeps it once sent. A physical device needs the position sent again and
 * again, so a background process does that until [clearLocation], replacing any earlier one for the same device.
 */
internal fun Cli.setLocation(device: CliDevice, coordinates: DeviceCoordinates) {
    val serial = device.serial!!
    if (!device.physical) return DeviceLocationService(settings, executor).deliver(serial, coordinates)
    LocationKeepers.stop(serial)
    LocationKeepers.start(serial, coordinates)
}

internal fun Cli.clearLocation(device: CliDevice) {
    val serial = device.serial!!
    if (device.physical) LocationKeepers.stop(serial) else DeviceLocationService(settings, executor).clear(serial)
}

/** Runs a physical device's location simulation until it's stopped: the background half of [Cli.setLocation]. */
internal fun Cli.locationKeeper(args: List<String>) {
    val (serial, latitude, longitude) = args.takeIf { it.size == 3 } ?: throw UsageException("Expected a serial and a location.")
    val files = LocationKeepers.files(serial)
    try {
        val coordinates = checkNotNull(coordinatesFromInput(latitude, longitude)) { "Invalid location." }
        val session = DeviceLocationService(settings, executor).mockSession(serial, coordinates)
        val stopped = CountDownLatch(1)
        // nohup already ignores hangups, so closing the terminal leaves it running.
        listOf("INT", "TERM").forEach { name -> Signal.handle(Signal(name)) { stopped.countDown(); session.stop() } }
        // The device keeps the simulation only while it hears from this process, so a crash restores its real location.
        val heartbeat = thread(isDaemon = true) {
            while (!stopped.await(2, TimeUnit.SECONDS)) runCatching { session.renew() }
        }
        session.run { files.ready.writeText("ready") }
        stopped.countDown()
        heartbeat.join(3000)
    } catch (e: Exception) {
        // Once the location is set, an error here means it was stopped on purpose, which cancels the device's script.
        if (files.ready.exists()) return
        files.error.writeText(e.message ?: "Could not simulate this device's location.")
        throw e
    } finally {
        files.pid.delete()
        files.ready.delete()
    }
}

/** The background processes simulating physical devices' locations, one per device, tracked by files in Robomori's settings folder. */
internal object LocationKeepers {
    class Files(val pid: File, val ready: File, val error: File)

    private val folder get() = File(HostPlatform().configDirectory, "locations")

    fun files(serial: String): Files {
        val name = serial.replace(Regex("[^A-Za-z0-9._-]"), "_")
        folder.mkdirs()
        return Files(File(folder, "$name.pid"), File(folder, "$name.ready"), File(folder, "$name.error"))
    }

    fun start(serial: String, coordinates: DeviceCoordinates) {
        val files = files(serial)
        files.error.delete(); files.ready.delete()
        val process = ProcessBuilder(keeperCommand(serial, coordinates))
            .redirectInput(ProcessBuilder.Redirect.from(File(if (HostOs.detect() == HostOs.WINDOWS) "NUL" else "/dev/null")))
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        files.pid.writeText(process.pid().toString())
        val deadline = System.currentTimeMillis() + 30_000
        while (!files.ready.exists()) {
            if (!process.isAlive) {
                files.pid.delete()
                error(files.error.takeIf { it.exists() }?.readText()?.trim()?.ifBlank { null } ?: "Could not simulate this device's location.")
            }
            if (System.currentTimeMillis() > deadline) {
                process.destroy()
                error("The device didn't start simulating its location in time.")
            }
            Thread.sleep(200)
        }
    }

    fun stop(serial: String) {
        val files = files(serial)
        val pid = files.pid.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull()
        ProcessHandle.of(pid ?: return).filter { it.isAlive }.ifPresent { process ->
            process.destroy()
            runCatching { process.onExit().get(15, TimeUnit.SECONDS) }
        }
        files.pid.delete(); files.ready.delete()
    }

    /**
     * This same command, run as the hidden keeper. The installed app runs its launcher; a development run repeats its
     * Java command up to the cli argument. Off Windows it runs under nohup, so closing the terminal doesn't stop it.
     */
    private fun keeperCommand(serial: String, coordinates: DeviceCoordinates): List<String> {
        val info = ProcessHandle.current().info()
        val arguments = info.arguments().orElse(emptyArray()).toList()
        val prefix = arguments.indexOf(CLI_ARGUMENT).let { if (it < 0) emptyList() else arguments.take(it) }
        val command = listOf(info.command().orElseThrow { IllegalStateException("Could not start the location simulation.") }) +
            prefix + listOf(CLI_ARGUMENT, LOCATION_KEEPER, serial, coordinates.latitude.toString(), coordinates.longitude.toString())
        return if (HostOs.detect() == HostOs.WINDOWS) command else listOf("/usr/bin/nohup") + command
    }
}
