package com.robomori

import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds

/** How old sdkmanager's package list may get before it's listed again. */
internal val CatalogMaxAge = 1.days

/**
 * sdkmanager's package list, kept between runs because listing it takes a while. The app and the command line share it.
 * The first line names the SDK the catalog was listed for: its installed flags and paths are specific to it.
 */
class SdkCatalogCache(private val file: File = File(HostPlatform().configDirectory, "sdk-catalog.txt")) {
    class Entry(val text: String, val age: Duration)

    fun read(settings: AppSettings): Entry? {
        if (!file.isFile) return null
        val lines = runCatching { file.readText() }.getOrNull()?.lines() ?: return null
        if (lines.firstOrNull() != "sdk=${settings.sdk}") return null
        val age = (System.currentTimeMillis() - file.lastModified()).coerceAtLeast(0).milliseconds
        return Entry(lines.drop(1).joinToString("\n"), age)
    }

    fun write(settings: AppSettings, text: String) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText("sdk=${settings.sdk}\n$text")
        }
    }
}
