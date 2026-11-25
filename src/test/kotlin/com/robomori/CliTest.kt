package com.robomori

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.*

class CliTest {
    @TempDir lateinit var root: File

    private val fake by lazy { FakeAndroid(root) }
    private val out = ByteArrayOutputStream()
    private val err = ByteArrayOutputStream()
    private var unlocked = true
    /** Physical devices `adb devices -l` lists, beside the fake's emulators. */
    private var physical = ""
    private val shellCommands = mutableListOf<List<String>>()
    private val adbCommands = mutableListOf<List<String>>()
    /** What the claimed device's settings file holds. */
    private var claimSettingsFile = ""

    /** Counts emulator starts, for the discovery file each emulator process reports. */
    private var boots = 1

    /** Answers the device commands the fake doesn't, as a booted Android 16 phone would. */
    private val executor = CommandExecutor { command, cancelled, output ->
        val args = command.args
        val shell = args.dropWhile { it != "shell" }.drop(1)
        if (shell.isNotEmpty()) shellCommands += shell
        adbCommands += args
        val script = shell.singleOrNull().orEmpty()
        when {
            // The file a claim keeps the device's settings in, on the device.
            script.startsWith("cat $CLAIM_SETTINGS") -> claimSettingsFile
            script.startsWith("printf '%s\\n' ") -> {
                claimSettingsFile = Regex("'([^']*)'").findAll(script.substringAfter("printf '%s\\n' ")).map { it.groupValues[1] }
                    .joinToString("\n") + "\n"
                ""
            }
            script == "rm -f $CLAIM_SETTINGS" -> { claimSettingsFile = ""; "" }
            args == listOf("devices", "-l") -> "List of devices attached\n$physical"
            shell.take(3) == listOf("cmd", "uimode", "night") -> "Night mode: ${shell.last()}\n"
            shell.take(2) == listOf("am", "get-config") -> "config: en-notnight-v36\n"
            shell.take(2) == listOf("dumpsys", "input_method") -> "mInputShown=false\n"
            shell.firstOrNull() == "settings" -> if ("get" in shell) "1.0\n" else ""
            shell.take(2) == listOf("getprop", "ro.build.version.release") -> "16\n"
            shell.take(2) == listOf("getprop", "ro.build.version.sdk") -> "36\n"
            shell.take(2) == listOf("getprop", "ro.serialno") -> "2B241FDH\n"
            args.take(4).drop(2) == listOf("emu", "geo") -> "OK\n"
            "reverse" in args -> ""
            args.takeLast(3) == listOf("emu", "avd", "discoverypath") -> "/avd/running/pid_$boots${args[1]}.ini\nOK\n"
            // The fake SDK lists ARM images; list this computer's own kind, as the real SDK would.
            args == listOf("--list") -> fake.run(command, cancelled, output).replace("arm64-v8a", abi)
            shell.isNotEmpty() && shell.first() != "getprop" -> ""
            else -> fake.run(command, cancelled, output)
        }
    }

    private val store by lazy { SettingsStore(File(root, "settings.properties")).also { it.save(fake.settings) } }

    private val windowRequests = mutableListOf<String>()
    private var appRunning = true
    private var appLaunches = 0
    private val windows by lazy { object : AppWindowRequests(File(root, "open-requests"), File(root, "app.lock")) {
        override fun request(emulator: String) { windowRequests += emulator }
        override fun appRunning() = appRunning
        override fun launchApp(os: HostOs): Boolean { appLaunches++; return true }
    } }

    /** What Robomori's agent on each device was asked, by serial. */
    private val agentRequests = mutableListOf<Pair<String, List<String>>>()
    /** How the agent answers a request; empty unless a test says otherwise. */
    private var agentAnswer: (List<String>) -> String = { "" }
    private var mcpInput = ""

    /** The folder commands run in: the worktree that claims devices. */
    private var workingDirectory: File? = null
    private var androidSerial: String? = null

    private val networkRules by lazy { FileNetworkRulesStore(File(root, "network-rules.json")) }

    private fun run(vararg args: String): Int =
        Cli(store, executor = executor, catalogCache = SdkCatalogCache(File(root, "sdk-catalog.txt")),
            mediaStore = EmulatorMediaStore(File(root, "emulator-media.properties")),
            localhostPorts = LocalhostPortsStore(File(root, "localhost-ports.properties")),
            presets = DevicePresetDirectory(File(root, "presets")), deviceNames = DeviceNamesStore(File(root, "names.properties")),
            unlocked = { unlocked }, out = PrintStream(out), err = PrintStream(err), pause = {},
            storagePlanner = { _, _ -> StoragePlan(ImageFootprint(0, 0), 0, emptyList()) }, windows = windows,
            claims = DeviceClaimsStore(File(root, "device-claims.properties")), workingDirectory = workingDirectory ?: root,
            androidSerial = androidSerial, input = mcpInput.byteInputStream(), networkRulesStore = networkRules,
            agentFor = { serial -> object : DeviceAgent {
                override fun request(vararg words: String): ByteArray {
                    agentRequests += serial to words.toList()
                    return agentAnswer(words.toList()).encodeToByteArray()
                }
            } }).run(args.toList())

    /** Runs commands from a new git worktree named [name]. */
    private fun inWorktree(name: String) {
        workingDirectory = File(root, name).apply { mkdirs(); File(this, ".git").writeText("gitdir: elsewhere\n") }
    }

    private fun output(): String = out.toString().trim().also { out.reset(); err.reset() }

    private fun json() = Json.parseToJsonElement(out.toString())
    private val abi = nativeImageAbi()!!
    private val nl = System.lineSeparator()

    @Test fun `files and data commands say what they need before touching a device`() {
        assertEquals(2, run("files", "push", File(root, "missing.txt").path, "/sdcard"))
        assertContains(err.toString(), "No file at")
        assertEquals(2, run("data", "query", "com.example.app", "app.db"))
        assertContains(err.toString(), "Name the app's package, database and SQL.")
        assertEquals(2, run("file", "list"), "The group is files")
    }

    @Test fun `network mock and rewrite add rules, which network rules lists, turns off and deletes`() {
        assertEquals(0, run("network", "mock", "api.example.com/v1/users*", "--method", "get", "--header", "Content-Type: text/plain",
            "--header", "X-A: 1", "--body", "hi"))
        val mockId = output().substringBefore(' ')
        assertEquals(0, run("network", "rewrite", "api.example.com/v1/flags", "--find", "\"on\":false", "--replace", "\"on\":true"))
        output()

        val (mock, rewrite) = networkRules.load()
        assertEquals(mockId, mock.id.take(8))
        assertEquals("GET", mock.method)
        assertEquals(NetworkRuleAction.Mock(200, listOf("Content-Type" to "text/plain", "X-A" to "1"), "hi"), mock.action)
        assertEquals(NetworkRuleAction.Modify(body = NetworkBodyEdit.Substitute("\"on\":false", "\"on\":true")), rewrite.action)

        assertEquals(2, run("network", "rewrite", "api.example.com/x"), "A rewrite that changes nothing is refused")
        assertEquals(2, run("network", "mock", "api.example.com/x", "--status", "999"))
        assertEquals(2, run("network", "delete-rule", "zzzz"))

        assertEquals(0, run("network", "disable-rule", mockId))
        assertFalse(networkRules.load().first().enabled)
        output()
        assertEquals(0, run("network", "rules", "--off"))
        assertTrue(output().startsWith("Rules are off"))
        assertFalse(networkRules.enabled())
        assertEquals(2, networkRules.load().size, "Turning rules off keeps them")

        assertEquals(0, run("network", "delete-rule", mockId))
        assertEquals(listOf(rewrite.id), networkRules.load().map { it.id })
    }

    @Test fun `create without a profile makes the newest Pixel phone on an installed image`() {
        File(fake.sdk, "system-images/android-36/google_apis/$abi").apply {
            mkdirs()
            File(this, "source.properties").writeText("Pkg.Desc=Google APIs ARM 64 System Image\n")
            File(this, "system.img").writeText("image")
        }
        assertEquals(0, run("emulator", "create"), err.toString())
        assertEquals("Pixel_10", out.toString().trim())
        val device = AndroidService(fake.settings, fake).devices().single()
        assertEquals("system-images/android-36/google_apis/$abi/", device.config["image.sysdir.1"])
    }

    @Test fun `create with a version downloads that image, and start waits for Android to boot`() {
        assertEquals(0, run("emulator", "create", "pixel-10", "--name", "Agent phone", "--api", "37", "--start", "--json"))
        val created = json().jsonObject
        assertEquals("Agent_phone", created["name"]?.jsonPrimitive?.content)
        assertEquals("emulator-5554", created["serial"]?.jsonPrimitive?.content)
        assertTrue(fake.commands.any { it.args.takeLast(2) == listOf("getprop", "sys.boot_completed") })
    }

    @Test fun `create downloads the Google APIs image unless the Play Store is asked for`() {
        fake.additionalImages = "\nsystem-images;android-37;google_apis;arm64-v8a | 2 | Google APIs ARM 64 System Image\n" +
            "system-images;android-37;google_apis;x86_64 | 2 | Google APIs Intel x86_64 Atom System Image\n" +
            "system-images;android-37;google_apis_playstore;x86_64 | 2 | Google Play Intel x86_64 Atom System Image\n"
        fun installed() = fake.commands.filter { it.args.firstOrNull() == "--install" }.map { it.args.last() }
        assertEquals(0, run("emulator", "create", "--api", "37"), err.toString())
        assertEquals(listOf("system-images;android-37;google_apis;$abi"), installed())
        fake.commands.clear()
        assertEquals(0, run("emulator", "create", "--api", "37", "--play-store"), err.toString())
        assertEquals(listOf("system-images;android-37;google_apis_playstore;$abi"), installed())
    }

    @Test fun `only phones, tablets and foldables take --play-store`() {
        assertEquals(2, run("emulator", "create", "pixel-watch-4-41mm", "--play-store"))
        assertContains(err.toString(), "--play-store is for phones, tablets and foldables.")
    }

    @Test fun `an unavailable version names the versions there are`() {
        assertEquals(1, run("emulator", "create", "pixel-10", "--api", "12"))
        assertContains(err.toString(), "Available: 37, 36")
        assertContains(err.toString(), "robomori image list --profile pixel-10")
        assertEquals(emptyList(), AndroidService(fake.settings, fake).devices())
    }

    @Test fun `emulators are found by the name the app shows`() {
        fake.device("Work_Phone")
        assertEquals(0, run("emulator", "start", "work phone", "--no-wait"))
        assertEquals(mapOf("Work_Phone" to "emulator-5554"), fake.active)
    }

    @Test fun `after the free trial every command needs a license, but help still shows`() {
        fake.device("Phone"); fake.device("Tablet")
        assertEquals(0, run("emulator", "start", "Phone", "--no-wait"), err.toString())
        assertEquals(0, run("emulator", "start", "Tablet", "--no-wait"), "During the trial, several emulators run at once")
        output()

        unlocked = false
        assertEquals(1, run("emulator", "stop", "Tablet"))
        assertContains(err.toString(), "Your Robomori free trial has ended. Buy a license at https://robomori.com/pricing")
        assertEquals(0, run("help"))
        assertEquals(0, run("version"))
        assertEquals(setOf("Phone", "Tablet"), fake.active.keys)
    }

    @Test fun `emulators started here hear the microphone chosen in the app`() {
        fake.device("Phone")
        assertEquals(0, run("emulator", "start", "Phone", "--no-wait"))
        assertTrue(fake.commands.none { FORWARD_MICROPHONE_ARGUMENT in it.args })

        EmulatorMediaStore(File(root, "emulator-media.properties")).save(EmulatorMedia(microphone = "MacBook Pro Microphone"))
        fake.active.clear()
        assertEquals(0, run("emulator", "start", "Phone", "--no-wait"))
        assertTrue(fake.commands.any { it.detached && FORWARD_MICROPHONE_ARGUMENT in it.args })
    }

    @Test fun `device list shows physical devices first, then running and stopped emulators`() {
        fake.device("Phone"); fake.device("Tablet")
        fake.active["Tablet"] = "emulator-5556"
        physical = "2B241FDH device usb:1 product:tokay model:Pixel_9 device:tokay\n"
        assertEquals(0, run("device", "list", "--json"), err.toString())
        val devices = json().jsonArray.map { it.jsonObject }
        assertEquals(listOf("Pixel 9", "Phone", "Tablet"), devices.map { it["name"]!!.jsonPrimitive.content })
        assertEquals(listOf("physical", "emulator", "emulator"), devices.map { it["kind"]!!.jsonPrimitive.content })
        assertEquals(listOf("connected", "not running", "running"), devices.map { it["state"]!!.jsonPrimitive.content })
        assertEquals("Android 16", devices[0]["android"]!!.jsonPrimitive.content)

        out.reset()
        assertEquals(0, run("device", "list", "--emulators", "--json"))
        assertEquals(2, json().jsonArray.size)
    }

    @Test fun `open shows a running emulator in the app and starts the app when it's closed`() {
        fake.device("Phone")
        fake.active["Phone"] = "emulator-5554"
        appRunning = false

        assertEquals(0, run("emulator", "open", "Phone"), err.toString())

        assertEquals("emulator-5554$nl", out.toString())
        assertEquals(listOf("Phone"), windowRequests)
        assertEquals(1, appLaunches)
        assertFalse(fake.commands.any { it.detached }, "A running emulator isn't started again")
    }

    @Test fun `open shows the window before starting a stopped emulator`() {
        fake.device("Phone")
        val launchesWhenShown = mutableListOf<Int>()
        val shown = object : AppWindowRequests(File(root, "open-requests"), File(root, "app.lock")) {
            override fun request(emulator: String) { launchesWhenShown += fake.commands.count { it.detached } }
            override fun appRunning() = true
        }
        Cli(store, executor = executor, catalogCache = SdkCatalogCache(File(root, "sdk-catalog.txt")),
            mediaStore = EmulatorMediaStore(File(root, "emulator-media.properties")),
            localhostPorts = LocalhostPortsStore(File(root, "localhost-ports.properties")),
            presets = DevicePresetDirectory(File(root, "presets")), deviceNames = DeviceNamesStore(File(root, "names.properties")),
            unlocked = { unlocked }, out = PrintStream(out), err = PrintStream(err), pause = {},
            storagePlanner = { _, _ -> StoragePlan(ImageFootprint(0, 0), 0, emptyList()) }, windows = shown).run(listOf("emulator", "open", "Phone"))

        assertEquals(listOf(0), launchesWhenShown, "The window is asked for before the emulator is launched")
        assertTrue(fake.commands.any { it.detached }, "The stopped emulator is then started")
    }

    @Test fun `a running emulator is stopped before it can be deleted`() {
        fake.device("Phone")
        fake.active["Phone"] = "emulator-5554"
        assertEquals(1, run("emulator", "delete", "Phone"))
        assertContains(err.toString(), "robomori emulator stop Phone")
        assertEquals(0, run("emulator", "stop", "Phone"))
        assertEquals(0, run("emulator", "delete", "Phone"))
        assertEquals(emptyList(), AndroidService(fake.settings, fake).devices())
    }

    @Test fun `a device that isn't running says how to start it`() {
        fake.device("Phone")
        assertEquals(1, run("controls", "dark-mode", "on", "--device", "Phone"))
        assertContains(err.toString(), "Phone isn't running. Start it with 'robomori emulator start Phone'.")
    }

    @Test fun `with several devices running, --device is required and the choices are listed`() {
        fake.device("Phone"); fake.device("Tablet")
        fake.active["Phone"] = "emulator-5554"; fake.active["Tablet"] = "emulator-5556"
        assertEquals(1, run("controls", "dark-mode", "on"))
        assertContains(err.toString(), "2 devices are running. Choose one with --device: Phone (emulator-5554), Tablet (emulator-5556).")
    }

    @Test fun `controls change the one running device`() {
        fake.device("Phone"); fake.active["Phone"] = "emulator-5554"
        assertEquals(0, run("controls", "dark-mode", "on"), err.toString())
        assertTrue(listOf("cmd", "uimode", "night", "yes") in shellCommands)
    }

    @Test fun `animations set all three Android scales`() {
        fake.device("Phone"); fake.active["Phone"] = "emulator-5554"
        assertEquals(0, run("controls", "animations", "off"), err.toString())
        AnimationScaleSetting.entries.forEach { assertTrue(listOf("settings", "put", "global", it.key, "0.0") in shellCommands) }
    }

    @Test fun `control values are checked before anything changes`() {
        fake.device("Phone"); fake.active["Phone"] = "emulator-5554"
        assertEquals(2, run("controls", "font-scale", "huge"))
        assertContains(err.toString(), "font-scale takes 0.85, 1, 1.15, 1.3, 1.5, 1.8, 2")
        assertEquals(2, run("controls", "dark-mod", "on"))
        assertContains(err.toString(), "Did you mean dark-mode?")
        assertTrue(shellCommands.none { "put" in it || "uimode" in it })
    }

    @Test fun `simulated location goes to the emulator as longitude then latitude`() {
        fake.device("Phone"); fake.active["Phone"] = "emulator-5554"
        assertEquals(0, run("simulate", "location", "set", "-33.8568,151.2153"), err.toString())
        assertTrue(listOf("-s", "emulator-5554", "emu", "geo", "fix", "151.2153", "-33.8568") in adbCommands)
        assertEquals("-33.8568,151.2153", out.toString().trim())
    }

    @Test fun `clearing a simulated location turns an emulator's location off`() {
        fake.device("Phone"); fake.active["Phone"] = "emulator-5554"
        assertEquals(0, run("simulate", "location", "clear"), err.toString())
        assertTrue(listOf("cmd", "location", "set-location-enabled", "false", "--user", "current") in shellCommands)
        assertEquals(2, run("simulate", "location", "none"))
    }

    @Test fun `buttons are pressed on the running device`() {
        fake.device("Phone"); fake.active["Phone"] = "emulator-5554"
        assertEquals(0, run("press", "back", "--quiet"), err.toString())
        assertEquals("emulator-5554" to listOf("key", "KEYCODE_BACK"), agentRequests.single())
        assertEquals(2, run("press", "bakc"))
        assertContains(err.toString(), "Did you mean back?")
    }

    @Test fun `the screen is read and operated through the device's agent`() {
        fake.device("Phone"); fake.active["Phone"] = "emulator-5554"
        agentAnswer = { words -> when (words.first()) {
            "ui" -> if ("diff" in words) "+ @2 button \"Next\" (540,900)" else "com.example 1080x2400\n@1 button \"Sign in\" (540,1200)"
            "tap" -> "@1 button \"Sign in\" (540,1200)"
            else -> ""
        } }
        assertEquals(0, run("screen", "read"), err.toString())
        assertEquals("com.example 1080x2400\n@1 button \"Sign in\" (540,1200)", output(), "The screen as the device wrote it")

        assertEquals(0, run("tap", "Sign in"), err.toString())
        assertEquals(listOf("tap", "Sign in"), agentRequests[1].second)
        assertEquals(listOf("ui", "diff"), agentRequests[2].second, "An action then shows what it changed")
        assertEquals("@1 button \"Sign in\" (540,1200)$nl+ @2 button \"Next\" (540,900)", output())

        agentRequests.clear()
        assertEquals(0, run("type", "ada@example.com", "--into", "@3", "--submit", "--quiet"), err.toString())
        assertEquals(listOf(listOf("type", "ada@example.com", "@3", "submit")), agentRequests.map { it.second })

        agentRequests.clear()
        assertEquals(0, run("scroll", "down", "--quiet"), err.toString())
        assertEquals(0, run("swipe", "900,1200", "100,1200", "--quiet"), err.toString())
        assertEquals(0, run("screen", "wait", "Welcome", "--timeout", "20"), err.toString())
        assertEquals(listOf(listOf("scroll", "down", ""), listOf("swipe", "900,1200", "100,1200"),
            listOf("wait", "Welcome", "20000")), agentRequests.map { it.second })

        assertEquals(2, run("scroll", "dwn"))
        assertContains(err.toString(), "Did you mean down?")
        assertEquals(2, run("swipe", "900,1200", "nowhere", "100,1200"), "Points between the ends are x,y")
    }

    @Test fun `gestures, keys and apps go to the device's agent`() {
        fake.device("Phone"); fake.active["Phone"] = "emulator-5554"
        agentAnswer = { words -> when (words.first()) {
            "apps" -> "com.android.settings\ncom.example"
            "clipboard" -> if (words.size == 1) "copied text" else ""
            "scroll" -> "@9 \"About phone\" (540,1500)"
            else -> ""
        } }
        val commands = listOf(
            listOf("tap", "Android version", "--times", "4") to listOf("tap", "Android version", "times=4"),
            listOf("swipe", "@5", "left", "--duration", "0.2s") to listOf("swipe", "@5", "left", "ms=200"),
            listOf("swipe", "790,960", "540,1210", "290,960") to listOf("swipe", "790,960", "290,960", "540,1210"),
            listOf("input", "drag", "Wi-Fi", "@7", "--duration", "800ms") to listOf("drag", "Wi-Fi", "@7", "ms=800"),
            listOf("input", "zoom", "in") to listOf("zoom", "in", ""),
            listOf("scroll", "down", "--until", "About phone") to listOf("scroll", "down", "", "until=About phone"),
            listOf("type", "--into", "@3", "--clear") to listOf("type", "", "@3", "clear"),
            listOf("press", "ctrl+a") to listOf("key", "ctrl+a"),
            listOf("press", "notifications") to listOf("key", "notifications"),
            listOf("press", "power", "--long") to listOf("key", "KEYCODE_POWER", "long"),
            listOf("app", "launch", "com.example", "--clear-data") to listOf("launch", "com.example", "clear"),
            listOf("app", "launch", "myapp://orders/42") to listOf("launch", "myapp://orders/42"),
            listOf("app", "close", "com.example") to listOf("close", "com.example"),
            listOf("device", "clipboard", "hello") to listOf("clipboard", "hello"),
            listOf("type", "--quiet", "--", "-v") to listOf("type", "-v", ""),
            listOf("type", "--", "--help") to listOf("type", "--help", ""),
            listOf("screen", "wait", "Done", "--timeout", "1m") to listOf("wait", "Done", "60000"),
            listOf("screen", "wait", "Done", "--timeout", "500ms") to listOf("wait", "Done", "500"),
            listOf("app", "logs", "com.example", "--since", "2m") to listOf("logs", "com.example", "120", ""),
            listOf("app", "logs", "com.example", "--since", "90") to listOf("logs", "com.example", "90", ""),
        )
        for ((command, request) in commands) {
            agentRequests.clear()
            assertEquals(0, run(*command.toTypedArray()), err.toString())
            assertEquals(request, agentRequests.first().second, command.joinToString(" "))
        }
        output()
        assertEquals(0, run("scroll", "down", "--until", "About phone"), err.toString())
        assertEquals("@9 \"About phone\" (540,1500)", output(), "Scrolling until something shows prints it")
        assertEquals(0, run("app", "list"), err.toString())
        assertEquals("com.android.settings${nl}com.example", output())
        assertEquals(0, run("device", "clipboard"), err.toString())
        assertEquals("copied text", output())

        assertEquals(2, run("press", "ctrl+bakc"))
        assertContains(err.toString(), "Did you mean back?")
        assertEquals(2, run("press", "hyper+a"))
        assertEquals(2, run("input", "zoom", "sideways"))
        assertEquals(2, run("tap", "x", "--times", "0"))
        assertEquals(2, run("type"), "Text is needed unless the field is cleared")
    }

    @Test fun `install puts APKs on the device through adb`() {
        fake.device("Phone"); fake.active["Phone"] = "emulator-5554"
        val apk = File(root, "app-debug.apk").apply { writeText("apk") }
        assertEquals(0, run("app", "install", apk.path, "--grant"), err.toString())
        assertTrue(listOf("-s", "emulator-5554", "install", "-r", "-t", "-g", apk.path) in adbCommands, adbCommands.toString())
        assertEquals(2, run("app", "install", File(root, "missing.apk").path))
        assertEquals(2, run("app", "install", File(root, "names.properties").apply { writeText("") }.path))
    }

    @Test fun `a command the agent can't carry out fails with its reason`() {
        fake.device("Phone"); fake.active["Phone"] = "emulator-5554"
        agentAnswer = { throw DeviceAgentException("Nothing on the screen matches \"Sign up\".") }
        assertEquals(1, run("tap", "Sign up"))
        assertEquals("robomori: Nothing on the screen matches \"Sign up\".", err.toString().trim())
    }

    @Test fun `the MCP server lists its tools and runs them on the device`() {
        fake.device("Phone"); fake.active["Phone"] = "emulator-5554"
        agentAnswer = { words -> when (words.first()) {
            "tap" -> "@1 button \"Sign in\" (540,1200)"
            "ui" -> "+ Welcome"
            "screenshot" -> "jpeg"
            else -> throw DeviceAgentException("No @9 on the screen last shown. Read the screen again.")
        } }
        mcpInput = listOf(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}""",
            """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
            """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""",
            """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"tap","arguments":{"target":"Sign in"}}}""",
            """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"press","arguments":{"key":"@9"}}}""",
            """{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"screenshot","arguments":{}}}""",
            """{"jsonrpc":"2.0","id":6,"method":"nothing"}""",
        ).joinToString("\n")
        assertEquals(0, run("mcp"), err.toString())
        val answers = out.toString().trim().lines().map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(listOf(1, 2, 3, 4, 5, 6), answers.map { it["id"]!!.jsonPrimitive.int }, "Notifications get no answer")
        assertEquals("2025-06-18", answers[0]["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content)
        val tools = answers[1]["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue(listOf("screen", "tap", "type", "scroll", "press", "screenshot").all { it in tools }, tools.toString())
        fun content(answer: JsonObject) = answer["result"]!!.jsonObject["content"]!!.jsonArray.single().jsonObject
        assertEquals("Tapped @1 button \"Sign in\" (540,1200)\n+ Welcome", content(answers[2])["text"]!!.jsonPrimitive.content,
            "An action answers with what it changed")
        assertEquals(true, answers[3]["result"]!!.jsonObject["isError"]?.jsonPrimitive?.boolean)
        assertEquals("image/jpeg", content(answers[4])["mimeType"]!!.jsonPrimitive.content)
        assertEquals(-32601, answers[5]["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        assertEquals(listOf("key", "@9"), agentRequests[2].second)
        assertEquals(listOf("screenshot", "540", "80"), agentRequests.last().second)
    }

    @Test fun `each MCP server claims its own emulator for its worktree, and releases it when the session ends`() {
        fake.device("Phone"); fake.device("Tablet")
        fake.active["Phone"] = "emulator-5554"; fake.active["Tablet"] = "emulator-5556"
        mcpInput = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"tap","arguments":{"target":"Sign in"}}}"""
        inWorktree("a")
        assertEquals(0, run("device", "claim", "Phone"), err.toString())
        inWorktree("b")
        assertEquals(0, run("mcp"), err.toString())
        assertEquals(listOf("emulator-5556"), agentRequests.map { it.first }.distinct(), "Not the device another worktree claimed")
        assertEquals(setOf("Phone"), DeviceClaimsStore(File(root, "device-claims.properties")).load().keys,
            "The server's claim ends with it; the other worktree's stays")

        inWorktree("d")
        assertEquals(0, run("device", "claim", "Tablet"), err.toString())
        agentRequests.clear(); out.reset()
        inWorktree("c")
        assertEquals(0, run("mcp"))
        assertContains(out.toString(), "Every running device is claimed")
        assertTrue(agentRequests.isEmpty(), "Never uses a device another worktree claimed")
    }

    @Test fun `the MCP server uses a phone that's the only device without claiming it`() {
        physical = "2B241FDH device usb:1 product:tokay model:Pixel_9 device:tokay\n"
        mcpInput = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"tap","arguments":{"target":"Sign in"}}}"""
        assertEquals(0, run("mcp"), err.toString())
        assertEquals(listOf("2B241FDH"), agentRequests.map { it.first }.distinct())
        assertTrue(DeviceClaimsStore(File(root, "device-claims.properties")).load().isEmpty())
    }

    @Test fun `every option that takes a time refuses what isn't one`() {
        fake.device("Phone"); fake.active["Phone"] = "emulator-5554"
        for (command in listOf(listOf("screen", "wait", "x", "--timeout", "soon"), listOf("app", "logs", "--since", "0"),
            listOf("swipe", "@1", "up", "--duration", "2m"), listOf("screen", "record", "--duration", "-1"))) {
            err.reset()
            assertEquals(2, run(*command.toTypedArray()), command.toString())
            assertContains(err.toString(), "takes a time such as 500ms, 30s or 2m")
        }
    }

    @Test fun `rotating prints what changed on the screen, unless quiet`() {
        fake.device("Phone"); fake.active["Phone"] = "emulator-5554"
        agentAnswer = { words -> if (words == listOf("ui", "diff", "acted")) "+ Landscape" else "" }
        assertEquals(0, run("device", "rotate"), err.toString())
        assertContains(out.toString(), "+ Landscape")
        agentRequests.clear()
        assertEquals(0, run("device", "rotate", "--quiet"), err.toString())
        assertTrue(agentRequests.isEmpty())
    }

    @Test fun `an emulator rotates through its motion sensors`() {
        fake.device("Phone"); fake.active["Phone"] = "emulator-5554"
        assertEquals(0, run("rotate", "counterclockwise"), err.toString())
        assertEquals(3, adbCommands.count { it == listOf("-s", "emulator-5554", "emu", "rotate") })
        assertEquals(0, run("rotate"))
        assertEquals(4, adbCommands.count { it == listOf("-s", "emulator-5554", "emu", "rotate") })
    }

    @Test fun `only a foldable folds, and only while it runs`() {
        fake.device("Phone"); fake.active["Phone"] = "emulator-5554"
        assertEquals(1, run("emulator", "fold", "Phone"))
        assertContains(err.toString(), "Phone isn't a foldable.")
    }

    @Test fun `the on-screen keyboard control shows the keyboard with a hardware one`() {
        fake.device("Phone"); fake.active["Phone"] = "emulator-5554"
        assertEquals(0, run("controls", "keyboard", "on"), err.toString())
        assertTrue(listOf("settings", "--user", "current", "put", "secure", "show_ime_with_hard_keyboard", "1") in shellCommands)
    }

    @Test fun `a running device's controls save as a preset, which can be deleted`() {
        fake.device("Phone"); fake.active["Phone"] = "emulator-5554"
        assertEquals(0, run("preset", "save", "Current"), err.toString())
        val saved = DevicePresetStore(DevicePresetDirectory(File(root, "presets")).files().single()).load().single()
        assertEquals("Current", saved.name)
        assertEquals(PresetValue.Scale(1f), saved.values[PresetSetting.Animations])
        assertEquals(0, run("preset", "delete", "current"))
        assertEquals(emptyList(), DevicePresetDirectory(File(root, "presets")).files())
    }

    @Test fun `presets saved in the app apply by name`() {
        fake.device("Phone"); fake.active["Phone"] = "emulator-5554"
        DevicePresetDirectory(File(root, "presets")).create(DevicePreset("Night", mapOf(PresetSetting.DarkMode to PresetValue.Toggle(true))))
        assertEquals(0, run("preset", "list"))
        assertContains(out.toString(), "Night  dark-mode on")
        assertEquals(0, run("preset", "apply", "night", "--json"), err.toString())
        assertTrue(listOf("cmd", "uimode", "night", "yes") in shellCommands)
        assertEquals(1, run("preset", "apply", "Nigth"))
        assertContains(err.toString(), "Did you mean Night?")
    }

    @Test fun `config changes the app's settings after checking them, and unset returns to the default`() {
        val emulator = File(fake.sdk, "emulator/" + HostPlatform().executable("emulator"))
        assertEquals(0, run("config", "set", "adb", emulator.path))
        assertEquals(emulator.path, store.load().adb)
        assertEquals(1, run("config", "set", "adb", File(root, "missing").path))
        assertContains(err.toString(), "isn't an executable file")

        out.reset()
        assertEquals(0, run("config", "list", "--json"))
        val sources = json().jsonArray.associate { it.jsonObject["key"]!!.jsonPrimitive.content to it.jsonObject["source"]!!.jsonPrimitive.content }
        assertEquals("set", sources["adb"]); assertEquals("from sdk", sources["emulator"])

        assertEquals(0, run("config", "unset", "adb"))
        assertEquals("", store.load().adb)
        assertFalse("adb" in store.chosen())
    }

    @Test fun `config ports changes the ports devices reach as localhost, and unset returns to 3000 and 8080`() {
        val ports = LocalhostPortsStore(File(root, "localhost-ports.properties"))
        assertEquals(0, run("config", "get", "ports"))
        assertEquals("3000,8080", out.toString().trim())

        assertEquals(0, run("config", "set", "ports", "5173, 3000"))
        assertEquals(listOf(5173, 3000), ports.load())
        assertEquals(1, run("config", "set", "ports", "abc"))
        assertContains(err.toString(), "Enter a port from 1 to 65535")
        assertEquals(listOf(5173, 3000), ports.load())

        assertEquals(0, run("config", "unset", "ports"))
        assertEquals(listOf(3000, 8080), ports.load())
        assertFalse(ports.chosen())
    }

    @Test fun `every command has its own help`() {
        assertEquals(0, run("emulator", "create", "--help"))
        assertContains(out.toString(), "Usage: robomori emulator create [<profile>]")
        out.reset()
        assertEquals(0, run("help", "controls"))
        assertContains(out.toString(), "animations <scale>")
        out.reset()
        assertEquals(0, run("emulator"))
        assertContains(out.toString(), "Usage: robomori emulator <command>")
        out.reset()
        assertEquals(0, run())
        assertContains(out.toString(), "Usage: robomori <command>")
    }

    @Test fun `mistyped commands are usage errors that suggest the right one`() {
        assertEquals(2, run("emulatr", "list"))
        assertContains(err.toString(), "Did you mean emulator?")
        assertEquals(2, run("emulator", "lst"))
        assertEquals(2, run("device", "list", "--colour"))
        assertEquals(2, run("emulator", "create", "pixel-99"))
        assertContains(err.toString(), "Did you mean pixel-10?")
        assertEquals(2, run("emulator", "stop"))
        assertEquals(2, run("help", "input", "tapp"))
        assertContains(err.toString(), "Did you mean input tap?")
        assertEquals(2, run("device", "list", "--lnog"))
        assertContains(err.toString(), "Did you mean --long?")
    }

    @Test fun `with --json, errors come back as JSON too`() {
        assertEquals(1, run("emulator", "start", "Nothing", "--json"))
        val error = Json.parseToJsonElement(err.toString().trim()).jsonObject
        assertContains(error["error"]!!.jsonPrimitive.content, "No emulator named Nothing")
    }

    @Test fun `each worktree claims its own running emulator and keeps it`() {
        fake.device("Phone"); fake.device("Tablet"); fake.device("Watch")
        fake.active["Phone"] = "emulator-5554"; fake.active["Tablet"] = "emulator-5556"
        physical = "2B241FDH device usb:1 product:tokay model:Pixel_9 device:tokay\n"

        inWorktree("a")
        assertEquals(0, run("device", "claim"), err.toString())
        assertEquals("emulator-5554", output())
        assertEquals(0, run("device", "claim"), "Claiming again returns the same device")
        assertEquals("emulator-5554", output())

        inWorktree("b")
        File(workingDirectory, "app/src").mkdirs()
        workingDirectory = File(workingDirectory, "app/src")
        assertEquals(0, run("device", "claim"), err.toString())
        assertEquals("emulator-5556", output(), "A subfolder claims for its whole worktree")

        inWorktree("c")
        assertEquals(1, run("device", "claim"), "Never takes a physical device or starts a stopped emulator")
        assertContains(err.toString(), "No free emulator: Phone is claimed by")
        assertContains(err.toString(), "Start one with 'robomori emulator start Watch', then run 'robomori device claim' again.")
        assertEquals(setOf("Phone", "Tablet"), fake.active.keys)
    }

    @Test fun `a claim leaves animations alone unless asked to turn them off`() {
        fake.device("Phone"); fake.active["Phone"] = "emulator-5554"
        fun animationsTurnedOff() = shellCommands.count { it == listOf("settings", "put", "global", "animator_duration_scale", "0.0") }

        inWorktree("a")
        assertEquals(0, run("device", "claim"), err.toString())
        assertEquals(0, animationsTurnedOff(), "A plain claim keeps the emulator's animations")
        assertEquals(0, run("device", "release"), err.toString())

        assertEquals(0, run("device", "claim", "--no-animations"), err.toString())
        assertEquals(1, animationsTurnedOff())
    }

    @Test fun `release puts back the settings the claim found`() {
        fake.device("Phone"); fake.active["Phone"] = "emulator-5554"
        inWorktree("a")
        assertEquals(0, run("device", "claim"), err.toString())
        assertContains(claimSettingsFile, "dark-mode=off\n")
        assertContains(claimSettingsFile, "animator_duration_scale=1.0\n")

        // As if dark mode was on and animations slower when the device was claimed.
        claimSettingsFile = claimSettingsFile.replace("dark-mode=off", "dark-mode=on").replace("animator_duration_scale=1.0", "animator_duration_scale=10.0")
        assertEquals(0, run("device", "release"), err.toString())
        assertTrue(shellCommands.contains(listOf("cmd", "uimode", "night", "yes")), "Dark mode goes back on")
        assertTrue(shellCommands.contains(listOf("settings", "put", "global", "animator_duration_scale", "10.0")), "So do slow animations")
        assertFalse(shellCommands.any { it.take(4) == listOf("settings", "put", "global", "window_animation_scale") },
            "What didn't change isn't touched")
        assertEquals("", claimSettingsFile, "The kept settings are forgotten")
    }

    @Test fun `a device to claim or release can be named with --device too`() {
        fake.device("Phone"); fake.device("Tablet"); fake.active["Phone"] = "emulator-5554"; fake.active["Tablet"] = "emulator-5556"
        inWorktree("a")
        assertEquals(0, run("device", "claim", "--device", "Tablet"), err.toString())
        assertEquals("emulator-5556", output())
        assertEquals(0, run("device", "release", "--device", "Tablet"), err.toString())
        assertEquals("Tablet", output())
    }

    @Test fun `commands use the claimed device and refuse ones other worktrees claimed`() {
        fake.device("Phone"); fake.device("Tablet")
        fake.active["Phone"] = "emulator-5554"; fake.active["Tablet"] = "emulator-5556"
        inWorktree("a"); run("device", "claim", "Tablet"); output()

        assertEquals(0, run("press", "back"), err.toString())
        assertEquals("emulator-5556", agentRequests.last().first)

        inWorktree("b")
        assertEquals(0, run("press", "back"), "The only unclaimed device needs no --device")
        assertEquals("emulator-5554", agentRequests.last().first)
        output()
        assertEquals(1, run("press", "back", "--device", "Tablet"))
        assertContains(err.toString(), "Tablet is claimed by")
        assertContains(err.toString(), "'robomori device claim Tablet --force'")
        output()
        assertEquals(1, run("emulator", "stop", "Tablet"))
        assertEquals(setOf("Phone", "Tablet"), fake.active.keys)
        output()
        assertEquals(1, run("device", "claim", "Tablet"))
        output()

        assertEquals(0, run("device", "claim", "Tablet", "--force"), err.toString())
        inWorktree("a")
        androidSerial = "emulator-5556"
        assertEquals(1, run("press", "back"), "ANDROID_SERIAL names the device it was told to use, which it lost in the takeover")
        assertContains(err.toString(), "Tablet is claimed by")
    }

    @Test fun `a claim ends on release, when the device stops, or when its worktree is gone`() {
        fake.device("Phone"); fake.active["Phone"] = "emulator-5554"
        inWorktree("a"); run("device", "claim"); output()
        assertEquals(0, run("device", "release"))
        assertEquals("Phone", output())
        inWorktree("b")
        assertEquals(0, run("device", "claim"), "Released devices are free again")
        output()

        fake.active.clear(); boots++
        assertEquals(0, run("device", "list", "--json"))
        assertEquals(JsonNull, json().jsonArray.single().jsonObject["claimedBy"], "A stopped device has no claim")
        output()
        fake.active["Phone"] = "emulator-5554"
        inWorktree("c")
        assertEquals(0, run("device", "claim"), "A restarted device is free again")
        output()

        File(root, "c").deleteRecursively()
        inWorktree("d")
        assertEquals(0, run("device", "claim"), "A deleted worktree's claim is free again")
        output()
        assertEquals(0, run("device", "list", "--json"))
        val owner = json().jsonArray.single().jsonObject["claimedBy"]!!.jsonPrimitive.content
        assertEquals(File(root, "d").absolutePath, owner)
    }
}

private const val CLAIM_SETTINGS = "/data/local/tmp/.robomori-claim-settings"
