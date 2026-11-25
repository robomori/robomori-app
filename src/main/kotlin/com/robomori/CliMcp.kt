package com.robomori

import kotlinx.serialization.json.*
import java.util.Base64

/**
 * `robomori mcp`: the device commands as a Model Context Protocol server over standard input and output. It stays
 * running and keeps its connection to each device's agent, so a tap and the screen it leads to come back in tens of
 * milliseconds, with no `robomori` process to start per action.
 */
internal fun Cli.mcp(options: Options) {
    options.expect()
    McpServer(this).serve()
}

private class McpServer(private val cli: Cli) {
    /** Each device's agent, by the name the tools were given, or "" for the default device. */
    private val agents = mutableMapOf<String, DeviceAgent>()
    /** Each device the tools use, by the name they were given, or "" for the default device. */
    private val devices = mutableMapOf<String, CliDevice>()
    /** Devices this server claimed, which it releases when the agent's session ends. */
    private val claimedHere = mutableSetOf<String>()

    fun serve() {
        val reader = cli.input.bufferedReader()
        try {
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val message = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull()
                if (message == null) {
                    send(buildJsonObject { put("jsonrpc", "2.0"); put("id", JsonNull); putError(-32700, "Parse error") })
                    continue
                }
                // Notifications, such as notifications/initialized, need no answer.
                val id = message["id"]?.takeUnless { it is JsonNull } ?: continue
                val params = message["params"] as? JsonObject ?: JsonObject(emptyMap())
                val method = message["method"]?.jsonPrimitive?.contentOrNull
                val result = when (method) {
                    "initialize" -> initialize(params)
                    "ping" -> JsonObject(emptyMap())
                    "tools/list" -> buildJsonObject { put("tools", tools) }
                    "tools/call" -> call(params)
                    else -> null
                }
                send(buildJsonObject {
                    put("jsonrpc", "2.0"); put("id", id)
                    if (result != null) put("result", result)
                    else if (method == null) putError(-32600, "Invalid request") else putError(-32601, "Method not found")
                })
            }
        } finally {
            agents.values.forEach { runCatching { it.close() } }
            claimedHere.forEach { runCatching { cli.releaseClaims(it) } }
        }
    }

    /**
     * The device a tool named, or without a name, the one this worktree claimed or else a free emulator, which it then
     * claims, as 'robomori device claim' does: agents working in parallel, each with its own server, each get their own.
     * A phone that's the only device is used without a claim, since a claim never picks a physical device by itself.
     */
    private fun device(name: String?): CliDevice = devices.getOrPut(name ?: "") {
        val wanted = name ?: cli.androidSerial
        val claimed = runCatching { cli.claimDevice(wanted) }
        if (claimed.isFailure && wanted == null) cli.target(Options(emptyList(), emptyMap(), emptySet()))
        else claimed.getOrThrow().also { (device, already) -> if (!already) claimedHere += device.name }.first
    }

    private fun JsonObjectBuilder.putError(code: Int, message: String) =
        put("error", buildJsonObject { put("code", code); put("message", message) })

    /** One message per line, in UTF-8 whatever the system's encoding, which on Windows mangles the screen's arrows. */
    private fun send(message: JsonObject) {
        cli.out.write((message.toString() + "\n").encodeToByteArray())
        cli.out.flush()
    }

    private fun initialize(params: JsonObject) = buildJsonObject {
        val requested = params["protocolVersion"]?.jsonPrimitive?.contentOrNull
        put("protocolVersion", requested?.takeIf { it in supportedVersions } ?: supportedVersions.first())
        put("capabilities", buildJsonObject { put("tools", JsonObject(emptyMap())) })
        put("serverInfo", buildJsonObject { put("name", "robomori"); put("version", APP_DISPLAY_VERSION) })
        put("instructions", INSTRUCTIONS)
    }

    private fun call(params: JsonObject): JsonObject {
        val name = params["name"]?.jsonPrimitive?.contentOrNull
        val args = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
        fun string(key: String) = args[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        fun flag(key: String) = args[key]?.jsonPrimitive?.booleanOrNull == true
        fun required(key: String) = string(key) ?: throw UsageException("Give $key.")
        val device = string("device")
        if (name == "devices") {
            val claimed = cli.claimedDevices(cli.devices())
            return text(cli.devices().filter { it.ready }.joinToString("\n") { running ->
                "${running.name} ${running.kind} ${running.serial}" + when (claimed[running.name]) {
                    null -> ""
                    cli.owner -> " (claimed here)"
                    else -> " (claimed by ${displayPath(claimed.getValue(running.name))})"
                }
            }.ifEmpty { "No device is running." })
        }
        if (name == "install") return try {
            val apks = cli.apks(listOf(required("apk")))
            cli.install(device(device), apks, flag("grant"))
            text("Installed ${apks.single().name}.")
        } catch (failure: Exception) { text(failure.message ?: failure.toString(), error = true) }
        if (tools.none { it.jsonObject["name"]?.jsonPrimitive?.content == name }) return text("Unknown tool: $name", error = true)
        return try {
            val agent = agent(device)
            /** Runs an action, then shows what it changed once the screen settled. */
            fun acted(done: String): JsonObject = text("$done\n${agent.text("ui", "diff")}")
            when (name) {
                "screen" -> text(agent.text(*listOfNotNull("ui", "all".takeIf { flag("all") }).toTypedArray()))
                "tap" -> acted("Tapped ${agent.text(*listOfNotNull("tap", required("target"), "long".takeIf { flag("long") },
                    args["times"]?.jsonPrimitive?.intOrNull?.let { "times=${it.coerceIn(1, 20)}" }).toTypedArray())}")
                "type" -> {
                    val clear = flag("clear")
                    val note = agent.text(*listOfNotNull("type", args["text"]?.jsonPrimitive?.contentOrNull ?: if (clear) "" else required("text"),
                        string("into") ?: "", "submit".takeIf { flag("submit") }, "clear".takeIf { clear }).toTypedArray())
                    acted(note.ifEmpty { "Typed." })
                }
                "scroll" -> {
                    val direction = required("direction")
                    if (direction !in listOf("up", "down", "left", "right")) throw UsageException("Scroll up, down, left or right.")
                    val found = agent.text(*listOfNotNull("scroll", direction, string("target") ?: "", string("until")?.let { "until=$it" }).toTypedArray())
                    acted(if (found.isEmpty()) "Scrolled $direction." else "Found $found")
                }
                "swipe", "drag" -> {
                    val through = string("through")?.trim()?.split(Regex("\\s+")).orEmpty()
                    val ms = args["duration_ms"]?.jsonPrimitive?.intOrNull?.let { "ms=${it.coerceIn(1, 60_000)}" }
                    agent.text(*(listOf(name, required("from"), required("to")) + through + listOfNotNull(ms)).toTypedArray())
                    acted(if (name == "swipe") "Swiped." else "Dragged.")
                }
                "zoom" -> {
                    val direction = required("direction")
                    if (direction !in listOf("in", "out")) throw UsageException("Zoom in or out.")
                    agent.text("zoom", direction, string("target") ?: "")
                    acted("Zoomed $direction.")
                }
                "press" -> {
                    agent.text(*listOfNotNull("key", required("key"), "long".takeIf { flag("long") }).toTypedArray())
                    acted("Pressed ${required("key")}.")
                }
                "launch" -> acted("Launched ${agent.text(*listOfNotNull("launch", required("app"), "restart".takeIf { flag("restart") },
                    "clear".takeIf { flag("clear_data") }).toTypedArray())}.")
                "close" -> acted("Closed ${agent.text("close", required("app"))}.")
                "apps" -> text(agent.text("apps"))
                "logs" -> text(agent.text("logs", string("app").orEmpty(), (args["since"]?.jsonPrimitive?.intOrNull ?: -1).toString(),
                    if (flag("all")) "all" else ""))
                "clipboard" -> {
                    val copy = args["text"]?.jsonPrimitive?.contentOrNull
                    if (copy == null) text(agent.text("clipboard").ifEmpty { "The clipboard is empty." })
                    else { agent.text("clipboard", copy); text("Copied.") }
                }
                "wait" -> {
                    val seconds = (args["timeout"]?.jsonPrimitive?.intOrNull ?: 10).coerceIn(1, 600)
                    val found = agent.text(*listOfNotNull("wait", required("text"), (seconds * 1000).toString(), "gone".takeIf { flag("gone") }).toTypedArray())
                    text(found.ifEmpty { "Gone." })
                }
                "screenshot" -> {
                    val size = args["size"]?.jsonPrimitive?.intOrNull ?: 540
                    val jpeg = agent.request("screenshot", size.coerceIn(100, 4000).toString(), "80")
                    buildJsonObject {
                        put("content", buildJsonArray { add(buildJsonObject {
                            put("type", "image"); put("data", Base64.getEncoder().encodeToString(jpeg)); put("mimeType", "image/jpeg")
                        }) })
                    }
                }
                else -> error("Unhandled tool: $name")
            }
        } catch (failure: Exception) {
            // A failed command leaves the connection usable; anything else might not have, so the next call reconnects.
            if (failure !is DeviceAgentException && failure !is UsageException) {
                agents.remove(device ?: "")?.close()
                devices.remove(device ?: "")
            }
            text(failure.message ?: failure.toString(), error = true)
        }
    }

    private fun agent(device: String?): DeviceAgent = agents.getOrPut(device ?: "") { cli.agent(device(device)) }

    private fun text(value: String, error: Boolean = false) = buildJsonObject {
        put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", McpServerText.forTools(value)) }) })
        if (error) put("isError", true)
    }

    companion object {
        val supportedVersions = listOf("2025-06-18", "2025-03-26", "2024-11-05")

        const val INSTRUCTIONS = "Operates Android emulators and devices. 'screen' reads the screen as text, one line per " +
            "element: @ref, role, \"label\", state, and (x,y) center, or [left,top,right,bottom] for lists; lines without " +
            "a ref are text to read. Name targets by @ref, by text, or as x,y. Every action returns only what changed on " +
            "the screen once it settled, or the whole screen when it's mostly new, so there's no need to read it again. " +
            "Toasts show as toast \"…\" lines. When an app crashes after an action, a crash line names its exception; " +
            "'logs' shows the stack trace. Without device, the first tool claims a free emulator for this worktree, as " +
            "'robomori device claim' does, so agents working in parallel each get their own; it's released when the session ends."

        private fun tool(name: String, description: String, vararg properties: Pair<String, JsonObject>, required: List<String> = emptyList()) =
            buildJsonObject {
                put("name", name)
                put("description", description)
                put("inputSchema", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        properties.forEach { (key, schema) -> put(key, schema) }
                        put("device", prop("string", "Device name or serial; only needed when several run."))
                    })
                    if (required.isNotEmpty()) put("required", buildJsonArray { required.forEach { add(it) } })
                })
            }

        private fun prop(type: String, description: String, choices: List<String>? = null) = buildJsonObject {
            put("type", type); put("description", description)
            choices?.let { put("enum", buildJsonArray { it.forEach(::add) }) }
        }

        private val target = prop("string", "@ref, text on the screen, or x,y")

        val tools = buildJsonArray {
            add(tool("screen", "Read the screen as text.",
                "all" to prop("boolean", "Include the status bar, navigation bar and keyboard.")))
            add(tool("tap", "Tap something.", "target" to target, "long" to prop("boolean", "Long press."),
                "times" to prop("integer", "Taps in a row, as fast as a double tap: 2 double taps."), required = listOf("target")))
            add(tool("type", "Type text into the focused field.", "text" to prop("string", "Text to type."),
                "into" to prop("string", "Field to tap first: @ref or its text."),
                "clear" to prop("boolean", "Replace the field's text instead of adding to it; with no text, empty it."),
                "submit" to prop("boolean", "Press enter afterwards.")))
            add(tool("scroll", "Scroll to show more in a direction.", "direction" to prop("string", "Which way to reveal more.",
                listOf("up", "down", "left", "right")), "target" to prop("string", "List to scroll: @ref or text. The largest when left out."),
                "until" to prop("string", "Keep scrolling until something with this text shows."), required = listOf("direction")))
            add(tool("swipe", "Swipe a finger from one place to another, such as to dismiss or page. For drag and drop, use drag.",
                "from" to target, "to" to prop("string", "@ref, text, x,y, or up, down, left or right to swipe that way."),
                "through" to prop("string", "x,y points between, separated by spaces, for a curved path such as around a dial."),
                "duration_ms" to prop("integer", "How long the swipe takes, in milliseconds; 300 by default."), required = listOf("from", "to")))
            add(tool("drag", "Press and hold something until it's picked up, then drop it elsewhere, such as to reorder a list.",
                "from" to target, "to" to target, "duration_ms" to prop("integer", "How long the move takes after the hold, in milliseconds; 600 by default."),
                required = listOf("from", "to")))
            add(tool("zoom", "Pinch with two fingers to zoom in or out.", "direction" to prop("string", "in or out.", listOf("in", "out")),
                "target" to prop("string", "What to zoom: @ref or text. The middle of the screen when left out."), required = listOf("direction")))
            add(tool("press", "Press a key or button, or open a system panel.", "key" to prop("string",
                "back, home, recents, enter, delete, tab, escape, power, volume-up, volume-down, up, down, left, right, " +
                    "notifications, quick-settings, power-menu, lock, a letter, or any KEYCODE_ name. Hold modifiers with +, " +
                    "such as ctrl+a."), "long" to prop("boolean", "Hold it down."), required = listOf("key")))
            add(tool("launch", "Open an app, or a link or deep link in the app that handles it.",
                "app" to prop("string", "Package, such as com.android.settings, or a link."),
                "restart" to prop("boolean", "Start over instead of resuming."),
                "clear_data" to prop("boolean", "Erase the app's data first, as on a fresh install."), required = listOf("app")))
            add(tool("close", "Stop an app completely.", "app" to prop("string", "Package."), required = listOf("app")))
            add(tool("install", "Install or update an app from an APK on this computer.", "apk" to prop("string", "Path to the APK."),
                "grant" to prop("boolean", "Grant all its permissions, so no prompts show."), required = listOf("apk")))
            add(tool("apps", "List the packages of the apps the launcher shows."))
            add(tool("clipboard", "Read the clipboard, or with text, copy text to it.", "text" to prop("string", "Text to copy.")))
            add(tool("logs", "What an app logged since logs were last read: warnings, errors and crash stack traces.",
                "app" to prop("string", "Package, such as com.example.app. Every app when left out."),
                "since" to prop("integer", "Seconds back to read from, instead of since logs were last read."),
                "all" to prop("boolean", "Every level, including debug and info.")))
            add(tool("wait", "Wait until text shows, or with gone until it doesn't.", "text" to prop("string", "Text to wait for."),
                "gone" to prop("boolean", "Wait for it to go away."), "timeout" to prop("integer", "Seconds; 10 by default."),
                required = listOf("text")))
            add(tool("screenshot", "See the screen as an image, for what text can't tell, such as layout or colors.",
                "size" to prop("integer", "The image's shorter side in pixels; 540 by default.")))
            add(tool("devices", "List running devices, and which are claimed."))
        }
    }
}

internal object McpServerText {
    /**
     * [text] with the robomori commands the device names, for people at a terminal, put as the tools that do the
     * same, since an agent here works through tools.
     */
    fun forTools(text: String): String = text
        .replace(Regex("""robomori app logs (\S+) shows the whole stack trace\.""")) { "The logs tool, with app ${it.groupValues[1]}, shows the whole stack trace." }
        .replace(Regex("""robomori input scroll (up|down|left|right) --until ("[^"]*")""")) { "scroll ${it.groupValues[1]} with until ${it.groupValues[2]}" }
        .replace("robomori input press back hides it.", "pressing back hides it.")
        .replace(Regex("""Name one with --into, such as --into (@\d+)\.""")) { "Name one with into, such as \"${it.groupValues[1]}\"." }
        .replace("or name it with --into.", "or name it with into.")
        .replace("Start another emulator, then run 'robomori device claim'.", "Start another emulator, then call the tool again.")
}
