package com.robomori

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class CommandLineToolTest {
    @TempDir lateinit var root: File

    private val home by lazy { File(root, "home") }
    private val app by lazy { File(root, "Robomori.AppImage").apply { writeText("app") } }

    private class FakeSystem : SystemCommands() {
        val path = mutableListOf("%USERPROFILE%\\bin")
        val asAdmin = mutableListOf<String>()
        override fun userPath() = path.toList()
        override fun addToUserPath(directory: File) { path -= directory.path; path += directory.path }
        override fun removeFromUserPath(directory: File) { path -= directory.path }
        override fun asAdministrator(script: String) {
            asAdmin += script
            ProcessBuilder("/bin/sh", "-c", script).start().waitFor()
        }
    }

    private fun tool(os: HostOs = HostOs.LINUX, executable: File? = app, system: SystemCommands = FakeSystem(),
                     macLinks: File = File(root, "usr-local-bin")) =
        CommandLineTool(HostPlatform(os, home, environment = emptyMap(), linuxUid = null), executable, system, macLinks)

    @Test fun `installing links the command onto the PATH, running the app as a command-line tool`() {
        val tool = tool()
        assertFalse(tool.installed)
        tool.install()
        assertTrue(tool.installed)
        assertEquals(File(home, ".local/bin/robomori"), tool.location)
        assertEquals(tool.script.toPath(), Files.readSymbolicLink(tool.location.toPath()))
        assertContains(tool.script.readText(), "exec '${app.path}' cli \"\$@\"")
        assertTrue(tool.script.canExecute())

        tool.uninstall()
        assertFalse(tool.installed)
        assertFalse(Files.exists(tool.location.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertFalse(tool.script.exists())
    }

    @Test fun `the command follows the app when it moves`() {
        tool().install()
        val moved = File(root, "Apps/Robomori.AppImage").apply { parentFile.mkdirs(); writeText("app") }
        val tool = tool(executable = moved)
        tool.refresh()
        assertContains(tool.script.readText(), moved.path)
        assertTrue(tool.installed)
    }

    @Test fun `refreshing never installs a command that was not installed`() {
        val tool = tool()
        tool.refresh()
        assertFalse(tool.script.exists())
    }

    @Test fun `another program's command is left alone`() {
        val other = File(home, ".local/bin/robomori").apply { parentFile.mkdirs(); writeText("someone else's") }
        val failure = assertFails { tool().install() }
        assertContains(failure.message.orEmpty(), "Another robomori command")
        assertEquals("someone else's", other.readText())
        tool().uninstall()
        assertEquals("someone else's", other.readText())
    }

    @Test fun `a command installed with Homebrew is left alone`() {
        val links = File(root, "usr-local-bin").apply { mkdirs() }
        val homebrew = File(links, "robomori").toPath()
        Files.createSymbolicLink(homebrew, File(root, "Caskroom/robomori/1.7.0/.homebrew-command-wrappers/robomori").toPath())
        val failure = assertFails { tool(HostOs.MAC, macLinks = links).install() }
        assertEquals("Homebrew already installed the robomori command.", failure.message)
        assertTrue(Files.isSymbolicLink(homebrew))
    }

    @Test fun `macOS asks for an administrator when the link folder is protected`() {
        // Windows doesn't make a folder read-only for its owner, so there's no protected folder to test with.
        org.junit.jupiter.api.Assumptions.assumeFalse(HostOs.detect() == HostOs.WINDOWS)
        val links = File(root, "protected").apply { mkdirs(); setWritable(false) }
        val system = FakeSystem()
        val tool = tool(HostOs.MAC, system = system, macLinks = links)
        try {
            tool.install()
            assertEquals(1, system.asAdmin.size)
            assertContains(system.asAdmin.single(), "ln -sfn")
        } finally { links.setWritable(true) }
    }

    @Test fun `Windows adds the command's folder to the user PATH and keeps the other entries`() {
        val system = FakeSystem()
        val tool = tool(HostOs.WINDOWS, executable = File(root, "robomori-cli.exe").apply { writeText("exe") }, system = system)
        tool.install()
        assertTrue(tool.installed)
        assertEquals(listOf("%USERPROFILE%\\bin", tool.script.parentFile.path), system.path)
        assertEquals("@echo off\r\n\"${File(root, "robomori-cli.exe").path}\" cli %*\r\n", tool.script.readText())
        tool.uninstall()
        assertEquals(listOf("%USERPROFILE%\\bin"), system.path)
    }

    @Test fun `a development run cannot install the command`() {
        val tool = tool(executable = null)
        assertFalse(tool.available)
        assertFails { tool.install() }
    }
}
