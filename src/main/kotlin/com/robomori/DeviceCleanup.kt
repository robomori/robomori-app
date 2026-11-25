package com.robomori

/** The apps Robomori installs on a device to do its work. */
internal val RobomoriHelperPackages = listOf("com.robomori.helper")

/** What removing Robomori from a device left behind. */
internal sealed interface DeviceCleanup {
    /** Nothing of Robomori's is left. */
    data object Removed : DeviceCleanup
    /**
     * The device can't be rooted, so certificates and lists laid over the system's in memory, if any, stay until it
     * restarts. Everything else is gone.
     */
    data object RemovedUntilRestart : DeviceCleanup
}

/**
 * Takes off a device what Robomori put on it: its helper apps, the certificate and transparency logs it laid over the
 * system's, Chrome's flag for its certificate, a proxy setting left pointing at it, and the files it staged. Each step
 * is best effort, so one that fails doesn't stop the rest.
 */
internal class DeviceCleanupService(settings: AppSettings, executor: CommandExecutor = ProcessExecutor()) {
    private val android = AndroidService(settings, executor)

    fun clean(serial: String): DeviceCleanup {
        val installed = runCatching { shell(serial, "pm list packages") }.getOrDefault("")
            .lineSequence().map { it.trim().removePrefix("package:") }.toSet()
        for (helper in RobomoriHelperPackages.filter { it in installed }) runCatching { shell(serial, "pm uninstall $helper") }
        // Only a proxy setting that points at Robomori: one the user set for another proxy stays.
        runCatching {
            if (shell(serial, "settings get global http_proxy").trim() == "127.0.0.1:$NetworkProxyDevicePort")
                shell(serial, "settings put global http_proxy :0")
        }
        runCatching { adb(serial, "reverse", "--remove", "tcp:$NetworkProxyDevicePort") }
        val rooted = runCatching { shell(serial, "su 0 id") }.getOrDefault("").contains("uid=0")
        if (rooted) runCatching { shell(serial, "su 0 sh -c ${shellQuote(untrustScript())}") }
        runCatching { shell(serial, "rm -rf /data/local/tmp/robomori-*") }
        return if (rooted) DeviceCleanup.Removed else DeviceCleanup.RemovedUntilRestart
    }

    private fun adb(serial: String, vararg args: String) = android.command(android.toolchain.adb, listOf("-s", serial) + args)
    private fun shell(serial: String, script: String) = adb(serial, "shell", script)
}

/**
 * Undoes [trustScript], [transparencyScript] and [chromeTrustScript]: uncovers the system's certificates, in Zygote and
 * the apps already running too, and the transparency log list, and takes Robomori's flag out of Chrome's command line.
 */
internal fun untrustScript() = """
    CERTS=/system/etc/security/cacerts
    APEX=/apex/com.android.conscrypt/cacerts
    for PID in ${'$'}(pidof zygote zygote64 2>/dev/null) ${'$'}(for Z in ${'$'}(pidof zygote zygote64 2>/dev/null); do ps -o PID= -P ${'$'}Z; done); do
        nsenter --mount=/proc/${'$'}PID/ns/mnt -- /bin/umount -l "${'$'}APEX" 2>/dev/null
    done
    while grep -q " ${'$'}CERTS tmpfs" /proc/mounts; do umount -l "${'$'}CERTS" || break; done
    while grep -q " $TransparencyLogDirectory tmpfs" /proc/mounts; do umount -l "$TransparencyLogDirectory" || break; done
    for F in ${ChromeCommandLines.joinToString(" ")}; do
        [ -f "${'$'}F" ] || continue
        REST=${'$'}(sed -E 's/ --ignore-certificate-errors-spki-list=[^ ]*//g' "${'$'}F")
        if [ "${'$'}REST" = chrome ]; then rm -f "${'$'}F"; else echo "${'$'}REST" > "${'$'}F"; fi
    done
    echo ROBOMORI_UNTRUSTED
""".trimIndent()
