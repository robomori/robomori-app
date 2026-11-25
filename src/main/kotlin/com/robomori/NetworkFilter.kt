package com.robomori

/** Which requests the list shows, as Chrome's Network filter bar picks them. */
internal data class NetworkFilter(
    /** Words that must all appear in a request's URL, method, status, app, or its decoded bodies and WebSocket messages. */
    val query: String = "",
    val type: NetworkTypeFilter = NetworkTypeFilter.All,
    /** Only requests that failed, were turned down, or answered with a status of 400 or more. */
    val errorsOnly: Boolean = false,
) {
    val isActive get() = query.isNotBlank() || type != NetworkTypeFilter.All || errorsOnly
}

/** The kinds of response the type chips pick, grouped from [NetworkExchange.type] for the traffic apps send. */
internal enum class NetworkTypeFilter {
    All,
    /** What apps call their servers with: JSON, XML, protobuf and plain text. */
    Api,
    Images,
    WebSocket,
    /** Web pages and what they load, from WebViews and browsers: HTML, scripts, stylesheets and fonts. */
    Web,
    /** Anything else, and requests whose type isn't known yet. */
    Other;

    fun matches(exchange: NetworkExchange): Boolean = this == All || this == groupOf(exchange.type)

    companion object {
        private val images = setOf("png", "jpeg", "jpg", "gif", "webp", "svg", "avif", "bmp", "icon", "vnd.microsoft.icon",
            "tiff", "heic", "heif", "apng")

        fun groupOf(type: String?): NetworkTypeFilter = when (type) {
            "json", "xml", "text", "protobuf", "x-protobuf", "grpc", "grpc-web", "grpc-web+proto" -> Api
            "document", "script", "stylesheet", "font" -> Web
            "websocket" -> WebSocket
            in images -> Images
            else -> Other
        }
    }
}

/** The requests that [filter] keeps, in the same order. */
internal fun List<NetworkExchange>.filteredBy(filter: NetworkFilter, appOf: (NetworkExchange) -> String?): List<NetworkExchange> {
    if (!filter.isActive) return this
    val words = filter.words
    return this.filter { exchange ->
        filter.type.matches(exchange) && (!filter.errorsOnly || exchange.isError) && words.all { word -> exchange.matches(word, appOf) }
    }
}

private fun NetworkExchange.matches(word: String, appOf: (NetworkExchange) -> String?): Boolean {
    val response = (outcome as? NetworkOutcome.Answered)?.response
    return url.lowercase().contains(word) || method.lowercase().contains(word) ||
        response?.status?.toString()?.contains(word) == true || response?.reason?.lowercase()?.contains(word) == true ||
        appOf(this)?.lowercase()?.contains(word) == true || bodyMatches(word).isNotEmpty()
}

/**
 * Whether a request went wrong: it failed, or the server answered with an error. A connection Robomori couldn't read
 * didn't go wrong for the app, so it isn't one.
 */
internal val NetworkExchange.isError: Boolean get() = when (val outcome = outcome) {
    NetworkOutcome.Pending, is NetworkOutcome.Encrypted, NetworkOutcome.CertificateRejected -> false
    is NetworkOutcome.Answered -> outcome.response.status >= 400
    is NetworkOutcome.Failed -> true
}
