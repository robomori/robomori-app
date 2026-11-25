package com.robomori

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant
import kotlin.test.assertEquals

class NetworkFilterTest {
    @TempDir lateinit var directory: File

    private val apps = mapOf(1L to "com.example.pantry", 2L to "com.example.notes")
    private val appOf: (NetworkExchange) -> String? = { apps[it.connection] }

    @Test fun `keeps requests that match every word of the query, in any field, ignoring case`() {
        val items = answered(1, "https://api.example.com/v1/items", method = "POST")
        val users = answered(2, "https://api.example.com/v1/users")
        val other = answered(3, "https://cdn.other.org/items")
        val all = listOf(items, users, other)

        assertEquals(listOf(1L, 3L), all.filteredBy(NetworkFilter("ITEMS"), appOf).ids())
        assertEquals(listOf(1L), all.filteredBy(NetworkFilter("  API   items "), appOf).ids())
        assertEquals(listOf(1L), all.filteredBy(NetworkFilter("post items"), appOf).ids())
        assertEquals(emptyList(), all.filteredBy(NetworkFilter("users items"), appOf).ids())
        assertEquals(listOf(1L, 2L, 3L), all.filteredBy(NetworkFilter("   "), appOf).ids())
    }

    @Test fun `matches a request by its app's package and its status`() {
        val pantry = answered(1, "https://example.com/a", connection = 1, status = 404, reason = "Not Found")
        val notes = answered(2, "https://example.com/b", connection = 2, status = 200, reason = "OK")
        val all = listOf(pantry, notes)

        assertEquals(listOf(1L), all.filteredBy(NetworkFilter("pantry"), appOf).ids())
        assertEquals(listOf(2L), all.filteredBy(NetworkFilter("com.example.notes"), appOf).ids())
        assertEquals(listOf(1L), all.filteredBy(NetworkFilter("404"), appOf).ids())
        assertEquals(listOf(1L), all.filteredBy(NetworkFilter("not found"), appOf).ids())
        assertEquals(listOf(2L), all.filteredBy(NetworkFilter("200 notes"), appOf).ids())
    }

    @Test fun `groups each type as apps send them, with web pages' parts together`() {
        val groups = mapOf(
            "application/json" to NetworkTypeFilter.Api,
            "application/xml" to NetworkTypeFilter.Api,
            "text/plain" to NetworkTypeFilter.Api,
            "application/x-protobuf" to NetworkTypeFilter.Api,
            "application/grpc" to NetworkTypeFilter.Api,
            "text/html" to NetworkTypeFilter.Web,
            "application/javascript" to NetworkTypeFilter.Web,
            "text/css" to NetworkTypeFilter.Web,
            "font/woff2" to NetworkTypeFilter.Web,
            "image/png" to NetworkTypeFilter.Images,
            "image/jpeg" to NetworkTypeFilter.Images,
            "image/svg+xml" to NetworkTypeFilter.Images,
            "image/webp" to NetworkTypeFilter.Images,
            "application/octet-stream" to NetworkTypeFilter.Other,
        )
        for ((contentType, group) in groups) {
            val exchange = answered(1, "https://example.com/", contentType = contentType)
            assertEquals(group, NetworkTypeFilter.groupOf(exchange.type), contentType)
            assertEquals(listOf(1L), listOf(exchange).filteredBy(NetworkFilter(type = group), appOf).ids(), contentType)
            assertEquals(listOf(1L), listOf(exchange).filteredBy(NetworkFilter(type = NetworkTypeFilter.All), appOf).ids(), contentType)
            for (otherGroup in NetworkTypeFilter.entries - group - NetworkTypeFilter.All) {
                assertEquals(emptyList(), listOf(exchange).filteredBy(NetworkFilter(type = otherGroup), appOf).ids(), "$contentType in $otherGroup")
            }
        }
        // A request still waiting has no type yet.
        assertEquals(NetworkTypeFilter.Other, NetworkTypeFilter.groupOf(pending(1).type))
    }

    @Test fun `keeps only requests that went wrong`() {
        val all = listOf(
            answered(1, "https://example.com/ok", status = 200),
            answered(2, "https://example.com/missing", status = 404),
            answered(3, "https://example.com/broken", status = 500),
            pending(4),
            pending(5).copy(outcome = NetworkOutcome.Failed("Connection reset")),
            pending(6).copy(outcome = NetworkOutcome.CertificateRejected),
            answered(7, "https://example.com/moved", status = 302),
        )

        assertEquals(listOf(2L, 3L, 5L), all.filteredBy(NetworkFilter(errorsOnly = true), appOf).ids(), "Connections Robomori couldn't read didn't go wrong")
        assertEquals(listOf(3L), all.filteredBy(NetworkFilter("broken", errorsOnly = true), appOf).ids())
    }

    @Test fun `lists one app's requests that the filter keeps, sorted`() {
        val all = listOf(
            answered(1, "https://example.com/items", connection = 1, millis = 300),
            answered(2, "https://example.com/items", connection = 2, millis = 100),
            answered(3, "https://example.com/items", connection = 1, millis = 50),
            answered(4, "https://example.com/users", connection = 1, millis = 10),
            answered(5, "https://example.com/items", connection = 1, millis = 200, status = 500),
        )
        val filter = NetworkFilter("items")
        val byTime = NetworkSort(NetworkColumn.Duration)

        assertEquals(listOf(3L, 5L, 1L), all.visible("com.example.pantry", filter, byTime, appOf).ids())
        assertEquals(listOf(1L, 2L, 3L, 5L), all.visible(null, filter, null, appOf).ids())
        assertEquals(listOf(5L), all.visible("com.example.pantry", filter.copy(errorsOnly = true), byTime, appOf).ids())
    }

    @Test fun `the inspector lists the requests its filter keeps`() = runBlocking {
        val authority = NetworkCertificateAuthority(directory)
        val network = NetworkInspectorState(NetworkInspectorService(AppSettings("/sdk", "/avd"), { _, _, _ -> "" }), { authority },
            "emulator-5554", this)
        network.exchanges += listOf(
            answered(1, "https://example.com/items", contentType = "application/json", millis = 300),
            answered(2, "https://example.com/logo.png", contentType = "image/png", millis = 100),
            answered(3, "https://example.com/items/9", contentType = "application/json", millis = 50, status = 404),
        )

        network.search("items")
        assertEquals(listOf(1L, 3L), network.visibleExchanges.ids())
        network.sortBy(NetworkColumn.Duration)
        assertEquals(listOf(3L, 1L), network.visibleExchanges.ids())
        network.showErrorsOnly(true)
        assertEquals(listOf(3L), network.visibleExchanges.ids())
        network.search("")
        network.showErrorsOnly(false)
        network.filterType(NetworkTypeFilter.Images)
        assertEquals(listOf(2L), network.visibleExchanges.ids())
        assertEquals(NetworkFilter(type = NetworkTypeFilter.Images), network.filter)
    }

    @Test fun `finds requests by what their bodies and WebSocket messages say, decoded`() {
        val zipped = java.io.ByteArrayOutputStream().also { java.util.zip.GZIPOutputStream(it).use { gzip -> gzip.write("""{"order":"Pumpkin Pie"}""".toByteArray()) } }.toByteArray()
        val response = answered(1, "https://example.com/orders").let {
            it.copy(outcome = NetworkOutcome.Answered((it.outcome as NetworkOutcome.Answered).response.copy(
                headers = listOf("Content-Type" to "application/json", "Content-Encoding" to "gzip"), body = body(zipped))))
        }
        val request = answered(2, "https://example.com/search").copy(requestHeaders = listOf("Content-Type" to "application/json"),
            requestBody = body("""{"query":"pumpkin soup"}""".toByteArray()))
        // An image whose bytes happen to spell the word is never searched.
        val image = answered(3, "https://example.com/a.png", contentType = "image/png").let {
            it.copy(outcome = NetworkOutcome.Answered((it.outcome as NetworkOutcome.Answered).response.copy(body = body("pumpkin".toByteArray()))))
        }
        val socket = answered(4, "wss://example.com/chat").copy(webSocket = WebSocketSession(listOf(
            WebSocketMessage(WebSocketDirection.Received, Instant.EPOCH, WebSocketOpcode.Text, body("""{"pie":"pumpkin"}""".toByteArray())))))
        val all = listOf(response, request, image, socket)

        assertEquals(listOf(1L, 2L, 4L), all.filteredBy(NetworkFilter("PUMPKIN"), appOf).ids())
        assertEquals(listOf(1L), all.filteredBy(NetworkFilter("pie orders"), appOf).ids())
        assertEquals(listOf(4L), all.filteredBy(NetworkFilter("pie", type = NetworkTypeFilter.WebSocket), appOf).ids())
        // Where each request matched, to show it.
        assertEquals(setOf(BodyMatch.ResponseBody), response.bodyMatches("pumpkin"))
        assertEquals(listOf("soup"), NetworkFilter("search soup").wordsIn(request, BodyMatch.RequestBody))
        assertEquals(setOf(BodyMatch.Messages), socket.bodyMatches("pie"))
    }

    @Test fun `decodes each body once, however often the filter changes`() {
        val captured = body("""{"name":"Ada"}""".toByteArray())
        val exchange = answered(1, "https://example.com/").copy(requestHeaders = listOf("Content-Type" to "application/json"), requestBody = captured)

        listOf(exchange).filteredBy(NetworkFilter("ada"), appOf)

        assertEquals("""{"name":"ada"}""", captured.searchable)
        assertEquals("""{"name":"ada"}""", captured.searchableText(exchange.requestHeaders))
    }

    private fun body(bytes: ByteArray) = CapturedBody(bytes, bytes.size.toLong())

    private fun List<NetworkExchange>.ids() = map { it.id }

    private fun answered(id: Long, url: String, method: String = "GET", connection: Long = 1, status: Int = 200, reason: String = "OK",
                         contentType: String = "application/json", millis: Long = 10) = NetworkExchange(
        id, connection, Instant.EPOCH, method, url, emptyList(), CapturedBody.Empty,
        NetworkOutcome.Answered(NetworkResponse(status, reason, listOf("Content-Type" to contentType), CapturedBody.Empty,
            Instant.EPOCH.plusMillis(millis))))

    private fun pending(id: Long) =
        NetworkExchange(id, 1, Instant.EPOCH, "GET", "https://example.com/$id", emptyList(), CapturedBody.Empty, NetworkOutcome.Pending)
}
