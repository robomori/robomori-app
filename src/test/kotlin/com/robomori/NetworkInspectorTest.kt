package com.robomori

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.GZIPOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetworkInspectorTest {
    @TempDir lateinit var directory: File

    @Test fun `reads each app's user ID`() {
        val output = "package:com.example.pantry uid:10123\npackage:com.android.shell uid:2000\npackage:com.shared.b uid:1000,10200\n"
        assertEquals(mapOf(10123 to "com.example.pantry", 2000 to "com.android.shell", 1000 to "com.shared.b"), parsePackageUids(output))
    }

    @Test fun `copies a request as a curl command`() {
        val exchange = exchange("POST", "https://api.example.com/items?q=it's",
            listOf("Host" to "api.example.com", "Content-Type" to "application/json", "Accept-Encoding" to "gzip"),
            """{"name":"it's"}""".toByteArray())

        assertEquals("""
            curl -X 'POST' 'https://api.example.com/items?q=it'\''s' \
              -H 'Content-Type: application/json' \
              -H 'Accept-Encoding: gzip' \
              --compressed \
              --data-binary '{"name":"it'\''s"}'
        """.trimIndent(), exchange.toCurl())
        assertEquals("curl 'https://example.com/'", exchange("GET", "https://example.com/", emptyList(), ByteArray(0)).toCurl())
    }

    @Test fun `shows a gzipped JSON body laid out`() {
        val zipped = ByteArrayOutputStream().also { GZIPOutputStream(it).use { gzip -> gzip.write("""{"a":[1,{"b":true}]}""".toByteArray()) } }.toByteArray()
        val body = CapturedBody(zipped, zipped.size.toLong())

        val text = body.text(listOf("Content-Encoding" to "gzip"))

        assertEquals("{\n    \"a\": [\n        1,\n        {\n            \"b\": true\n        }\n    ]\n}", prettyBody(text!!))
        assertEquals("not json {", prettyBody("not json {"))
        assertNull(CapturedBody(byteArrayOf(0, 1, 2, 3, -1), 5).text(emptyList()))
        assertNull(body.text(listOf("Content-Encoding" to "br")))
    }

    @Test fun `reads Brotli and zstd bodies as text`() {
        // "hello" as Brotli stores it uncompressed.
        val brotli = byteArrayOf(0x0b, 0x02, 0x80.toByte(), 0x68, 0x65, 0x6c, 0x6c, 0x6f, 0x03)
        assertEquals("hello", CapturedBody(brotli, brotli.size.toLong()).text(listOf("Content-Encoding" to "br")))

        val input = """{"ok":true}""".toByteArray()
        val compressor = io.airlift.compress.zstd.ZstdCompressor()
        val output = ByteArray(compressor.maxCompressedLength(input.size))
        val zstd = output.copyOf(compressor.compress(input, 0, input.size, output, 0, output.size))
        assertEquals("""{"ok":true}""", CapturedBody(zstd, zstd.size.toLong()).text(listOf("Content-Encoding" to "zstd")))
    }

    @Test fun `names the certificate file by its subject hash`() {
        val authority = NetworkCertificateAuthority(directory)
        assertTrue(Regex("[0-9a-f]{8}\\.0").matches(authority.systemFileName))
        // Kept, so devices that trust it go on trusting it.
        assertEquals(authority.certificate, NetworkCertificateAuthority(directory).certificate)
    }

    @Test fun `says why a connection failed in plain words, keeping Robomori's own reasons`() {
        assertEquals("The server refused the connection.", java.net.ConnectException("Connection refused").plainMessage)
        assertEquals("The server's name couldn't be found. Check the address, and that this computer is online.",
            java.net.UnknownHostException("api.example.com").plainMessage)
        assertEquals("The server took too long to answer.", java.net.SocketTimeoutException("Read timed out").plainMessage)
        assertEquals("The server closed the connection.", java.net.SocketException("Connection reset").plainMessage)
        assertEquals("The server closed the connection without answering.",
            java.io.IOException("The server closed the connection without answering.").plainMessage)
        assertEquals("The connection to the server failed.", java.io.EOFException("SSL peer shut down incorrectly").plainMessage)
    }

    @Test fun `won't record a device another Robomori is recording, unless what it left behind is gone`() = runBlocking {
        java.net.ServerSocket(0).use { elsewhere ->
            val listening = rootedInspector(CopyOnWriteArrayList(), FakeVpnHelper()) { line ->
                if (line == "reverse --list") "emulator-5554 tcp:18765 tcp:${elsewhere.localPort}\n" else null
            }
            listening.start()
            while (listening.routing == NetworkRouting.Starting) delay(10)
            assertEquals("Robomori's command-line tool is recording this device's traffic. Stop it, then try again.",
                (listening.routing as NetworkRouting.Failed).cause.message)
        }
        val closedPort = java.net.ServerSocket(0).use { it.localPort }
        val stale = rootedInspector(CopyOnWriteArrayList(), FakeVpnHelper()) { line ->
            if (line == "reverse --list") "emulator-5554 tcp:18765 tcp:$closedPort\n" else null
        }
        stale.start()
        while (stale.routing == NetworkRouting.Starting) delay(10)
        assertEquals(NetworkRouting.On(CertificateTrust.System), stale.routing)
        stale.stop()
    }

    @Test fun `waits to install the VPN helper while the device's package manager is down, as while Android restarts`() = runBlocking {
        val commands = CopyOnWriteArrayList<String>()
        val packageManagerUp = java.util.concurrent.atomic.AtomicBoolean(false)
        val network = rootedInspector(commands, FakeVpnHelper()) { line ->
            if (line == "shell pm path android") if (packageManagerUp.get()) "package:/system/framework/framework-res.apk\n" else "" else null
        }

        network.start()
        delay(300)
        assertEquals(NetworkRouting.Starting, network.routing)
        assertTrue(commands.none { it.startsWith("install") || it.startsWith("shell su") })

        packageManagerUp.set(true)
        while (network.routing == NetworkRouting.Starting) delay(10)
        assertEquals(NetworkRouting.On(CertificateTrust.System), network.routing)
        network.stop()
    }

    @Test fun `installs and starts the VPN helper after installing the certificate, and turns it off on stopping`() = runBlocking {
        val helper = FakeVpnHelper()
        val commands = CopyOnWriteArrayList<String>()
        val network = rootedInspector(commands, helper)

        network.start()
        while (network.routing == NetworkRouting.Starting) delay(10)

        assertEquals(NetworkRouting.On(CertificateTrust.System), network.routing)
        assertTrue("emulator-5554" in NetworkInspectorState.inspecting)
        val port = commands.first { it.startsWith("reverse tcp:") }.substringAfterLast("tcp:")
        assertEquals(listOf("shell getprop sys.boot_completed", "shell pm path android", "reverse --list", "shell su 0 id", "shell su 0 sh -c 'sh /data/local/tmp/robomori-trust.sh'",
            "shell pm path com.robomori.helper 2>/dev/null; true", "install -r -t", "shell appops set com.robomori.helper ACTIVATE_VPN allow",
            "shell pm grant com.robomori.helper android.permission.POST_NOTIFICATIONS", "reverse tcp:18765 tcp:$port",
            "shell am broadcast -f 0x20 -n com.robomori.helper/.Starter --ei port 18765"),
            commands.filterNot { it.startsWith("push") || "chrome-command-line" in it || "base64" in it }
                .map { if (it.startsWith("install")) "install -r -t" else it })

        commands.clear()
        network.stop()
        coroutineContext.job.children.forEach { it.join() }

        assertEquals(NetworkRouting.Off, network.routing)
        assertEquals(listOf("shell am broadcast -f 0x20 -n com.robomori.helper/.Starter --ez stop true", "reverse --remove tcp:18765"), commands)
        assertTrue("emulator-5554" !in NetworkInspectorState.inspecting)
    }

    @Test fun `restarts Chrome when the certificate is newly installed, as it reads trusted certificates only as it starts`() = runBlocking {
        suspend fun chromeScript(trustOutput: String): String {
            val commands = CopyOnWriteArrayList<String>()
            val network = rootedInspector(commands, FakeVpnHelper()) { line ->
                if ("robomori-trust.sh'" in line) trustOutput else null
            }
            network.start()
            while (network.routing == NetworkRouting.Starting) delay(10)
            network.stop()
            coroutineContext.job.children.forEach { it.join() }
            return commands.single { "chrome-command-line" in it }
        }

        assertTrue("-c 'CHANGED=1\n" in chromeScript("ROBOMORI_INSTALLED\nROBOMORI_TRUSTED\n"))
        assertTrue("-c 'CHANGED=\n" in chromeScript("ROBOMORI_TRUSTED\n"), "Already installed, so Chrome running now has it.")
    }

    @Test fun `keeps the VPN helper the device already has when it is the same build`() = runBlocking {
        val helper = FakeVpnHelper()
        val commands = CopyOnWriteArrayList<String>()
        val network = rootedInspector(commands, helper) { line ->
            when (line) {
                "shell pm path com.robomori.helper 2>/dev/null; true" -> "package:/data/app/~~a/com.robomori.helper-b/base.apk\n"
                "shell sha256sum '/data/app/~~a/com.robomori.helper-b/base.apk'" -> "${VpnHelper.sha256}  /data/app/~~a/com.robomori.helper-b/base.apk\n"
                else -> null
            }
        }

        network.start()
        while (network.routing == NetworkRouting.Starting) delay(10)
        network.stop()
        coroutineContext.job.children.forEach { it.join() }

        assertTrue(commands.none { it.startsWith("install") || it.startsWith("uninstall") }, commands.toString())
    }

    @Test fun `replaces a VPN helper signed with another key, or newer than this one`() = runBlocking {
        for (refusal in listOf("INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match",
            "INSTALL_FAILED_VERSION_DOWNGRADE: Update version code 3 is older than current 4")) {
            val helper = FakeVpnHelper()
            val commands = CopyOnWriteArrayList<String>()
            var installs = 0
            val network = rootedInspector(commands, helper) { line ->
                if (!line.startsWith("install -r -t")) null
                else if (installs++ == 0) throw AndroidCommandException("Failure [$refusal]", 1)
                else "Success\n"
            }

            network.start()
            while (network.routing == NetworkRouting.Starting) delay(10)
            network.stop()
            coroutineContext.job.children.forEach { it.join() }

            assertEquals(listOf("install", "uninstall com.robomori.helper", "install"),
                commands.filter { it.startsWith("install") || it.startsWith("uninstall") }.map { if (it.startsWith("install")) "install" else it }, refusal)
        }
    }

    @Test fun `says when the VPN on the device is turned off while recording`() = runBlocking {
        val helper = FakeVpnHelper()
        val network = rootedInspector(CopyOnWriteArrayList(), helper)
        network.start()
        while (network.routing == NetworkRouting.Starting) delay(10)

        helper.turnOff()
        val deadline = System.currentTimeMillis() + 5000
        while (network.routing is NetworkRouting.On && System.currentTimeMillis() < deadline) delay(10)

        assertTrue(network.routing is NetworkRouting.Failed, network.routing.toString())
        assertTrue("emulator-5554" !in NetworkInspectorState.inspecting)
    }

    @Test fun `names the app behind each connection from the user ID the helper finds`() = runBlocking {
        val origin = "10.215.173.1 40000 100.64.0.1 80"
        val helper = FakeVpnHelper(mapOf(origin to 10150))
        val site = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { it.sendResponseHeaders(204, -1); it.close() }
            start()
        }
        val network = rootedInspector(CopyOnWriteArrayList(), helper) { line ->
            if (line == "shell pm list packages -U") "package:com.example.pantry uid:10150\n" else null
        }
        try {
            network.start()
            while (network.routing == NetworkRouting.Starting) delay(10)
            val port = helper.hostPort

            withContext(Dispatchers.IO) {
                socksConnect(port, "127.0.0.1", site.address.port, origin).use { socket ->
                    socket.getOutputStream().write("GET /items HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray())
                    socket.getInputStream().readBytes()
                }
            }
            val deadline = System.currentTimeMillis() + 5000
            while (network.exchanges.none { it.outcome is NetworkOutcome.Answered } && System.currentTimeMillis() < deadline) delay(10)

            val exchange = network.exchanges.single { it.outcome is NetworkOutcome.Answered }
            assertEquals("http://localhost:${site.address.port}/items", exchange.url)
            assertEquals("com.example.pantry", network.appOf(exchange))
        } finally {
            network.stop()
            site.stop(0)
            coroutineContext.job.children.forEach { it.join() }
        }
    }

    @Test fun `clears a proxy setting an earlier version of Robomori left behind`() = runBlocking {
        val commands = CopyOnWriteArrayList<String>()
        val executor = CommandExecutor { command, _, _ ->
            val line = command.args.drop(2).joinToString(" ")
            commands += line
            if (line == "shell settings get global http_proxy") "127.0.0.1:18765\n" else ""
        }
        val network = NetworkInspectorState(NetworkInspectorService(AppSettings("/sdk", "/avd"), executor),
            { NetworkCertificateAuthority(directory) }, "emulator-5554", this)

        network.clearStaleRoute()

        assertEquals(listOf("shell settings get global http_proxy", "shell settings put global http_proxy :0"), commands)
    }

    /** An inspector of a rooted emulator with [helper] on it; [answer] answers other commands first, when it knows them. */
    private fun CoroutineScope.rootedInspector(commands: MutableList<String>, helper: FakeVpnHelper,
                                               answer: (String) -> String? = { null }): NetworkInspectorState {
        val executor = CommandExecutor { command, _, _ ->
            val line = command.args.drop(2).joinToString(" ")
            commands += line
            answer(line) ?: helper.answer(line) ?: when {
                line == "shell getprop sys.boot_completed" -> "1\n"
                line == "shell su 0 id" -> "uid=0(root) gid=0(root)"
                line.startsWith("shell su 0 sh") -> "ROBOMORI_TRUSTED\n"
                else -> ""
            }
        }
        val authority = NetworkCertificateAuthority(directory)
        return NetworkInspectorState(NetworkInspectorService(AppSettings("/sdk", "/avd"), executor), { authority }, "emulator-5554", this)

    }

    @Test fun `finds the processes of installed apps`() {
        val output = """
            PID   UID NAME
              1     0 init
            812  1000 system_server
            4410 10150 com.example.pantry
            4422 10150 com.example.pantry:sync
            4500 1010150 com.example.pantry
            4600 99012 com.android.chrome:sandboxed_process0
        """.trimIndent()
        assertEquals(listOf(AppProcess(4410, 10150, "com.example.pantry"), AppProcess(4422, 10150, "com.example.pantry:sync"),
            AppProcess(4500, 1010150, "com.example.pantry")), parseAppProcesses(output))
    }

    @Test fun `reads the ABI an app's code runs as`() {
        assertEquals("armeabi-v7a", parsePrimaryAbi("    primaryCpuAbi=armeabi-v7a\n    secondaryCpuAbi=null"))
        assertNull(parsePrimaryAbi("    primaryCpuAbi=null"))
        assertNull(parsePrimaryAbi(""))
    }

    @Test fun `loads the agent into each debuggable app when the device can't be rooted`() = runBlocking {
        val helper = FakeVpnHelper()
        val commands = CopyOnWriteArrayList<String>()
        val executor = CommandExecutor { command, _, _ ->
            val line = command.args.drop(2).joinToString(" ")
            commands += line
            helper.answer(line) ?: when {
                line == "shell getprop sys.boot_completed" -> "1\n"
                line == "shell su 0 id" -> "/system/bin/sh: su: inaccessible or not found"
                line == "root" -> "adbd cannot run as root in production builds"
                line == "shell ps -A -o PID,UID,NAME" -> "PID UID NAME\n812 1000 system_server\n4700 10080 com.google.android.gms\n4410 10150 com.example.pantry\n"
                line == "shell pm list packages -U" -> "package:com.example.pantry uid:10150\npackage:com.google.android.gms uid:10080\n"
                line == "shell run-as com.example.pantry id 2>&1" -> "uid=10150(u0_a150) gid=10150(u0_a150)"
                // As adb does, the command fails for an app that isn't debuggable.
                line == "shell run-as com.google.android.gms id 2>&1" -> error("run-as: package not debuggable: com.google.android.gms")
                line.startsWith("shell dumpsys package") -> "    primaryCpuAbi=null"
                line == "shell getprop ro.product.cpu.abi" -> "arm64-v8a\n"
                line.startsWith("shell run-as com.example.pantry sh -c") -> "ROBOMORI_READY\n"
                else -> ""
            }
        }
        val authority = NetworkCertificateAuthority(directory)
        val network = NetworkInspectorState(NetworkInspectorService(AppSettings("/sdk", "/avd"), executor), { authority }, "emulator-5554", this)

        network.start()
        val deadline = System.currentTimeMillis() + 5000
        // Two rounds of looking for new processes, so a second one finds nothing left to do.
        while (commands.count { it == "shell ps -A -o PID,UID,NAME" } < 2 && System.currentTimeMillis() < deadline) kotlinx.coroutines.delay(20)
        network.stop()
        coroutineContext.job.children.forEach { it.join() }

        assertEquals(NetworkRouting.Off, network.routing)
        val folder = "/data/user/0/com.example.pantry/code_cache/robomori-network"
        assertEquals(listOf("shell cmd activity attach-agent 4410 $folder/agent.so=$folder/cacerts"),
            commands.filter { "attach-agent" in it })
        val install = commands.single { it.startsWith("shell run-as com.example.pantry sh -c") }
        assertTrue("cp /data/local/tmp/robomori-network/arm64-v8a.so $folder/agent.so" in install)
        assertTrue("cp /data/local/tmp/robomori-network/${authority.systemFileName} $folder/cacerts/" in install)
        assertTrue(commands.none { it.startsWith("shell run-as com.google.android.gms sh") })
    }

    @Test fun `names a request by the end of its path, as Chrome does`() {
        assertEquals("get?client=okhttp", exchange("GET", "https://httpbin.org/get?client=okhttp", emptyList(), ByteArray(0)).name)
        assertEquals("users", exchange("GET", "https://api.example.com/v1/users/", emptyList(), ByteArray(0)).name)
        assertEquals("example.com", exchange("GET", "https://example.com/", emptyList(), ByteArray(0)).name)
    }

    @Test fun `reads the query string's parameters, decoded and in order`() {
        assertEquals(listOf("q" to "hello world", "page" to "2", "flag" to ""),
            queryParameters("https://example.com/search?q=hello%20world&page=2&flag"))
        assertEquals(emptyList(), queryParameters("https://example.com/"))
    }

    @Test fun `draws a gzipped SVG at its view box's size when it names no size`() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 140 22"><rect width="140" height="22" fill="#0e65c0"/></svg>"""
        val gzipped = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(svg.toByteArray()) } }.toByteArray()
        val body = CapturedBody(gzipped, gzipped.size.toLong()).decoded(listOf("Content-Encoding" to "gzip"))!!

        val image = renderSvg(body)!!

        assertEquals(140f to 22f, image.width to image.height)
        assertEquals(280 to 44, image.bitmap.width to image.bitmap.height, "Drawn at twice its size, for Retina screens.")
    }

    @Test fun `names a response's type from its Content-Type`() {
        fun typeOf(contentType: String) = answered(1, contentType = contentType).type
        assertEquals("json", typeOf("application/json; charset=utf-8"))
        assertEquals("json", typeOf("application/problem+json"))
        assertEquals("document", typeOf("text/html"))
        assertEquals("script", typeOf("application/javascript"))
        assertEquals("stylesheet", typeOf("text/css"))
        assertEquals("png", typeOf("image/png"))
        assertEquals("svg", typeOf("image/svg+xml"))
        assertEquals("protobuf", typeOf("application/x-protobuf"))
        assertEquals("protobuf", typeOf("application/x-protobuffer"))
        assertEquals("protobuf", typeOf("application/vnd.google.protobuf"))
        assertNull(exchange("GET", "https://example.com/", emptyList(), ByteArray(0)).type)
    }

    @Test fun `sorts requests by a column, either way, with the ones lacking a value last`() {
        val slow = answered(1, millis = 900, size = 10)
        val fast = answered(2, millis = 20, size = 5000)
        val pending = exchange("GET", "https://example.com/pending", emptyList(), ByteArray(0)).copy(id = 3)
        val all = listOf(slow, fast, pending)

        assertEquals(listOf(2L, 1L, 3L), all.sortedBy(NetworkSort(NetworkColumn.Duration), { null }).map { it.id })
        assertEquals(listOf(1L, 2L, 3L), all.sortedBy(NetworkSort(NetworkColumn.Duration, ascending = false), { null }).map { it.id })
        assertEquals(listOf(1L, 2L, 3L), all.sortedBy(NetworkSort(NetworkColumn.Size), { null }).map { it.id })
        assertEquals(listOf(2L, 1L, 3L), all.sortedBy(NetworkSort(NetworkColumn.App), { if (it.id == 2L) "a.app" else if (it.id == 1L) "b.app" else null }).map { it.id })
    }

    @Test fun `shows when a request started to the millisecond, in the clock the Mac uses`() {
        val time = Instant.parse("2026-11-23T09:50:47.123Z")
        val athens = java.time.ZoneId.of("Europe/Athens")

        assertEquals("11:50:47.123", formatRequestTime(time, athens, hours24 = true))
        assertEquals("11:50:47.123 AM", formatRequestTime(time, athens, hours24 = false))
        assertEquals("22:50:47.123", formatRequestTime(time, java.time.ZoneId.of("Pacific/Auckland"), hours24 = true))
    }

    @Test fun `numbers requests in the order they were made, and sorts by that number either way`() = runBlocking {
        val network = NetworkInspectorState(NetworkInspectorService(AppSettings("/sdk", "/avd"), { _, _, _ -> "" }),
            { NetworkCertificateAuthority(directory) }, "emulator-5554", this)
        network.exchanges += listOf(answered(-7), answered(40), answered(41))

        assertEquals(mapOf(-7L to 1, 40L to 2, 41L to 3), network.numbers)
        network.sortBy(NetworkColumn.Number)
        assertEquals(listOf(-7L, 40L, 41L), network.visibleExchanges.map { it.id })
        network.sortBy(NetworkColumn.Number)
        assertEquals(listOf(41L, 40L, -7L), network.visibleExchanges.map { it.id })
    }

    @Test fun `sorts requests by the method and the time they started`() {
        val later = answered(1).copy(startedAt = Instant.EPOCH.plusSeconds(5), method = "POST")
        val earlier = answered(2).copy(startedAt = Instant.EPOCH.plusSeconds(1), method = "GET")

        assertEquals(listOf(2L, 1L), listOf(later, earlier).sortedBy(NetworkSort(NetworkColumn.Time), { null }).map { it.id })
        assertEquals(listOf(2L, 1L), listOf(later, earlier).sortedBy(NetworkSort(NetworkColumn.Method), { null }).map { it.id })
    }

    private fun answered(id: Long, contentType: String = "text/plain", millis: Long = 10, size: Long = 0) = NetworkExchange(
        id, 1, Instant.EPOCH, "GET", "https://example.com/$id", emptyList(), CapturedBody.Empty,
        NetworkOutcome.Answered(NetworkResponse(200, "OK", listOf("Content-Type" to contentType), CapturedBody(ByteArray(0), size),
            Instant.EPOCH.plusMillis(millis))))

    private fun exchange(method: String, url: String, headers: List<Pair<String, String>>, body: ByteArray) =
        NetworkExchange(1, 1, Instant.EPOCH, method, url, headers, CapturedBody(body, body.size.toLong()), NetworkOutcome.Pending)
}
