package com.robomori

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * A device's side of Robomori's VPN helper, for tests: answers the adb commands that set the helper up, and when told
 * to start, connects to the proxy that `adb reverse` points at, says its VPN is up, and answers which app owns each
 * connection from [owners].
 */
internal class FakeVpnHelper(private val owners: Map<String, Int> = emptyMap()) : AutoCloseable {
    /** The proxy's port on this computer, once `adb reverse` points at it. */
    @Volatile var hostPort = 0; private set
    private val controls = CopyOnWriteArrayList<Socket>()

    /** What adb prints for [line], one of the helper's commands, or null for any other command. */
    fun answer(line: String): String? = when {
        line == "shell pm path android" -> "package:/system/framework/framework-res.apk\n"
        line.startsWith("shell pm path ${VpnHelper.packageName}") -> ""
        line.startsWith("install -r -t ") -> "Performing Streamed Install\nSuccess\n"
        line.startsWith("reverse tcp:$NetworkProxyDevicePort tcp:") -> { hostPort = line.substringAfterLast(':').toInt(); "" }
        line == "shell ${VpnHelper.startCommand(NetworkProxyDevicePort)}" -> { connect(); "Broadcast completed: result=0\n" }
        else -> null
    }

    /** Ends the control connection, as the helper does when someone turns its VPN off on the device. */
    fun turnOff() = controls.forEach { it.close() }

    override fun close() = turnOff()

    private fun connect() {
        val socket = Socket(InetAddress.getLoopbackAddress(), hostPort)
        controls += socket
        val output = socket.getOutputStream()
        output.write("\u0001robomori-vpn 1\nready\n".toByteArray())
        thread(isDaemon = true) {
            runCatching {
                socket.getInputStream().bufferedReader().forEachLine { line ->
                    if (!line.startsWith("uid ")) return@forEachLine
                    val (_, id, origin) = line.split(' ', limit = 3)
                    synchronized(output) { output.write("uid $id ${owners[origin] ?: -1}\n".toByteArray()) }
                }
            }
        }
    }
}

/**
 * Opens a connection to [host]:[port] through the proxy at [proxyPort] as the VPN helper does: SOCKS5, with the
 * connection's [origin] on the device as the username. [host] is sent as an address when it is one, else as a name.
 */
internal fun socksConnect(proxyPort: Int, host: String, port: Int, origin: String = "10.215.173.1 40000 100.64.0.1 $port"): Socket {
    val socket = Socket(InetAddress.getLoopbackAddress(), proxyPort).apply { soTimeout = 5000 }
    val output = DataOutputStream(socket.getOutputStream())
    val input = DataInputStream(socket.getInputStream())
    output.write(byteArrayOf(5, 1, 2))
    check(input.readUnsignedShort() == 0x0502) { "The proxy turned down username authentication." }
    output.write(byteArrayOf(1, origin.length.toByte()) + origin.toByteArray() + byteArrayOf(1, '-'.code.toByte()))
    check(input.readUnsignedShort() == 0x0100) { "The proxy turned down the username." }
    val address = if (host.matches(Regex("[0-9.]+"))) byteArrayOf(1) + InetAddress.getByName(host).address
        else byteArrayOf(3, host.length.toByte()) + host.toByteArray()
    output.write(byteArrayOf(5, 1, 0) + address)
    output.writeShort(port)
    val reply = ByteArray(10).also(input::readFully)
    check(reply[1].toInt() == 0) { "The proxy refused the connection: ${reply[1]}" }
    return socket
}
