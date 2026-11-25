package com.robomori

import java.io.File
import java.nio.file.Files

/**
 * The agent that makes a debuggable app trust Robomori's certificate when the device can't be rooted, so the Network
 * page reads the app's HTTPS with no change to the app. Its source is in `app/network-agent`.
 */
internal object NetworkAgent {
    /** The ABIs the agent is built for. */
    val abis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

    /** Where the agent and Robomori's certificate wait on the device, for each app to copy into its own folder. */
    const val deviceFolder = "/data/local/tmp/robomori-network"

    /** The agent for each ABI, with Robomori's certificate under its system file name, in a folder to push. */
    fun stage(authority: NetworkCertificateAuthority): File {
        val folder = Files.createTempDirectory("robomori-network-").toFile().apply { deleteOnExit() }
        for (abi in abis) File(folder, "$abi.so").apply { writeBytes(library(abi)); deleteOnExit() }
        File(folder, authority.systemFileName).apply { writeText(authority.pem); deleteOnExit() }
        return folder
    }

    /** The agent built for [abi]. It also changes what a running app holds in memory, for [AgentLiveAppData]. */
    fun library(abi: String): ByteArray =
        NetworkAgent::class.java.classLoader.getResourceAsStream("network-agent/$abi/librobomori-network.so")
            ?.use { it.readBytes() } ?: error("Robomori's network agent is missing. Reinstall Robomori and try again.")

    /** The app's own folder for the agent, which the app's process can read. */
    fun appFolder(packageName: String) = "/data/user/0/$packageName/code_cache/robomori-network"

    /**
     * Copies the agent for [abi] into [packageName]'s folder, with the system's certificates and Robomori's, through
     * run-as, which every debuggable app allows. Prints ROBOMORI_READY when done.
     */
    fun installScript(packageName: String, abi: String, certificate: String): String {
        val folder = appFolder(packageName)
        return "run-as $packageName sh -c '" +
            "rm -rf $folder && mkdir -p $folder/cacerts && cp $deviceFolder/$abi.so $folder/agent.so && " +
            // From Android 14 the system's certificates come from the Conscrypt module.
            "if [ -d /apex/com.android.conscrypt/cacerts ]; then cp /apex/com.android.conscrypt/cacerts/* $folder/cacerts/; " +
            "else cp /system/etc/security/cacerts/* $folder/cacerts/; fi && " +
            "cp $deviceFolder/$certificate $folder/cacerts/ && echo ROBOMORI_READY'"
    }

    /** What `--attach-agent` and `attach-agent` load: the agent, given the folder of certificates to trust. */
    fun argument(packageName: String) = appFolder(packageName).let { "$it/agent.so=$it/cacerts" }
}

/** A process of an app on the device. */
internal data class AppProcess(val pid: Int, val uid: Int, val name: String)

/** Reads `ps -A -o PID,UID,NAME` for the processes of installed apps, leaving out the system's and isolated ones. */
internal fun parseAppProcesses(output: String): List<AppProcess> = output.lineSequence().mapNotNull { line ->
    val fields = line.trim().split(Regex("\\s+"))
    if (fields.size < 3) return@mapNotNull null
    val pid = fields[0].toIntOrNull() ?: return@mapNotNull null
    val uid = fields[1].toIntOrNull() ?: return@mapNotNull null
    if (uid % 100_000 !in 10_000..19_999) return@mapNotNull null
    AppProcess(pid, uid, fields[2])
}.toList()

/** The ABI an app's code runs as, from `dumpsys package`, or null when it has no native code and runs as the device's. */
internal fun parsePrimaryAbi(output: String): String? = output.lineSequence()
    .map(String::trim).firstOrNull { it.startsWith("primaryCpuAbi=") }
    ?.substringAfter('=')?.takeIf { it.isNotBlank() && it != "null" }
