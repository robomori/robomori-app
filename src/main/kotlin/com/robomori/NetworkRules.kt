package com.robomori

import androidx.compose.runtime.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.time.Instant
import java.util.UUID

/**
 * Changes the responses of the requests it matches, as Charles' Map Local and Rewrite do: [url] is the site and path,
 * such as `api.example.com/v1/users*`, where `*` stands for any text; [method] is the request's, or null for any.
 */
internal data class NetworkRule(
    val id: String,
    val url: String,
    val action: NetworkRuleAction,
    val method: String? = null,
    val enabled: Boolean = true,
)

internal sealed interface NetworkRuleAction {
    /** Answers with this response without contacting the site. */
    data class Mock(val status: Int, val headers: List<Pair<String, String>>, val body: String) : NetworkRuleAction

    /** Changes the site's response: its status when [status] is set, its headers, and its body. */
    data class Modify(
        val status: Int? = null,
        /** Headers added, or replacing those of the same name. */
        val setHeaders: List<Pair<String, String>> = emptyList(),
        val removeHeaders: List<String> = emptyList(),
        val body: NetworkBodyEdit? = null,
    ) : NetworkRuleAction
}

internal sealed interface NetworkBodyEdit {
    data class Replace(val body: String) : NetworkBodyEdit
    /** Replaces every [find] in the body's text with [replacement]. */
    data class Substitute(val find: String, val replacement: String) : NetworkBodyEdit
}

/**
 * Whether the request [method] made to [url] is one this rule changes. Without a scheme, the pattern matches the URL
 * after it; without a `?`, it matches whatever the query is. Letters match in either case.
 */
internal fun NetworkRule.matches(method: String, url: String): Boolean {
    if (this.method != null && !this.method.equals(method, ignoreCase = true)) return false
    val pattern = this.url.trim()
    if (pattern.isEmpty()) return false
    var target = if ("://" in pattern) url else url.substringAfter("://")
    if ('?' !in pattern) target = target.substringBefore('?')
    val regex = pattern.split('*').joinToString(".*") { Regex.escape(it) } + "/?"
    return Regex(regex, RegexOption.IGNORE_CASE).matches(target)
}

/** The first of [rules] that is on and changes the request [method] made to [url]. */
internal fun List<NetworkRule>.matching(method: String, url: String): NetworkRule? =
    firstOrNull { it.enabled && it.matches(method, url) }

/** A response as a rule writes it to the app. */
internal class RuleResponse(val status: Int, val reason: String, val headers: List<Pair<String, String>>, val body: ByteArray)

/** Headers that describe how a body goes over the wire, which a rule's response works out itself. */
private val framingHeaders = setOf("content-length", "transfer-encoding", "content-encoding")

/** The response [mock] answers with: its body as UTF-8, with its length. */
internal fun mockResponse(mock: NetworkRuleAction.Mock): RuleResponse {
    val body = mock.body.toByteArray(Charsets.UTF_8)
    val headers = mock.headers.filterNot { it.first.lowercase() in framingHeaders } + ("Content-Length" to body.size.toString())
    return RuleResponse(mock.status, reasonPhrase(mock.status), headers, body)
}

/**
 * The site's response, changed by [modify]. A body edit works on the body decompressed, which then goes to the app
 * uncompressed; a body that can't be decompressed is left as it is. The headers then give the body's new length.
 */
internal fun modifyResponse(response: RuleResponse, modify: NetworkRuleAction.Modify, hasBody: Boolean): RuleResponse {
    var headers = response.headers
    var body = response.body
    if (hasBody && modify.body != null) {
        val decoded = CapturedBody(body, body.size.toLong()).decoded(headers)
        if (decoded != null) {
            body = when (val edit = modify.body) {
                is NetworkBodyEdit.Replace -> edit.body
                is NetworkBodyEdit.Substitute -> decoded.toString(Charsets.UTF_8).let {
                    if (edit.find.isEmpty()) it else it.replace(edit.find, edit.replacement)
                }
            }.toByteArray(Charsets.UTF_8)
            headers = headers.filterNot { it.first.equals("Content-Encoding", ignoreCase = true) }
        }
    }
    val removed = (modify.removeHeaders + modify.setHeaders.map { it.first }).map(String::lowercase).toSet()
    headers = headers.filterNot { it.first.lowercase() in removed } + modify.setHeaders
    if (hasBody) {
        headers = headers.filterNot { it.first.equals("Content-Length", true) || it.first.equals("Transfer-Encoding", true) } +
            ("Content-Length" to body.size.toString())
    }
    val status = modify.status ?: response.status
    return RuleResponse(status, if (modify.status != null) reasonPhrase(status) else response.reason, headers, body)
}

/**
 * Reads the site's response body after [head] from [siteIn], changes the response by [modify], and writes it to
 * [output]. Returns the response as the app got it.
 */
internal fun relayModified(modify: NetworkRuleAction.Modify, method: String, head: HttpHead, siteIn: InputStream,
                           output: OutputStream): NetworkResponse {
    val framing = responseFraming(method, head)
    val content = BodyCapture(limit = Int.MAX_VALUE)
    copyBody(framing, siteIn, OutputStream.nullOutputStream(), content)
    val original = RuleResponse(head.status, head.reason, head.headers, content.captured.bytes)
    val changed = modifyResponse(original, modify, hasBody = framing != BodyFraming.None)
    writeRuleResponse(changed, output, withBody = framing != BodyFraming.None)
    return changed.recorded()
}

internal fun writeRuleResponse(response: RuleResponse, output: OutputStream, withBody: Boolean) {
    output.write(HttpHead("HTTP/1.1 ${response.status} ${response.reason}".trimEnd(), response.headers).encode())
    if (withBody) output.write(response.body)
    output.flush()
}

/** The response as the inspector keeps it. */
internal fun RuleResponse.recorded(): NetworkResponse {
    val kept = BodyCapture().apply { add(body, 0, body.size) }
    return NetworkResponse(status, reason, headers, kept.captured, Instant.now())
}

private fun reasonPhrase(status: Int) = when (status) {
    200 -> "OK"; 201 -> "Created"; 202 -> "Accepted"; 204 -> "No Content"
    301 -> "Moved Permanently"; 302 -> "Found"; 304 -> "Not Modified"; 307 -> "Temporary Redirect"; 308 -> "Permanent Redirect"
    400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"
    408 -> "Request Timeout"; 409 -> "Conflict"; 410 -> "Gone"; 413 -> "Content Too Large"; 422 -> "Unprocessable Content"
    429 -> "Too Many Requests"; 500 -> "Internal Server Error"; 501 -> "Not Implemented"; 502 -> "Bad Gateway"
    503 -> "Service Unavailable"; 504 -> "Gateway Timeout"
    else -> ""
}

internal fun NetworkRule.toJson(): JsonObject = buildJsonObject {
    put("id", id)
    put("url", url)
    method?.let { put("method", it) }
    put("enabled", enabled)
    fun JsonObjectBuilder.headers(name: String, headers: List<Pair<String, String>>) = putJsonArray(name) {
        for ((key, value) in headers) addJsonObject { put("name", key); put("value", value) }
    }
    when (action) {
        is NetworkRuleAction.Mock -> putJsonObject("mock") {
            put("status", action.status)
            headers("headers", action.headers)
            put("body", action.body)
        }
        is NetworkRuleAction.Modify -> putJsonObject("modify") {
            action.status?.let { put("status", it) }
            headers("setHeaders", action.setHeaders)
            putJsonArray("removeHeaders") { action.removeHeaders.forEach { add(it) } }
            when (val body = action.body) {
                is NetworkBodyEdit.Replace -> put("replaceBody", body.body)
                is NetworkBodyEdit.Substitute -> { put("find", body.find); put("replacement", body.replacement) }
                null -> {}
            }
        }
    }
}

/** The rule [json] holds, or null when it isn't one. */
internal fun networkRuleOf(json: JsonElement): NetworkRule? = runCatching {
    val rule = json.jsonObject
    fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    fun JsonObject.headers(key: String) = (this[key] as? JsonArray).orEmpty().map {
        it.jsonObject.text("name")!! to it.jsonObject.text("value").orEmpty()
    }
    val mock = rule["mock"] as? JsonObject
    val modify = rule["modify"] as? JsonObject
    val action = when {
        mock != null -> NetworkRuleAction.Mock(mock.getValue("status").jsonPrimitive.int, mock.headers("headers"), mock.text("body").orEmpty())
        modify != null -> NetworkRuleAction.Modify(
            status = (modify["status"] as? JsonPrimitive)?.intOrNull,
            setHeaders = modify.headers("setHeaders"),
            removeHeaders = (modify["removeHeaders"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content },
            body = modify.text("replaceBody")?.let(NetworkBodyEdit::Replace)
                ?: modify.text("find")?.let { NetworkBodyEdit.Substitute(it, modify.text("replacement").orEmpty()) },
        )
        else -> return null
    }
    NetworkRule(rule.text("id")!!, rule.text("url")!!, action, rule.text("method"),
        (rule["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true)
}.getOrNull()

internal interface NetworkRulesStore {
    fun load(): List<NetworkRule>
    fun save(rules: List<NetworkRule>)
    /** Whether rules are used at all: off keeps them, on and off each, without changing any response. */
    fun enabled(): Boolean = true
    fun saveEnabled(enabled: Boolean) {}
    /** Changes whenever the saved rules do, such as when `robomori network mock` adds one. */
    fun version(): Long = 0
}

/** The rules, kept between runs in `network-rules.json`, for every device. */
internal class FileNetworkRulesStore(private val file: File = File(HostPlatform().configDirectory, "network-rules.json")) : NetworkRulesStore {
    private fun read(): JsonObject? = if (!file.isFile) null else Json.parseToJsonElement(file.readText()) as? JsonObject

    override fun load(): List<NetworkRule> = (read()?.get("rules") as? JsonArray).orEmpty().mapNotNull(::networkRuleOf)

    override fun save(rules: List<NetworkRule>) = write(rules, enabled())

    override fun enabled(): Boolean = runCatching { (read()?.get("enabled") as? JsonPrimitive)?.booleanOrNull }.getOrNull() ?: true

    override fun saveEnabled(enabled: Boolean) = write(load(), enabled)

    private fun write(rules: List<NetworkRule>, enabled: Boolean) {
        writeAtomically(file, buildJsonObject {
            put("enabled", enabled)
            putJsonArray("rules") { rules.forEach { add(it.toJson()) } }
        }.toString())
    }

    override fun version(): Long = if (file.isFile) file.lastModified() * 31 + file.length() else 0
}

/** What a rule does, as the editor offers it. */
internal enum class NetworkRuleKind { Mock, Modify }

/** How a Modify rule changes the body. */
internal enum class NetworkBodyEditKind { Keep, Replace, Substitute }

/** Why a rule being edited can't be saved yet. */
internal enum class NetworkRuleProblem { MissingUrl, InvalidStatus, InvalidHeader, MissingFind }

/**
 * A rule as it is being edited, its fields as typed. Both kinds keep their fields, so switching between them loses
 * nothing. Headers are one `Name: value` per line; headers to remove are names separated by commas.
 */
internal data class NetworkRuleDraft(
    /** The rule this edits, or null for a new one. */
    val id: String? = null,
    val enabled: Boolean = true,
    val method: String? = null,
    val url: String = "",
    val kind: NetworkRuleKind = NetworkRuleKind.Mock,
    val mockStatus: String = "200",
    val mockHeaders: String = "Content-Type: application/json",
    val mockBody: String = "",
    val modifyStatus: String = "",
    val setHeaders: String = "",
    val removeHeaders: String = "",
    val bodyEdit: NetworkBodyEditKind = NetworkBodyEditKind.Keep,
    val replacementBody: String = "",
    val find: String = "",
    val replacement: String = "",
) {
    val problem: NetworkRuleProblem? get() = when {
        url.isBlank() -> NetworkRuleProblem.MissingUrl
        kind == NetworkRuleKind.Mock && status(mockStatus) == null -> NetworkRuleProblem.InvalidStatus
        kind == NetworkRuleKind.Modify && modifyStatus.isNotBlank() && status(modifyStatus) == null -> NetworkRuleProblem.InvalidStatus
        headers(if (kind == NetworkRuleKind.Mock) mockHeaders else setHeaders) == null -> NetworkRuleProblem.InvalidHeader
        kind == NetworkRuleKind.Modify && bodyEdit == NetworkBodyEditKind.Substitute && find.isEmpty() -> NetworkRuleProblem.MissingFind
        else -> null
    }

    /** The rule, under [newId] when it is new, or null while there is a [problem]. */
    fun toRule(newId: () -> String = { UUID.randomUUID().toString() }): NetworkRule? {
        if (problem != null) return null
        val action = when (kind) {
            NetworkRuleKind.Mock -> NetworkRuleAction.Mock(status(mockStatus)!!, headers(mockHeaders)!!, mockBody)
            NetworkRuleKind.Modify -> NetworkRuleAction.Modify(
                status = modifyStatus.takeIf(String::isNotBlank)?.let(::status),
                setHeaders = headers(setHeaders)!!,
                removeHeaders = removeHeaders.split(',').map(String::trim).filter(String::isNotEmpty),
                body = when (bodyEdit) {
                    NetworkBodyEditKind.Keep -> null
                    NetworkBodyEditKind.Replace -> NetworkBodyEdit.Replace(replacementBody)
                    NetworkBodyEditKind.Substitute -> NetworkBodyEdit.Substitute(find, replacement)
                },
            )
        }
        return NetworkRule(id ?: newId(), url.trim(), action, method, enabled)
    }

    companion object {
        fun of(rule: NetworkRule): NetworkRuleDraft {
            val draft = NetworkRuleDraft(id = rule.id, enabled = rule.enabled, method = rule.method, url = rule.url)
            return when (val action = rule.action) {
                is NetworkRuleAction.Mock -> draft.copy(kind = NetworkRuleKind.Mock, mockStatus = action.status.toString(),
                    mockHeaders = headerLines(action.headers), mockBody = action.body)
                is NetworkRuleAction.Modify -> draft.copy(kind = NetworkRuleKind.Modify, modifyStatus = action.status?.toString().orEmpty(),
                    setHeaders = headerLines(action.setHeaders), removeHeaders = action.removeHeaders.joinToString(", "),
                    bodyEdit = when (action.body) {
                        null -> NetworkBodyEditKind.Keep
                        is NetworkBodyEdit.Replace -> NetworkBodyEditKind.Replace
                        is NetworkBodyEdit.Substitute -> NetworkBodyEditKind.Substitute
                    },
                    replacementBody = (action.body as? NetworkBodyEdit.Replace)?.body.orEmpty(),
                    find = (action.body as? NetworkBodyEdit.Substitute)?.find.orEmpty(),
                    replacement = (action.body as? NetworkBodyEdit.Substitute)?.replacement.orEmpty())
            }
        }

        /**
         * A rule for requests like [exchange]: its method, site and path, answering with its response as it came,
         * decompressed, ready to change. Its body is also what replaces the body when the rule changes the response.
         */
        fun from(exchange: NetworkExchange): NetworkRuleDraft {
            val uri = runCatching { URI(exchange.url) }.getOrNull()
            val url = if (uri?.host == null) exchange.url.substringAfter("://").substringBefore('?')
                else exchange.url.substringAfter("://").substringBefore('/').substringBefore('?') + (uri.rawPath ?: "").ifEmpty { "/" }
            val response = (exchange.outcome as? NetworkOutcome.Answered)?.response
            val draft = NetworkRuleDraft(method = exchange.method, url = url)
            if (response == null) return draft
            // Laid out, as servers send JSON on one line, which is hard to change.
            val body = response.body.text(response.headers)?.let(::prettyBody).orEmpty()
            val headers = response.headers.filterNot { it.first.lowercase() in framingHeaders || it.first.equals("Connection", true) }
            return draft.copy(mockStatus = response.status.toString(), mockHeaders = headerLines(headers), mockBody = body,
                replacementBody = body)
        }
    }
}

private fun status(text: String) = text.trim().toIntOrNull()?.takeIf { it in 100..599 }

private fun headerLines(headers: List<Pair<String, String>>) = headers.joinToString("\n") { (name, value) -> "$name: $value" }

/** `Name: value` lines as headers, or null when a line isn't one. */
private fun headers(text: String): List<Pair<String, String>>? = text.lines().filter(String::isNotBlank).map { line ->
    val name = line.substringBefore(':', "").trim()
    if (name.isEmpty() || name.any { it.isWhitespace() }) return null
    name to line.substringAfter(':').trim()
}

/** What the rules page shows: the list of rules, or one rule being edited, which returns to the list when [fromList]. */
internal sealed interface NetworkRulesDialog {
    data object RuleList : NetworkRulesDialog
    data class Edit(val draft: NetworkRuleDraft, val fromList: Boolean) : NetworkRulesDialog
}

/**
 * The rules that change apps' responses, on every device, kept between runs. With [watchMillis], reads them again
 * that often when they changed elsewhere, such as through `robomori network mock`.
 */
internal class NetworkRulesState(private val store: NetworkRulesStore, private val scope: CoroutineScope, watchMillis: Long? = null) {
    var rules by mutableStateOf(runCatching { store.load() }.getOrDefault(emptyList())); private set
    /** Whether rules change responses at all; turning them off keeps each rule as it is. */
    var allEnabled by mutableStateOf(runCatching { store.enabled() }.getOrDefault(true)); private set
    var dialog by mutableStateOf<NetworkRulesDialog?>(null); private set
    /** What the proxies read, from their own threads, as requests come in. */
    @Volatile var active: List<NetworkRule> = activeOf(rules, allEnabled); private set
    /** The rules as last changed, which each save writes, so saves that run out of order still end with the latest. */
    @Volatile private var latest = rules
    private val writes = Mutex()
    /** The saved rules' version as this last read or wrote them. */
    @Volatile private var known = runCatching { store.version() }.getOrDefault(0)

    init {
        if (watchMillis != null) scope.launch {
            while (true) {
                delay(watchMillis)
                val saved = writes.withLock {
                    withContext(Dispatchers.IO) {
                        val version = runCatching { store.version() }.getOrDefault(known)
                        if (version == known) null
                        else runCatching { store.load() to store.enabled() }.getOrNull().also { known = version }
                    }
                } ?: continue
                // What the proxies use first, so the list never shows rules that aren't working yet.
                active = activeOf(saved.first, saved.second)
                latest = saved.first
                allEnabled = saved.second
                rules = saved.first
            }
        }
    }

    val enabledCount get() = if (allEnabled) rules.count { it.enabled } else 0

    fun rule(id: String) = rules.firstOrNull { it.id == id }

    fun openList() { dialog = NetworkRulesDialog.RuleList }

    fun close() { dialog = null }

    fun newRule() { dialog = NetworkRulesDialog.Edit(NetworkRuleDraft(), fromList = dialog == NetworkRulesDialog.RuleList) }

    /** Starts a rule for requests like [exchange], ready to change its response. */
    fun ruleFrom(exchange: NetworkExchange) { dialog = NetworkRulesDialog.Edit(NetworkRuleDraft.from(exchange), fromList = false) }

    fun edit(rule: NetworkRule) {
        dialog = NetworkRulesDialog.Edit(NetworkRuleDraft.of(rule), fromList = dialog == NetworkRulesDialog.RuleList)
    }

    fun updateDraft(draft: NetworkRuleDraft) {
        val editing = dialog as? NetworkRulesDialog.Edit ?: return
        dialog = editing.copy(draft = draft)
    }

    /** Leaves the rule being edited as it was. */
    fun cancelEdit() {
        val editing = dialog as? NetworkRulesDialog.Edit ?: return
        dialog = if (editing.fromList) NetworkRulesDialog.RuleList else null
    }

    /** Saves the rule being edited, unless it has a problem. */
    fun saveDraft() {
        val editing = dialog as? NetworkRulesDialog.Edit ?: return
        val rule = editing.draft.toRule() ?: return
        change { rules -> if (rules.any { it.id == rule.id }) rules.map { if (it.id == rule.id) rule else it } else rules + rule }
        dialog = if (editing.fromList) NetworkRulesDialog.RuleList else null
    }

    fun setEnabled(id: String, enabled: Boolean) = change { rules -> rules.map { if (it.id == id) it.copy(enabled = enabled) else it } }

    /** Turns every rule on or off at once, keeping whether each is on. */
    fun useRules(enabled: Boolean) {
        allEnabled = enabled
        active = activeOf(rules, enabled)
        persist { store.saveEnabled(enabled) }
    }

    fun delete(id: String) = change { rules -> rules.filterNot { it.id == id } }

    /** A copy of the rule, right after it, so it can be changed into a similar one. */
    fun duplicate(id: String, newId: () -> String = { UUID.randomUUID().toString() }) = change { rules ->
        rules.flatMap { if (it.id == id) listOf(it, it.copy(id = newId())) else listOf(it) }
    }

    /** Moves the rule [by] places up (negative) or down; the first rule that matches a request is the one that changes it. */
    fun move(id: String, by: Int) = change { rules ->
        val from = rules.indexOfFirst { it.id == id }
        if (from < 0) return@change rules
        val to = (from + by).coerceIn(0, rules.lastIndex)
        rules.toMutableList().apply { add(to, removeAt(from)) }
    }

    private fun change(update: (List<NetworkRule>) -> List<NetworkRule>) {
        val changed = update(rules)
        rules = changed
        latest = changed
        active = activeOf(changed, allEnabled)
        persist { store.save(latest) }
    }

    private fun persist(write: () -> Unit) {
        scope.launch {
            writes.withLock {
                withContext(NonCancellable + Dispatchers.IO) { runCatching { write(); known = store.version() } }
            }
        }
    }

    private fun activeOf(rules: List<NetworkRule>, allEnabled: Boolean) = if (allEnabled) rules.filter { it.enabled } else emptyList()
}
