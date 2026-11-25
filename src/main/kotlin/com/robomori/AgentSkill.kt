package com.robomori

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/** The skill's name, which is also its folder in a skills folder. */
internal const val AGENT_SKILL_NAME = "use-robomori"

/** What a skills folder is shown with: an agent's logo, any agent's, or a folder the person chose. */
internal enum class SkillLogo { Claude, Codex, Cursor, Gemini, AnyAgent, Folder }

/** A skills folder an agent reads, which the skill can be installed into. [path] is [folder] as people write it, with `~`. */
internal data class SkillDestination(val name: String, val folder: File, val path: String, val logo: SkillLogo = SkillLogo.Folder)

/**
 * Installs the Robomori skill, which teaches coding agents the `robomori` command, into the skills folder the person
 * chooses: the shared `~/.agents/skills`, one agent's own folder, or any other folder. The skill ships with the app, so
 * it describes this version's commands. The app remembers where it installed the skill in [installs], and rewrites
 * those copies whenever it starts.
 *
 * A copy put there some other way, such as with `npx skills add` or as a link, is left alone.
 */
internal class AgentSkill(
    private val home: File = HostPlatform().home,
    private val installs: File = File(HostPlatform().configDirectory, "agent-skill-folders"),
    private val skill: () -> String = ::bundledSkill,
) {
    val standard: List<SkillDestination> get() = listOf(
        destination("Claude Code", File(home, ".claude/skills"), SkillLogo.Claude),
        destination("Codex", File(home, ".codex/skills"), SkillLogo.Codex),
        destination("Cursor", File(home, ".cursor/skills"), SkillLogo.Cursor),
        destination("Gemini CLI", File(home, ".gemini/skills"), SkillLogo.Gemini),
        destination("Other agents", File(home, ".agents/skills"), SkillLogo.AnyAgent),
    )

    /** The standard destinations, then the other folders the skill was installed into. */
    fun destinations(): List<SkillDestination> = standard + (remembered() - standard.map { it.folder }.toSet()).map(::custom)

    fun custom(folder: File) = destination(tilde(folder), folder)

    private fun destination(name: String, folder: File, logo: SkillLogo = SkillLogo.Folder) = SkillDestination(name, folder, tilde(folder), logo)

    private fun tilde(folder: File) = if (folder.startsWith(home)) "~/" + folder.relativeTo(home).invariantSeparatorsPath else folder.path

    fun installed(folder: File) = isOurs(copy(folder))

    fun install(folder: File) {
        val copy = copy(folder)
        check(isOurs(copy) || !Files.exists(copy.toPath(), NOFOLLOW_LINKS)) {
            "This folder already has a $AGENT_SKILL_NAME skill, installed another way."
        }
        write(copy)
        remember(remembered() + folder)
    }

    fun uninstall(folder: File) {
        copy(folder).takeIf(::isOurs)?.deleteRecursively()
        remember(remembered() - folder)
    }

    /** Keeps installed copies matching this version of the app. */
    fun refresh() = remembered().map(::copy).filter(::isOurs).forEach(::write)

    private fun copy(folder: File) = File(folder, AGENT_SKILL_NAME)

    private fun write(copy: File) {
        copy.mkdirs()
        File(copy, "SKILL.md").writeText(skill())
        File(copy, MARKER).writeText("Installed by the Robomori app, which keeps this skill up to date.\n")
    }

    private fun isOurs(copy: File) = !Files.isSymbolicLink(copy.toPath()) && File(copy, MARKER).isFile

    private fun remembered(): Set<File> = if (installs.isFile) installs.readLines().filter(String::isNotBlank).map(::File).toSet() else emptySet()

    private fun remember(folders: Set<File>) {
        installs.parentFile.mkdirs()
        installs.writeText(folders.joinToString("") { it.path + "\n" })
    }

    private companion object {
        const val MARKER = ".installed-by-robomori"
    }
}

private fun bundledSkill(): String = checkNotNull(AgentSkill::class.java.getResourceAsStream("/agent-skill/$AGENT_SKILL_NAME/SKILL.md")) {
    "The Robomori skill is missing from this build."
}.use { it.readBytes().decodeToString() }

/**
 * The CLI & Agents settings page's view of [skill]. Installing opens a dialog of [destinations], where the person
 * installs the skill for their coding agent, or into a folder they choose.
 */
internal class AgentSkillState(private val scope: CoroutineScope, private val skill: AgentSkill = AgentSkill()) {
    var destinations by mutableStateOf(skill.standard); private set
    /** The folders the skill is installed in. */
    var installed by mutableStateOf(emptySet<File>()); private set
    var choosing by mutableStateOf(false); private set
    /** The folders being installed into or removed from. */
    var working by mutableStateOf(emptySet<File>()); private set
    var error by mutableStateOf<String?>(null); private set

    /** The destinations the skill is installed in, for the page to name. */
    val installedIn get() = destinations.filter { it.folder in installed }

    fun load() = scope.launch {
        val (all, done) = withContext(Dispatchers.IO) {
            val all = skill.destinations()
            all to all.map { it.folder }.filter(skill::installed).toSet()
        }
        destinations = all; installed = done
    }

    fun choose() { error = null; choosing = true }

    fun close() { if (working.isEmpty()) choosing = false }

    fun install(destination: SkillDestination) = change(setOf(destination.folder), skill::install)

    fun uninstall(destination: SkillDestination) = change(setOf(destination.folder), skill::uninstall)

    /** Asks for another skills folder with [pick] and installs the skill there. */
    fun chooseFolder(pick: suspend (File?) -> File?) {
        scope.launch {
            val folder = runCatching { pick(null) }.getOrNull() ?: return@launch
            val destination = destinations.firstOrNull { it.folder == folder } ?: skill.custom(folder).also { destinations = destinations + it }
            install(destination)
        }
    }

    fun refresh() = scope.launch(Dispatchers.IO) { runCatching { skill.refresh() } }

    private fun change(folders: Set<File>, action: (File) -> Unit) {
        if (working.isNotEmpty() || folders.isEmpty()) return
        working = folders; error = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { folders.forEach(action) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = e.message ?: "Could not change the skill."
            } finally {
                val nowInstalled = withContext(Dispatchers.IO) { folders.filter(skill::installed).toSet() }
                installed = installed - folders + nowInstalled
                working = emptySet()
            }
        }
    }
}
