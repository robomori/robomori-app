package com.robomori

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.*
import sun.misc.Signal
import java.io.File
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * `robomori network record`: routes a device's traffic through Robomori, as the app's Network page does, and prints
 * each request as it finishes, until Ctrl+C or `--duration`. With `--output`, saves them as a HAR file too, with their
 * headers and bodies. The rules saved in the app, or with `robomori network mock` and `rewrite`, change responses here
 * too, including ones added while it records.
 */
internal fun Cli.networkRecord(options: Options) {
    options.expect(values = setOf("device", "duration", "app", "output"))
    val duration = options.value("duration")?.let { parseTime(it, "--duration", max = 24 * 3_600_000L) }
    val onlyApp = options.value("app")
    val har = options.value("output")?.let { File(it).absoluteFile }
    val device = target(options)
    val serial = device.serial!!
    val service = NetworkInspectorService(settings, executor)
    if (service.recordedElsewhere(serial)) throw IllegalStateException(
        "Robomori's app is recording ${device.name}'s traffic. Close its Network page, then try again.")
    val rules = LiveRules(networkRulesStore)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val network = NetworkInspectorState(service, { NetworkCertificateAuthority(HostPlatform().configDirectory) }, serial, scope,
        rules = rules::active, listApps = { InstalledAppsService(settings, executor).appsWithData(it, everyApp = true) })
    val stop = CountDownLatch(1)
    val previous = listOf("INT", "TERM").associateWith { Signal.handle(Signal(it)) { stop.countDown() } }
    val printed = mutableSetOf<Long>()
    fun printFinished(all: Boolean) {
        for (exchange in network.exchanges.toList()) {
            if (exchange.id in printed || (!all && exchange.inProgress)) continue
            val app = network.appOf(exchange)
            // An app's connections are named a moment after they open; wait for that before leaving any out.
            if (onlyApp != null && app == null && !all) continue
            printed += exchange.id
            if (onlyApp != null && app != onlyApp) continue
            if (jsonOutput) printJson(exchange.toCliJson(app)) else out.println(exchange.cliLine(app))
            out.flush()
        }
    }
    try {
        progress("Starting to record ${device.name}'s traffic…")
        network.start()
        while (true) {
            when (val routing = network.routing) {
                is NetworkRouting.Failed -> throw IllegalStateException(routing.cause.message ?: "Robomori couldn't record ${device.name}'s traffic.")
                is NetworkRouting.On -> break
                else -> if (stop.await(100, TimeUnit.MILLISECONDS)) return
            }
        }
        if ((network.routing as NetworkRouting.On).trust == CertificateTrust.AppConfiguration) {
            progress("Reading HTTPS from debug builds only: this device doesn't let Robomori read every app's. Other apps show their servers.")
        }
        progress(if (duration != null) "Recording for ${formatTime(duration)}…" else "Recording. Press Ctrl+C to stop.")
        val until = duration?.let { System.currentTimeMillis() + it }
        while (true) {
            rules.refresh()
            printFinished(all = false)
            (network.routing as? NetworkRouting.Failed)?.let { throw IllegalStateException(it.cause.message ?: "Recording stopped.") }
            val left = until?.let { it - System.currentTimeMillis() }
            if (left != null && left <= 0) break
            if (stop.await(minOf(left ?: 200, 200), TimeUnit.MILLISECONDS)) break
        }
        printFinished(all = true)
    } finally {
        previous.forEach { (name, handler) -> Signal.handle(Signal(name), handler) }
        network.stop()
        // Lets the device reach the network directly again before this process ends.
        runCatching { service.unroute(serial) }
        scope.cancel()
    }
    if (har != null) {
        val listed = network.exchanges.filter { onlyApp == null || network.appOf(it) == onlyApp }
        har.parentFile?.mkdirs()
        writeAtomically(har, toHar(listed, network::appOf))
        progress("Saved ${listed.size} requests to ${har.path}")
    }
}

/** The rules file, read again when it changes, so rules added while recording apply at once. */
private class LiveRules(private val store: NetworkRulesStore) {
    private var version = -1L
    @Volatile var active: List<NetworkRule> = emptyList(); private set

    fun refresh() {
        val current = store.version()
        if (current == version) return
        version = current
        active = runCatching { if (store.enabled()) store.load().filter { it.enabled } else emptyList() }.getOrDefault(emptyList())
    }

    init { refresh() }
}

/** One line for a finished request: its status, method, URL, how long it took, its size and its app. */
private fun NetworkExchange.cliLine(app: String?): String {
    val status = when (val outcome = outcome) {
        is NetworkOutcome.Answered -> outcome.response.status.toString()
        is NetworkOutcome.Failed -> "FAILED"
        NetworkOutcome.CertificateRejected, is NetworkOutcome.Encrypted -> "ENCRYPTED"
        NetworkOutcome.Pending -> "PENDING"
    }
    val details = listOfNotNull(
        durationMillis?.takeUnless { outcome is NetworkOutcome.Encrypted }?.let { "$it ms" },
        responseSize?.let { "$it B" },
        app,
        rule?.let { "changed by rule ${it.shortId}" },
        (outcome as? NetworkOutcome.Failed)?.message,
    )
    return "$status $method $url" + if (details.isEmpty()) "" else "  (${details.joinToString(", ")})"
}

private fun NetworkExchange.toCliJson(app: String?): JsonObject = buildJsonObject {
    put("id", id)
    put("time", formatRequestTime(startedAt, ZoneId.systemDefault(), hours24 = true))
    put("startedAt", startedAt.toString())
    app?.let { put("app", it) }
    put("method", method)
    put("url", url)
    when (val outcome = outcome) {
        is NetworkOutcome.Answered -> put("status", outcome.response.status)
        is NetworkOutcome.Failed -> put("error", outcome.message)
        NetworkOutcome.CertificateRejected, is NetworkOutcome.Encrypted -> put("encrypted", true)
        NetworkOutcome.Pending -> {}
    }
    type?.let { put("type", it) }
    durationMillis?.takeUnless { outcome is NetworkOutcome.Encrypted }?.let { put("durationMs", it) }
    responseSize?.let { put("size", it) }
    rule?.let { put("rule", it.shortId) }
}

/**
 * `robomori network rules`: the rules, in the order they're tried, each with the short ID the other commands take.
 * `--off` stops every rule from changing responses, keeping them; `--on` uses them again.
 */
internal fun Cli.networkRules(options: Options) {
    options.expect(flags = setOf("on", "off"))
    val store = networkRulesStore
    if (options.flag("on") && options.flag("off")) throw UsageException("Choose --on or --off.")
    if (options.flag("on") || options.flag("off")) store.saveEnabled(options.flag("on"))
    val rules = store.load()
    val enabled = store.enabled()
    if (jsonOutput) return printJson(buildJsonObject {
        put("enabled", enabled)
        putJsonArray("rules") { rules.forEach { add(JsonObject(it.toJson() + ("id" to JsonPrimitive(it.shortId)))) } }
    })
    if (!enabled) out.println("Rules are off: none change responses. Turn them on with 'robomori network rules --on'.")
    if (rules.isEmpty()) return out.println("No rules. Add one with 'robomori network mock' or 'robomori network rewrite'.")
    for (rule in rules) out.println("${rule.shortId}  ${if (rule.enabled) "on " else "off"}  ${rule.summary}")
}

/** `robomori network mock <url>`: a rule that answers matching requests itself, without contacting the server. */
internal fun Cli.networkMock(options: Options) {
    options.expect(1..1, values = setOf("method", "status", "header", "body", "body-file"), what = "requests' URL, such as api.example.com/v1/users*")
    val draft = NetworkRuleDraft(method = method(options), url = options.positional.single(), kind = NetworkRuleKind.Mock,
        mockStatus = options.value("status") ?: "200",
        mockHeaders = options.values("header").ifEmpty { listOf("Content-Type: application/json") }.joinToString("\n"),
        mockBody = body(options).orEmpty())
    addRule(draft)
}

/** `robomori network rewrite <url>`: a rule that changes the server's real response to matching requests. */
internal fun Cli.networkRewrite(options: Options) {
    options.expect(1..1, values = setOf("method", "status", "header", "remove-header", "body", "body-file", "find", "replace"),
        what = "requests' URL, such as api.example.com/v1/users*")
    val body = body(options)
    val find = options.value("find")
    if (body != null && find != null) throw UsageException("Choose --body or --find: one replaces the body, the other text in it.")
    if (find == null && options.value("replace") != null) throw UsageException("--replace goes with --find.")
    val draft = NetworkRuleDraft(method = method(options), url = options.positional.single(), kind = NetworkRuleKind.Modify,
        modifyStatus = options.value("status").orEmpty(), setHeaders = options.values("header").joinToString("\n"),
        removeHeaders = options.values("remove-header").joinToString(", "),
        bodyEdit = when {
            body != null -> NetworkBodyEditKind.Replace
            find != null -> NetworkBodyEditKind.Substitute
            else -> NetworkBodyEditKind.Keep
        },
        replacementBody = body.orEmpty(), find = find.orEmpty(), replacement = options.value("replace").orEmpty())
    if (draft.bodyEdit == NetworkBodyEditKind.Keep && draft.modifyStatus.isBlank() && draft.setHeaders.isBlank() && draft.removeHeaders.isBlank()) {
        throw UsageException("Say what to change: --status, --header, --remove-header, --body or --find.")
    }
    addRule(draft)
}

internal fun Cli.networkDeleteRule(options: Options) = changeRule(options, "Deleted") { rules, rule -> rules - rule }

internal fun Cli.networkEnableRule(options: Options) =
    changeRule(options, "Turned on") { rules, rule -> rules.map { if (it.id == rule.id) it.copy(enabled = true) else it } }

internal fun Cli.networkDisableRule(options: Options) =
    changeRule(options, "Turned off") { rules, rule -> rules.map { if (it.id == rule.id) it.copy(enabled = false) else it } }

private fun method(options: Options) = options.value("method")?.uppercase()?.takeUnless { it == "ANY" }

/** The body given with --body, or read from the file --body-file names. */
private fun body(options: Options): String? {
    val text = options.value("body")
    val file = options.value("body-file")
    if (text != null && file != null) throw UsageException("Choose --body or --body-file.")
    return text ?: file?.let { path ->
        File(path).takeIf { it.isFile }?.readText() ?: throw UsageException("No file at $path.")
    }
}

private fun Cli.addRule(draft: NetworkRuleDraft) {
    val rule = draft.toRule() ?: throw UsageException(when (draft.problem!!) {
        NetworkRuleProblem.MissingUrl -> "Give the requests' server and path, such as api.example.com/v1/users*."
        NetworkRuleProblem.InvalidStatus -> "Give --status as a code from 100 to 599."
        NetworkRuleProblem.InvalidHeader -> "Write each --header as 'Name: value'."
        NetworkRuleProblem.MissingFind -> "Give the text to find with --find."
    })
    val store = networkRulesStore
    store.save(store.load() + rule)
    if (jsonOutput) printJson(JsonObject(rule.toJson() + ("id" to JsonPrimitive(rule.shortId))))
    else out.println("${rule.shortId}  ${rule.summary}")
}

private fun Cli.changeRule(options: Options, done: String, change: (List<NetworkRule>, NetworkRule) -> List<NetworkRule>) {
    options.expect(1..1, what = "rule's ID, from 'robomori network rules'")
    val id = options.positional.single()
    val store = networkRulesStore
    val rules = store.load()
    val matches = rules.filter { it.id.startsWith(id, ignoreCase = true) }
    val rule = matches.singleOrNull() ?: throw UsageException(
        if (matches.isEmpty()) "No rule $id." else "$id matches ${matches.size} rules. Give more of its ID.",
        "Run 'robomori network rules' to see them.")
    store.save(change(rules, rule))
    if (jsonOutput) printJson(buildJsonObject { put("id", rule.shortId); put("summary", rule.summary) })
    else out.println("$done ${rule.shortId}  ${rule.summary}")
}

/** The start of the rule's ID, enough to tell it apart, as the commands print and take it. */
internal val NetworkRule.shortId: String get() = id.take(8)
