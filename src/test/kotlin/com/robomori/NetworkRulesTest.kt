package com.robomori

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.zip.GZIPOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetworkRulesTest {
    @TempDir lateinit var directory: File

    @Test fun `matches the site and path with wildcards, whatever the scheme and query`() {
        fun rule(url: String, method: String? = null) = NetworkRule("r", url, NetworkRuleAction.Modify(), method)

        assertTrue(rule("api.example.com/users").matches("GET", "https://api.example.com/users?page=2"))
        assertTrue(rule("api.example.com/users").matches("GET", "https://api.example.com/users/"))
        assertFalse(rule("api.example.com/users").matches("GET", "https://api.example.com/users/9"))
        assertTrue(rule("api.example.com/users/*").matches("GET", "https://api.example.com/users/9"))
        assertTrue(rule("*.example.com/*").matches("GET", "http://cdn.example.com/a.png"))
        assertTrue(rule("API.example.com/users").matches("GET", "https://api.example.com/users"))
        assertFalse(rule("api.example.com/users?page=1").matches("GET", "https://api.example.com/users?page=2"))
        assertTrue(rule("http://api.example.com/*").matches("GET", "http://api.example.com/x"))
        assertFalse(rule("http://api.example.com/*").matches("GET", "https://api.example.com/x"))
        assertFalse(rule("api.example.com/users", method = "POST").matches("GET", "https://api.example.com/users"))
        assertTrue(rule("api.example.com/users", method = "POST").matches("post", "https://api.example.com/users"))
        assertFalse(rule("example.com/a.b").matches("GET", "https://example.com/aXb"), "Only * is special.")
    }

    @Test fun `the first rule that is on applies`() {
        val off = NetworkRule("off", "example.com/*", NetworkRuleAction.Modify(status = 500), enabled = false)
        val first = NetworkRule("first", "example.com/*", NetworkRuleAction.Modify(status = 404))
        val second = NetworkRule("second", "example.com/*", NetworkRuleAction.Modify(status = 401))

        assertEquals(first, listOf(off, first, second).matching("GET", "https://example.com/x"))
        assertNull(listOf(off).matching("GET", "https://example.com/x"))
    }

    @Test fun `a body edit decompresses the body and gives its new length`() {
        val gzipped = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write("hello world".toByteArray()) } }.toByteArray()
        val original = RuleResponse(200, "OK", listOf("Content-Encoding" to "gzip", "Content-Length" to "${gzipped.size}", "ETag" to "1"), gzipped)

        val changed = modifyResponse(original, NetworkRuleAction.Modify(removeHeaders = listOf("etag"),
            body = NetworkBodyEdit.Substitute("world", "Robomori")), hasBody = true)

        assertEquals("hello Robomori", changed.body.decodeToString())
        assertEquals(listOf("Content-Length" to "14"), changed.headers)
        assertEquals("OK", changed.reason)
    }

    @Test fun `a mock response says its own length`() {
        val response = mockResponse(NetworkRuleAction.Mock(404, listOf("Content-Length" to "99", "Content-Type" to "text/plain"), "nope"))

        assertEquals(listOf("Content-Type" to "text/plain", "Content-Length" to "4"), response.headers)
        assertEquals("Not Found", response.reason)
    }

    @Test fun `a rule made from a request starts with its URL and its response, decompressed`() {
        val gzipped = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write("""{"a":1}""".toByteArray()) } }.toByteArray()
        val exchange = NetworkExchange(1, 1, Instant.EPOCH, "POST", "https://api.example.com:8443/v1/items?page=2", emptyList(),
            CapturedBody.Empty, NetworkOutcome.Answered(NetworkResponse(201, "Created",
                listOf("Content-Type" to "application/json", "Content-Encoding" to "gzip", "Content-Length" to "${gzipped.size}"),
                CapturedBody(gzipped, gzipped.size.toLong()), Instant.EPOCH)))

        val draft = NetworkRuleDraft.from(exchange)

        assertEquals("api.example.com:8443/v1/items", draft.url)
        assertEquals("POST", draft.method)
        assertEquals("201", draft.mockStatus)
        assertEquals("Content-Type: application/json", draft.mockHeaders)
        assertEquals("{\n    \"a\": 1\n}", draft.mockBody)
        val rule = draft.copy(mockStatus = "500").toRule { "new" }!!
        assertEquals(NetworkRule("new", "api.example.com:8443/v1/items",
            NetworkRuleAction.Mock(500, listOf("Content-Type" to "application/json"), "{\n    \"a\": 1\n}"), "POST"), rule)
        assertTrue(rule.matches("POST", exchange.url))
    }

    @Test fun `says why a rule can't be saved`() {
        assertEquals(NetworkRuleProblem.MissingUrl, NetworkRuleDraft().problem)
        assertEquals(NetworkRuleProblem.InvalidStatus, NetworkRuleDraft(url = "a", mockStatus = "abc").problem)
        assertEquals(NetworkRuleProblem.InvalidHeader, NetworkRuleDraft(url = "a", mockHeaders = "no colon").problem)
        assertEquals(NetworkRuleProblem.MissingFind, NetworkRuleDraft(url = "a", kind = NetworkRuleKind.Modify,
            bodyEdit = NetworkBodyEditKind.Substitute).problem)
        assertNull(NetworkRuleDraft(url = "a", kind = NetworkRuleKind.Modify, modifyStatus = "").problem)
    }

    @Test fun `rules made, turned off and deleted are kept between runs`() = runBlocking {
        val store = FileNetworkRulesStore(File(directory, "network-rules.json"))
        val scope = CoroutineScope(coroutineContext.job + Dispatchers.Default)
        val rules = NetworkRulesState(store, scope)
        val exchange = NetworkExchange(1, 1, Instant.EPOCH, "GET", "https://example.com/items", emptyList(), CapturedBody.Empty,
            NetworkOutcome.Answered(NetworkResponse(200, "OK", emptyList(), CapturedBody.Empty, Instant.EPOCH)))

        rules.ruleFrom(exchange)
        rules.updateDraft((rules.dialog as NetworkRulesDialog.Edit).draft.copy(mockStatus = "500"))
        rules.saveDraft()
        rules.openList()
        rules.newRule()
        rules.updateDraft(NetworkRuleDraft(url = "example.com/other", kind = NetworkRuleKind.Modify, modifyStatus = "404"))
        rules.saveDraft()

        assertEquals(NetworkRulesDialog.RuleList, rules.dialog, "Back to the list it was opened from.")
        val (mock, modify) = rules.rules
        assertEquals(NetworkRuleAction.Mock(500, emptyList(), ""), mock.action)
        assertEquals(listOf(mock, modify), rules.active)

        rules.setEnabled(mock.id, false)
        rules.edit(modify)
        rules.updateDraft((rules.dialog as NetworkRulesDialog.Edit).draft.copy(setHeaders = "X-A: 1"))
        rules.saveDraft()
        assertEquals(listOf(modify.id), rules.active.map { it.id })
        assertEquals(listOf("X-A" to "1"), (rules.active.single().action as NetworkRuleAction.Modify).setHeaders)

        val expected = rules.rules
        withTimeout(5000) { while (runCatching { store.load() }.getOrNull() != expected) yield() }
        assertEquals(expected, NetworkRulesState(store, scope).rules)

        rules.delete(mock.id)
        withTimeout(5000) { while (runCatching { store.load() }.getOrNull()?.size != 1) yield() }
        assertEquals(listOf(modify.id), NetworkRulesState(store, scope).rules.map { it.id })
    }

    @Test fun `rules can be duplicated, moved and all turned off, which is kept between runs`() = runBlocking {
        val store = FileNetworkRulesStore(File(directory, "network-rules.json"))
        val scope = CoroutineScope(coroutineContext.job + Dispatchers.Default)
        val rules = NetworkRulesState(store, scope)
        rules.openList()
        for (url in listOf("a.com/x", "b.com/x")) {
            rules.newRule()
            rules.updateDraft(NetworkRuleDraft(url = url))
            rules.saveDraft()
        }
        val (a, b) = rules.rules

        rules.duplicate(a.id) { "copy" }
        assertEquals(listOf(a.id, "copy", b.id), rules.rules.map { it.id })
        rules.move(b.id, -1)
        rules.move(b.id, -5)
        assertEquals(listOf(b.id, a.id, "copy"), rules.rules.map { it.id })

        rules.useRules(false)
        assertEquals(emptyList(), rules.active)
        assertEquals(0, rules.enabledCount)
        withTimeout(5000) { while (store.enabled()) yield() }
        val reopened = NetworkRulesState(store, scope)
        assertFalse(reopened.allEnabled)
        assertEquals(listOf(b.id, a.id, "copy"), reopened.rules.map { it.id }, "Turning rules off keeps them")
        reopened.useRules(true)
        assertEquals(3, reopened.active.size)
    }

    @Test fun `picks up rules saved elsewhere, such as by the CLI`() = runBlocking {
        val store = FileNetworkRulesStore(File(directory, "network-rules.json"))
        val scope = CoroutineScope(coroutineContext.job + Dispatchers.Default)
        val rules = NetworkRulesState(store, scope, watchMillis = 20)
        val added = NetworkRule("1", "a.com/*", NetworkRuleAction.Mock(200, emptyList(), ""))

        // A second later, so the file's change shows even where it keeps times to the second.
        Thread.sleep(1100)
        store.save(listOf(added))

        withTimeout(5000) { while (rules.rules != listOf(added)) yield() }
        assertEquals(listOf(added), rules.active)
        scope.coroutineContext.job.cancelChildren()
    }

    @Test fun `a rule made from a JSON response starts with its body laid out`() {
        val exchange = NetworkExchange(1, 1, Instant.EPOCH, "GET", "https://example.com/items?page=2", emptyList(), CapturedBody.Empty,
            NetworkOutcome.Answered(NetworkResponse(200, "OK", listOf("Content-Type" to "application/json"),
                CapturedBody("""{"items":[1,2]}""".toByteArray(), 15), Instant.EPOCH)))

        val draft = NetworkRuleDraft.from(exchange)

        assertEquals("example.com/items", draft.url)
        assertEquals("{\n    \"items\": [\n        1,\n        2\n    ]\n}", draft.mockBody)
    }

    @Test fun `every kind of rule survives saving`() {
        val store = FileNetworkRulesStore(File(directory, "rules.json"))
        val rules = listOf(
            NetworkRule("1", "a.com/*", NetworkRuleAction.Mock(201, listOf("A" to "b"), "body"), "GET", enabled = false),
            NetworkRule("2", "b.com/x", NetworkRuleAction.Modify(500, listOf("C" to "d"), listOf("E"), NetworkBodyEdit.Replace("{}"))),
            NetworkRule("3", "c.com/x", NetworkRuleAction.Modify(body = NetworkBodyEdit.Substitute("a", "b"))),
        )

        store.save(rules)

        assertEquals(rules, store.load())
    }

    @Test fun `a saved session remembers which rule changed a request`() {
        val rule = NetworkRule("1", "a.com/*", NetworkRuleAction.Mock(500, emptyList(), "x"))
        val exchange = NetworkExchange(1, 1, Instant.EPOCH, "GET", "https://a.com/b", emptyList(), CapturedBody.Empty,
            NetworkOutcome.Answered(NetworkResponse(500, "Internal Server Error", emptyList(), CapturedBody("x".toByteArray(), 1), Instant.EPOCH)),
            rule)

        assertEquals(rule, parseHar(toHar(listOf(exchange)) { null }).exchanges.single().rule)
    }
}
