package com.robomori

import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyStore
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Rules applied by the proxy to requests apps make to real local sites. */
class NetworkRulesProxyTest {
    @TempDir lateinit var directory: File
    private val closing = mutableListOf<AutoCloseable>()
    private val exchanges = CopyOnWriteArrayList<NetworkExchange>()
    private val siteHits = AtomicInteger()
    private var rules = listOf<NetworkRule>()

    @AfterEach fun close() = closing.asReversed().forEach { it.close() }

    @Test fun `answers HTTPS with a rule's mock response without contacting the site`() {
        val (authority, siteAuthority) = authorities()
        val site = httpsSite(siteAuthority)
        val mock = NetworkRule("mock", "localhost:${site.address.port}/items", NetworkRuleAction.Mock(503,
            listOf("Content-Type" to "application/json", "Retry-After" to "5"), """{"error":"down"}"""), method = "GET")
        rules = listOf(mock)
        val client = client(proxy(authority, siteAuthority), trusting(authority))

        val response = client.send(HttpRequest.newBuilder(URI("https://localhost:${site.address.port}/items?page=2")).build(),
            HttpResponse.BodyHandlers.ofString())
        // The connection stays open for the next request, which the rule doesn't match.
        val other = client.send(HttpRequest.newBuilder(URI("https://localhost:${site.address.port}/other")).build(),
            HttpResponse.BodyHandlers.ofString())

        assertEquals(503, response.statusCode())
        assertEquals("""{"error":"down"}""", response.body())
        assertEquals("5", response.headers().firstValue("Retry-After").orElse(null))
        assertEquals("site", other.body())
        assertEquals(1, siteHits.get(), "Only the request the rule doesn't match reaches the site.")
        val recorded = answered().first { it.url.endsWith("/items?page=2") }
        assertEquals(mock, recorded.rule)
        assertEquals(503, (recorded.outcome as NetworkOutcome.Answered).response.status)
        assertNull(answered().first { it.url.endsWith("/other") }.rule)
    }

    @Test fun `changes a compressed HTTPS response's status, headers and body text`() {
        val (authority, siteAuthority) = authorities()
        val site = httpsSite(siteAuthority)
        rules = listOf(NetworkRule("modify", "localhost:*/items", NetworkRuleAction.Modify(status = 500,
            setHeaders = listOf("X-Robomori" to "yes"), removeHeaders = listOf("X-Site"),
            body = NetworkBodyEdit.Substitute("\"premium\":false", "\"premium\":true"))))

        val response = client(proxy(authority, siteAuthority), trusting(authority)).send(
            HttpRequest.newBuilder(URI("https://localhost:${site.address.port}/items")).build(), HttpResponse.BodyHandlers.ofString())

        assertEquals(500, response.statusCode())
        assertEquals("""{"premium":true,"name":"Ada"}""", response.body())
        assertEquals("yes", response.headers().firstValue("X-Robomori").orElse(null))
        assertTrue(response.headers().firstValue("X-Site").isEmpty)
        assertTrue(response.headers().firstValue("Content-Encoding").isEmpty, "The body goes to the app uncompressed.")
        val recorded = answered().single()
        assertEquals("modify", recorded.rule?.id)
        val answer = (recorded.outcome as NetworkOutcome.Answered).response
        assertEquals("""{"premium":true,"name":"Ada"}""", answer.body.text(answer.headers))
    }

    @Test fun `replaces the body of a chunked plain HTTP response`() {
        val site = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/feed") { exchange ->
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { it.write("original".toByteArray()) }
            }
            start()
        }
        closing += AutoCloseable { site.stop(0) }
        rules = listOf(NetworkRule("replace", "127.0.0.1:${site.address.port}/feed",
            NetworkRuleAction.Modify(body = NetworkBodyEdit.Replace("[]"))))

        val response = client(proxy(NetworkCertificateAuthority(File(directory, "a")))).send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:${site.address.port}/feed")).build(), HttpResponse.BodyHandlers.ofString())

        assertEquals(200, response.statusCode())
        assertEquals("[]", response.body())
        assertEquals("2", response.headers().firstValue("Content-Length").orElse(null))
    }

    private fun authorities() = NetworkCertificateAuthority(File(directory, "a")) to NetworkCertificateAuthority(File(directory, "site"))

    private fun answered(): List<NetworkExchange> {
        val deadline = System.nanoTime() + 2_000_000_000
        while (exchanges.none { it.outcome is NetworkOutcome.Answered } && System.nanoTime() < deadline) Thread.sleep(5)
        return exchanges.filter { it.outcome is NetworkOutcome.Answered }
    }

    private fun proxy(authority: NetworkCertificateAuthority, siteAuthority: NetworkCertificateAuthority? = null): NetworkProxy {
        val trust = siteAuthority?.let(::trusting)
        return NetworkProxy(authority, { _, _ -> }, { exchanges += it }, rules = { rules }) { host, port, tls ->
            val socket = Socket(host, port)
            if (!tls) socket else (trust!!.socketFactory.createSocket(socket, host, port, true) as SSLSocket).apply { startHandshake() }
        }.also(closing::add)
    }

    /** A site whose /items answers gzipped JSON with an X-Site header, and whose other paths answer "site". */
    private fun httpsSite(authority: NetworkCertificateAuthority) = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        httpsConfigurator = HttpsConfigurator(authority.serverContext("localhost"))
        createContext("/") { exchange ->
            siteHits.incrementAndGet()
            if (exchange.requestURI.path == "/items") {
                val body = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write("""{"premium":false,"name":"Ada"}""".toByteArray()) } }.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.responseHeaders.add("Content-Encoding", "gzip")
                exchange.responseHeaders.add("X-Site", "1")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            } else {
                exchange.sendResponseHeaders(200, 4)
                exchange.responseBody.use { it.write("site".toByteArray()) }
            }
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
