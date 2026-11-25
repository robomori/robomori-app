package com.composables.codepush

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.Properties

/**
 * The code updates downloaded to this computer, and which of them are safe to run.
 *
 * ```
 * <directory>/files/<sha256>.jar             jars, stored by content
 * <directory>/patches/<base>/<patch>/        manifest and manifest.sig of each complete update
 * <directory>/patches/<base>/state.properties
 * ```
 * An update counts as installed only once its manifest is in place, which happens after all its jars are.
 * Before running an update the launcher marks it as being tried; the app marks it healthy once it's up.
 * An update still being tried at the next launch never came up, so it's marked bad and never run again,
 * unless it has come up healthy before.
 */
class PatchStore(val directory: File) {
    private val files = File(directory, "files")

    fun file(sha256: String) = File(files, "$sha256.jar")

    /** Installed updates for [base] whose signature and jars check out, newest first. */
    fun installed(app: String, base: String, publicKey: String): List<Manifest> =
        baseDirectory(base).listFiles().orEmpty().mapNotNull { it.name.toIntOrNull()?.let { patch -> read(it, patch) } }
            .filter { it.app == app && it.base == base }
            .filter { manifest -> manifest.replacements.all { file(it.sha256).length() == it.size } }
            .filter { Signing.verify(manifestFile(it).readBytes(), File(manifestFile(it).parentFile, SIGNATURE).readText(), publicKey) }
            .sortedByDescending { it.patch }

    /**
     * Picks the update to run now: the newest installed one that never failed, or null for the installed code.
     * An update that was being tried and never reported healthy is marked bad first.
     */
    fun beginLaunch(app: String, base: String, publicKey: String): Manifest? {
        val state = state(base)
        // An update that has come up before is kept: the app was most likely quit before it could report in.
        state.trying?.takeIf { it != state.good }?.let { state.bad += it }
        val chosen = installed(app, base, publicKey).firstOrNull { it.patch !in state.bad }
        state.trying = chosen?.patch
        save(base, state)
        return chosen
    }

    /**
     * The newest update the app has confirmed by coming up healthy, or null for the installed code. Reads the record
     * without changing it, for launches that never report healthy, such as a command-line tool.
     */
    fun confirmed(app: String, base: String, publicKey: String): Manifest? {
        val state = state(base)
        val good = state.good?.takeIf { it !in state.bad } ?: return null
        return installed(app, base, publicKey).firstOrNull { it.patch == good }
    }

    /** The running update came up fine: keep running it on later launches. */
    fun markHealthy(base: String) {
        val state = state(base)
        val trying = state.trying ?: return
        state.good = trying
        state.trying = null
        save(base, state)
    }

    fun bad(base: String): Set<Int> = state(base).bad

    /** Adds a downloaded update whose jars are already in place. The manifest goes in last, so it's all or nothing. */
    fun install(manifest: Manifest, signature: String) {
        manifest.replacements.forEach { check(file(it.sha256).length() == it.size) { "Missing ${it.module} jar" } }
        val target = File(baseDirectory(manifest.base), manifest.patch.toString())
        val staging = File(baseDirectory(manifest.base), ".${manifest.patch}.partial").apply { deleteRecursively(); mkdirs() }
        File(staging, MANIFEST).writeText(manifest.text())
        File(staging, SIGNATURE).writeText(signature)
        target.deleteRecursively()
        Files.move(staging.toPath(), target.toPath(), ATOMIC_MOVE)
    }

    /** Stores a downloaded jar under its content hash. */
    fun storeFile(sha256: String, bytes: ByteArray) {
        require(Signing.sha256(bytes) == sha256) { "Downloaded jar does not match its manifest" }
        files.mkdirs()
        val temporary = File.createTempFile("download-", ".partial", files)
        try {
            temporary.writeBytes(bytes)
            Files.move(temporary.toPath(), file(sha256).toPath(), REPLACE_EXISTING, ATOMIC_MOVE)
        } finally { temporary.delete() }
    }

    /** Removes updates for other installed versions, and jars no remaining update uses. */
    fun prune(keepBase: String) {
        File(directory, "patches").listFiles().orEmpty().filter { it.name != keepBase }.forEach { it.deleteRecursively() }
        val used = baseDirectory(keepBase).listFiles().orEmpty().mapNotNull { dir -> dir.name.toIntOrNull()?.let { read(dir, it) } }
            .flatMap { it.replacements }.map { "${it.sha256}.jar" }.toSet()
        files.listFiles().orEmpty().filter { it.name !in used }.forEach { it.delete() }
    }

    private fun baseDirectory(base: String) = File(File(directory, "patches"), base)
    private fun manifestFile(manifest: Manifest) = File(File(baseDirectory(manifest.base), manifest.patch.toString()), MANIFEST)

    private fun read(directory: File, patch: Int): Manifest? = runCatching {
        Manifest.parse(File(directory, MANIFEST).readText()).takeIf { it.patch == patch }
    }.getOrNull()

    private class State(var trying: Int?, var good: Int?, val bad: MutableSet<Int>)

    private fun state(base: String): State {
        val file = File(baseDirectory(base), STATE)
        val values = Properties().apply { if (file.isFile) file.inputStream().use(::load) }
        return State(values.getProperty("trying")?.toIntOrNull(), values.getProperty("good")?.toIntOrNull(),
            values.getProperty("bad").orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }.toMutableSet())
    }

    private fun save(base: String, state: State) {
        val directory = baseDirectory(base).apply { mkdirs() }
        val values = Properties()
        state.trying?.let { values["trying"] = it.toString() }
        state.good?.let { values["good"] = it.toString() }
        if (state.bad.isNotEmpty()) values["bad"] = state.bad.sorted().joinToString(",")
        val temporary = File.createTempFile("state-", ".partial", directory)
        try {
            temporary.outputStream().use { values.store(it, null) }
            Files.move(temporary.toPath(), File(directory, STATE).toPath(), REPLACE_EXISTING, ATOMIC_MOVE)
        } finally { temporary.delete() }
    }

    private companion object {
        const val MANIFEST = "manifest"
        const val SIGNATURE = "manifest.sig"
        const val STATE = "state.properties"
    }
}
