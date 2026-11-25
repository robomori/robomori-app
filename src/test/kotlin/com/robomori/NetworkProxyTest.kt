package com.robomori

import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NetworkProxyTest {
    @TempDir lateinit var directory: File
    private val closing = mutableListOf<AutoCloseable>()
    private val exchanges = CopyOnWriteArrayList<NetworkExchange>()
    /** The app user ID the proxy learned for each connection. */
    private val owners = java.util.concurrent.ConcurrentHashMap<Long, Int>()

    @AfterEach fun close() = closing.asReversed().forEach { it.close() }

    @Test fun `decrypts HTTPS the VPN sends to an address, naming the site from the TLS handshake`() {
        val authority = NetworkCertificateAuthority(File(directory, "a"))
        val siteAuthority = NetworkCertificateAuthority(File(directory, "site"))
        val site = httpsSite(siteAuthority)
        val proxy = proxy(authority, siteAuthority)

        val socket = socksConnect(proxy.port, "127.0.0.1", site.address.port).also(closing::add)
        val tls = trusting(authority).socketFactory.createSocket(socket, "localhost", site.address.port, true) as SSLSocket
        // Java leaves a name without a dot out of the handshake unless told to send it.
        tls.sslParameters = tls.sslParameters.apply { serverNames = listOf(javax.net.ssl.SNIHostName("localhost")) }
        tls.outputStream.write("GET /hello HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray())

        assertTrue(tls.inputStream.readBytes().decodeToString().endsWith("hi"))
        assertEquals("https://localhost:${site.address.port}/hello", answered().single().url)
    }

    @Test fun `records plain HTTP the VPN sends to an address under the name in its Host header`() {
        val site = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/items") { it.sendResponseHeaders(204, -1); it.close() }
            start()
        }
        closing += AutoCloseable { site.stop(0) }
        val proxy = proxy(NetworkCertificateAuthority(File(directory, "a")))

        socksConnect(proxy.port, "127.0.0.1", site.address.port).use { socket ->
            socket.getOutputStream().write("GET /items HTTP/1.1\r\nHost: localhost:${site.address.port}\r\nConnection: close\r\n\r\n".toByteArray())
            assertTrue(socket.getInputStream().readBytes().decodeToString().startsWith("HTTP/1.1 204"))
        }

        assertEquals("http://localhost:${site.address.port}/items", answered().single().url)
    }

    @Test fun `passes through and lists a connection that isn't HTTP`() {
        val echo = java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).also(closing::add)
        Thread.ofVirtual().start { echo.accept().use { it.getInputStream().transferTo(it.getOutputStream()) } }
        val proxy = proxy(NetworkCertificateAuthority(File(directory, "a")))

        socksConnect(proxy.port, "127.0.0.1", echo.localPort).use { socket ->
            socket.getOutputStream().write(byteArrayOf(0, 1, 2, 3))
            socket.shutdownOutput()
            assertEquals(listOf<Byte>(0, 1, 2, 3), socket.getInputStream().readBytes().toList())
        }
        // Listed, unread, so the connection still shows which site the app talked to.
        val deadline = System.currentTimeMillis() + 5000
        while (exchanges.lastOrNull()?.outcome.let { it !is NetworkOutcome.Encrypted || it.finishedAt == null } && System.currentTimeMillis() < deadline) Thread.sleep(10)
        val outcome = exchanges.last().outcome as NetworkOutcome.Encrypted
        assertEquals(4L, outcome.sent)
        assertEquals(4L, outcome.received)
    }

    @Test fun `turns down UDP, so apps using QUIC fall back to TCP`() {
        val proxy = proxy(NetworkCertificateAuthority(File(directory, "a")))
        Socket("127.0.0.1", proxy.port).also(closing::add).apply { soTimeout = 5000 }.let { socket ->
            val output = socket.getOutputStream()
            output.write(byteArrayOf(5, 1, 0))
            output.write(byteArrayOf(5, 3, 0, 1, 0, 0, 0, 0, 0, 0))
            val reply = socket.getInputStream().readNBytes(12)
            assertEquals(listOf<Byte>(5, 0, 5, 7), reply.take(4))
        }
    }

    @Test fun `asks the VPN helper which app opened each connection`() {
        val proxy = proxy(NetworkCertificateAuthority(File(directory, "a")))
        val origin = "10.215.173.1 40000 100.64.0.1 443"
        // The helper's control connection, answering the one question it expects.
        val control = Socket("127.0.0.1", proxy.port).also(closing::add)
        control.getOutputStream().write("\u0001robomori-vpn 1\nready\n".toByteArray())
        proxy.awaitHelper(5000)
        Thread.ofVirtual().start {
            val reader = control.getInputStream().bufferedReader()
            val question = generateSequence { reader.readLine() }.first { it.startsWith("uid ") }
            assertEquals(origin, question.split(' ', limit = 3)[2])
            control.getOutputStream().write("uid ${question.split(' ')[1]} 10150\n".toByteArray())
        }

        socksConnect(proxy.port, "example.com", 443, origin).also(closing::add)

        val deadline = System.nanoTime() + 5_000_000_000
        while (owners.isEmpty() && System.nanoTime() < deadline) Thread.sleep(5)
        assertEquals(mapOf(1L to 10150), owners.toMap())
    }

    @Test fun `says why the VPN helper didn't start`() {
        val proxy = proxy(NetworkCertificateAuthority(File(directory, "a")))
        Socket("127.0.0.1", proxy.port).also(closing::add).getOutputStream()
            .write("\u0001robomori-vpn 1\nfailed Android has not allowed the VPN\n".toByteArray())

        val failure = assertFailsWith<IllegalStateException> { proxy.awaitHelper(5000) }
        assertTrue("Android has not allowed the VPN" in failure.message!!, failure.message)
    }

    @Test fun `reads the server name from a TLS ClientHello`() {
        val engine = SSLContext.getDefault().createSSLEngine("api.example.com", 443).apply { useClientMode = true }
        val hello = java.nio.ByteBuffer.allocate(engine.session.packetBufferSize)
        engine.wrap(java.nio.ByteBuffer.allocate(0), hello)

        assertEquals("api.example.com", clientHelloServerName(hello.array().copyOf(hello.position())))
        assertEquals(null, clientHelloServerName(byteArrayOf(0x16, 3, 1, 0, 5, 1, 2, 3)))
    }

    @Test fun `relays and records a plain HTTP request`() {
        val site = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/items") { exchange ->
                val body = """{"items":[1,2]}""".toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(201, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        closing += AutoCloseable { site.stop(0) }
        val proxy = proxy(NetworkCertificateAuthority(File(directory, "a")))

        val response = client(proxy).send(HttpRequest.newBuilder(URI("http://127.0.0.1:${site.address.port}/items?page=2"))
            .POST(HttpRequest.BodyPublishers.ofString("hello")).build(), HttpResponse.BodyHandlers.ofString())

        assertEquals(201, response.statusCode())
        assertEquals("""{"items":[1,2]}""", response.body())
        val exchange = answered().single()
        assertEquals("POST", exchange.method)
        assertEquals("http://127.0.0.1:${site.address.port}/items?page=2", exchange.url)
        assertEquals("hello", exchange.requestBody.bytes.decodeToString())
        val answer = (exchange.outcome as NetworkOutcome.Answered).response
        assertEquals(201, answer.status)
        assertEquals("""{"items":[1,2]}""", answer.body.bytes.decodeToString())
    }

    @Test fun `decrypts HTTPS for a client that trusts the authority`() {
        val authority = NetworkCertificateAuthority(File(directory, "a"))
        val siteAuthority = NetworkCertificateAuthority(File(directory, "site"))
        val site = httpsSite(siteAuthority)
        val proxy = proxy(authority, siteAuthority)

        val response = client(proxy, trusting(authority)).send(
            HttpRequest.newBuilder(URI("https://localhost:${site.address.port}/hello")).build(), HttpResponse.BodyHandlers.ofString())

        assertEquals("hi", response.body())
        val exchange = answered().single()
        assertEquals("https://localhost:${site.address.port}/hello", exchange.url)
        assertEquals("hi", (exchange.outcome as NetworkOutcome.Answered).response.body.bytes.decodeToString())
    }

    @Test fun `passes a site through unread once a client turns down the authority's certificate`() {
        val authority = NetworkCertificateAuthority(File(directory, "a"))
        val siteAuthority = NetworkCertificateAuthority(File(directory, "site"))
        val site = httpsSite(siteAuthority)
        val proxy = proxy(authority, siteAuthority)
        // Trusts only the site's own certificate, as an app that pins it does.
        val pinned = client(proxy, trusting(siteAuthority))
        val request = HttpRequest.newBuilder(URI("https://localhost:${site.address.port}/hello")).build()

        // Twice, as a connection dropped mid-handshake once looks the same.
        repeat(2) { assertFailsWith<SSLHandshakeException> { pinned.send(request, HttpResponse.BodyHandlers.ofString()) } }
        val response = pinned.send(request, HttpResponse.BodyHandlers.ofString())

        assertEquals("hi", response.body())
        assertEquals(NetworkOutcome.CertificateRejected, exchanges.first().outcome)
        // The connection that passes through is listed by its site, and with what went each way once it closes.
        pinned.close()
        val deadline = System.nanoTime() + 5_000_000_000
        while ((exchanges.last().outcome as? NetworkOutcome.Encrypted)?.finishedAt == null && System.nanoTime() < deadline) Thread.sleep(10)
        val passed = exchanges.last()
        assertEquals("https://localhost:${site.address.port}", passed.url)
        val encrypted = passed.outcome as NetworkOutcome.Encrypted
        assertTrue(encrypted.sent > 0 && encrypted.received > 0, "$encrypted")
        assertEquals(encrypted.received, passed.responseSize)
        assertEquals(2, exchanges.map { it.id }.distinct().size)
    }

    @Test fun `keeps reading a site after a client drops a connection mid-handshake, as Chrome does with spares`() {
        val authority = NetworkCertificateAuthority(File(directory, "a"))
        val siteAuthority = NetworkCertificateAuthority(File(directory, "site"))
        val site = httpsSite(siteAuthority)
        val proxy = proxy(authority, siteAuthority)
        Socket("127.0.0.1", proxy.port).use { socket ->
            socket.getOutputStream().write("CONNECT localhost:${site.address.port} HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
            val reply = socket.getInputStream().bufferedReader()
            while (reply.readLine().isNotEmpty()) Unit
            // Sends its greeting, then hangs up before the handshake ends.
            val engine = trusting(authority).createSSLEngine("localhost", site.address.port).apply { useClientMode = true; beginHandshake() }
            val hello = java.nio.ByteBuffer.allocate(engine.session.packetBufferSize)
            engine.wrap(java.nio.ByteBuffer.allocate(0), hello)
            socket.getOutputStream().write(hello.array(), 0, hello.position())
        }
        Thread.sleep(200)

        val response = client(proxy, trusting(authority)).send(
            HttpRequest.newBuilder(URI("https://localhost:${site.address.port}/hello")).build(), HttpResponse.BodyHandlers.ofString())

        assertEquals("hi", response.body())
        assertEquals(1, answered().size, "Read, not passed through.")
        assertTrue(exchanges.none { it.outcome == NetworkOutcome.CertificateRejected }, "$exchanges")
    }

    @Test fun `reads the site a TLS greeting names`() {
        val server = java.net.ServerSocket(0).also(closing::add)
        val client = Thread {
            runCatching {
                val socket = SSLContext.getDefault().socketFactory.createSocket("127.0.0.1", server.localPort) as SSLSocket
                socket.sslParameters = socket.sslParameters.apply { serverNames = listOf(javax.net.ssl.SNIHostName("api.example.com")) }
                socket.use { it.startHandshake() }
            }
        }.apply { start() }
        val hello = server.accept().use { accepted ->
            val buffer = ByteArray(16 * 1024)
            var read = 0
            accepted.soTimeout = 2000
            // The record's length is in its header; read until the whole greeting is in.
            while (read < 5 || read < 5 + ((buffer[3].toInt() and 0xff) shl 8 or (buffer[4].toInt() and 0xff)))
                read += accepted.getInputStream().read(buffer, read, buffer.size - read).also { check(it > 0) }
            buffer.copyOf(read)
        }
        client.join(2000)

        assertEquals("api.example.com", clientHelloServerName(hello))
        assertEquals(null, clientHelloServerName("GET / HTTP/1.1".toByteArray()))
    }

    @Test fun `reads a site again once the app trusts the authority, even if a connection from before turns it down`() {
        val authority = NetworkCertificateAuthority(File(directory, "a"))
        val siteAuthority = NetworkCertificateAuthority(File(directory, "site"))
        val site = httpsSite(siteAuthority)
        val proxy = proxy(authority, siteAuthority)
        val request = HttpRequest.newBuilder(URI("https://localhost:${site.address.port}/hello")).build()
        assertFailsWith<SSLHandshakeException> { client(proxy, trusting(siteAuthority)).send(request, HttpResponse.BodyHandlers.ofString()) }
        // A connection the app opened before it trusted Robomori, whose handshake fails only after.
        val early = Socket("127.0.0.1", proxy.port).also(closing::add)
        early.getOutputStream().write("CONNECT localhost:${site.address.port} HTTP/1.1\r\n\r\n".toByteArray())
        early.getInputStream().bufferedReader().readLine()

        proxy.forgetRejections()
        early.getOutputStream().write(byteArrayOf(0x16, 0x03, 0x01, 0x00, 0x05, 1, 2, 3, 4, 5))
        early.getOutputStream().flush()
        early.getInputStream().read()
        val response = client(proxy, trusting(authority)).send(request, HttpResponse.BodyHandlers.ofString())

        assertEquals("hi", response.body())
        assertEquals("https://localhost:${site.address.port}/hello", answered().single().url)
    }

    @Test fun `closes a connection passing through unread when asked, so the app opens one that is read`() {
        val authority = NetworkCertificateAuthority(File(directory, "a"))
        val siteAuthority = NetworkCertificateAuthority(File(directory, "site"))
        val site = httpsSite(siteAuthority)
        val proxy = proxy(authority, siteAuthority)
        fun tunnel() = Socket("127.0.0.1", proxy.port).also(closing::add).apply {
            soTimeout = 5000
            getOutputStream().write("CONNECT localhost:${site.address.port} HTTP/1.1\r\n\r\n".toByteArray())
            getInputStream().bufferedReader().readLine()
        }
        // Connections 1 and 2 turn the certificate down, so the site passes through from now on.
        repeat(2) { tunnel().apply { getOutputStream().write(byteArrayOf(0x16, 0x03, 0x01, 0x00, 0x05, 1, 2, 3, 4, 5)); getInputStream().read() } }
        // Connection 3 passes through to the site.
        val passing = tunnel().apply { getOutputStream().write(byteArrayOf(0x16, 0x03, 0x01)); getOutputStream().flush() }
        Thread.sleep(200)

        proxy.closePassThrough(setOf(3L))

        // Closed: the end of the stream, or a reset; a timeout would mean it's still open.
        val read = runCatching { passing.getInputStream().read() }
        assertTrue(read.getOrNull() == -1 || read.exceptionOrNull() is java.net.SocketException, "still open: $read")
    }

    @Test fun `answers 502 when the site can't be reached`() {
        val proxy = proxy(NetworkCertificateAuthority(File(directory, "a")))
        val closedPort = java.net.ServerSocket(0).use { it.localPort }

        val response = client(proxy).send(HttpRequest.newBuilder(URI("http://127.0.0.1:$closedPort/")).build(),
            HttpResponse.BodyHandlers.ofString())

        assertEquals(502, response.statusCode())
        assertTrue(exchanges.last().outcome is NetworkOutcome.Failed)
    }

    @Test fun `copies a chunked body and keeps its content`() {
        val wire = "5\r\nhello\r\n6; ext=1\r\n world\r\n0\r\nTrailer: x\r\n\r\nNEXT"
        val output = java.io.ByteArrayOutputStream()
        val capture = BodyCapture()
        val input = wire.byteInputStream()

        copyBody(BodyFraming.Chunked, input, output, capture)

        assertEquals(wire.removeSuffix("NEXT"), output.toString())
        assertEquals("hello world", capture.captured.bytes.decodeToString())
        assertEquals("NEXT", input.readBytes().decodeToString())
    }

    @Test fun `keeps only the start of a large body`() {
        val capture = BodyCapture(limit = 4)
        capture.add("abcdefgh".toByteArray(), 0, 8)
        assertEquals("abcd", capture.captured.bytes.decodeToString())
        assertEquals(8, capture.captured.size)
        assertTrue(capture.captured.truncated)
    }

    @Test fun `relays a WebSocket both ways and records its messages as they pass`() {
        val site = webSocketSite()
        val proxy = proxy(NetworkCertificateAuthority(File(directory, "a")))
        val received = LinkedBlockingQueue<Any>()
        val listener = object : WebSocket.Listener {
            private val text = StringBuilder()
            override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                text.append(data)
                if (last) { received.add(text.toString()); text.clear() }
                webSocket.request(1)
                return null
            }
            override fun onBinary(webSocket: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
                received.add(ByteArray(data.remaining()).also(data::get).toList())
                webSocket.request(1)
                return null
            }
            override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
                received.add("closed $statusCode")
                return null
            }
        }
        val socket = client(proxy).newWebSocketBuilder().buildAsync(URI("ws://127.0.0.1:${site.localPort}/chat"), listener).get(5, SECONDS)
        fun next() = received.poll(5, SECONDS)

        socket.sendText("hello", true).get(5, SECONDS)
        assertEquals("echo: hello", next())
        // Listed as open, so its time is still pending.
        val open = exchanges.last { it.webSocket != null }
        assertEquals(101, open.status)
        assertTrue(open.inProgress)
        assertEquals("websocket", open.type)
        socket.sendText("fragments", true).get(5, SECONDS)
        assertEquals("hello", next())
        socket.sendBinary(ByteBuffer.wrap(byteArrayOf(1, 2, 3)), true).get(5, SECONDS)
        assertEquals(listOf<Byte>(1, 2, 3), next())
        socket.sendClose(1000, "bye").get(5, SECONDS)
        assertEquals("closed 1000", next())

        val deadline = System.nanoTime() + 5_000_000_000
        while (exchanges.last().webSocket?.closedAt == null && System.nanoTime() < deadline) Thread.sleep(10)
        val closed = exchanges.last().webSocket!!
        fun WebSocketMessage.content(): Any = text ?: data.bytes.toList()
        assertEquals(listOf(
            Triple(WebSocketDirection.Sent, WebSocketOpcode.Text, "hello"),
            Triple(WebSocketDirection.Received, WebSocketOpcode.Text, "echo: hello"),
            Triple(WebSocketDirection.Sent, WebSocketOpcode.Text, "fragments"),
            // Put together from its fragments, around the ping between them.
            Triple(WebSocketDirection.Received, WebSocketOpcode.Text, "hello"),
            Triple(WebSocketDirection.Sent, WebSocketOpcode.Binary, listOf<Byte>(1, 2, 3)),
            Triple(WebSocketDirection.Received, WebSocketOpcode.Binary, listOf<Byte>(1, 2, 3)),
        ), closed.messages.take(6).map { Triple(it.direction, it.opcode, it.content()) })
        assertEquals(listOf(WebSocketDirection.Sent to 1000, WebSocketDirection.Received to 1000),
            closed.messages.drop(6).map { it.direction to it.closeCode })
        assertEquals(closed.messages.sortedBy { it.at }, closed.messages)
        assertTrue(!exchanges.last().inProgress)
    }

    /**
     * A WebSocket site that answers each text message with "echo: " and it, except "fragments", which it answers with
     * "hello" in two fragments with a ping between them; it sends binary messages back as they are, and closes when asked.
     */
    private fun webSocketSite(): java.net.ServerSocket {
        val server = java.net.ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress()).also(closing::add)
        Thread.ofVirtual().start {
            runCatching {
                server.accept().use { socket ->
                    val input = java.io.BufferedInputStream(socket.getInputStream())
                    val output = socket.getOutputStream()
                    val head = readHead(input)!!
                    val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                        .digest((head.header("Sec-WebSocket-Key") + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
                    output.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
                    while (true) {
                        val (opcode, payload) = readClientFrame(input) ?: break
                        when (opcode) {
                            1 -> if (payload.decodeToString() == "fragments") {
                                output.write(serverFrame(1, "hel".toByteArray(), fin = false))
                                output.write(serverFrame(9, "ping".toByteArray()))
                                output.write(serverFrame(0, "lo".toByteArray()))
                            } else output.write(serverFrame(1, "echo: ".toByteArray() + payload))
                            2 -> output.write(serverFrame(2, payload))
                            8 -> { output.write(serverFrame(8, payload)); output.flush(); break }
                        }
                        output.flush()
                    }
                }
            }
        }
        return server
    }

    /** A frame from the app, unmasked, as its opcode and payload, or null at the end of the stream. */
    private fun readClientFrame(input: java.io.InputStream): Pair<Int, ByteArray>? {
        val first = input.read()
        if (first < 0) return null
        val second = input.read()
        val length = when (val short = second and 0x7F) {
            126 -> (input.read() shl 8) or input.read()
            127 -> (0 until 8).fold(0L) { total, _ -> (total shl 8) or input.read().toLong() }.toInt()
            else -> short
        }
        val mask = if (second and 0x80 != 0) input.readNBytes(4) else null
        val payload = input.readNBytes(length)
        if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        return (first and 0x0F) to payload
    }

    private fun serverFrame(opcode: Int, payload: ByteArray, fin: Boolean = true) =
        byteArrayOf(((if (fin) 0x80 else 0) or opcode).toByte(), payload.size.toByte()) + payload

    /** The answered requests, once the proxy has recorded one: it does so just after the client has the response. */
    private fun answered(): List<NetworkExchange> {
        val deadline = System.nanoTime() + 2_000_000_000
        while (exchanges.none { it.outcome is NetworkOutcome.Answered } && System.nanoTime() < deadline) Thread.sleep(5)
        return exchanges.filter { it.outcome is NetworkOutcome.Answered }
    }

    private fun proxy(authority: NetworkCertificateAuthority, siteAuthority: NetworkCertificateAuthority? = null): NetworkProxy {
        val trust = siteAuthority?.let(::trusting)
        return NetworkProxy(authority, { connection, uid -> owners[connection] = uid }, { exchanges += it }) { host, port, tls ->
            val socket = Socket(host, port)
            // The proxy starts the handshake, to time it.
            if (!tls) socket else trust!!.socketFactory.createSocket(socket, host, port, true)
        }.also(closing::add)
    }

    private fun httpsSite(authority: NetworkCertificateAuthority) = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        httpsConfigurator = HttpsConfigurator(authority.serverContext("localhost"))
        createContext("/hello") { exchange ->
            exchange.sendResponseHeaders(200, 2)
            exchange.responseBody.use { it.write("hi".toByteArray()) }
        }
        start()
        closing += AutoCloseable { stop(0) }
    }

    private fun trusting(authority: NetworkCertificateAuthority): SSLContext {
        val store = KeyStore.getInstance("PKCS12").apply { load(null, null); setCertificateEntry("ca", authority.certificate) }
        val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        return SSLContext.getInstance("TLS").apply { init(null, managers.trustManagers, null) }
    }

    private fun client(proxy: NetworkProxy, ssl: SSLContext? = null) = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .proxy(ProxySelector.of(InetSocketAddress("127.0.0.1", proxy.port)))
        .apply { if (ssl != null) sslContext(ssl) }
        .build().also { closing += it }
}
