package com.robomori

import java.io.File
import java.io.PrintStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.*

/** The first argument that runs Robomori as a command-line tool instead of opening its window. */
internal const val CLI_ARGUMENT = "cli"

/**
 * `robomori <command>`: Robomori for scripts and coding agents. Commands are grouped by what they act on (`device`,
 * `emulator`, `app`, `screen`, `input`, `preset`, `config`), such as `robomori input tap`. Results go to [out], as JSON with `--json`; progress and errors go to [err]. [run] returns the exit code:
 * 0 when it worked, 1 when it failed, 2 when the command was typed wrong.
 */
internal class Cli(
    internal val settingsStore: SettingsStore = SettingsStore(),
    private val loadSettings: () -> AppSettings = { settingsStore.load() },
    internal val executor: CommandExecutor = ProcessExecutor(),
    internal val catalogCache: SdkCatalogCache = SdkCatalogCache(),
    internal val presets: DevicePresetDirectory = DevicePresetDirectory(),
    internal val deviceNames: DeviceNamesStore = DeviceNamesStore(),
    /** The camera and microphone chosen in the app, which emulators started here use too. */
    internal val mediaStore: EmulatorMediaStore = EmulatorMediaStore(),
    /** The graphics chosen in the app, which emulators started here use too. */
    internal val graphicsStore: EmulatorGraphicsStore = EmulatorGraphicsStore(),
    /** The computer's ports that devices reach as localhost, set up on emulators started here too. */
    internal val localhostPorts: LocalhostPortsStore = LocalhostPortsStore(),
    internal val capture: AdbDeviceCapture = AdbDeviceCapture(),
    /** Whether Robomori may be used: with a license, or during the free trial, which the first run of the app or CLI starts. */
    private val unlocked: () -> Boolean = { AppLicense(CoroutineScope(Dispatchers.IO)).unlocked },
    internal val out: PrintStream = System.out,
    internal val err: PrintStream = System.err,
    internal val pause: (Long) -> Unit = Thread::sleep,
    internal val bootTimeoutMillis: Long = 180_000,
    internal val storagePlanner: (CreateSpec, AppSettings) -> StoragePlan = ::planDeviceStorage,
    internal val windows: AppWindowRequests = AppWindowRequests(),
    internal val claims: DeviceClaimsStore = DeviceClaimsStore(),
    /** Where the command runs. Devices claimed from here belong to its git worktree. */
    internal val workingDirectory: File = File(System.getProperty("user.dir")),
    /** The device adb uses when none is named, which commands here use too. */
    internal val androidSerial: String? = System.getenv("ANDROID_SERIAL"),
    /** Robomori's agent on the device with this serial; through adb when absent. */
    internal val agentFor: ((String) -> DeviceAgent)? = null,
    internal val input: java.io.InputStream = System.`in`,
    /** The rules that change responses, shared with the app's Network page. */
    internal val networkRulesStore: NetworkRulesStore = FileNetworkRulesStore(),
) {
    internal val settings by lazy(loadSettings)
    internal val android by lazy { AndroidService(settings, executor) }
    internal val owner by lazy { claimOwner(workingDirectory) }
    private var json = false

    fun run(args: List<String>): Int {
        json = "--json" in options(args)
        return try {
            route(args)
            0
        } catch (e: UsageException) {
            report(e.message.orEmpty(), e.hint)
            2
        } catch (e: Exception) {
            report(e.message ?: e.toString(), null)
            1
        }
    }

    private fun route(given: List<String>) {
        val args = CliHelp.canonical(given)
        val command = args.firstOrNull()
        if (command == null || command == "help" || command == "--help" || command == "-h") return printHelp(args.drop(1))
        if (command == "version" || command == "--version") {
            Options.parse(args.drop(1)).expect()
            return if (json) printJson(buildJsonObject { put("version", APP_DISPLAY_VERSION) }) else out.println("$APP_NAME $APP_DISPLAY_VERSION")
        }
        if (command == LOCATION_KEEPER) return locationKeeper(args.drop(1))
        // Everything but help and the version needs a license once the free trial is over, as the app does.
        if (!unlocked()) throw IllegalStateException("Your $APP_NAME free trial has ended. Buy a license at https://robomori.com/pricing, " +
            "then activate it in $APP_NAME under Settings → Your License.")
        val group = CliHelp.groups[command]
        if (group == null && command !in CliHelp.commands) {
            throw UsageException("Unknown command: $command.${suggestion(command, CliHelp.topLevel + CliHelp.aliases.keys.filter { ' ' !in it })}",
                "Run 'robomori help' to see the commands.")
        }
        val subcommand = args.getOrNull(1)?.takeIf { group != null && !it.startsWith("-") }
        val rest = args.drop(if (subcommand != null) 2 else 1)
        if ("--help" in options(rest) || "-h" in options(rest)) return printHelp(listOfNotNull(command, subcommand))
        if (group != null) {
            if (subcommand == null) return printHelp(listOf(command))
            if (subcommand !in group) throw UsageException("Unknown command: $command $subcommand.${suggestion(subcommand, group)}",
                "Run 'robomori help $command' to see its commands.")
        }
        val options = Options.parse(rest)
        when (listOfNotNull(command, subcommand).joinToString(" ")) {
            "device list" -> deviceList(options)
            "emulator create" -> emulatorCreate(options)
            "emulator start" -> emulatorStart(options)
            "emulator open" -> emulatorOpen(options)
            "emulator stop" -> emulatorStop(options)
            "emulator delete" -> emulatorDelete(options)
            "emulator wipe" -> emulatorWipe(options)
            "emulator fold" -> emulatorFold(options, folded = true)
            "emulator unfold" -> emulatorFold(options, folded = false)
            "device restart" -> deviceRestart(options)
            "device claim" -> claim(options)
            "device release" -> release(options)
            "input press" -> press(options)
            "device rotate" -> rotate(options)
            "device controls" -> controls(options)
            "device location" -> simulateLocation(options)
            "screen shot" -> screenshot(options)
            "screen record" -> record(options)
            "screen read" -> ui(options)
            "input tap" -> tap(options)
            "input type" -> type(options)
            "input scroll" -> scroll(options)
            "input swipe" -> swipe(options)
            "input drag" -> drag(options)
            "input zoom" -> zoom(options)
            "app launch" -> launch(options)
            "app close" -> close(options)
            "app list" -> apps(options)
            "device clipboard" -> clipboard(options)
            "app install" -> install(options)
            "app uninstall" -> uninstall(options)
            "screen wait" -> waitFor(options)
            "app logs" -> logs(options)
            "mcp" -> mcp(options)
            "preset list" -> presetList(options)
            "preset apply" -> presetApply(options)
            "preset save" -> presetSave(options)
            "preset delete" -> presetDelete(options)
            "profile list" -> profileList(options)
            "image list" -> imageList(options)
            "config list" -> configList(options)
            "config get" -> configGet(options)
            "config set" -> configSet(options)
            "config unset" -> configUnset(options)
            "files list" -> fileList(options)
            "files read" -> fileRead(options)
            "files pull" -> filePull(options)
            "files push" -> filePush(options)
            "files delete" -> fileDelete(options)
            "data list" -> dataList(options)
            "data query" -> dataQuery(options)
            "data prefs" -> dataPrefs(options)
            "network record" -> networkRecord(options)
            "network rules" -> networkRules(options)
            "network mock" -> networkMock(options)
            "network rewrite" -> networkRewrite(options)
            "network delete-rule" -> networkDeleteRule(options)
            "network enable-rule" -> networkEnableRule(options)
            "network disable-rule" -> networkDisableRule(options)
        }
    }

    private fun printHelp(path: List<String>) {
        val words = CliHelp.canonical(path.filterNot { it.startsWith("-") })
        val asked = words.joinToString(" ")
        val names = CliHelp.topLevel + CliHelp.groups.flatMap { (group, commands) -> commands.map { "$group $it" } }
        val best = names.minBy { editDistance(asked, it) }.takeIf { editDistance(asked, it) <= maxOf(2, asked.length / 3) }
        val text = CliHelp.text(words) ?: throw UsageException("No help for $asked." + (best?.let { " Did you mean $it?" } ?: ""),
            "Run 'robomori help' to see the commands.")
        out.print(text)
    }

    /** [args] before --, after which everything is text, such as to type "-v". */
    private fun options(args: List<String>) = args.takeWhile { it != "--" }

    private fun report(message: String, hint: String?) {
        if (json) err.println(Json.encodeToString(JsonElement.serializer(), buildJsonObject {
            put("error", message); hint?.let { put("hint", it) }
        })) else {
            err.println("robomori: $message")
            hint?.let(err::println)
        }
    }

    internal fun progress(message: String) = err.println(message)

    internal fun printJson(element: JsonElement) = out.println(compactJson.encodeToString(JsonElement.serializer(), element))

    internal fun printTable(header: List<String>, rows: List<List<String>>) {
        val widths = header.indices.map { column -> (rows.map { it[column] } + header[column]).maxOf(String::length) }
        (listOf(header) + rows).forEach { row ->
            out.println(row.mapIndexed { column, cell -> if (column == row.lastIndex) cell else cell.padEnd(widths[column]) }.joinToString("  "))
        }
    }

    internal val jsonOutput get() = json

    private companion object {
        /** On one line: agents read it, and pipe through jq to read it yourself. */
        val compactJson = Json
    }
}

/** A command typed wrong: exit code 2, with a [hint] at how to type it. */
internal class UsageException(message: String, val hint: String? = null) : IllegalArgumentException(message)

/** " Did you mean x?" when [word] is close to one of [choices], so a typo is one step from fixed. */
internal fun suggestion(word: String, choices: Collection<String>): String {
    val best = choices.minByOrNull { editDistance(word.lowercase(), it.lowercase()) } ?: return ""
    return if (editDistance(word.lowercase(), best.lowercase()) <= maxOf(2, word.length / 3)) " Did you mean ${shellWord(best)}?" else ""
}

internal fun editDistance(a: String, b: String): Int {
    var previous = IntArray(b.length + 1) { it }
    for (i in a.indices) {
        val current = IntArray(b.length + 1).also { it[0] = i + 1 }
        for (j in b.indices) current[j + 1] = minOf(previous[j + 1] + 1, current[j] + 1, previous[j] + if (a[i] == b[j]) 0 else 1)
        previous = current
    }
    return previous[b.length]
}

/** [text] as it would be typed in a shell: quoted when it has spaces. */
internal fun shellWord(text: String) = if (text.any { it.isWhitespace() || it in "'\"$&;|<>()" }) "\"$text\"" else text

/** `--name value` options, `--flag` switches and positional arguments, in any order. */
internal class Options(val positional: List<String>, private val values: Map<String, String>, private val flags: Set<String>,
                       private val repeated: Map<String, List<String>> = emptyMap()) {
    fun value(name: String) = values[name]
    /** Every value given for an option that can be given more than once, such as --header, in order. */
    fun values(name: String) = repeated[name].orEmpty()
    fun flag(name: String) = name in flags

    /** Rejects anything the command doesn't take, and a wrong number of positional arguments. */
    fun expect(positional: IntRange = 0..0, values: Set<String> = emptySet(), flags: Set<String> = emptySet(), what: String = "argument") {
        val known = (values + flags).map { "--$it" }
        (this.values.keys - values).firstOrNull()?.let { throw UsageException("Unknown option: --$it.${suggestion("--$it", known)}") }
        (this.flags - flags - "json").firstOrNull()?.let { throw UsageException("Unknown option: --$it.${suggestion("--$it", known)}") }
        if (this.positional.size > positional.last) throw UsageException("Unexpected argument: ${this.positional[positional.last]}.")
        if (this.positional.size < positional.first) throw UsageException("Name the $what.")
    }

    companion object {
        private val valueOptions = setOf("device", "profile", "name", "api", "output", "duration", "into", "timeout", "times", "until", "size", "since",
            "app", "method", "status", "header", "remove-header", "body", "body-file", "find", "replace", "limit")
        private val shortOptions = mapOf("o" to "output")

        fun parse(args: List<String>): Options {
            val positional = mutableListOf<String>(); val values = mutableMapOf<String, String>(); val flags = mutableSetOf<String>()
            val repeated = mutableMapOf<String, MutableList<String>>()
            val queue = ArrayDeque(args)
            while (queue.isNotEmpty()) {
                val arg = queue.removeFirst()
                if (arg == "--") { positional += queue; break }
                val name = when {
                    arg.startsWith("--") -> arg.removePrefix("--")
                    // A leading minus followed by a digit is a value, such as a southern latitude.
                    arg.length > 1 && arg.startsWith("-") && !arg[1].isDigit() -> shortOptions[arg.drop(1)]
                        ?: throw UsageException("Unknown option: $arg.")
                    else -> { positional += arg; continue }
                }
                val (key, inline) = name.split('=', limit = 2).let { it[0] to it.getOrNull(1) }
                if (key in valueOptions) {
                    val value = inline ?: queue.removeFirstOrNull() ?: throw UsageException("--$key needs a value.")
                    values[key] = value
                    repeated.getOrPut(key) { mutableListOf() } += value
                } else flags += key
            }
            return Options(positional, values, flags, repeated)
        }
    }
}
