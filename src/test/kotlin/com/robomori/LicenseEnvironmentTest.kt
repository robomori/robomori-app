package com.robomori

import java.io.File
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class LicenseEnvironmentTest {
    @TempDir lateinit var directory: File

    private val environment = mapOf("ROBOMORI_LICENSE_ORIGIN" to "http://127.0.0.1:8090",
        "ROBOMORI_PURCHASE_URL" to "http://127.0.0.1:8080/pricing",
        "ROBOMORI_LICENSE_PUBLIC_KEY" to "test-public-key")

    @Test fun `production ignores development settings and files`() {
        File(directory, "license-service.properties").writeText("origin=http://example.com")
        assertNull(localLicenseSettings(environment, development = false, directory))
        assertEquals("origin=http://example.com", File(directory, "license-service.properties").readText())
    }
    @Test fun `development launches share saved service settings without environment overrides`() {
        val first = assertNotNull(localLicenseSettings(environment, true, directory))
        val next = assertNotNull(localLicenseSettings(emptyMap(), true, directory))
        assertEquals(first, next)
        assertEquals("test-public-key", next.publicKey)
        assertEquals(directory, next.directory)
    }
    @Test fun `empty variables fall back to the saved settings instead of failing`() {
        val saved = assertNotNull(localLicenseSettings(environment, true, directory))
        val blank = mapOf("ROBOMORI_LICENSE_ORIGIN" to "", "ROBOMORI_PURCHASE_URL" to "", "ROBOMORI_LICENSE_PUBLIC_KEY" to "")
        assertEquals(saved, localLicenseSettings(blank, true, directory))
    }
    @Test fun `development uses a fixed directory separate from production`() {
        val settings = assertNotNull(localLicenseSettings(emptyMap(), true))
        assertEquals(File(HostPlatform().configDirectory.parentFile, "Robomori Development"), settings.directory)
        assertNotEquals(HostPlatform().configDirectory.canonicalFile, settings.directory.canonicalFile)
    }
    @Test fun `obsolete directory override cannot split development activations`() {
        val settings = assertNotNull(localLicenseSettings(environment +
            ("ROBOMORI_LICENSE_DIRECTORY" to HostPlatform().configDirectory.path), true, directory))
        assertEquals(directory, settings.directory)
    }
    @Test fun `development rejects remote service and purchase endpoints`() {
        assertFails { localLicenseSettings(environment + ("ROBOMORI_LICENSE_ORIGIN" to "http://example.com"), true, directory) }
        assertFails { localLicenseSettings(environment + ("ROBOMORI_PURCHASE_URL" to "http://example.com/pricing"), true, directory) }
        assertFalse(File(directory, "license-service.properties").exists())
    }
}
