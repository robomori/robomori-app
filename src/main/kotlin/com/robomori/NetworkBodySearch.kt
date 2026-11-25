package com.robomori

/** Where in a request the filter's words were found, besides its URL and other fields. */
internal enum class BodyMatch { RequestBody, ResponseBody, Messages }

/** Marks a body worked out to have no text, apart from not worked out yet. */
private object NoText

/** Content types whose bodies are never text, so they aren't decoded to be searched. */
private val binaryKinds = setOf("image", "video", "audio", "font")
private val binaryTypes = setOf("application/octet-stream", "application/x-protobuf", "application/protobuf", "application/grpc",
    "application/zip", "application/gzip", "application/pdf", "application/wasm", "application/vnd.android.package-archive")

/**
 * The body decoded as text in lower case, for the filter to search, or null when it's binary. Worked out once per body
 * and kept on it, so typing in the filter over a long session decompresses each body only the first time.
 */
internal fun CapturedBody.searchableText(headers: List<Pair<String, String>>): String? {
    when (val known = searchable) {
        NoText -> return null
        is String -> return known
    }
    val mime = headers.firstOrNull { it.first.equals("Content-Type", true) }?.second?.substringBefore(';')?.trim()?.lowercase().orEmpty()
    val text = if (size == 0L || mime.substringBefore('/') in binaryKinds || mime in binaryTypes) null else text(headers)?.lowercase()
    searchable = text ?: NoText
    return text
}

/** Where [word], in lower case, appears in the request's bodies or its WebSocket's text messages. */
internal fun NetworkExchange.bodyMatches(word: String): Set<BodyMatch> = buildSet {
    if (requestBody.searchableText(requestHeaders)?.contains(word) == true) add(BodyMatch.RequestBody)
    val response = (outcome as? NetworkOutcome.Answered)?.response
    if (response != null && response.body.searchableText(response.headers)?.contains(word) == true) add(BodyMatch.ResponseBody)
    if (webSocket?.messages?.any { it.opcode == WebSocketOpcode.Text && it.data.searchableText(emptyList())?.contains(word) == true } == true)
        add(BodyMatch.Messages)
}

/** The words of [filter]'s query found in [part] of [exchange], to say where a request matched. */
internal fun NetworkFilter.wordsIn(exchange: NetworkExchange, part: BodyMatch): List<String> =
    words.filter { part in exchange.bodyMatches(it) }

/** The query's words in lower case, which must all appear in a request for it to be listed. */
internal val NetworkFilter.words: List<String> get() = query.lowercase().split(Regex("\\s+")).filter(String::isNotEmpty)
