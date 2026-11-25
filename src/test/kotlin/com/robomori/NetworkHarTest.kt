package com.robomori

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.Base64
import java.util.zip.GZIPOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetworkHarTest {
    @TempDir lateinit var directory: File

    private val started = Instant.parse("2026-11-21T10:15:30.123456789Z")
    private val json = """{"items":[1,2]}""".toByteArray()
    private val zipped = ByteArrayOutputStream().also { GZIPOutputStream(it).use { gzip -> gzip.write(json) } }.toByteArray()
    private val png = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13)

    private val answered = NetworkExchange(1, 7, started, "POST", "https://api.example.com/items?q=a%20b&flag",
        listOf("Host" to "api.example.com", "Content-Type" to "application/json"), body(json),
        NetworkOutcome.Answered(NetworkResponse(201, "Created",
            listOf("Content-Type" to "application/json", "Content-Encoding" to "gzip", "Location" to "/items/3"),
            body(zipped), started.plusMillis(250))))
    private val image = NetworkExchange(2, 7, started.plusSeconds(1), "GET", "https://cdn.example.com/a.png", emptyList(), CapturedBody.Empty,
        NetworkOutcome.Answered(NetworkResponse(200, "OK", listOf("Content-Type" to "image/png"), CapturedBody(png, 5000), started.plusSeconds(2))))
    private val pending = NetworkExchange(3, 8, started.plusSeconds(3), "GET", "https://example.com/slow", listOf("Accept" to "*/*"),
        CapturedBody.Empty, NetworkOutcome.Pending)
    private val pinned = NetworkExchange(4, 9, started.plusSeconds(4), "CONNECT", "https://bank.example.com", emptyList(),
        CapturedBody.Empty, NetworkOutcome.CertificateRejected)
    private val failed = NetworkExchange(5, 8, started.plusSeconds(5), "PUT", "http://example.com/upload", emptyList(),
        CapturedBody(png, png.size.toLong()), NetworkOutcome.Failed("Connection reset"))
    private val all = listOf(answered, image, pending, pinned, failed)
    private val apps = mapOf(7L to "com.example.pantry", 8L to "com.example.notes")

    @Test fun `writes the fields HAR 1_2 requires`() {
        val log = Json.parseToJsonElement(toHar(all) { apps[it.connection] }).jsonObject.getValue("log").jsonObject

        assertEquals("1.2", log.string("version"))
        assertEquals("Robomori", log.getValue("creator").jsonObject.string("name"))
        val entries = log.getValue("entries").jsonArray.map { it.jsonObject }
        assertEquals(5, entries.size)
        for (entry in entries) {
            assertTrue(setOf("startedDateTime", "time", "request", "response", "cache", "timings").all { it in entry })
            val request = entry.getValue("request").jsonObject
            assertTrue(setOf("method", "url", "httpVersion", "cookies", "headers", "queryString", "headersSize", "bodySize").all { it in request })
            val response = entry.getValue("response").jsonObject
            assertTrue(setOf("status", "statusText", "httpVersion", "cookies", "headers", "content", "redirectURL", "headersSize", "bodySize").all { it in response })
            assertTrue(setOf("size", "mimeType").all { it in response.getValue("content").jsonObject })
            assertTrue(setOf("send", "wait", "receive").all { it in entry.getValue("timings").jsonObject })
        }
        val first = entries[0]
        assertEquals("2026-11-21T10:15:30.123456789Z", first.string("startedDateTime"))
        assertEquals(250, first.getValue("time").jsonPrimitive.int)
        val request = first.getValue("request").jsonObject
        assertEquals(listOf("q" to "a b", "flag" to ""), request.getValue("queryString").jsonArray.map {
            it.jsonObject.string("name") to it.jsonObject.string("value") })
        assertEquals(-1, request.getValue("headersSize").jsonPrimitive.int)
        assertEquals("application/json", request.getValue("postData").jsonObject.string("mimeType"))
        val response = first.getValue("response").jsonObject
        assertEquals(201, response.getValue("status").jsonPrimitive.int)
        assertEquals("Created", response.string("statusText"))
        assertEquals("/items/3", response.string("redirectURL"))
        assertEquals(zipped.size, response.getValue("bodySize").jsonPrimitive.int)
        assertEquals("com.example.pantry", first.getValue("_robomori").jsonObject.string("app"))
        // Requests without an answer have no status, and say how they ended.
        assertEquals(listOf(0, 0, 0), entries.drop(2).map { it.getValue("response").jsonObject.getValue("status").jsonPrimitive.int })
        assertEquals(listOf("pending", "certificateRejected", "failed"), entries.drop(2).map { it.getValue("_robomori").jsonObject.string("outcome") })
        assertEquals("Connection reset", entries[4].getValue("_robomori").jsonObject.string("message"))
        assertFalse("postData" in entries[2].getValue("request").jsonObject)
    }

    @Test fun `writes text bodies decoded and binary ones in base64`() {
        val entries = Json.parseToJsonElement(toHar(listOf(answered, image, failed)) { null }).jsonObject
            .getValue("log").jsonObject.getValue("entries").jsonArray.map { it.jsonObject }

        val content = entries[0].getValue("response").jsonObject.getValue("content").jsonObject
        assertEquals("""{"items":[1,2]}""", content.string("text"))
        assertNull(content["encoding"])
        assertEquals(json.size, content.getValue("size").jsonPrimitive.int)
        assertEquals("""{"items":[1,2]}""", entries[0].getValue("request").jsonObject.getValue("postData").jsonObject.string("text"))
        // The gzipped bytes are kept for the round trip, since the readable text differs from them.
        assertEquals(Base64.getEncoder().encodeToString(zipped),
            entries[0].getValue("_robomori").jsonObject.getValue("responseBody").jsonObject.string("bytes"))
        // A plain text body isn't written twice.
        assertNull(entries[0].getValue("_robomori").jsonObject.getValue("requestBody").jsonObject["bytes"])

        val binary = entries[1].getValue("response").jsonObject.getValue("content").jsonObject
        assertEquals(Base64.getEncoder().encodeToString(png), binary.string("text"))
        assertEquals("base64", binary.string("encoding"))
        assertEquals(true, entries[1].getValue("_robomori").jsonObject.getValue("responseBody").jsonObject.getValue("truncated").jsonPrimitive.boolean)
        assertEquals("base64", entries[2].getValue("request").jsonObject.getValue("postData").jsonObject.string("encoding"))
    }

    @Test fun `reads back every kind of request exactly`() {
        val session = parseHar(toHar(all) { apps[it.connection] })

        assertEquals(all, session.exchanges)
        assertEquals(apps, session.apps)
        val truncated = (session.exchanges[1].outcome as NetworkOutcome.Answered).response.body
        assertTrue(truncated.truncated)
        assertEquals(5000, truncated.size)
    }

    @Test fun `reads other tools' HAR files as well as they allow`() {
        val har = """
            {"log":{"version":"1.2","creator":{"name":"WebInspector","version":"537.36"},"entries":[
              {"startedDateTime":"2026-11-21T12:00:00.000+02:00","time":120.5,
               "request":{"method":"GET","url":"https://example.com/a","headers":[{"name":"Accept","value":"*/*"}]},
               "response":{"status":200,"statusText":"OK","headers":[{"name":"Content-Encoding","value":"br"},{"name":"Content-Type","value":"text/plain"}],
                 "content":{"size":5,"mimeType":"text/plain","text":"hello"}}},
              {"startedDateTime":"2026-11-21T12:00:01.000Z","time":0,
               "request":{"method":"POST","url":"https://example.com/b","headers":[],"postData":{"mimeType":"","text":"AAE=","encoding":"base64"}},
               "response":{"status":0,"statusText":"","headers":[],"content":{"size":0,"mimeType":""},"_error":"net::ERR_FAILED"}}
            ]}}
        """.trimIndent()

        val session = parseHar(har)

        assertEquals(emptyMap(), session.apps)
        val (first, second) = session.exchanges
        assertEquals(Instant.parse("2026-11-21T10:00:00Z"), first.startedAt)
        val response = assertIs<NetworkOutcome.Answered>(first.outcome).response
        assertEquals(listOf("Content-Type" to "text/plain"), response.headers)
        assertEquals("hello", response.body.text(response.headers))
        assertEquals(Instant.parse("2026-11-21T10:00:00.120Z"), response.finishedAt)
        assertTrue(first.connection != second.connection)
        assertEquals(NetworkOutcome.Failed("net::ERR_FAILED"), second.outcome)
        assertTrue(byteArrayOf(0, 1).contentEquals(second.requestBody.bytes))

        assertFailsWith<IllegalArgumentException> { parseHar("""{"name":"not a har"}""") }
        assertFailsWith<IllegalArgumentException> { parseHar("not json") }
    }

    @Test fun `saves, loads and clears each emulator's session`() {
        val store = NetworkSessionStore(File(directory, "network-sessions"))

        assertNull(store.load("Pixel_9"))
        store.save("Pixel_9", all) { apps[it.connection] }

        assertTrue(File(directory, "network-sessions/Pixel_9.har").isFile)
        assertEquals(NetworkSession(all, apps), store.load("Pixel_9"))
        assertNull(store.load("Pixel_Fold"))
        store.clear("Pixel_9")
        assertNull(store.load("Pixel_9"))
        assertFalse(File(directory, "network-sessions/Pixel_9.har").exists())
    }

    @Test fun `restores the saved session before new requests, numbered apart from them`() = runBlocking {
        val store = NetworkSessionStore(File(directory, "network-sessions"))
        store.save("Pixel_9", all) { apps[it.connection] }

        val network = inspector(this, store)
        settle()

        assertEquals(all.map { it.url }, network.exchanges.map { it.url })
        assertEquals((-5L..-1L).toList(), network.exchanges.map { it.id })
        assertTrue(network.exchanges.all { it.connection < 0 })
        assertEquals("com.example.pantry", network.appOf(network.exchanges[0]))
        assertEquals("com.example.notes", network.appOf(network.exchanges[2]))
        assertNull(network.appOf(network.exchanges[3]))

        network.clear()
        settle()
        assertTrue(network.exchanges.isEmpty())
        assertNull(store.load("Pixel_9"))
    }

    @Test fun `saves new requests a moment after they come in, and on stopping`() = runBlocking {
        val store = NetworkSessionStore(File(directory, "network-sessions"))
        var onExchange: ((NetworkExchange) -> Unit)? = null
        val authority = NetworkCertificateAuthority(directory)
        val network = inspector(this, store, authority) { app, exchange, after ->
            onExchange = exchange
            NetworkProxy(authority, app, exchange, after)
        }
        network.start()
        while (network.routing == NetworkRouting.Starting) delay(10)

        onExchange!!(answered)
        while (store.load("Pixel_9") == null) delay(10)
        assertEquals(listOf(answered), store.load("Pixel_9")!!.exchanges)

        onExchange(image)
        delay(20)
        network.stop()
        settle()
        assertEquals(listOf(answered, image), store.load("Pixel_9")!!.exchanges)
    }

    @Test fun `exports the listed requests to a file, and says when it can't`() = runBlocking {
        val network = inspector(this, NetworkSessionStore(File(directory, "network-sessions")).also {
            it.save("Pixel_9", listOf(answered, pending)) { null }
        })
        settle()
        val file = File(directory, "export.har")

        assertEquals("Pixel_9-network-20261121-1530.har", network.exportFileName(java.time.LocalDateTime.of(2026, 11, 21, 15, 30)))
        network.exportHar(file)
        while (network.export == NetworkExport.Exporting) delay(10)

        assertEquals(NetworkExport.Saved(file), network.export)
        assertEquals(listOf(answered.url, pending.url), parseHar(file.readText()).exchanges.map { it.url })

        network.exportHar(File(file, "inside-a-file/export.har"))
        while (network.export == NetworkExport.Exporting) delay(10)
        assertIs<NetworkExport.Failed>(network.export)
        coroutineContext.job.children.forEach { it.cancel() }
    }

    private fun inspector(scope: CoroutineScope, store: NetworkSessionStore, authority: NetworkCertificateAuthority = NetworkCertificateAuthority(directory),
                          proxies: ((Long, Int) -> Unit, (NetworkExchange) -> Unit, Long) -> NetworkProxy = { app, exchange, after -> NetworkProxy(authority, app, exchange, after) }) =
        NetworkInspectorState(NetworkInspectorService(AppSettings("/sdk", "/avd"), executor), { authority }, "emulator-5554", scope,
            proxies = proxies, avd = "Pixel_9", sessions = store, saveDelayMillis = 50)

    private val helper = FakeVpnHelper()
    private val executor = CommandExecutor { command, _, _ ->
        val line = command.args.drop(2).joinToString(" ")
        helper.answer(line) ?: when {
            line == "shell getprop sys.boot_completed" -> "1\n"
            line == "shell su 0 id" -> "uid=0(root) gid=0(root)"
            line.startsWith("shell su 0 sh") -> "ROBOMORI_TRUSTED\n"
            else -> ""
        }
    }

    /** Waits for the work the state started, such as restoring and saving, to finish. */
    private suspend fun CoroutineScope.settle() {
        repeat(3) { coroutineContext.job.children.toList().forEach { it.join() } }
    }

    private fun body(bytes: ByteArray) = CapturedBody(bytes, bytes.size.toLong())
    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
}
