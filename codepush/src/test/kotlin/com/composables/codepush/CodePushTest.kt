package com.composables.codepush

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class CodePushTest {
    @TempDir lateinit var root: File

    private val keys = Signing.newKeyPair()
    private val privateKey = keys.first
    private val publicKey = keys.second
    private val config by lazy {
        CodePushConfig("com.example.app", "com.example.MainKt", "https://updates.example.com/code/", publicKey, "1.4.0", File(root, "data"))
    }
    private val server by lazy { File(root, "server") }

    /** Serves the publisher's folder the way the updates URL would. */
    private fun client() = CodePushClient(config) { url ->
        File(server, url.removePrefix(config.updatesUrl)).takeIf { it.isFile }?.readBytes()
    }

    private fun jar(name: String, content: String) = File(root, name).apply { writeText(content) }

    private fun publish(patch: Int, vararg modules: Pair<String, File>, key: String = privateKey, base: String = "1.4.0",
                        published: java.time.Instant = java.time.Instant.now()) =
        CodePushPublisher.publish(server, config.appId, base, patch, modules.toMap(), key, published)

    private val installed = listOf(File("/app/Robomori-1.4.0-5754f4.jar"), File("/app/analytics-1.0.0-3a64.jar"), File("/app/skiko-awt-0.9.jar"))

    @Test fun `manifests have one canonical text form`() {
        val manifest = Manifest("com.example.app", "1.4.0", 3, 1_794_003_000, listOf(Manifest.Replacement("analytics", "a".repeat(64), 10)))
        assertEquals(manifest, Manifest.parse(manifest.text()))
        assertFailsWith<IllegalArgumentException> { Manifest.parse(manifest.text() + "extra line\n") }
        assertFailsWith<IllegalArgumentException> { Manifest.parse(manifest.text().replace("patch 3", "patch 0")) }
    }

    @Test fun `signatures only verify with the matching key and message`() {
        val signature = Signing.sign("hello".toByteArray(), privateKey)
        assertTrue(Signing.verify("hello".toByteArray(), signature, publicKey))
        assertFalse(Signing.verify("hullo".toByteArray(), signature, publicKey))
        assertFalse(Signing.verify("hello".toByteArray(), signature, Signing.newKeyPair().second))
    }

    @Test fun `a published update downloads and is picked at the next launch`() {
        publish(1, "Robomori" to jar("app.jar", "new app code"))

        assertEquals(1, client().downloadLatest())
        val update = assertNotNull(CodePushLauncher.pick(config, installed))

        assertEquals(1, update.manifest.patch)
        assertEquals("new app code", update.jars.single().readText())
    }

    @Test fun `only updates the app accepts by publish time are downloaded, newest first`() {
        val cutoff = java.time.Instant.parse("2026-12-01T00:00:00Z")
        publish(1, "Robomori" to jar("one.jar", "one"), published = java.time.Instant.parse("2026-11-10T00:00:00Z"))
        publish(2, "Robomori" to jar("two.jar", "two"), published = java.time.Instant.parse("2026-11-20T00:00:00Z"))
        publish(3, "Robomori" to jar("three.jar", "three"), published = java.time.Instant.parse("2026-12-05T00:00:00Z"))

        assertEquals(2, client().downloadLatest { !it.isAfter(cutoff) })
        assertNull(client().downloadLatest { !it.isAfter(cutoff) })
        assertEquals(3, client().downloadLatest())
    }

    @Test fun `a downloaded update is ready for the next launch until it runs or fails`() {
        assertNull(client().readyPatch())
        publish(1, "Robomori" to jar("one.jar", "one"))
        client().downloadLatest()
        assertEquals(1, client().readyPatch())

        CodePushLauncher.pick(config, installed)
        CodePushLauncher.pick(config, installed)
        assertNull(client().readyPatch(), "update 1 never came up, so it's no longer offered")
    }

    @Test fun `nothing new on the server downloads nothing`() {
        assertNull(client().downloadLatest())
        publish(1, "Robomori" to jar("app.jar", "new app code"))
        client().downloadLatest()
        assertNull(client().downloadLatest())
    }

    @Test fun `updates signed with another key are refused`() {
        publish(1, "Robomori" to jar("app.jar", "evil code"), key = Signing.newKeyPair().first)
        assertFailsWith<IllegalStateException> { client().downloadLatest() }
        assertNull(CodePushLauncher.pick(config, installed))
    }

    @Test fun `a jar that does not match its manifest is refused`() {
        publish(1, "Robomori" to jar("app.jar", "new app code"))
        File(server, "${config.appId}/files").listFiles()!!.single().writeText("tampered")
        assertFails { client().downloadLatest() }
        assertNull(CodePushLauncher.pick(config, installed))
    }

    @Test fun `an update that never comes up is rolled back for good`() {
        publish(1, "Robomori" to jar("one.jar", "patch one"))
        client().downloadLatest()
        CodePushLauncher.pick(config, installed)
        client().markHealthy()

        publish(2, "Robomori" to jar("two.jar", "patch two"))
        client().downloadLatest()
        assertEquals(2, CodePushLauncher.pick(config, installed)?.manifest?.patch)
        // Patch 2 crashed before the app marked it healthy: the next launch goes back to patch 1.
        assertEquals(1, CodePushLauncher.pick(config, installed)?.manifest?.patch)
        assertEquals(setOf(2), PatchStore(config.dataDirectory).bad("1.4.0"))
        // Patch 1 came up fine before, so quitting it before it reports in again doesn't count against it.
        assertEquals(1, CodePushLauncher.pick(config, installed)?.manifest?.patch)
    }

    @Test fun `a command-line launch runs the confirmed update and never rolls back a new one`() {
        publish(1, "Robomori" to jar("one.jar", "patch one"))
        client().downloadLatest()
        // Nothing has been confirmed yet, so the tool runs the installed code.
        assertNull(CodePushLauncher.pick(config, installed, tool = true))
        CodePushLauncher.pick(config, installed)
        client().markHealthy()

        publish(2, "Robomori" to jar("two.jar", "patch two"))
        client().downloadLatest()
        assertEquals(1, CodePushLauncher.pick(config, installed, tool = true)?.manifest?.patch)
        assertEquals(1, CodePushLauncher.pick(config, installed, tool = true)?.manifest?.patch)
        // The app still gets to try patch 2: tool launches didn't count as failed tries.
        assertEquals(2, CodePushLauncher.pick(config, installed)?.manifest?.patch)
        assertEquals(emptySet(), PatchStore(config.dataDirectory).bad("1.4.0"))
        client().markHealthy()
        assertEquals(2, CodePushLauncher.pick(config, installed, tool = true)?.manifest?.patch)
    }

    @Test fun `a failed update is not downloaded again`() {
        publish(1, "Robomori" to jar("one.jar", "patch one"))
        client().downloadLatest()
        CodePushLauncher.pick(config, installed)
        assertNull(CodePushLauncher.pick(config, installed))
        assertNull(client().downloadLatest())
    }

    @Test fun `updates for another installed version are ignored and cleared`() {
        publish(1, "Robomori" to jar("old.jar", "for 1.3.0"), base = "1.3.0")
        assertNull(client().downloadLatest())

        val store = PatchStore(config.dataDirectory)
        File(config.dataDirectory, "patches/1.3.0/1").mkdirs()
        publish(1, "Robomori" to jar("app.jar", "for 1.4.0"))
        client().downloadLatest()
        assertFalse(File(config.dataDirectory, "patches/1.3.0").exists())
        assertEquals(listOf(1), store.installed(config.appId, "1.4.0", publicKey).map { it.patch })
    }

    @Test fun `an update for a module the app does not have is not run`() {
        publish(1, "missing" to jar("missing.jar", "code"))
        client().downloadLatest()
        assertFails { CodePushLauncher.pick(config, installed) }
    }

    private fun jarWith(name: String, resource: String) = File(root, name).apply {
        java.util.zip.ZipOutputStream(outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("marker.txt"))
            zip.write(resource.toByteArray())
            zip.closeEntry()
        }
    }

    @Test fun `the update's own jars win over the installed ones, and the rest comes from the installed app`() {
        // Jars are compared by where a resource came from, without opening them: Windows locks open jars, and the
        // test's folder couldn't be deleted afterwards.
        java.net.URLClassLoader(arrayOf(jarWith("installed.jar", "installed").toURI().toURL()), null).use { installedApp ->
            UpdateClassLoader(1, listOf(jarWith("update.jar", "update")), installedApp).use { loader ->
                assertTrue(loader.getResource("marker.txt")!!.path.contains("update.jar!"))
                assertEquals(listOf("update.jar!", "installed.jar!"),
                    java.util.Collections.list(loader.getResources("marker.txt")).map { url -> listOf("update.jar!", "installed.jar!").first { it in url.path } })
                assertEquals(String::class.java, loader.loadClass("java.lang.String"))
            }
        }
    }

    @Test fun `packaged jars are matched by module name`() {
        assertTrue(CodePushLauncher.isModuleJar("Robomori-1.4.0-5754f44.jar", "Robomori"))
        assertTrue(CodePushLauncher.isModuleJar("analytics.jar", "analytics"))
        assertFalse(CodePushLauncher.isModuleJar("analytics-extra-1.0.jar", "analytics"))
        assertFalse(CodePushLauncher.isModuleJar("Robomori-1.4.0.zip", "Robomori"))
    }
}
