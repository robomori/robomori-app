package com.robomori

import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.ByteBuffer
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** A body as it went over the wire, decoded from chunks but still in its content encoding, kept up to a limit. */
internal class CapturedBody(val bytes: ByteArray, val size: Long) {
    val truncated get() = size > bytes.size
    override fun equals(other: Any?) = other is CapturedBody && size == other.size && bytes.contentEquals(other.bytes)
    override fun hashCode() = size.hashCode()
    /** The body's text in lower case for the filter to search, worked out once: see [searchableText]. */
    @Volatile internal var searchable: Any? = null
    companion object { val Empty = CapturedBody(ByteArray(0), 0) }
}

internal data class NetworkResponse(val status: Int, val reason: String, val headers: List<Pair<String, String>>,
                                    val body: CapturedBody, val finishedAt: Instant)

/** How a request ended up, besides answered. */
internal sealed interface NetworkOutcome {
    data object Pending : NetworkOutcome
    data class Answered(val response: NetworkResponse) : NetworkOutcome
    /** The app turned down Robomori's certificate for the site, so later connections to it go through unread. */
    data object CertificateRejected : NetworkOutcome
    /**
     * A connection Robomori passed through without reading, as the app doesn't accept its certificate for the site:
     * [sent] and [received] count the encrypted bytes each way, and [finishedAt] is when it closed, or null while open.
     */
    data class Encrypted(val sent: Long, val received: Long, val finishedAt: Instant?) : NetworkOutcome
    data class Failed(val message: String) : NetworkOutcome
}

/**
 * One request an app made and what came back. [connection] identifies the app's connection it came over. [webSocket]
 * holds the messages of a request that became a WebSocket.
 */
internal data class NetworkExchange(
    val id: Long,
    val connection: Long,
    val startedAt: Instant,
    val method: String,
    val url: String,
    val requestHeaders: List<Pair<String, String>>,
    val requestBody: CapturedBody,
    val outcome: NetworkOutcome,
    /** The rule that changed the response, as it was then. */
    val rule: NetworkRule? = null,
    val webSocket: WebSocketSession? = null,
)

/** The failed handshakes in a row after which a site passes through unread. */
private const val RefusalsToPassThrough = 2

/**
 * A proxy on this computer that records every request through it. Its one port takes the device's connections from
 * Robomori's VPN helper as SOCKS5, the helper's own control connection, and HTTP proxy requests. HTTPS is decrypted
 * with certificates from [authority]; sites whose certificate an app turns down, such as ones it pins, are passed
 * through without reading, as is anything that isn't HTTP.
 */
internal class NetworkProxy(
    private val authority: NetworkCertificateAuthority,
    /** Called with the app user ID behind a connection, as the VPN helper names it, before its first request. */
    private val onApp: (connection: Long, uid: Int) -> Unit,
    private val onExchange: (NetworkExchange) -> Unit,
    /** Numbers connections and requests after this, so they don't take the numbers of ones from an earlier proxy. */
    numberAfter: Long = 0,
    /** The rules that change responses, read for each request. */
    private val rules: () -> List<NetworkRule> = { emptyList() },
    /** Connects to a site; with [tls], returns an [SSLSocket] the proxy starts the handshake of. */
    private val connectUpstream: (host: String, port: Int, tls: Boolean) -> Socket = ::connectSite,
) : Closeable {
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val threads: ExecutorService = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("network-proxy-", 0).factory())
    private val connections = AtomicLong(numberAfter)
    private val exchanges = AtomicLong(numberAfter)
    private val open = ConcurrentHashMap.newKeySet<Socket>()
    /** The app connections passing through unread, by connection. */
    private val passing = ConcurrentHashMap<Long, Socket>()
    /** Sites, as host:port, that turned down Robomori's certificate. */
    private val passthrough = ConcurrentHashMap.newKeySet<String>()
    /** How many times in a row each site's handshake failed, until it passes through. */
    private val refusals = ConcurrentHashMap<String, Int>()
    /** The VPN helper's control connection, while it's open. */
    @Volatile private var helper: HelperLink? = null
    /** Completes when the VPN helper says its VPN is up, or fails with why it isn't. */
    private val helperStarted = CompletableFuture<Unit>()
    /** Completes when the VPN helper's control connection closes, which turns its VPN off. */
    private val helperGone = CompletableFuture<Unit>()

    val port: Int get() = server.localPort

    /** The last connection opened before [forgetRejections], whose rejections no longer count. */
    @Volatile private var forgottenThrough = 0L

    /** Reads again the sites apps turned down Robomori's certificate for, as they may trust it now. */
    fun forgetRejections() {
        // A connection already open may still turn the certificate down, from before the app trusted it.
        forgottenThrough = connections.get()
        passthrough.clear()
        refusals.clear()
    }

    /** Waits up to [timeoutMillis] for the VPN helper to say its VPN is up, and throws with the reason when it isn't. */
    fun awaitHelper(timeoutMillis: Long) {
        try {
            helperStarted.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            throw IllegalStateException("Robomori's VPN didn't start on the device. Try again.")
        } catch (failure: ExecutionException) {
            throw IllegalStateException("Robomori's VPN didn't start on the device: ${failure.cause?.message}")
        }
    }

    /** Waits until the VPN helper's VPN is off, as when someone turns it off in the device's settings. */
    fun awaitHelperGone() { helperGone.get() }

    init {
        threads.execute {
            while (!server.isClosed) {
                val client = try { server.accept() } catch (_: IOException) { break }
                track(client)
                threads.execute { client.use { serveClient(it) } }
            }
        }
    }

    override fun close() {
        server.close()
        open.forEach { runCatching { it.close() } }
        threads.shutdownNow()
    }

    private fun track(socket: Socket) { open += socket }

    private fun serveClient(client: Socket) {
        try {
            val input = BufferedInputStream(client.getInputStream())
            input.mark(1)
            val first = input.read()
            input.reset()
            when (first) {
                -1 -> {}
                SOCKS_VERSION -> serveSocks(client, input)
                HELPER_HELLO -> serveHelper(client, input)
                else -> {
                    val connection = connections.incrementAndGet()
                    serve(input, client.getOutputStream(), connection, target = null) { head -> tunnel(client, input, head, connection) }
                }
            }
        } catch (_: IOException) {
        } finally { open -= client }
    }

    /**
     * Answers a SOCKS5 CONNECT from the VPN helper, then reads the connection as [intercept] does. The helper sends the
     * connection's addresses on the device as the username, for the device to say which app opened it.
     */
    private fun serveSocks(client: Socket, input: BufferedInputStream) {
        val data = DataInputStream(input)
        val output = client.getOutputStream()
        fun reply(vararg bytes: Int) { output.write(ByteArray(bytes.size) { bytes[it].toByte() }); output.flush() }
        fun answer(code: Int) = reply(SOCKS_VERSION, code, 0, 1, 0, 0, 0, 0, 0, 0)
        fun text(length: Int) = String(ByteArray(length).also(data::readFully), Charsets.US_ASCII)
        data.readUnsignedByte()
        val methods = ByteArray(data.readUnsignedByte()).also(data::readFully)
        var origin: String? = null
        when {
            SOCKS_USERNAME in methods -> {
                reply(SOCKS_VERSION, SOCKS_USERNAME.toInt())
                data.readUnsignedByte()
                origin = text(data.readUnsignedByte())
                data.skipNBytes(data.readUnsignedByte().toLong())
                reply(1, 0)
            }
            SOCKS_NO_AUTHENTICATION in methods -> reply(SOCKS_VERSION, SOCKS_NO_AUTHENTICATION.toInt())
            else -> return reply(SOCKS_VERSION, 0xFF)
        }
        data.readUnsignedByte()
        val command = data.readUnsignedByte()
        data.readUnsignedByte()
        val (host, named) = when (data.readUnsignedByte()) {
            1 -> InetAddress.getByAddress(ByteArray(4).also(data::readFully)).hostAddress to false
            3 -> text(data.readUnsignedByte()) to true
            4 -> InetAddress.getByAddress(ByteArray(16).also(data::readFully)).hostAddress to false
            else -> return answer(SOCKS_ADDRESS_NOT_SUPPORTED)
        }
        val port = data.readUnsignedShort()
        // UDP isn't carried, so apps using QUIC, such as Chrome, fall back to HTTPS over TCP, which Robomori reads.
        if (command != SOCKS_CONNECT) return answer(SOCKS_COMMAND_NOT_SUPPORTED)
        answer(0)
        val connection = connections.incrementAndGet()
        origin?.let { helper?.uidOf(it) }?.let { onApp(connection, it) }
        intercept(client, input, host, port, connection, named)
    }

    /**
     * Keeps the VPN helper's control connection: answers which app owns each connection through it, and pings so the
     * helper notices when Robomori is gone and turns its VPN off.
     */
    private fun serveHelper(client: Socket, input: BufferedInputStream) {
        val reader = input.bufferedReader(Charsets.US_ASCII)
        reader.readLine()
        val link = HelperLink(client)
        helper = link
        val pings = threads.submit {
            while (true) {
                Thread.sleep(HELPER_PING_MILLIS)
                link.send("ping")
            }
        }
        try {
            while (true) {
                val line = reader.readLine() ?: break
                val words = line.split(' ')
                when (words[0]) {
                    "ready" -> helperStarted.complete(Unit)
                    "failed" -> helperStarted.completeExceptionally(IllegalStateException(line.substringAfter(' ')))
                    "uid" -> link.answer(words.getOrNull(1)?.toLongOrNull(), words.getOrNull(2)?.toIntOrNull())
                }
            }
        } finally {
            pings.cancel(true)
            if (helper === link) helper = null
            helperStarted.completeExceptionally(IOException("It closed as soon as it started. If another VPN is on, turn it off, then try again."))
            helperGone.complete(Unit)
        }
    }

    /** Questions to the VPN helper over its control connection, each answered on a line of its own. */
    private class HelperLink(socket: Socket) {
        private val output = socket.getOutputStream()
        private val questions = AtomicLong()
        private val answers = ConcurrentHashMap<Long, CompletableFuture<Int>>()

        fun send(line: String) = synchronized(output) {
            output.write("$line\n".toByteArray(Charsets.US_ASCII))
            output.flush()
        }

        /** The app user ID that owns the connection [origin] names, or null when the device doesn't say in time. */
        fun uidOf(origin: String): Int? {
            val id = questions.incrementAndGet()
            val answer = CompletableFuture<Int>()
            answers[id] = answer
            return try {
                send("uid $id $origin")
                answer.get(HELPER_ANSWER_MILLIS, TimeUnit.MILLISECONDS).takeIf { it >= 0 }
            } catch (_: Exception) {
                null
            } finally { answers.remove(id) }
        }

        fun answer(id: Long?, uid: Int?) { if (id != null && uid != null) answers[id]?.complete(uid) }
    }

    /** Answers a CONNECT, then reads the tunnel as [intercept] does. */
    private fun tunnel(client: Socket, input: BufferedInputStream, head: HttpHead, connection: Long) {
        val target = head.startLine.split(' ').getOrNull(1).orEmpty()
        val host = target.substringBeforeLast(':').removePrefix("[").removeSuffix("]")
        val port = target.substringAfterLast(':', "443").toIntOrNull() ?: 443
        val output = client.getOutputStream()
        output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        output.flush()
        intercept(client, input, host, port, connection, named = true)
    }

    /**
     * Reads an app's connection to [host]: decrypts TLS, records plain HTTP, and passes anything else through unread.
     * [named] is false when the app dialed an address rather than a name; the site's name then comes from the TLS
     * handshake or the Host header, and Robomori connects to the site by that name.
     */
    private fun intercept(client: Socket, input: BufferedInputStream, host: String, port: Int, connection: Long, named: Boolean) {
        val output = client.getOutputStream()
        // The server speaks first in these protocols, such as SSH and SMTP, so there is nothing to wait for.
        if (port in serverFirstPorts) return passThrough(client, input, output, host, port, connection)
        val wait = if (port == 80 || port == 443) 0 else CLIENT_FIRST_WAIT_MILLIS
        val first = peek(client, input, 1, wait).firstOrNull()?.toInt()
            ?: return passThrough(client, input, output, host, port, connection)
        if (first != TLS_HANDSHAKE) {
            if (!looksLikeHttp(peek(client, input, 8, CLIENT_FIRST_WAIT_MILLIS))) return passThrough(client, input, output, host, port, connection)
            return serve(input, output, connection, Target(host, port, tls = false, named = named)) { }
        }
        val name = if (named) host else peekServerName(client, input) ?: host
        val site = "$name:$port"
        if (site in passthrough) return passThrough(client, input, output, host, port, connection, "https://${hostPort(name, port, tls = true)}")
        // The bytes already read off the socket, which the TLS session starts from.
        val buffered = ByteArray(input.available())
        input.mark(buffered.size)
        val read = input.read(buffered).coerceAtLeast(0)
        val tls = authority.serverContext(name).socketFactory
            .createSocket(client, ByteArrayInputStream(buffered, 0, read), true) as SSLSocket
        tls.sslParameters = tls.sslParameters.apply { applicationProtocols = arrayOf("http/1.1") }
        try {
            tls.startHandshake()
        } catch (_: SSLException) {
            // Apps also drop connections mid-handshake, as Chrome does with spares it opened in advance, and that looks
            // the same as turning the certificate down. An app that pins its certificates turns it down every time, so
            // the site passes through unread only after it's turned down twice in a row.
            if (connection <= forgottenThrough || refusals.merge(site, 1, Int::plus)!! < RefusalsToPassThrough) return
            refusals.remove(site)
            passthrough += site
            onExchange(NetworkExchange(exchanges.incrementAndGet(), connection, Instant.now(), "CONNECT",
                "https://${hostPort(name, port, tls = true)}", emptyList(), CapturedBody.Empty, NetworkOutcome.CertificateRejected))
            return
        }
        refusals.remove(site)
        serve(BufferedInputStream(tls.inputStream), tls.outputStream, connection, Target(name, port, tls = true)) { }
    }

    /** The server name the TLS ClientHello waiting in [input] asks for, leaving it there to be read. */
    private fun peekServerName(client: Socket, input: BufferedInputStream): String? {
        val header = peek(client, input, 5, CLIENT_FIRST_WAIT_MILLIS)
        if (header.size < 5) return null
        val length = ((header[3].toInt() and 0xFF) shl 8) or (header[4].toInt() and 0xFF)
        return clientHelloServerName(peek(client, input, 5 + length, CLIENT_FIRST_WAIT_MILLIS))
    }

    /** Up to [count] bytes waiting in [input], left there, as many as arrive within [timeoutMillis], or all at 0. */
    private fun peek(client: Socket, input: BufferedInputStream, count: Int, timeoutMillis: Int): ByteArray {
        val bytes = ByteArrayOutputStream()
        val timeout = client.soTimeout
        input.mark(count)
        client.soTimeout = timeoutMillis
        try {
            while (bytes.size() < count) bytes.write(input.read().takeIf { it >= 0 } ?: break)
        } catch (_: SocketTimeoutException) {
        } finally {
            client.soTimeout = timeout
            input.reset()
        }
        return bytes.toByteArray()
    }

    /**
     * Relays a connection as it is, and lists it with the bytes that went each way, so connections Robomori can't
     * read still show which site the app talked to.
     */
    private fun passThrough(client: Socket, input: InputStream, output: OutputStream, host: String, port: Int,
                            connection: Long, url: String = "${if (port == 80) "http" else "https"}://${hostPort(host, port, tls = port != 80)}") {
        val id = exchanges.incrementAndGet()
        val started = Instant.now()
        val sent = CountingOutputStream(OutputStream.nullOutputStream())
        val received = CountingOutputStream(output)
        fun record(finishedAt: Instant?) = onExchange(NetworkExchange(id, connection, started, "CONNECT", url, emptyList(),
            CapturedBody.Empty, NetworkOutcome.Encrypted(sent.count, received.count, finishedAt)))
        record(null)
        val upstreamSocket = try { connectUpstream(host, port, false) } catch (failure: IOException) {
            onExchange(NetworkExchange(id, connection, started, "CONNECT", url, emptyList(), CapturedBody.Empty,
                NetworkOutcome.Failed(failure.plainMessage)))
            return
        }
        passing[connection] = client
        try {
            upstreamSocket.use { site ->
                track(site)
                val upstream = site.getOutputStream()
                val tee = object : OutputStream() {
                    override fun write(b: Int) { upstream.write(b); sent.write(b) }
                    override fun write(b: ByteArray, off: Int, len: Int) { upstream.write(b, off, len); sent.write(b, off, len) }
                    override fun flush() = upstream.flush()
                    override fun close() = upstream.close()
                }
                try {
                    // Each side's end is passed on as it comes, as a protocol may still answer after the app's last bytes.
                    pump(input, received, site.getInputStream(), tee,
                        endClient = { client.shutdownOutput() }, endSite = { site.shutdownOutput() })
                } finally { open -= site }
            }
        } finally {
            passing.remove(connection)
            record(Instant.now())
        }
    }

    /**
     * Closes the [connections] passing through unread, so the app opens new ones that are read, now that it trusts
     * Robomori. Apps reconnect on their own when a connection they keep for reuse closes.
     */
    fun closePassThrough(connections: Set<Long>) {
        for (connection in connections) passing.remove(connection)?.let { runCatching { it.close() } }
    }

    /** A site requests go to; one not [named] takes its name from each request's Host header. */
    private data class Target(val host: String, val port: Int, val tls: Boolean, val named: Boolean = true)

    /**
     * Relays the requests on one connection from an app. [target] is the site a CONNECT tunnel leads to; without one,
     * requests name their site in full, and a CONNECT goes to [onConnect].
     */
    private fun serve(input: BufferedInputStream, output: OutputStream, connection: Long, target: Target?,
                      onConnect: (HttpHead) -> Unit) {
        var upstream: Pair<Target, Socket>? = null
        try {
            while (true) {
                val head = readHead(input) ?: return
                val parts = head.startLine.split(' ', limit = 3)
                if (parts.size < 3) return
                val (method, requestTarget, version) = parts
                if (target == null && method.equals("CONNECT", ignoreCase = true)) return onConnect(head)
                val site: Target
                val path: String
                if (target != null) {
                    site = if (target.named) target else target.copy(host = hostHeaderName(head) ?: target.host, named = true)
                    path = requestTarget
                } else {
                    val uri = runCatching { URI(requestTarget) }.getOrNull()
                    if (uri?.host == null || uri.scheme?.lowercase() != "http") {
                        output.write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                        return
                    }
                    site = Target(uri.host.removePrefix("[").removeSuffix("]"), if (uri.port > 0) uri.port else 80, tls = false)
                    path = (uri.rawPath?.ifEmpty { null } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
                }
                val url = "${if (site.tls) "https" else "http"}://${hostPort(site.host, site.port, site.tls)}$path"
                val expectsContinue = head.header("Expect")?.equals("100-continue", ignoreCase = true) == true
                val forwarded = HttpHead("$method $path $version", withoutWebSocketExtensions(
                    head.headers.filterNot { (name) -> name.equals("Proxy-Connection", true) || (expectsContinue && name.equals("Expect", true)) }))
                val id = exchanges.incrementAndGet()
                val started = Instant.now()
                val rule = rules().matching(method, url)
                fun record(body: CapturedBody, outcome: NetworkOutcome, webSocket: WebSocketSession? = null) =
                    onExchange(NetworkExchange(id, connection, started, method, url, forwarded.headers, body, outcome, rule,
                        webSocket))
                record(CapturedBody.Empty, NetworkOutcome.Pending)
                val requestBody = BodyCapture()
                var answered = false
                try {
                    val mock = rule?.action as? NetworkRuleAction.Mock
                    if (mock != null) {
                        // Answered here, without contacting the site.
                        if (expectsContinue) output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                        copyBody(requestFraming(head), input, OutputStream.nullOutputStream(), requestBody)
                        val response = mockResponse(mock)
                        answered = true
                        writeRuleResponse(response, output, withBody = responseFraming(method, HttpHead("HTTP/1.1 ${response.status}", emptyList())) != BodyFraming.None)
                        record(requestBody.captured, NetworkOutcome.Answered(response.recorded()))
                        if (!keepsAlive(head)) return
                        continue
                    }
                    val socket = upstream?.takeIf { it.first == site }?.second ?: run {
                        upstream?.second?.let { open -= it; it.close() }
                        connectUpstream(site.host, site.port, site.tls).also { socket ->
                            track(socket)
                            upstream = site to socket
                            if (socket is SSLSocket) socket.startHandshake()
                        }
                    }
                    val siteIn = BufferedInputStream(socket.getInputStream())
                    val siteOut = socket.getOutputStream()
                    if (expectsContinue) output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                    siteOut.write(forwarded.encode())
                    copyBody(requestFraming(head), input, siteOut, requestBody)
                    siteOut.flush()
                    var response: HttpHead
                    while (true) {
                        response = readHead(siteIn) ?: throw IOException("The server closed the connection without answering.")
                        val status = response.status
                        if (status in 100..199 && status != 101) { output.write(response.encode()); output.flush() } else break
                    }
                    val modify = rule?.action as? NetworkRuleAction.Modify
                    if (modify != null && response.status != 101) {
                        val modified = relayModified(modify, method, response, siteIn, output)
                        answered = true
                        record(requestBody.captured, NetworkOutcome.Answered(modified))
                        if (responseFraming(method, response) == BodyFraming.UntilClose || !keepsAlive(head) || !keepsAlive(response)) return
                        continue
                    }
                    output.write(response.encode())
                    answered = true
                    val responseBody = BodyCapture()
                    val finished = lazy { Instant.now() }
                    fun answer(webSocket: WebSocketSession? = null) = record(requestBody.captured, NetworkOutcome.Answered(
                        NetworkResponse(response.status, response.reason, response.headers, responseBody.captured, finished.value)), webSocket)
                    if (response.status == 101) {
                        output.flush()
                        if (!isWebSocketUpgrade(response.headers)) {
                            answer()
                            // Another upgraded protocol: relay it as it is.
                            pump(input, output, siteIn, siteOut)
                            return
                        }
                        // A WebSocket: relayed as it is, while its messages are read from the frames going each way.
                        val recorder = WebSocketRecorder({ session -> answer(session) })
                        answer(WebSocketSession())
                        pump(input, recorder.receiving(output), siteIn, recorder.sending(siteOut))
                        recorder.close()
                        return
                    }
                    val framing = responseFraming(method, response)
                    copyBody(framing, siteIn, output, responseBody)
                    output.flush()
                    answer()
                    if (framing == BodyFraming.UntilClose || !keepsAlive(head) || !keepsAlive(response)) return
                } catch (failure: IOException) {
                    record(requestBody.captured, NetworkOutcome.Failed(failure.plainMessage))
                    if (!answered) {
                        val message = "Robomori could not reach $url: ${failure.plainMessage}".toByteArray()
                        output.write(("HTTP/1.1 502 Bad Gateway\r\nContent-Type: text/plain; charset=utf-8\r\n" +
                            "Content-Length: ${message.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
                        output.write(message)
                        output.flush()
                    }
                    return
                }
            }
        } finally {
            upstream?.second?.let { open -= it; runCatching { it.close() } }
        }
    }

    /** Relays bytes both ways until both sides end, ending each with [endClient] and [endSite]. */
    private fun pump(clientIn: InputStream, clientOut: OutputStream, siteIn: InputStream, siteOut: OutputStream,
                     endClient: () -> Unit = { clientOut.close() }, endSite: () -> Unit = { siteOut.close() }) {
        val back = threads.submit { runCatching { siteIn.transferTo(clientOut) }; runCatching { clientOut.flush(); endClient() } }
        runCatching { clientIn.transferTo(siteOut) }
        runCatching { siteOut.flush(); endSite() }
        runCatching { back.get() }
    }

    private companion object {
        const val TLS_HANDSHAKE = 0x16
        const val SOCKS_VERSION = 5
        const val SOCKS_NO_AUTHENTICATION: Byte = 0
        const val SOCKS_USERNAME: Byte = 2
        const val SOCKS_CONNECT = 1
        const val SOCKS_COMMAND_NOT_SUPPORTED = 7
        const val SOCKS_ADDRESS_NOT_SUPPORTED = 8
        /** The first byte of the VPN helper's control connection, which neither SOCKS5 nor HTTP starts with. */
        const val HELPER_HELLO = 1
        /** The helper turns its VPN off after 8 seconds without a ping. */
        const val HELPER_PING_MILLIS = 2000L
        const val HELPER_ANSWER_MILLIS = 2000L
        /** How long an app has to send its first bytes on ports besides HTTP's and HTTPS's, before passing through. */
        const val CLIENT_FIRST_WAIT_MILLIS = 2000
        /** FTP, SSH, Telnet, SMTP, POP3, IMAP, mail submission and MySQL. */
        val serverFirstPorts = setOf(21, 22, 23, 25, 110, 143, 587, 3306)
    }
}

/** Counts the bytes written through it. */
private class CountingOutputStream(private val target: OutputStream) : OutputStream() {
    @Volatile var count = 0L; private set
    override fun write(b: Int) { target.write(b); count++ }
    override fun write(b: ByteArray, off: Int, len: Int) { target.write(b, off, len); count += len }
    override fun flush() = target.flush()
    override fun close() = target.close()
}

/** Whether [start] begins as an HTTP/1 request does, with a method in capitals and a space. */
private fun looksLikeHttp(start: ByteArray): Boolean {
    val method = start.toString(Charsets.ISO_8859_1).substringBefore(' ', "")
    return method.length in 3..7 && method.all { it in 'A'..'Z' }
}

/** The host a request's Host header names, without its port. */
private fun hostHeaderName(head: HttpHead): String? =
    head.header("Host")?.let { runCatching { URI("http://$it").host }.getOrNull() }?.removePrefix("[")?.removeSuffix("]")

/** The server name a TLS ClientHello asks for, or null when [record] isn't a whole one or names none. */
internal fun clientHelloServerName(record: ByteArray): String? = try {
    val bytes = ByteBuffer.wrap(record)
    fun skip(count: Int) { bytes.position(bytes.position() + count) }
    fun short() = bytes.short.toInt() and 0xFFFF
    fun byte() = bytes.get().toInt() and 0xFF
    var name: String? = null
    // The record's type, then its version and length; the handshake's type, then its length and version, and the random.
    if (byte() == 0x16) {
        skip(4)
        if (byte() == 1) {
            skip(3 + 2 + 32)
            skip(byte())
            skip(short())
            skip(byte())
            val end = short() + bytes.position()
            while (name == null && bytes.position() + 4 <= end) {
                val type = short()
                val length = short()
                if (type != 0) { skip(length); continue }
                // The server name extension: a list whose entry of type 0 is the host name.
                skip(2)
                if (byte() != 0) break
                name = String(ByteArray(short()).also(bytes::get), Charsets.US_ASCII)
            }
        }
    }
    name
} catch (_: RuntimeException) { null }

/** The address an emulator's apps reach this computer at. */
private const val EmulatorHostAlias = "10.0.2.2"

private fun connectSite(host: String, port: Int, tls: Boolean): Socket {
    val socket = Socket()
    socket.connect(InetSocketAddress(if (host == EmulatorHostAlias) "127.0.0.1" else host, port), 15_000)
    if (!tls) return socket
    // The proxy starts the handshake itself, to time it.
    val secure = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(socket, host, port, true) as SSLSocket
    secure.sslParameters = secure.sslParameters.apply {
        endpointIdentificationAlgorithm = "HTTPS"
        applicationProtocols = arrayOf("http/1.1")
        if (!host.matches(Regex("[0-9.]+|.*:.*"))) serverNames = listOf(SNIHostName(host))
    }
    return secure
}

private fun hostPort(host: String, port: Int, tls: Boolean): String {
    val name = if (':' in host) "[$host]" else host
    return if (port == (if (tls) 443 else 80)) name else "$name:$port"
}

/** The start line and headers of an HTTP/1.x message. */
internal class HttpHead(val startLine: String, val headers: List<Pair<String, String>>) {
    fun header(name: String) = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
    val status: Int get() = startLine.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
    val reason: String get() = startLine.split(' ', limit = 3).getOrNull(2).orEmpty()
    fun encode(): ByteArray = buildString {
        append(startLine).append("\r\n")
        for ((name, value) in headers) append(name).append(": ").append(value).append("\r\n")
        append("\r\n")
    }.toByteArray(Charsets.ISO_8859_1)
}

/** Reads a message's head, or null when the stream ends before one starts. */
internal fun readHead(input: InputStream): HttpHead? {
    val lines = mutableListOf<String>()
    val line = ByteArrayOutputStream()
    var total = 0
    while (true) {
        val byte = input.read()
        if (byte < 0) {
            if (lines.isEmpty() && line.size() == 0) return null
            throw IOException("The connection closed in the middle of a message.")
        }
        if (++total > 64 * 1024) throw IOException("The message's headers are too long.")
        if (byte == '\n'.code) {
            val text = line.toString(Charsets.ISO_8859_1).removeSuffix("\r")
            line.reset()
            // Blank lines before a message are allowed and skipped.
            if (text.isEmpty()) { if (lines.isEmpty()) continue else break }
            lines += text
        } else line.write(byte)
    }
    val headers = mutableListOf<Pair<String, String>>()
    for (header in lines.drop(1)) {
        if (header.first().isWhitespace() && headers.isNotEmpty()) {
            val (name, value) = headers.removeLast()
            headers += name to "$value ${header.trim()}"
        } else headers += header.substringBefore(':').trim() to header.substringAfter(':', "").trim()
    }
    return HttpHead(lines.first(), headers)
}

internal sealed interface BodyFraming {
    data object None : BodyFraming
    data class Length(val bytes: Long) : BodyFraming
    data object Chunked : BodyFraming
    data object UntilClose : BodyFraming
}

private fun HttpHead.chunked() = header("Transfer-Encoding")?.lowercase()?.contains("chunked") == true
private fun HttpHead.contentLength() = header("Content-Length")?.trim()?.toLongOrNull()

internal fun requestFraming(head: HttpHead): BodyFraming = when {
    head.chunked() -> BodyFraming.Chunked
    else -> head.contentLength()?.takeIf { it > 0 }?.let(BodyFraming::Length) ?: BodyFraming.None
}

internal fun responseFraming(method: String, head: HttpHead): BodyFraming = when {
    method.equals("HEAD", ignoreCase = true) || head.status in 100..199 || head.status == 204 || head.status == 304 -> BodyFraming.None
    head.chunked() -> BodyFraming.Chunked
    else -> head.contentLength()?.let { if (it > 0) BodyFraming.Length(it) else BodyFraming.None } ?: BodyFraming.UntilClose
}

private fun keepsAlive(head: HttpHead): Boolean {
    val connection = head.header("Connection")?.lowercase().orEmpty()
    val version = if (head.startLine.startsWith("HTTP/")) head.startLine.substringBefore(' ') else head.startLine.substringAfterLast(' ')
    return if (version == "HTTP/1.0") "keep-alive" in connection else "close" !in connection
}

/** Keeps the first [limit] bytes of a body and counts the rest. */
internal class BodyCapture(private val limit: Int = 1024 * 1024) {
    private val kept = ByteArrayOutputStream()
    private var size = 0L
    fun add(bytes: ByteArray, offset: Int, length: Int) {
        val room = (limit - kept.size()).coerceAtLeast(0)
        kept.write(bytes, offset, minOf(room, length))
        size += length
    }
    val captured get() = if (size == 0L) CapturedBody.Empty else CapturedBody(kept.toByteArray(), size)
}

/** Copies one message body as it is framed, keeping its content in [capture]. */
internal fun copyBody(framing: BodyFraming, input: InputStream, output: OutputStream, capture: BodyCapture) {
    val buffer = ByteArray(16 * 1024)
    fun copy(count: Long) {
        var left = count
        while (left > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (read < 0) throw IOException("The connection closed in the middle of a body.")
            output.write(buffer, 0, read)
            capture.add(buffer, 0, read)
            left -= read
        }
    }
    fun line(): String {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            if (byte < 0) throw IOException("The connection closed in the middle of a body.")
            bytes.write(byte)
            if (byte == '\n'.code) break
            if (bytes.size() > 8192) throw IOException("A chunk's size line is too long.")
        }
        output.write(bytes.toByteArray())
        return bytes.toString(Charsets.ISO_8859_1).trim()
    }
    when (framing) {
        BodyFraming.None -> {}
        is BodyFraming.Length -> copy(framing.bytes)
        BodyFraming.Chunked -> while (true) {
            val size = line().substringBefore(';').trim().toLongOrNull(16) ?: throw IOException("A chunk's size is not valid.")
            if (size == 0L) {
                // Trailers, up to the blank line that ends the body.
                while (line().isNotEmpty()) Unit
                break
            }
            copy(size)
            line()
        }
        BodyFraming.UntilClose -> while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            capture.add(buffer, 0, read)
        }
    }
}

/**
 * Why a connection to a server failed, in words people know: Java's own messages, such as "Connection refused" or a
 * bare host name, are put plainly. Robomori's own failures already are, and keep theirs.
 */
internal val IOException.plainMessage: String get() = when (this) {
    is java.net.UnknownHostException -> "The server's name couldn't be found. Check the address, and that this computer is online."
    is java.net.ConnectException -> "The server refused the connection."
    is java.net.SocketTimeoutException -> "The server took too long to answer."
    is java.net.NoRouteToHostException -> "The server couldn't be reached. Check that this computer is online."
    is javax.net.ssl.SSLException -> "Robomori couldn't make a secure connection to the server."
    is java.net.SocketException -> "The server closed the connection."
    else -> message?.takeIf { javaClass == IOException::class.java } ?: "The connection to the server failed."
}
