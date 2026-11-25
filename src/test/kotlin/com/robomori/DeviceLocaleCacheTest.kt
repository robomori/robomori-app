package com.robomori

import java.io.File
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class DeviceLocaleCacheTest {
    @TempDir lateinit var root: File
    private val fingerprint = "a".repeat(64)
    private val english = DeviceLocale("en-US")
    private val arabic = DeviceLocale("ar-EG")
    private val info = DeviceLocaleInfo(english, listOf(english, arabic))

    @Test fun `cached locales survive a new app session`() {
        DeviceLocaleCache(root).put("device-one", CachedDeviceLocales(fingerprint, info))

        assertEquals(CachedDeviceLocales(fingerprint, info), DeviceLocaleCache(root).get("device-one"))
    }

    @Test fun `different devices have independent cached locales`() {
        val cache = DeviceLocaleCache(root)
        cache.put("device-one", CachedDeviceLocales(fingerprint, info))
        cache.put("device-two", CachedDeviceLocales(fingerprint, info.copy(current = arabic)))

        val restored = DeviceLocaleCache(root)
        assertEquals(english, restored.get("device-one")?.info?.current)
        assertEquals(arabic, restored.get("device-two")?.info?.current)
    }

    @Test fun `corrupt cache falls back to device discovery`() {
        DeviceLocaleCache(root).put("device", CachedDeviceLocales(fingerprint, info))
        root.listFiles()!!.single().writeText("invalid cache")

        assertNull(DeviceLocaleCache(root).get("device"))
    }

    @Test fun `warm refresh reads the current locale without fetching the supported list again`() {
        val cache = DeviceLocaleCache(root)
        cache.put("device", CachedDeviceLocales(fingerprint, info))
        val commands = mutableListOf<Command>()
        val service = service(cache) { command ->
            commands += command
            if ("app_process" in command.args) "ROBOMORI_CURRENT\tar-EG\nROBOMORI_BUILD\t$fingerprint" else ""
        }

        val refreshed = service.read("serial")

        assertEquals(info.copy(current = arabic), refreshed)
        assertFalse(commands.any { "getprop" in it.args })
        assertEquals(listOf("describe", fingerprint), commands.single { "app_process" in it.args }.args.takeLast(2))
    }

    @Test fun `system build changes replace the supported locale cache`() {
        val cache = DeviceLocaleCache(root)
        cache.put("device", CachedDeviceLocales(fingerprint, info))
        val newBuild = "b".repeat(64)
        val service = service(cache) { command -> if ("app_process" in command.args)
            "ROBOMORI_CURRENT\ten-US\nROBOMORI_BUILD\t$newBuild\nROBOMORI_LOCALE\ten-US\nROBOMORI_LOCALE\tja-JP" else "" }

        service.read("serial")

        assertEquals(CachedDeviceLocales(newBuild, DeviceLocaleInfo(english, listOf(english, DeviceLocale("ja-JP")))), cache.get("device"))
    }

    @Test fun `mismatched system builds cannot silently reuse stale supported locales`() {
        val cache = DeviceLocaleCache(root)
        cache.put("device", CachedDeviceLocales(fingerprint, info))
        val service = service(cache) { command -> if ("app_process" in command.args)
            "ROBOMORI_CURRENT\ten-US\nROBOMORI_BUILD\t${"b".repeat(64)}" else "" }

        assertFailsWith<IllegalStateException> { service.read("serial") }
    }

    private fun service(cache: DeviceLocaleCache, run: (Command) -> String): DeviceLocaleService {
        val helper = File(root, "helper.jar").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        return DeviceLocaleService(AppSettings("/sdk", "/avds"), CommandExecutor { command, _, _ -> run(command) },
            helper = { helper }, cache = cache, deviceKey = "device")
    }
}
