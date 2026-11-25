package com.robomori

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.*

class DeviceAgentTest {
    @TempDir lateinit var root: File

    /** Stands in for the agent on the device: answers version with [version] and echoes other commands. */
    private inner class FakeAgent(var version: String) : AutoCloseable {
        val server = ServerSocket(0)
        val requests = mutableListOf<List<String>>()
        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    thread(isDaemon = true) { serve(socket) }
                }
            }
        }
        private fun serve(socket: Socket) = socket.use {
            val input = DataInputStream(it.getInputStream()); val output = DataOutputStream(it.getOutputStream())
            while (true) {
                val words = runCatching { ByteArray(input.readInt()).also(input::readFully).decodeToString().split("\u0000") }.getOrNull() ?: break
                synchronized(requests) { requests += words }
                val (status, answer) = when (words.first()) {
                    "version" -> 0 to version
                    "tap" -> if (words[1] == "@9") 1 to "No @9 on the screen last shown." else 0 to "tapped ${words[1]}"
                    else -> 0 to words.joinToString(" ")
                }
                output.writeByte(status); output.writeInt(answer.length); output.write(answer.encodeToByteArray()); output.flush()
            }
        }
        fun connect() = Socket("127.0.0.1", server.localPort)
        override fun close() = server.close()
    }

    private val agents = mutableListOf<FakeAgent>()
    @AfterEach fun closeAgents() = agents.forEach { it.close() }
    private fun fakeAgent(version: String) = FakeAgent(version).also { agents += it }
    private val jar by lazy { DeviceHelperJar(File(root, "helper.jar").apply { writeText("dex") }, "v2") }

    @Test fun `an agent already running at this version is used as is`() {
        val running = fakeAgent("v2")
        val adb = mutableListOf<List<String>>()
        val agent = AdbDeviceAgent("emulator-5554", adb = { adb += it; "" }, connect = running::connect, helper = { jar })
        assertEquals("tapped Sign in", agent.text("tap", "Sign in"))
        assertEquals("ui diff", agent.text("ui", "diff"), "Commands share one connection")
        assertEquals(emptyList(), adb, "Nothing is pushed or started")
        assertEquals(listOf(listOf("version"), listOf("tap", "Sign in"), listOf("ui", "diff")), running.requests)
    }

    @Test fun `the agent is pushed and started when none is running`() {
        val started = fakeAgent("v2")
        val adb = mutableListOf<List<String>>()
        val agent = AdbDeviceAgent("emulator-5554", adb = { adb += it; "" }, connect = {
            if (adb.any { it.first() == "shell" }) started.connect() else null
        }, helper = { jar }, pause = {})
        assertEquals("tapped 540,1200", agent.text("tap", "540,1200"))
        assertEquals(listOf("push", jar.file.path, "/data/local/tmp/robomori-agent.jar"), adb[0])
        assertContains(adb[1].last(), "app_process / com.robomori.device.Agent v2")
    }

    @Test fun `an agent from another version is told to quit and replaced`() {
        val old = fakeAgent("v1")
        val new = fakeAgent("v2")
        val adb = mutableListOf<List<String>>()
        val agent = AdbDeviceAgent("emulator-5554", adb = { adb += it; "" }, connect = {
            if (adb.isEmpty()) old.connect() else new.connect()
        }, helper = { jar }, pause = {})
        agent.text("ui")
        assertEquals(listOf(listOf("version"), listOf("quit")), old.requests)
        assertEquals(listOf("version"), new.requests.first())
    }

    @Test fun `a command the agent couldn't carry out fails with its reason, and the connection stays usable`() {
        val running = fakeAgent("v2")
        val agent = AdbDeviceAgent("emulator-5554", adb = { "" }, connect = running::connect, helper = { jar })
        assertEquals("No @9 on the screen last shown.", assertFailsWith<DeviceAgentException> { agent.text("tap", "@9") }.message)
        assertEquals("tapped @1", agent.text("tap", "@1"))
    }

    @Test fun `an agent that never starts fails with a reason`() {
        val agent = AdbDeviceAgent("emulator-5554", adb = { "" }, connect = { null }, helper = { jar }, pause = {})
        assertContains(assertFailsWith<IllegalStateException> { agent.text("ui") }.message!!, "couldn't start its agent")
    }
}
