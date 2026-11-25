package com.robomori

import kotlinx.serialization.json.*
import java.io.File

/*
 * Commands that read and operate a running device's screen for coding agents, through Robomori's agent on the device.
 * Each answers in milliseconds, so most of a command's time is starting `robomori` itself; `robomori mcp` skips even
 * that.
 */

/** Opens the agent on a running device. */
internal fun Cli.agent(device: CliDevice): DeviceAgent = agentFor?.invoke(device.serial!!)
    ?: AdbDeviceAgent(device.serial!!, adb = { args -> android.command(android.toolchain.adb, listOf("-s", device.serial) + args) })

internal fun Cli.ui(options: Options) {
    options.expect(values = setOf("device"), flags = setOf("all"))
    val device = target(options)
    val screen = agent(device).use { it.text(*listOfNotNull("ui", "all".takeIf { options.flag("all") }).toTypedArray()) }
    if (jsonOutput) printJson(buildJsonObject { put("device", device.name); put("screen", screen) }) else out.println(screen)
}

internal fun Cli.tap(options: Options) {
    options.expect(1..1, values = setOf("device", "times"), flags = setOf("long", "quiet"),
        what = "thing to tap: an @ref from 'robomori screen read', its text, or x,y")
    val times = options.value("times")?.let { it.toIntOrNull()?.takeIf { n -> n in 1..20 } ?: throw UsageException("--times takes 1 to 20.") }
    act(options, "tapped") {
        it.text(*listOfNotNull("tap", options.positional.single(), "long".takeIf { options.flag("long") }, times?.let { n -> "times=$n" }).toTypedArray())
    }
}

internal fun Cli.type(options: Options) {
    val clear = options.flag("clear")
    options.expect(if (clear) 0..1 else 1..1, values = setOf("device", "into"), flags = setOf("submit", "clear", "quiet"), what = "text to type")
    val text = options.positional.singleOrNull() ?: ""
    act(options, "typed") { agent ->
        // The agent says when the field ended up showing something else, such as a number field refusing letters.
        agent.text(*listOfNotNull("type", text, options.value("into") ?: "", "submit".takeIf { options.flag("submit") },
            "clear".takeIf { clear }).toTypedArray()).ifEmpty { text }
    }
}

private val directions = listOf("up", "down", "left", "right")

private fun direction(value: String, verb: String, choices: List<String> = directions): String {
    if (value !in choices) throw UsageException("Unknown direction: $value.${suggestion(value, choices)}", "$verb ${choices.joinToString(", ")}.")
    return value
}

internal fun Cli.scroll(options: Options) {
    options.expect(1..2, values = setOf("device", "until"), flags = setOf("quiet"), what = "direction: ${directions.joinToString(", ")}")
    val direction = direction(options.positional.first(), "Scroll")
    act(options, "scrolled") { agent ->
        agent.text(*listOfNotNull("scroll", direction, options.positional.getOrElse(1) { "" },
            options.value("until")?.let { "until=$it" }).toTypedArray()).ifEmpty { direction }
    }
}

private val point = Regex("""\s*(\d+)\s*,\s*(\d+)\s*""")

/** --duration as the agent takes it for a gesture, in milliseconds. */
private fun Options.duration(): String? = value("duration")?.let { "ms=" + parseTime(it, "--duration", max = 60_000) }

internal fun Cli.swipe(options: Options) {
    options.expect(2..50, values = setOf("device", "duration"), flags = setOf("quiet"),
        what = "start and end: an @ref, text or x,y each, or a direction for the end, such as 540,1600 540,400")
    val from = options.positional.first()
    val to = options.positional.last()
    val through = options.positional.subList(1, options.positional.size - 1)
    through.firstOrNull { point.matchEntire(it) == null }?.let {
        throw UsageException("Give the points between the start and the end as x,y, not $it.")
    }
    act(options, "swiped") { agent ->
        agent.text(*(listOf("swipe", from, to) + through + listOfNotNull(options.duration())).toTypedArray())
        if (to in directions) "$from $to" else "$from to $to"
    }
}

internal fun Cli.drag(options: Options) {
    options.expect(2..2, values = setOf("device", "duration"), flags = setOf("quiet"), what = "thing to drag and where to drop it")
    val (from, to) = options.positional
    act(options, "dragged") { agent ->
        agent.text(*listOfNotNull("drag", from, to, options.duration()).toTypedArray())
        "$from to $to"
    }
}

internal fun Cli.zoom(options: Options) {
    options.expect(1..2, values = setOf("device"), flags = setOf("quiet"), what = "direction: in or out")
    val direction = direction(options.positional.first(), "Zoom", listOf("in", "out"))
    act(options, "zoomed") { agent -> agent.text("zoom", direction, options.positional.getOrElse(1) { "" }); direction }
}

internal fun Cli.launch(options: Options) {
    options.expect(1..1, values = setOf("device"), flags = setOf("restart", "clear-data", "quiet"),
        what = "app's package, such as com.android.settings, or a link")
    act(options, "launched") {
        it.text(*listOfNotNull("launch", options.positional.single(), "restart".takeIf { options.flag("restart") },
            "clear".takeIf { options.flag("clear-data") }).toTypedArray())
    }
}

internal fun Cli.close(options: Options) {
    options.expect(1..1, values = setOf("device"), flags = setOf("quiet"), what = "app's package, such as com.android.settings")
    act(options, "closed") { it.text("close", options.positional.single()) }
}

internal fun Cli.apps(options: Options) {
    options.expect(values = setOf("device"))
    val device = target(options)
    val apps = agent(device).use { it.text("apps") }.lines().filter { it.isNotBlank() }
    if (jsonOutput) printJson(buildJsonObject { put("device", device.name); put("apps", buildJsonArray { apps.forEach(::add) }) })
    else apps.forEach(out::println)
}

internal fun Cli.clipboard(options: Options) {
    options.expect(0..1, values = setOf("device"), what = "text to copy")
    val device = target(options)
    val text = options.positional.singleOrNull()
    val clip = agent(device).use { if (text == null) it.text("clipboard") else it.text("clipboard", text).let { text } }
    if (jsonOutput) printJson(buildJsonObject { put("device", device.name); put("clipboard", clip) })
    else if (text == null && clip.isNotEmpty()) out.println(clip)
}

internal fun Cli.install(options: Options) {
    options.expect(1..20, values = setOf("device"), flags = setOf("grant"), what = "APK to install")
    val apks = apks(options.positional)
    val device = target(options)
    install(device, apks, grant = options.flag("grant"))
    if (jsonOutput) printJson(buildJsonObject { put("device", device.name); put("installed", buildJsonArray { apks.forEach { add(it.path) } }) })
    else out.println("Installed ${apks.joinToString(", ") { it.name }} on ${device.name}.")
}

/** The APKs at [paths], from the folder the command runs in. */
internal fun Cli.apks(paths: List<String>): List<File> = paths.map { path ->
    workingDirectory.resolve(path).takeIf { it.isFile } ?: throw UsageException("There's no file at $path.")
}.onEach { if (it.extension.lowercase() != "apk") throw UsageException("${it.name} isn't an APK.") }

/** Installs [apks] on [device]: several are the splits of one app, which install together. */
internal fun Cli.install(device: CliDevice, apks: List<File>, grant: Boolean) {
    android.command(android.toolchain.adb, listOf("-s", device.serial!!, if (apks.size > 1) "install-multiple" else "install", "-r", "-t")
        + listOfNotNull("-g".takeIf { grant }) + apks.map { it.path })
}

internal fun Cli.uninstall(options: Options) {
    options.expect(1..1, values = setOf("device"), what = "app's package, such as com.example.app")
    val app = options.positional.single()
    if (!app.matches(Regex("""[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)+"""))) throw UsageException("Name the app by its package, such as com.example.app.")
    val device = target(options)
    // pm path fails for a package that isn't installed, where adb uninstall only says it failed.
    if (runCatching { android.command(android.toolchain.adb, listOf("-s", device.serial!!, "shell", "pm", "path", app)) }.getOrDefault("").isBlank())
        throw IllegalArgumentException("$app isn't installed.")
    val result = android.command(android.toolchain.adb, listOf("-s", device.serial!!, "uninstall", app))
    if (!result.contains("Success")) throw IllegalStateException(result.trim().ifEmpty { "Couldn't uninstall $app." })
    if (jsonOutput) printJson(buildJsonObject { put("device", device.name); put("uninstalled", app) })
    else out.println("Uninstalled $app from ${device.name}.")
}

internal fun Cli.waitFor(options: Options) {
    options.expect(1..1, values = setOf("device", "timeout"), flags = setOf("gone"), what = "text to wait for")
    val timeout = options.value("timeout")?.let { parseTime(it, "--timeout", max = 3_600_000) } ?: 10_000
    val device = target(options)
    val found = agent(device).use {
        it.text(*listOfNotNull("wait", options.positional.single(), timeout.toString(), "gone".takeIf { options.flag("gone") }).toTypedArray())
    }
    if (jsonOutput) printJson(buildJsonObject { put("device", device.name); put("found", found) }) else if (found.isNotEmpty()) out.println(found)
}

/**
 * A time as every option takes it, in milliseconds: 500ms, 30s, 2m, or a number of seconds such as 30 or 0.3, up to
 * [max] milliseconds.
 */
internal fun parseTime(value: String, option: String, max: Long): Long =
    Regex("""(\d+(?:\.\d+)?)\s*(ms|s|m)?""").matchEntire(value.trim())?.let { match ->
        val unit = when (match.groupValues[2]) { "ms" -> 1.0; "m" -> 60_000.0; else -> 1000.0 }
        Math.round(match.groupValues[1].toDouble() * unit)
    }?.takeIf { it in 1..max } ?: throw UsageException("$option takes a time such as 500ms, 30s or 2m, up to ${formatTime(max)}.")

/** [ms] as people write it: 500ms, 30s or 2m. */
internal fun formatTime(ms: Long): String = when {
    ms % 60_000 == 0L -> "${ms / 60_000}m"
    ms % 1000 == 0L -> "${ms / 1000}s"
    else -> "${ms}ms"
}

/**
 * Runs [action] on the target device and prints what it reports, then, unless --quiet, what changed on the screen once it
 * settled, which saves the agent a second command.
 */
private fun Cli.act(options: Options, verb: String, action: (DeviceAgent) -> String) {
    val device = target(options)
    agent(device).use { agent ->
        val result = action(agent)
        val screen = if (!options.flag("quiet")) agent.text("ui", "diff") else null
        if (jsonOutput) printJson(buildJsonObject {
            put("device", device.name); put(verb, result); screen?.let { put("screen", it) }
        }) else {
            if (result.isNotEmpty()) out.println(result)
            screen?.let(out::println)
        }
    }
}

internal fun Cli.logs(options: Options) {
    options.expect(0..1, values = setOf("device", "since"), flags = setOf("all"), what = "app's package, such as com.example.app")
    val app = options.positional.singleOrNull()?.trim().orEmpty()
    val since = options.value("since")?.let { ((parseTime(it, "--since", max = 86_400_000) + 999) / 1000).toInt() } ?: -1
    val device = target(options)
    val log = agent(device).use { it.text("logs", app, since.toString(), if (options.flag("all")) "all" else "") }
    if (jsonOutput) printJson(buildJsonObject { put("device", device.name); put("app", app); put("log", log) }) else out.println(log)
}
