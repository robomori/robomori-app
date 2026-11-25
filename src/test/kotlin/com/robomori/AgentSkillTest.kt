package com.robomori

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class AgentSkillTest {
    @TempDir lateinit var root: File

    private val home by lazy { File(root, "home") }
    private var text = "skill v1"
    private val skill by lazy { AgentSkill(home, File(root, "config/agent-skill-folders")) { text } }
    private val shared by lazy { File(home, ".agents/skills") }
    private val claude by lazy { File(home, ".claude/skills") }

    @Test fun `installs only into the chosen folder`() {
        assertFalse(skill.installed(shared))
        skill.install(shared)
        assertTrue(skill.installed(shared))
        assertEquals("skill v1", File(shared, "use-robomori/SKILL.md").readText())
        assertFalse(skill.installed(claude))
        assertFalse(File(claude, "use-robomori").exists())
    }

    @Test fun `uninstalling removes the skill from that folder only`() {
        skill.install(shared)
        skill.install(claude)
        skill.uninstall(shared)
        assertFalse(File(shared, "use-robomori").exists())
        assertTrue(skill.installed(claude))
    }

    @Test fun `refreshing rewrites every copy it installed with the app's skill`() {
        val custom = File(root, "my-skills")
        skill.install(claude)
        skill.install(custom)
        text = "skill v2"
        skill.refresh()
        assertEquals("skill v2", File(claude, "use-robomori/SKILL.md").readText())
        assertEquals("skill v2", File(custom, "use-robomori/SKILL.md").readText())
        assertFalse(File(shared, "use-robomori").exists())
    }

    @Test fun `lists a custom folder it installed into after the standard ones`() {
        val custom = File(home, "my-skills")
        skill.install(custom)
        assertEquals(skill.standard + SkillDestination("~/my-skills", custom, "~/my-skills"), skill.destinations())
    }

    @Test fun `leaves a skill installed another way alone`() {
        val elsewhere = File(root, "robomori-skills").apply { mkdirs(); File(this, "SKILL.md").writeText("mine") }
        claude.mkdirs()
        Files.createSymbolicLink(File(claude, "use-robomori").toPath(), elsewhere.toPath())
        assertFailsWith<IllegalStateException> { skill.install(claude) }
        skill.uninstall(claude)
        assertEquals("mine", File(claude, "use-robomori/SKILL.md").readText())
    }

    @Test fun `the app ships the skill`() {
        AgentSkill(home, File(root, "config/agent-skill-folders")).install(shared)
        assertContains(File(shared, "use-robomori/SKILL.md").readText(), "name: use-robomori")
    }
}
