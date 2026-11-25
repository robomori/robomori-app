package com.robomori

import java.io.File
import java.io.IOException
import java.io.StringWriter
import java.security.MessageDigest
import java.util.Properties

internal data class CachedDeviceLocales(val fingerprint: String, val info: DeviceLocaleInfo)

internal class DeviceLocaleCache(private val directory: File = File(HostPlatform().cacheDirectory, "device-locales")) {
    private val entries = mutableMapOf<String, CachedDeviceLocales?>()

    @Synchronized
    fun get(device: String): CachedDeviceLocales? {
        if (entries.containsKey(device)) return entries[device]
        val cached = runCatching {
            val values = Properties().apply { file(device).inputStream().use(::load) }
            require(values.getProperty("version") == "1")
            val fingerprint = values.getProperty("fingerprint")
            require(fingerprint != null && fingerprint.matches(Regex("[a-f0-9]{64}")))
            val current = requireNotNull(DeviceLocale.parse(values.getProperty("current").orEmpty()))
            val available = values.getProperty("available").split(',').map { requireNotNull(DeviceLocale.parse(it)) }
            require(available.isNotEmpty() && current in available)
            CachedDeviceLocales(fingerprint, DeviceLocaleInfo(current, available))
        }.getOrNull()
        entries[device] = cached
        return cached
    }

    @Synchronized
    fun put(device: String, cached: CachedDeviceLocales) {
        if (entries[device] == cached) return
        entries[device] = cached
        val values = Properties().apply {
            setProperty("version", "1")
            setProperty("fingerprint", cached.fingerprint)
            setProperty("current", cached.info.current.tag)
            setProperty("available", cached.info.available.joinToString(",") { it.tag })
        }
        try {
            atomicWrite(file(device), StringWriter().also { values.store(it, null) }.toString())
        } catch (_: IOException) {
            // A disk cache failure must not prevent using the live locale controls.
        }
    }

    private fun file(device: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(device.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(directory, "$hash.properties")
    }
}
