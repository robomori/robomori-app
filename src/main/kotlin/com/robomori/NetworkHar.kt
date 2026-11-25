package com.robomori

import kotlinx.serialization.json.*
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Base64

/** Requests read back from a HAR file, with the package of the app behind each connection Robomori knew. */
internal data class NetworkSession(val exchanges: List<NetworkExchange>, val apps: Map<Long, String>)

private const val robomoriKey = "_robomori"

/**
 * [exchanges] as a HAR 1.2 file. Bodies are written decoded, as text where they are text and in base64 otherwise, so
 * other tools such as Chrome's "Import HAR" can read them. Each entry also carries a `_robomori` object with what HAR
 * has no place for: the app, the connection, how the request ended, and the bytes as they went over the wire when
 * they differ from the readable body, so [parseHar] restores every request exactly. Like Chrome's, a WebSocket's
 * messages are in `_webSocketMessages`.
 */
internal fun toHar(exchanges: List<NetworkExchange>, appOf: (NetworkExchange) -> String?): String {
    val har = buildJsonObject {
        putJsonObject("log") {
            put("version", "1.2")
            putJsonObject("creator") {
                put("name", "Robomori")
                put("version", runCatching { APP_VERSION }.getOrDefault("development"))
            }
            putJsonArray("entries") { exchanges.forEach { add(harEntry(it, appOf(it))) } }
        }
    }
    return har.toString()
}

private fun harEntry(exchange: NetworkExchange, app: String?): JsonObject = buildJsonObject {
    val response = (exchange.outcome as? NetworkOutcome.Answered)?.response
    val millis = exchange.durationMillis?.coerceAtLeast(0) ?: 0
    val request = harBody(exchange.requestBody, exchange.requestHeaders)
    val answer = response?.let { harBody(it.body, it.headers) }
    put("startedDateTime", exchange.startedAt.toString())
    put("time", millis)
    putJsonObject("request") {
        put("method", exchange.method)
        put("url", exchange.url)
        put("httpVersion", "HTTP/1.1")
        put("cookies", JsonArray(emptyList()))
        put("headers", harHeaders(exchange.requestHeaders))
        put("queryString", harQuery(exchange.url))
        if (exchange.requestBody.size > 0) putJsonObject("postData") {
            put("mimeType", exchange.requestHeaders.header("Content-Type").orEmpty())
            request.text?.let { put("text", it) }
            if (request.base64) put("encoding", "base64")
        }
        put("headersSize", -1)
        put("bodySize", exchange.requestBody.size)
    }
    putJsonObject("response") {
        put("status", response?.status ?: 0)
        put("statusText", response?.reason.orEmpty())
        put("httpVersion", if (response != null) "HTTP/1.1" else "")
        put("cookies", JsonArray(emptyList()))
        put("headers", harHeaders(response?.headers.orEmpty()))
        putJsonObject("content") {
            put("size", answer?.size ?: 0)
            put("mimeType", response?.headers?.header("Content-Type").orEmpty())
            answer?.text?.let { put("text", it) }
            if (answer?.base64 == true) put("encoding", "base64")
        }
        put("redirectURL", response?.headers?.header("Location").orEmpty())
        put("headersSize", -1)
        put("bodySize", response?.body?.size ?: 0)
        // Chrome's own field for requests that got no answer.
        when (val outcome = exchange.outcome) {
            is NetworkOutcome.Failed -> put("_error", outcome.message)
            NetworkOutcome.CertificateRejected -> put("_error", "The app rejected Robomori's certificate for this server.")
            is NetworkOutcome.Encrypted -> put("_error", "Encrypted: Robomori passed this connection through without reading it.")
            else -> {}
        }
    }
    putJsonObject("cache") {}
    putJsonObject("timings") {
        put("send", 0)
        put("wait", millis)
        put("receive", 0)
    }
    exchange.webSocket?.let { session ->
        put("_resourceType", "websocket")
        put("_webSocketMessages", harMessages(session.messages))
    }
    putJsonObject(robomoriKey) {
        put("id", exchange.id)
        put("connection", exchange.connection)
        app?.let { put("app", it) }
        when (val outcome = exchange.outcome) {
            NetworkOutcome.Pending -> put("outcome", "pending")
            is NetworkOutcome.Answered -> put("outcome", "answered")
            NetworkOutcome.CertificateRejected -> put("outcome", "certificateRejected")
            is NetworkOutcome.Encrypted -> {
                put("outcome", "encrypted"); put("sent", outcome.sent); put("received", outcome.received)
                outcome.finishedAt?.let { put("finishedAt", it.toString()) }
            }
            is NetworkOutcome.Failed -> { put("outcome", "failed"); put("message", outcome.message) }
        }
        response?.let { put("finishedAt", it.finishedAt.toString()) }
        exchange.webSocket?.let { session -> putJsonObject("webSocket") { session.closedAt?.let { put("closedAt", it.toString()) } } }
        put("requestBody", request.raw)
        answer?.let { put("responseBody", it.raw) }
        exchange.rule?.let { put("rule", it.toJson()) }
    }
}

/** How a body is written: readable in [text], and as captured in [raw]. */
private class HarBody(val text: String?, val base64: Boolean, val size: Long, val raw: JsonObject)

private fun harBody(body: CapturedBody, headers: List<Pair<String, String>>): HarBody {
    val decoded = if (body.size == 0L) "" else body.text(headers)
    val encoded = headers.header("Content-Encoding")?.trim()?.lowercase().let { it != null && it != "" && it != "identity" }
    // Binary content without a content encoding is the same either way; compressed content Robomori can't read is
    // left out, since anything written would be still compressed.
    val text = decoded ?: if (!encoded) Base64.getEncoder().encodeToString(body.bytes) else null
    val base64 = decoded == null && text != null
    val sameAsWire = when {
        decoded != null -> decoded.toByteArray(Charsets.UTF_8).contentEquals(body.bytes)
        else -> base64
    }
    val raw = buildJsonObject {
        put("size", body.size)
        put("truncated", body.truncated)
        // Left out when the readable body already holds the exact bytes.
        if (!sameAsWire) put("bytes", Base64.getEncoder().encodeToString(body.bytes))
    }
    val size = if (decoded != null && !body.truncated) decoded.toByteArray(Charsets.UTF_8).size.toLong() else body.size
    return HarBody(text, base64, size, raw)
}

/**
 * A WebSocket's messages as Chrome writes them: `send` or `receive`, the time in seconds, the opcode, and text messages'
 * text, others' bytes in base64. `_at` holds the exact time and `_size` a cut-off message's full size, for [parseHar].
 */
private fun harMessages(messages: List<WebSocketMessage>) = buildJsonArray {
    for (message in messages) addJsonObject {
        put("type", if (message.direction == WebSocketDirection.Sent) "send" else "receive")
        put("time", message.at.epochSecond + message.at.nano / 1e9)
        put("opcode", message.opcode.code)
        put("data", message.text ?: Base64.getEncoder().encodeToString(message.data.bytes))
        put("_at", message.at.toString())
        if (message.data.truncated) put("_size", message.data.size)
    }
}

private fun parseMessages(messages: JsonArray?): List<WebSocketMessage> = messages.orEmpty().mapNotNull { element ->
    val message = element as? JsonObject ?: return@mapNotNull null
    val opcode = message.intOrNull("opcode")?.let(WebSocketOpcode::of) ?: return@mapNotNull null
    val data = message.string("data").orEmpty()
    val bytes = if (opcode == WebSocketOpcode.Text) data.toByteArray(Charsets.UTF_8) else runCatching { Base64.getDecoder().decode(data) }.getOrDefault(ByteArray(0))
    val at = message.string("_at")?.let(Instant::parse)
        ?: (message["time"] as? JsonPrimitive)?.doubleOrNull?.let { Instant.ofEpochSecond(0, (it * 1e9).toLong()) } ?: Instant.EPOCH
    val direction = if (message.string("type") == "send") WebSocketDirection.Sent else WebSocketDirection.Received
    WebSocketMessage(direction, at, opcode, CapturedBody(bytes, (message["_size"] as? JsonPrimitive)?.longOrNull ?: bytes.size.toLong()))
}

private fun harHeaders(headers: List<Pair<String, String>>) = buildJsonArray {
    for ((name, value) in headers) addJsonObject { put("name", name); put("value", value) }
}

private fun harQuery(url: String) = buildJsonArray {
    val query = runCatching { URI(url).rawQuery }.getOrNull() ?: return@buildJsonArray
    for (part in query.split('&')) {
        if (part.isEmpty()) continue
        val name = part.substringBefore('=')
        val value = part.substringAfter('=', "")
        addJsonObject { put("name", urlDecode(name)); put("value", urlDecode(value)) }
    }
}

private fun urlDecode(text: String) = runCatching { URLDecoder.decode(text, Charsets.UTF_8) }.getOrDefault(text)

private fun List<Pair<String, String>>.header(name: String) = firstOrNull { it.first.equals(name, true) }?.second

/**
 * The requests in a HAR [text]. Files Robomori wrote come back exactly as they were recorded. Other tools' files are
 * read as well as HAR allows: their bodies are already decoded, so content encodings are dropped from the headers,
 * each request gets its own connection, no app is known, and requests without a status count as failed.
 *
 * @throws IllegalArgumentException when [text] is not a HAR file.
 */
internal fun parseHar(text: String): NetworkSession {
    val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
    val entries = (root?.get("log") as? JsonObject)?.get("entries") as? JsonArray
        ?: throw IllegalArgumentException("Not a HAR file: it has no log.entries.")
    val apps = mutableMapOf<Long, String>()
    val exchanges = entries.mapIndexed { index, element ->
        val entry = element as? JsonObject ?: throw IllegalArgumentException("Not a HAR file: entry $index is not an object.")
        val robomori = entry[robomoriKey] as? JsonObject
        if (robomori != null) robomoriExchange(entry, robomori).also { exchange ->
            robomori.string("app")?.let { apps[exchange.connection] = it }
        } else foreignExchange(entry, index + 1L)
    }
    return NetworkSession(exchanges, apps)
}

private fun robomoriExchange(entry: JsonObject, robomori: JsonObject): NetworkExchange {
    val request = entry.obj("request")
    val response = entry.obj("response")
    val requestHeaders = request.headers()
    val requestBody = capturedBody(robomori.obj("requestBody"), request["postData"] as? JsonObject)
    val outcome = when (val kind = robomori.string("outcome")) {
        "pending" -> NetworkOutcome.Pending
        "certificateRejected" -> NetworkOutcome.CertificateRejected
        "encrypted" -> NetworkOutcome.Encrypted(robomori.long("sent"), robomori.long("received"),
            robomori.string("finishedAt")?.let(Instant::parse))
        "failed" -> NetworkOutcome.Failed(robomori.string("message").orEmpty())
        "answered" -> NetworkOutcome.Answered(NetworkResponse(
            status = response.int("status"),
            reason = response.string("statusText").orEmpty(),
            headers = response.headers(),
            body = capturedBody(robomori.obj("responseBody"), response.obj("content")),
            finishedAt = Instant.parse(robomori.string("finishedAt")),
        ))
        else -> throw IllegalArgumentException("Unknown outcome: $kind")
    }
    val webSocket = (robomori["webSocket"] as? JsonObject)?.let { session ->
        WebSocketSession(parseMessages(entry["_webSocketMessages"] as? JsonArray), session.string("closedAt")?.let(Instant::parse))
    }
    return NetworkExchange(
        id = robomori.long("id"),
        connection = robomori.long("connection"),
        startedAt = Instant.parse(entry.string("startedDateTime")),
        method = request.string("method").orEmpty(),
        url = request.string("url").orEmpty(),
        requestHeaders = requestHeaders,
        requestBody = requestBody,
        outcome = outcome,
        rule = robomori["rule"]?.let(::networkRuleOf),
        webSocket = webSocket,
    )
}

/** The captured bytes: written out in [raw] when they differ from the readable body in [content]. */
private fun capturedBody(raw: JsonObject, content: JsonObject?): CapturedBody {
    val size = raw.long("size")
    val bytes = raw.string("bytes")?.let(Base64.getDecoder()::decode) ?: content?.let(::readableBytes) ?: ByteArray(0)
    return CapturedBody(bytes, size)
}

private fun readableBytes(content: JsonObject): ByteArray? {
    val text = content.string("text") ?: return null
    return if (content.string("encoding") == "base64") Base64.getDecoder().decode(text) else text.toByteArray(Charsets.UTF_8)
}

private fun foreignExchange(entry: JsonObject, number: Long): NetworkExchange {
    val request = entry.obj("request")
    val response = entry["response"] as? JsonObject
    val started = entry.string("startedDateTime")?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() } ?: Instant.EPOCH
    val requestBody = (request["postData"] as? JsonObject)?.let(::readableBytes)?.let { CapturedBody(it, it.size.toLong()) } ?: CapturedBody.Empty
    val status = response?.intOrNull("status") ?: 0
    val outcome = if (response == null || status <= 0) {
        NetworkOutcome.Failed(response?.string("_error")?.takeIf(String::isNotBlank) ?: "No response.")
    } else {
        val body = (response["content"] as? JsonObject)?.let(::readableBytes)?.let { CapturedBody(it, it.size.toLong()) } ?: CapturedBody.Empty
        val time = (entry["time"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it > 0 } ?: 0.0
        NetworkOutcome.Answered(NetworkResponse(status, response.string("statusText").orEmpty(), response.headers().decoded(), body,
            started.plusMillis(time.toLong())))
    }
    // A recorded WebSocket has closed by the time the file was written.
    val webSocket = (entry["_webSocketMessages"] as? JsonArray)?.let { messages ->
        val parsed = parseMessages(messages)
        WebSocketSession(parsed, parsed.maxOfOrNull { it.at } ?: started)
    }
    return NetworkExchange(number, number, started, request.string("method") ?: "GET", request.string("url").orEmpty(),
        request.headers().decoded(), requestBody, outcome, webSocket = webSocket)
}

/** HAR bodies are already decoded, so the headers no longer describe them. */
private fun List<Pair<String, String>>.decoded() = filterNot { it.first.equals("Content-Encoding", true) }

private fun JsonObject.headers(): List<Pair<String, String>> = (this["headers"] as? JsonArray).orEmpty().mapNotNull {
    val header = it as? JsonObject ?: return@mapNotNull null
    (header.string("name") ?: return@mapNotNull null) to header.string("value").orEmpty()
}

private fun JsonObject.obj(key: String) = this[key] as? JsonObject ?: throw IllegalArgumentException("Not a HAR file: no $key.")
private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.intOrNull(key: String) = (this[key] as? JsonPrimitive)?.intOrNull
private fun JsonObject.int(key: String) = intOrNull(key) ?: throw IllegalArgumentException("Not a HAR file: no $key.")
private fun JsonObject.long(key: String) = (this[key] as? JsonPrimitive)?.longOrNull ?: throw IllegalArgumentException("Not a HAR file: no $key.")

/** Each emulator's requests, kept between runs in `<AVD name>.har`. */
internal class NetworkSessionStore(private val directory: File = File(HostPlatform().configDirectory, "network-sessions")) {
    private fun file(avd: String) = File(directory, avd.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".har")

    /** The saved session, or null when there is none or it can't be read. */
    fun load(avd: String): NetworkSession? {
        val file = file(avd)
        if (!file.isFile) return null
        return runCatching { parseHar(file.readText()) }.getOrNull()
    }

    fun save(avd: String, exchanges: List<NetworkExchange>, appOf: (NetworkExchange) -> String?) {
        if (exchanges.isEmpty()) return clear(avd)
        writeAtomically(file(avd), toHar(exchanges, appOf))
    }

    fun clear(avd: String) { file(avd).delete() }
}

/** Writes [text] to a temporary file beside [file] and moves it into place, so a half-written file is never read. */
internal fun writeAtomically(file: File, text: String) {
    val parent = file.absoluteFile.parentFile.apply { mkdirs() }
    val temporary = File.createTempFile(".${file.name}-", ".tmp", parent)
    try {
        temporary.writeText(text)
        runCatching { Files.move(temporary.toPath(), file.toPath(), REPLACE_EXISTING, ATOMIC_MOVE) }
            .getOrElse { Files.move(temporary.toPath(), file.toPath(), REPLACE_EXISTING) }
    } finally { temporary.delete() }
}
