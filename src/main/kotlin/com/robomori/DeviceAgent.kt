package com.robomori

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.Socket
import java.nio.file.Files
import java.security.MessageDigest

/**
 * Robomori's agent on a device, which reads its screen as text and operates it for coding agents; see `Agent.java` in
 * the device helper for its commands. A command takes milliseconds, where `uiautomator dump` and `adb shell input`
 * take a second or more.
 */
internal interface DeviceAgent : AutoCloseable {
    /** Sends a command and returns the agent's answer. Throws [DeviceAgentException] with its reason when it failed. */
    fun request(vararg words: String): ByteArray
    fun text(vararg words: String): String = request(*words).decodeToString()
    override fun close() {}
}

/** A command the agent couldn't carry out, such as a tap on something that isn't on the screen. */
internal class DeviceAgentException(message: String) : IllegalStateException(message)

/** The device helper bundled with the app, and its version: the helper's own hash, so every build replaces the last. */
internal class DeviceHelperJar(val file: File, val version: String) {
    /** Where it goes in an app's own folder, relative to it, to run as the app or inside the app's process. */
    val inApp get() = "code_cache/robomori-database-$version.jar"

    companion object {
        val bundled: DeviceHelperJar by lazy {
            val bytes = DeviceHelperJar::class.java.classLoader.getResourceAsStream("device-helper/device-helper.jar")
                ?.use(InputStream::readBytes) ?: error("Robomori's device helper is missing. Reinstall Robomori and try again.")
            val file = Files.createTempFile("robomori-device-helper-", ".jar").toFile().apply { deleteOnExit(); writeBytes(bytes) }
            DeviceHelperJar(file, MessageDigest.getInstance("SHA-256").digest(bytes).take(8).joinToString("") { "%02x".format(it) })
        }
    }
}

/**
 * Reaches the agent through the adb server's own protocol instead of an adb process, so each command costs one round
 * trip. Starts the agent on first use, and replaces one another version of Robomori left running.
 */
internal class AdbDeviceAgent(
    private val serial: String,
    /** Runs adb with these arguments on this device, such as `push`, and returns its output. */
    private val adb: (List<String>) -> String,
    private val connect: () -> Socket? = { adbLocalSocket(serial, AGENT_SOCKET) },
    private val helper: () -> DeviceHelperJar = { DeviceHelperJar.bundled },
    private val pause: (Long) -> Unit = Thread::sleep,
) : DeviceAgent {
    private var connection: Connection? = null

    override fun request(vararg words: String): ByteArray {
        val current = connection ?: open().also { connection = it }
        return try { current.send(words.toList()) } catch (e: IOException) {
            close()
            throw IllegalStateException("Lost the connection to $serial. Check that it's still running, then try again.", e)
        }
    }

    private fun open(): Connection {
        val jar = helper()
        running(jar.version)?.let { return it }
        adb(listOf("push", jar.file.path, REMOTE_JAR))
        adb(listOf("shell", "CLASSPATH=$REMOTE_JAR nohup app_process / com.robomori.device.Agent ${jar.version} >/dev/null 2>&1 &"))
        repeat(60) {
            running(jar.version)?.let { return it }
            pause(50)
        }
        error("Robomori couldn't start its agent on $serial. Check that the device is unlocked and try again.")
    }

    /** The agent already running at [version]; one at another version is told to quit. */
    private fun running(version: String): Connection? {
        val existing = Connection(connect() ?: return null)
        val answer = runCatching { existing.send(listOf("version")).decodeToString() }.getOrNull()
        if (answer == version) return existing
        runCatching { existing.send(listOf("quit")) }
        existing.close()
        return null
    }

    override fun close() {
        connection?.close()
        connection = null
    }

    /** Requests are their words joined by NUL, after their length; answers a status byte, a length and the bytes. */
    private class Connection(private val socket: Socket) : AutoCloseable {
        private val input = DataInputStream(socket.getInputStream().buffered())
        private val output = DataOutputStream(socket.getOutputStream().buffered())

        fun send(words: List<String>): ByteArray {
            // Long enough for the slowest command, so an agent that stopped answering can't hang the caller.
            socket.soTimeout = 60_000 + if (words.first() == "wait") words.getOrNull(2)?.toIntOrNull() ?: 0 else 0
            val request = words.joinToString("\u0000").encodeToByteArray()
            output.writeInt(request.size)
            output.write(request)
            output.flush()
            val failed = input.readByte().toInt() != 0
            val answer = ByteArray(input.readInt().also { if (it !in 0..64 * 1024 * 1024) throw IOException("Invalid answer") })
            input.readFully(answer)
            if (failed) throw DeviceAgentException(answer.decodeToString())
            return answer
        }

        override fun close() { runCatching { socket.close() } }
    }

    companion object {
        const val AGENT_SOCKET = "robomori-agent"
        private const val REMOTE_JAR = "/data/local/tmp/robomori-agent.jar"
    }
}

/** The adb server's port, which ANDROID_ADB_SERVER_PORT moves, as adb itself reads it. */
private fun adbServerPort(): Int = System.getenv("ANDROID_ADB_SERVER_PORT")?.toIntOrNull() ?: 5037

/**
 * A stream to the abstract socket [name] on [serial], through the adb server: what `adb forward` sets up, without a
 * process or a port. Null when the server, the device or the socket isn't there.
 */
internal fun adbLocalSocket(serial: String, name: String, port: Int = adbServerPort()): Socket? {
    val socket = try { Socket("127.0.0.1", port) } catch (_: IOException) { return null }
    return try {
        socket.tcpNoDelay = true
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        for (request in listOf("host:transport:$serial", "localabstract:$name")) {
            output.write("%04x%s".format(request.length, request).encodeToByteArray())
            output.flush()
            val status = input.readNBytes(4).decodeToString()
            if (status != "OKAY") throw IOException("adb: $status")
        }
        socket
    } catch (_: IOException) {
        runCatching { socket.close() }
        null
    }
}
