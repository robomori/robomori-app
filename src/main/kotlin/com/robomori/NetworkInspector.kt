package com.robomori

import androidx.compose.runtime.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URI
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import io.airlift.compress.zstd.ZstdInputStream
import org.brotli.dec.BrotliInputStream

/** The port the VPN helper sends the device's traffic to; `adb reverse` carries it to the proxy on this computer. */
internal const val NetworkProxyDevicePort = 18765
/** The proxy setting Robomori used before its VPN, which a device left inspected by such a version may still have. */
private const val legacyProxySetting = "127.0.0.1:$NetworkProxyDevicePort"

/** Whether apps on the device accept the sites' certificates Robomori makes, so their HTTPS traffic can be read. */
internal enum class CertificateTrust {
    /** Installed as a system certificate, which every app trusts. */
    System,
    /** The device can't be rooted, so only apps that trust Robomori's certificate in their own configuration. */
    AppConfiguration,
}

internal class NetworkInspectorService(settings: AppSettings, executor: CommandExecutor = ProcessExecutor()) {
    private val android = AndroidService(settings, executor)

    /**
     * Whether Android has finished booting and can install apps, so its settings and services can be changed. It can
     * say it has booted while its package manager is still down, such as while its system restarts.
     */
    fun ready(serial: String): Boolean = runCatching {
        android.bootCompleted(serial) && shell(serial, "pm path android").lineSequence().any { it.startsWith("package:") }
    }.getOrDefault(false)

    /**
     * Sends every app's TCP traffic to the proxy at [hostPort] on this computer, through the VPN helper, which this
     * installs when the device has no copy of this build of it. adb lets it start its VPN without asking the user. The
     * helper then connects back to the proxy, which tells when its VPN is up.
     */
    fun route(serial: String, hostPort: Int) {
        installHelper(serial)
        shell(serial, "appops set ${VpnHelper.packageName} ACTIVATE_VPN allow")
        // Shows that traffic is being inspected. Android before 13 grants it on install and refuses to grant it here.
        runCatching { shell(serial, "pm grant ${VpnHelper.packageName} android.permission.POST_NOTIFICATIONS") }
        adb(serial, "reverse", "tcp:$NetworkProxyDevicePort", "tcp:$hostPort")
        shell(serial, VpnHelper.startCommand(NetworkProxyDevicePort))
    }

    /**
     * Lets the device reach the network directly again. The VPN would turn itself off soon after the proxy closes
     * anyway; this turns it off at once.
     */
    /**
     * Whether another Robomori, such as the app while the CLI records, already sends this device's traffic to itself:
     * its port on the device leads to a proxy still listening on this computer. One left behind by a Robomori that
     * crashed leads nowhere, and doesn't count.
     */
    fun recordedElsewhere(serial: String): Boolean = runCatching {
        adb(serial, "reverse", "--list").lineSequence().mapNotNull { line ->
            val ports = line.trim().split(Regex("\\s+")).filter { it.startsWith("tcp:") }
            ports.getOrNull(1)?.removePrefix("tcp:")?.toIntOrNull()?.takeIf { ports.first() == "tcp:$NetworkProxyDevicePort" }
        }.any { port -> runCatching { java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", port), 300) } }.isSuccess }
    }.getOrDefault(false)

    fun unroute(serial: String) {
        runCatching { shell(serial, VpnHelper.stopCommand) }
        runCatching { adb(serial, "reverse", "--remove", "tcp:$NetworkProxyDevicePort") }
    }

    private fun installHelper(serial: String) {
        val path = shell(serial, "pm path ${VpnHelper.packageName} 2>/dev/null; true").lineSequence()
            .map(String::trim).firstOrNull { it.startsWith("package:") }?.removePrefix("package:")
        if (path != null && shell(serial, "sha256sum ${quoteShell(path)}").substringBefore(' ').trim() == VpnHelper.sha256) return
        val apk = VpnHelper.stage()
        try {
            fun install() = try {
                adb(serial, "install", "-r", "-t", apk.absolutePath)
            } catch (failure: AndroidCommandException) { failure.output }
            var output = install()
            // A copy signed with another key, such as one built on another computer, can't be updated in place, nor can
            // a newer one, left by a newer Robomori, be replaced by this one's.
            if ("INSTALL_FAILED_UPDATE_INCOMPATIBLE" in output || "INSTALL_FAILED_VERSION_DOWNGRADE" in output) {
                runCatching { adb(serial, "uninstall", VpnHelper.packageName) }
                output = install()
            }
            check(output.lineSequence().any { it.trim() == "Success" }) {
                "Robomori couldn't install Robomori Network, the app it records traffic with, on the device. Restart the device, then try again."
            }
        } finally { apk.delete() }
    }

    /** Whether the device still sends its traffic to a proxy setting an earlier version of Robomori left behind. */
    fun hasLegacyProxy(serial: String): Boolean = shell(serial, "settings get global http_proxy").trim() == legacyProxySetting

    /** Clears the proxy setting an earlier version of Robomori left behind, which cuts the device off the network. */
    fun clearLegacyProxy(serial: String) {
        // ":0" takes effect at once, where deleting the setting waits for apps to look it up again.
        shell(serial, "settings put global http_proxy :0")
    }

    /**
     * Installs [authority]'s certificate among the system's, which needs root. Lasts until the device restarts, since
     * the system certificates are only covered over in memory. Chrome and Android 17's transparency checks are then
     * told about it too, as far as they can be.
     */
    fun trust(serial: String, authority: NetworkCertificateAuthority): CertificateTrust {
        val name = authority.systemFileName
        val certificate = File.createTempFile("robomori-ca", ".0").apply { writeText(authority.pem); deleteOnExit() }
        val script = File.createTempFile("robomori-trust", ".sh").apply { writeText(trustScript(name)); deleteOnExit() }
        try {
            adb(serial, "push", certificate.absolutePath, "/data/local/tmp/robomori-ca.0")
            adb(serial, "push", script.absolutePath, "/data/local/tmp/robomori-trust.sh")
        } finally { certificate.delete(); script.delete() }
        val root = rootShell(serial) ?: return CertificateTrust.AppConfiguration
        val output = root("sh /data/local/tmp/robomori-trust.sh")
        check("ROBOMORI_TRUSTED" in output) { output.lineSequence().map(String::trim).lastOrNull(String::isNotEmpty) ?: "Could not install Robomori's certificate." }
        // Neither stops the apps that trust the system's certificates from being read, so a failure is let go.
        runCatching { root(chromeTrustScript(authority.spkiFingerprint, restart = "ROBOMORI_INSTALLED" in output)) }
        runCatching { addTransparencyLogs(serial, root, authority) }
        return CertificateTrust.System
    }

    /**
     * Adds Robomori's transparency logs to the device's list, which Android 17 checks sites' certificates against,
     * so apps accept the timestamps in the certificates Robomori makes. Like the certificate, the list is only
     * covered over in memory, until the device restarts. Devices without a list don't check.
     */
    private fun addTransparencyLogs(serial: String, root: (String) -> String, authority: NetworkCertificateAuthority) {
        val encoded = root("base64 $TransparencyLogListPath 2>/dev/null").trim().takeIf { it.isNotEmpty() } ?: return
        val list = TransparencyLogList.decode(Base64.getMimeDecoder().decode(encoded)) ?: return
        val updated = list.including(authority.transparencyLogs, authority.certificate.notBefore.toInstant())
        if (updated == list) return
        val file = File.createTempFile("robomori-ct", ".ctfb").apply { writeBytes(updated.encode()); deleteOnExit() }
        try { adb(serial, "push", file.absolutePath, "/data/local/tmp/robomori-ct.ctfb") } finally { file.delete() }
        val output = root(transparencyScript())
        check("ROBOMORI_LOGS" in output) { "Could not add Robomori's transparency logs." }
    }

    /** Runs a script as root, through `su` or adbd restarted as root, or null when the device can't be rooted. */
    private fun rootShell(serial: String): ((String) -> String)? = when {
        // Google APIs and AOSP images have su for the shell, as do rooted phones.
        runCatching { shell(serial, "su 0 id") }.getOrNull()?.contains("uid=0") == true ->
            { script -> shell(serial, "su 0 sh -c ${shellQuote(script)}") }
        becomeRoot(serial) -> { script -> shell(serial, script) }
        else -> null
    }

    /** Puts the network agent and Robomori's certificate on the device, where each app can copy them from. */
    fun prepareAgent(serial: String, authority: NetworkCertificateAuthority) {
        val staged = NetworkAgent.stage(authority)
        try {
            shell(serial, "rm -rf ${NetworkAgent.deviceFolder} && mkdir -p ${NetworkAgent.deviceFolder}")
            adb(serial, "push", "${staged.absolutePath}/.", NetworkAgent.deviceFolder)
            // Apps read these as themselves, not as the shell that pushed them.
            shell(serial, "chmod 755 ${NetworkAgent.deviceFolder} && chmod 644 ${NetworkAgent.deviceFolder}/*")
        } finally { staged.deleteRecursively() }
    }

    /** The processes of installed apps that run now. */
    fun appProcesses(serial: String): List<AppProcess> = parseAppProcesses(shell(serial, "ps -A -o PID,UID,NAME"))

    /** Whether [packageName] is debuggable, which lets Robomori reach its files and load the agent into it. */
    fun isDebuggable(serial: String, packageName: String): Boolean =
        // run-as fails for any other app, which adb reports as the command failing.
        runCatching { shell(serial, "run-as $packageName id 2>&1") }.getOrDefault("").contains("uid=")

    /** Copies the agent into [packageName]'s own folder, and returns what loads it. */
    fun installAgent(serial: String, packageName: String, authority: NetworkCertificateAuthority): String {
        require(packageName.matches(Regex("[A-Za-z0-9_.]+"))) { "Choose a valid app." }
        val abi = parsePrimaryAbi(shell(serial, "dumpsys package $packageName | grep primaryCpuAbi="))
            ?: shell(serial, "getprop ro.product.cpu.abi").trim()
        check(abi in NetworkAgent.abis) { "Robomori can't read this app's traffic on $abi." }
        val output = shell(serial, NetworkAgent.installScript(packageName, abi, authority.systemFileName))
        check("ROBOMORI_READY" in output) { output.lineSequence().map(String::trim).lastOrNull(String::isNotEmpty) ?: "Could not copy Robomori's agent into $packageName." }
        return NetworkAgent.argument(packageName)
    }

    /** Loads the agent into the running process [pid], which [installAgent] prepared. */
    fun attachAgent(serial: String, pid: Int, argument: String) {
        val output = shell(serial, "cmd activity attach-agent $pid $argument")
        check(output.isBlank()) { output.trim() }
    }

    /** Restarts adbd as root, which images with Google Play refuse. */
    private fun becomeRoot(serial: String): Boolean {
        val output = runCatching { adb(serial, "root") }.getOrElse { return false }
        if ("cannot run as root" in output || "not allowed" in output) return false
        adb(serial, "wait-for-device")
        return shell(serial, "id").contains("uid=0")
    }

    /** Each app user ID's package. */
    fun packages(serial: String): Map<Int, String> = parsePackageUids(shell(serial, "pm list packages -U"))

    private fun adb(serial: String, vararg args: String) = android.command(android.toolchain.adb, listOf("-s", serial) + args)
    private fun shell(serial: String, script: String) = adb(serial, "shell", script)
}

/**
 * Copies the system certificates and Robomori's into memory, laid over the system's folder. From Android 14 the
 * certificates come from the Conscrypt module, which each app process sees in its own mount namespace, so the copy
 * is laid over it in Zygote, which starts new apps, and in every app already running.
 */
internal fun trustScript(name: String) = """
    set -e
    CERTS=/system/etc/security/cacerts
    APEX=/apex/com.android.conscrypt/cacerts
    if [ ! -f "${'$'}CERTS/$name" ]; then
        rm -rf /data/local/tmp/robomori-cacerts
        mkdir -p -m 700 /data/local/tmp/robomori-cacerts
        if [ -d "${'$'}APEX" ]; then cp ${'$'}APEX/* /data/local/tmp/robomori-cacerts/; else cp ${'$'}CERTS/* /data/local/tmp/robomori-cacerts/; fi
        mount -t tmpfs tmpfs "${'$'}CERTS"
        mv /data/local/tmp/robomori-cacerts/* "${'$'}CERTS/"
        rm -rf /data/local/tmp/robomori-cacerts
        echo ROBOMORI_INSTALLED
    fi
    cp /data/local/tmp/robomori-ca.0 "${'$'}CERTS/$name"
    chown root:root ${'$'}CERTS/*
    chmod 644 ${'$'}CERTS/*
    chcon u:object_r:system_file:s0 ${'$'}CERTS/*
    if [ -d "${'$'}APEX" ]; then
        ZYGOTES="${'$'}(pidof zygote || true) ${'$'}(pidof zygote64 || true)"
        for PID in ${'$'}ZYGOTES ${'$'}(for Z in ${'$'}ZYGOTES; do ps -o PID= -P ${'$'}Z; done); do
            if [ ! -f "/proc/${'$'}PID/root${'$'}APEX/$name" ]; then
                nsenter --mount=/proc/${'$'}PID/ns/mnt -- /bin/mount --bind "${'$'}CERTS" "${'$'}APEX" 2>/dev/null || true
            fi
        done
    fi
    echo ROBOMORI_TRUSTED
""".trimIndent() + "\n"

/** Where Android 17 keeps its list of transparency logs, which Conscrypt checks sites' certificates against. */
internal const val TransparencyLogDirectory = "/data/misc/keychain/ct"
private const val TransparencyLogListPath = "$TransparencyLogDirectory/v3/current/log_list.ctfb"

/**
 * Lays a copy of the transparency log list with Robomori's logs over the device's, in memory. It is read-only, so the
 * device's daily update of the list can't replace it while it's there.
 */
internal fun transparencyScript() = """
    set -e
    CT=$TransparencyLogDirectory
    CONTEXT=${'$'}(ls -Zd /data/misc/keychain | cut -d' ' -f1)
    while grep -q " ${'$'}CT " /proc/mounts; do umount -l "${'$'}CT"; done
    mount -t tmpfs tmpfs "${'$'}CT"
    mkdir -p "${'$'}CT/v3/current"
    mv /data/local/tmp/robomori-ct.ctfb "${'$'}CT/v3/current/log_list.ctfb"
    chown -R root:root "${'$'}CT"
    chmod -R 755 "${'$'}CT"
    chmod 644 "${'$'}CT/v3/current/log_list.ctfb"
    chcon -R "${'$'}CONTEXT" "${'$'}CT"
    mount -o remount,ro "${'$'}CT" || true
    echo ROBOMORI_LOGS
""".trimIndent()

/** The files Chrome reads its command line from: the first on rooted and userdebug devices, the second for debug apps. */
internal val ChromeCommandLines = listOf("/data/local/chrome-command-line", "/data/local/tmp/chrome-command-line")
private const val ChromeSpkiFlag = "--ignore-certificate-errors-spki-list"

/**
 * Tells Chrome to accept Robomori's certificate, as HTTP Toolkit does. Chrome checks sites against its own root store,
 * so the system's certificates don't reach it. Flags already there are kept. Chrome restarts when they change, or
 * on [restart], when the certificate was just installed: it reads both only as it starts, and a Chrome left running
 * turns Robomori down, which then passes its sites through unread.
 */
internal fun chromeTrustScript(spkiFingerprint: String, restart: Boolean) = """
    CHANGED=${if (restart) "1" else ""}
    for F in ${ChromeCommandLines.joinToString(" ")}; do
        OLD=${'$'}(cat "${'$'}F" 2>/dev/null || true)
        REST=${'$'}(echo "${'$'}OLD" | sed -E 's/ $ChromeSpkiFlag=[^ ]*//g')
        NEW="${'$'}{REST:-chrome} $ChromeSpkiFlag=$spkiFingerprint"
        if [ "${'$'}OLD" != "${'$'}NEW" ]; then
            echo "${'$'}NEW" > "${'$'}F"
            chmod 644 "${'$'}F"
            chcon u:object_r:shell_data_file:s0 "${'$'}F" 2>/dev/null || true
            CHANGED=1
        fi
    done
    if [ -n "${'$'}CHANGED" ]; then am force-stop com.android.chrome; fi
    echo ROBOMORI_CHROME
""".trimIndent()

internal fun parsePackageUids(output: String): Map<Int, String> = buildMap {
    for (line in output.lineSequence()) {
        val name = line.trim().removePrefix("package:").substringBefore(' ').takeIf { line.trim().startsWith("package:") } ?: continue
        val uid = line.substringAfter("uid:", "").substringBefore(',').trim().toIntOrNull() ?: continue
        // Apps that share a user ID show as the first one listed.
        putIfAbsent(uid, name)
    }
}

/** How the last export of the requests to a HAR file went. */
internal sealed interface NetworkExport {
    data object Exporting : NetworkExport
    data class Saved(val file: File) : NetworkExport
    data class Failed(val cause: Throwable) : NetworkExport
}

internal sealed interface NetworkRouting {
    data object Off : NetworkRouting
    data object Starting : NetworkRouting
    data class On(val trust: CertificateTrust) : NetworkRouting
    data class Failed(val cause: Throwable) : NetworkRouting
}

/**
 * The network traffic of one device: whether it goes through Robomori, and the requests its apps made. With an [avd]
 * and [sessions], the requests are kept between runs of the emulator: restored when created, and saved a moment after
 * they change.
 */
internal class NetworkInspectorState(
    private val service: NetworkInspectorService,
    private val authority: () -> NetworkCertificateAuthority,
    val serial: String,
    private val scope: CoroutineScope,
    /** The rules that change responses, which every device's proxy reads. */
    rules: () -> List<NetworkRule> = { emptyList() },
    private val proxies: (onApp: (Long, Int) -> Unit, onExchange: (NetworkExchange) -> Unit, numberAfter: Long) -> NetworkProxy =
        { onApp, onExchange, numberAfter -> NetworkProxy(authority(), onApp, onExchange, numberAfter = numberAfter, rules = rules) },
    /** The emulator's AVD name, when known. */
    val avd: String? = null,
    private val sessions: NetworkSessionStore? = null,
    private val saveDelayMillis: Long = 2000,
    /** Whether the device is a Google Play emulator that a copy with Google APIs could stand in for. */
    private val canCreateGoogleApisCopy: Boolean = false,
    /** Every app on the device with code, for the names and icons the requests' apps are shown with. */
    private val listApps: (serial: String) -> List<InstalledApp> = { emptyList() },
) {
    /** The device's apps by package, loaded as recording starts; empty until then or when they can't be read. */
    var installedApps by mutableStateOf<Map<String, InstalledApp>>(emptyMap()); private set

    var routing by mutableStateOf<NetworkRouting>(NetworkRouting.Off); private set
    /** The failure the user closed the dialog about, so it stays closed until recording fails again. */
    private var dismissedFailure by mutableStateOf<NetworkRouting.Failed?>(null)

    /** Why recording stopped while requests are listed, until the user closes it; null when it hasn't. */
    val stoppedRecording: NetworkRouting.Failed?
        get() = (routing as? NetworkRouting.Failed)?.takeIf { it !== dismissedFailure && exchanges.isNotEmpty() }

    fun dismissStoppedRecording() { dismissedFailure = routing as? NetworkRouting.Failed }
    /** Whose traffic is read, once recording has started. */
    val coverage: NetworkCoverage? get() = networkCoverage(routing, canCreateGoogleApisCopy)
    /** The requests, oldest first. */
    val exchanges = mutableStateListOf<NetworkExchange>()
    /** The package of the app behind each connection. */
    private val apps = mutableStateMapOf<Long, String>()
    /** The app whose requests are listed, or null for every app's. */
    var appFilter by mutableStateOf<String?>(null); private set
    var selectedId by mutableStateOf<Long?>(null); private set
    val selected get() = exchanges.firstOrNull { it.id == selectedId }

    /** How the requests are ordered, or null for the order they were made in, like Chrome's. */
    var sort by mutableStateOf<NetworkSort?>(null); private set
    var columnShares by mutableStateOf(DefaultNetworkColumnShares); private set
    /** The widths the columns that fit their content were dragged to; until then they fit it. */
    var fixedColumnWidths by mutableStateOf<Map<NetworkColumn, Dp>>(emptyMap()); private set

    fun resizeFixedColumn(column: NetworkColumn, width: Dp) { fixedColumnWidths = fixedColumnWidths + (column to width) }
    /** The columns from left to right; the request's number always comes first. */
    var columnOrder by mutableStateOf(NetworkColumn.entries.toList()); private set

    fun reorderColumns(order: List<NetworkColumn>) {
        if (order.firstOrNull() == NetworkColumn.Number && order.toSet() == NetworkColumn.entries.toSet()) columnOrder = order
    }

    /** Each listed request's place in the order they were made, from 1, by its id. */
    val numbers by derivedStateOf { exchanges.withIndex().associate { (index, exchange) -> exchange.id to index + 1 } }

    val active get() = routing is NetworkRouting.On || routing == NetworkRouting.Starting
    val appsSeen get() = apps.values.distinct().sorted()
    /** Which requests are listed besides [appFilter]: by text, type and errors. */
    var filter by mutableStateOf(NetworkFilter()); private set
    /** Worked out again only when the requests or what picks them change, as matching bodies takes a while. */
    val visibleExchanges by derivedStateOf { exchanges.visible(appFilter, filter, sort, ::appOf) }

    fun appOf(exchange: NetworkExchange): String? = apps[exchange.connection]

    private var proxy: NetworkProxy? = null
    private var job: Job? = null
    private val packages = ConcurrentHashMap<Int, String>()
    private val persisted get() = avd != null && sessions != null
    private var restoring: Job? = null
    private var saving: Job? = null
    /** Keeps saves and clearing the saved file in order. */
    private val writes = Mutex()

    /** How the last export went, shown for a moment. */
    var export by mutableStateOf<NetworkExport?>(null); private set
    private var exportShown: Job? = null

    init {
        if (persisted) restoring = scope.launch { restore() }
    }

    fun start() {
        if (this.active) return
        routing = NetworkRouting.Starting
        job = scope.launch {
            val owners = Channel<Pair<Long, Int>>(Channel.UNLIMITED)
            try {
                // An emulator that's still starting has no settings to route it with, nor can it install the VPN helper.
                while (!runInterruptible(Dispatchers.IO) { service.ready(serial) }) delay(bootPollMillis)
                launch {
                    runCatching { runInterruptible(Dispatchers.IO) { listApps(serial) } }
                        .onSuccess { apps -> installedApps = apps.associateBy { it.packageName } }
                }
                if (runInterruptible(Dispatchers.IO) { service.recordedElsewhere(serial) }) throw IllegalStateException(
                    "Robomori's command-line tool is recording this device's traffic. Stop it, then try again.")
                // Before routing: becoming root restarts adb on the device, which drops its reverse ports.
                val trust = runInterruptible(Dispatchers.IO) { service.trust(serial, authority()) }
                val started = runInterruptible(Dispatchers.IO) {
                    // After the requests already listed, as turning inspection off and on starts a new proxy.
                    val numberAfter = maxOf(exchanges.maxOfOrNull { it.id } ?: 0, exchanges.maxOfOrNull { it.connection } ?: 0, apps.keys.maxOrNull() ?: 0)
                    proxies({ connection, uid -> owners.trySend(connection to uid) }, { exchange -> scope.launch { record(exchange) } }, numberAfter)
                }
                proxy = started
                runInterruptible(Dispatchers.IO) {
                    service.route(serial, started.port)
                    started.awaitHelper(helperStartMillis)
                }
                inspecting += serial
                routing = NetworkRouting.On(trust)
                // In a scope of their own, so the VPN turning off ends this work along with it.
                coroutineScope {
                    if (trust == CertificateTrust.AppConfiguration) launch { loadAgentIntoApps(started) }
                    launch { attribute(owners) }
                    runInterruptible(Dispatchers.IO) { started.awaitHelperGone() }
                    throw IllegalStateException("Robomori's VPN on the device was turned off.")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                withContext(NonCancellable + Dispatchers.IO) { close() }
                routing = NetworkRouting.Failed(failure)
            }
        }
    }

    fun stop() {
        if (saving != null) { saving?.cancel(); saving = null; scope.launch { save() } }
        if (routing == NetworkRouting.Off) return
        job?.cancel()
        job = null
        routing = NetworkRouting.Off
        scope.launch { withContext(Dispatchers.IO) { close() } }
    }

    /**
     * Lets the device reach the network directly when a version of Robomori from before the VPN left it routed through
     * a proxy setting. The VPN needs no clearing: it turns itself off once Robomori is gone.
     */
    suspend fun clearStaleRoute() {
        if (active) return
        withContext(Dispatchers.IO) { runCatching { if (service.hasLegacyProxy(serial)) service.clearLegacyProxy(serial) } }
    }

    private fun close() {
        inspecting -= serial
        proxy?.close()
        proxy = null
        service.unroute(serial)
    }

    /**
     * Loads the agent into [packageName] as it starts, when the device can't trust Robomori's certificate for every app,
     * so its first requests are read too. Returns what `am start --attach-agent` takes, or null when there is no need.
     */
    fun agentForLaunch(packageName: String): String? {
        val proxy = proxy ?: return null
        if (routing != NetworkRouting.On(CertificateTrust.AppConfiguration)) return null
        if (!service.isDebuggable(serial, packageName)) return null
        return service.installAgent(serial, packageName, authority()).also { proxy.forgetRejections() }
    }

    /**
     * Loads the agent into each debuggable app's processes as they start, and into those already running, so the app
     * trusts Robomori's certificate. Without it, these devices show HTTPS only from apps configured to trust it.
     */
    private suspend fun loadAgentIntoApps(proxy: NetworkProxy) {
        if (runCatching { runInterruptible(Dispatchers.IO) { service.prepareAgent(serial, authority()) } }.isFailure) return
        val handled = mutableSetOf<Int>()
        val attempts = mutableMapOf<Int, Int>()
        val debuggable = mutableMapOf<String, Boolean>()
        while (currentCoroutineContext().isActive) {
            runCatching {
                runInterruptible(Dispatchers.IO) {
                    val processes = service.appProcesses(serial)
                    val running = processes.mapTo(mutableSetOf()) { it.pid }
                    handled.retainAll(running)
                    attempts.keys.retainAll(running)
                    for (process in processes) {
                        if (process.pid in handled) continue
                        val packageName = packageOf(process.uid)
                        if (packageName == null || !debuggable.getOrPut(packageName) { service.isDebuggable(serial, packageName) }) {
                            handled += process.pid
                            continue
                        }
                        try {
                            service.attachAgent(serial, process.pid, service.installAgent(serial, packageName, authority()))
                            // Sites the app turned down before it trusted Robomori are read from now on, and the
                            // connections it opened before then make way for ones that are read.
                            proxy.forgetRejections()
                            proxy.closePassThrough(apps.filterValues { it == packageName }.keys)
                            handled += process.pid
                        } catch (failure: Exception) {
                            // A process that is still starting can't take an agent yet; one that never does is let go.
                            if (attempts.merge(process.pid, 1, Int::plus)!! >= maxAgentAttempts) handled += process.pid
                        }
                    }
                }
            }
            delay(agentPollMillis)
        }
    }

    private fun packageOf(uid: Int): String? =
        packages[uid] ?: runCatching { service.packages(serial) }.getOrDefault(emptyMap()).also(packages::putAll)[uid]

    fun select(exchange: NetworkExchange?) { selectedId = exchange?.id }

    fun filter(app: String?) { appFilter = app }

    fun search(query: String) { filter = filter.copy(query = query) }

    fun filterType(type: NetworkTypeFilter) { filter = filter.copy(type = type) }

    fun showErrorsOnly(errorsOnly: Boolean) { filter = filter.copy(errorsOnly = errorsOnly) }

    /** Orders by [column], or the other way when it already is. */
    fun sortBy(column: NetworkColumn) {
        val current = sort
        sort = if (current?.column == column) current.copy(ascending = !current.ascending) else NetworkSort(column)
    }

    /** Takes [widths] for the columns after the name, as dragging the line between two of them sets them. */
    fun resizeColumns(shares: Map<NetworkColumn, Float>) {
        columnShares = shares
    }

    /**
     * Clears the requests, and the saved ones too. Sites that turned down Robomori's certificate are tried again, so
     * clearing after changing an app, such as making it trust Robomori, reads it.
     */
    fun clear() {
        exchanges.clear()
        selectedId = null
        proxy?.forgetRejections()
        restoring?.cancel()
        saving?.cancel()
        saving = null
        val avd = avd ?: return
        val sessions = sessions ?: return
        scope.launch { writes.withLock { withContext(NonCancellable + Dispatchers.IO) { sessions.clear(avd) } } }
    }

    /** A name to save the requests under, such as `Pixel_9-network-20261121-1530.har`. */
    fun exportFileName(now: LocalDateTime = LocalDateTime.now()): String =
        "${avd ?: serial}-network-${now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))}.har"

    /** Writes the listed requests to [file] as HAR. */
    fun exportHar(file: File) {
        val listed = visibleExchanges
        val apps = apps.toMap()
        exportShown?.cancel()
        export = NetworkExport.Exporting
        exportShown = scope.launch {
            export = try {
                withContext(Dispatchers.IO) { writeAtomically(file, toHar(listed) { apps[it.connection] }) }
                NetworkExport.Saved(file)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                NetworkExport.Failed(failure)
            }
            delay(exportShownMillis)
            export = null
        }
    }

    private fun record(exchange: NetworkExchange) {
        val index = exchanges.indexOfLast { it.id == exchange.id }
        if (index >= 0) exchanges[index] = exchange
        else exchanges += exchange
        trim()
        saveLater()
    }

    /** The oldest requests make room, so a long session doesn't fill the memory, or the saved file. */
    private fun trim() {
        var kept = exchanges.sumOf { it.keptBytes }
        while (exchanges.size > 1 && (exchanges.size > maxExchanges || kept > maxKeptBytes)) kept -= exchanges.removeAt(0).keptBytes
    }

    /**
     * Puts the saved requests before any recorded since. The proxy numbers requests and connections from 1 each time
     * it starts, so the saved ones are numbered below 0 to keep them apart from new ones.
     */
    private suspend fun restore() {
        val avd = avd ?: return
        val session = withContext(Dispatchers.IO) { sessions?.load(avd) } ?: return
        val connections = session.exchanges.map { it.connection }.distinct().withIndex()
            .associate { (index, connection) -> connection to -(index + 1L) }
        val count = session.exchanges.size
        val restored = session.exchanges.mapIndexed { index, exchange ->
            exchange.copy(id = index - count.toLong(), connection = connections.getValue(exchange.connection))
        }
        for ((connection, app) in session.apps) connections[connection]?.let { apps.putIfAbsent(it, app) }
        exchanges.addAll(0, restored)
        trim()
    }

    /** Saves the requests a moment after they change, together with any other changes in the meantime. */
    private fun saveLater() {
        if (!persisted || saving != null) return
        saving = scope.launch {
            delay(saveDelayMillis)
            saving = null
            save()
        }
    }

    private suspend fun save() {
        val avd = avd ?: return
        val sessions = sessions ?: return
        // Saving before the saved requests are back would lose them.
        restoring?.join()
        writes.withLock {
            val snapshot = exchanges.toList()
            val apps = apps.toMap()
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { sessions.save(avd, snapshot) { apps[it.connection] } }
            }
        }
    }

    /** Names the app behind each connection, from the user ID the VPN helper found for it. */
    private suspend fun attribute(owners: Channel<Pair<Long, Int>>) {
        owners.consumeEach { (connection, uid) ->
            apps[connection] = runInterruptible(Dispatchers.IO) { packageOf(uid) } ?: "uid $uid"
            saveLater()
        }
    }

    companion object {
        private const val maxExchanges = 2000
        private const val maxKeptBytes = 128L * 1024 * 1024
        /** How often new app processes are looked for, to load the agent into them. */
        private const val agentPollMillis = 1000L
        /** How often a starting emulator is checked for having booted, before recording its traffic. */
        private const val bootPollMillis = 1000L
        /** How long the VPN helper has to install, start and connect back. */
        private const val helperStartMillis = 15_000L
        private const val maxAgentAttempts = 5
        private const val exportShownMillis = 4000L
        /** The devices whose traffic goes through Robomori, to let them reach the network directly when the app quits. */
        val inspecting: MutableSet<String> = ConcurrentHashMap.newKeySet()
    }
}

/** The query string's parameters, decoded, in order; a name without a value has an empty one. */
internal fun queryParameters(url: String): List<Pair<String, String>> {
    val query = runCatching { URI(url).rawQuery }.getOrNull() ?: return emptyList()
    fun decode(text: String) = runCatching { java.net.URLDecoder.decode(text, Charsets.UTF_8) }.getOrDefault(text)
    return query.split('&').filter(String::isNotEmpty).map { part ->
        decode(part.substringBefore('=')) to decode(part.substringAfter('=', ""))
    }
}

/**
 * The columns of the request list, as Chrome's Network tab has them; App stands in for its Initiator, Time is when the
 * request started, and Number counts the requests in the order they were made.
 */
internal enum class NetworkColumn { Number, Name, App, Method, Status, Type, Time, Size, Duration }

internal data class NetworkSort(val column: NetworkColumn, val ascending: Boolean = true)

/** Each column's share of the table's width besides the number's, so they all grow and shrink with it, as Chrome's do. Status is just wide enough for its title and a code. */
private val DefaultNetworkColumnShares = mapOf(NetworkColumn.Name to .25f,
    NetworkColumn.Type to .08f, NetworkColumn.App to .15f, NetworkColumn.Duration to .07f)
internal val MinNetworkColumnWidth = 30.dp

/** The requests the list shows: [app]'s, or every app's when null, that [filter] keeps, ordered by [sort]. */
internal fun List<NetworkExchange>.visible(app: String?, filter: NetworkFilter, sort: NetworkSort?,
                                           appOf: (NetworkExchange) -> String?): List<NetworkExchange> =
    this.filter { app == null || appOf(it) == app }.filteredBy(filter, appOf).let { shown -> sort?.let { shown.sortedBy(it, appOf) } ?: shown }

/** The requests ordered by [sort]; ones without a value in its column, such as a pending request's size, go last. */
internal fun List<NetworkExchange>.sortedBy(sort: NetworkSort, appOf: (NetworkExchange) -> String?): List<NetworkExchange> {
    // The list is in the order the requests were made, which is what their numbers count.
    if (sort.column == NetworkColumn.Number) return if (sort.ascending) this else reversed()
    fun <T : Comparable<T>> by(value: (NetworkExchange) -> T?) = Comparator<NetworkExchange> { a, b ->
        val (x, y) = value(a) to value(b)
        when {
            x == null || y == null -> compareValues(x == null, y == null)
            sort.ascending -> x.compareTo(y)
            else -> y.compareTo(x)
        }
    }
    val comparator = when (sort.column) {
        NetworkColumn.Number -> error("Numbers keep the list's own order.")
        NetworkColumn.Name -> by { it.name.lowercase() }
        NetworkColumn.Method -> by { it.method }
        NetworkColumn.Status -> by { it.status }
        NetworkColumn.Type -> by { it.type }
        NetworkColumn.App -> by { appOf(it) }
        NetworkColumn.Size -> by { it.responseSize }
        NetworkColumn.Time -> by { it.startedAt }
        NetworkColumn.Duration -> by { it.durationMillis }
    }
    // The same values keep the order the requests were made in.
    return sortedWith(comparator.thenBy { it.id })
}

/** What Chrome's Name column shows: the last part of the path with the query, or the host for the site's root. */
internal val NetworkExchange.name: String get() {
    val uri = runCatching { URI(url) }.getOrNull() ?: return url
    val last = (uri.rawPath ?: "").trimEnd('/').substringAfterLast('/')
    return if (last.isEmpty() && uri.rawQuery == null) uri.host ?: url else last + (uri.rawQuery?.let { "?$it" } ?: "")
}

/** The response's status code, or null when there is none. */
internal val NetworkExchange.status: Int? get() = (outcome as? NetworkOutcome.Answered)?.response?.status

/** The response body's full size, or null before it arrives. */
internal val NetworkExchange.responseSize: Long? get() = when (val outcome = outcome) {
    is NetworkOutcome.Answered -> outcome.response.body.size
    // What the site sent, still encrypted.
    is NetworkOutcome.Encrypted -> outcome.received
    else -> null
}

/** How long the request took, or null while it's in progress, as a WebSocket is until it closes. */
internal val NetworkExchange.durationMillis: Long? get() = finishedAt?.let { java.time.Duration.between(startedAt, it).toMillis() }

/** The time of day [time] was at in [zone], to the millisecond, as "11:50:47.123", or "11:50:47.123 AM" without [hours24]. */
internal fun formatRequestTime(time: java.time.Instant, zone: java.time.ZoneId, hours24: Boolean): String =
    DateTimeFormatter.ofPattern(if (hours24) "HH:mm:ss.SSS" else "h:mm:ss.SSS a", java.util.Locale.ENGLISH).format(time.atZone(zone))

/** What the response is, named as Chrome's Type column names it, from its Content-Type. */
internal val NetworkExchange.type: String? get() {
    if (webSocket != null) return "websocket"
    val response = (outcome as? NetworkOutcome.Answered)?.response ?: return null
    val mime = response.headers.firstOrNull { it.first.equals("Content-Type", true) }?.second
        ?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    val (kind, subtype) = mime.substringBefore('/') to mime.substringAfter('/', "")
    return when {
        mime == "text/html" -> "document"
        "javascript" in subtype || "ecmascript" in subtype -> "script"
        mime == "text/css" -> "stylesheet"
        kind == "font" || "font" in subtype -> "font"
        subtype == "json" || subtype.endsWith("+json") -> "json"
        subtype == "xml" || subtype.endsWith("+xml") && kind != "image" -> "xml"
        mime == "text/plain" -> "text"
        kind == "image" -> subtype.removeSuffix("+xml").removePrefix("x-")
        // Servers name it several ways; it's one kind of body.
        subtype.removePrefix("x-") in setOf("protobuf", "protobuffer") || subtype == "vnd.google.protobuf" -> "protobuf"
        else -> subtype.removePrefix("x-").ifEmpty { kind }
    }
}

private val NetworkExchange.keptBytes: Long
    get() = requestBody.bytes.size.toLong() + ((outcome as? NetworkOutcome.Answered)?.response?.body?.bytes?.size ?: 0) +
        (webSocket?.messages?.sumOf { it.data.bytes.size.toLong() } ?: 0)

/** A request as a curl command that makes it again. */
internal fun NetworkExchange.toCurl(): String = buildString {
    append("curl")
    if (method != "GET" || requestBody.size > 0) append(" -X ").append(shellQuote(method))
    append(" ").append(shellQuote(url))
    for ((name, value) in requestHeaders) {
        // curl works these out itself.
        if (name.equals("Host", true) || name.equals("Content-Length", true) || name.equals("Connection", true)) continue
        append(" \\\n  -H ").append(shellQuote("$name: $value"))
    }
    if (requestHeaders.any { (name, value) -> name.equals("Accept-Encoding", true) && value.isNotBlank() }) append(" \\\n  --compressed")
    if (requestBody.size > 0) {
        val text = requestBody.text(requestHeaders)
        append(" \\\n  --data-binary ").append(shellQuote(text ?: "@body"))
    }
}

internal fun shellQuote(text: String) = "'" + text.replace("'", "'\\''") + "'"

/** A body as text, decompressed, or null when it is binary. */
/** The body as the site sent it, before the compression its Content-Encoding names, or null when it can't be undone. */
internal fun CapturedBody.decoded(headers: List<Pair<String, String>>): ByteArray? {
    val encoding = headers.firstOrNull { it.first.equals("Content-Encoding", true) }?.second?.trim()?.lowercase()
    return runCatching {
        when (encoding) {
            null, "", "identity" -> bytes
            "gzip" -> GZIPInputStream(ByteArrayInputStream(bytes)).readBytes()
            "deflate" -> InflaterInputStream(ByteArrayInputStream(bytes)).readBytes()
            // What Chrome, WebView and Cronet often ask for.
            "br" -> BrotliInputStream(ByteArrayInputStream(bytes)).readBytes()
            "zstd" -> ZstdInputStream(ByteArrayInputStream(bytes)).readBytes()
            else -> null
        }
    }.getOrNull()
}

internal fun CapturedBody.text(headers: List<Pair<String, String>>): String? {
    if (size == 0L) return ""
    val decoded = decoded(headers) ?: return null
    val text = decoded.toString(Charsets.UTF_8)
    // Text has few control characters besides line breaks and tabs.
    val control = text.count { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' } + text.count { it == '�' }
    return if (control > text.length / 100) null else text
}

private val prettyJson = Json { prettyPrint = true }

/** [text] laid out, when it is JSON; otherwise as it is. */
internal fun prettyBody(text: String): String {
    val trimmed = text.trim()
    if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) return text
    return runCatching { prettyJson.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(trimmed)) }.getOrDefault(text)
}

internal val NetworkExchange.host: String get() = runCatching { URI(url).host }.getOrNull() ?: url
internal val NetworkExchange.path: String get() = runCatching { URI(url).let { (it.rawPath ?: "").ifEmpty { "/" } + (it.rawQuery?.let { q -> "?$q" } ?: "") } }.getOrDefault(url)
