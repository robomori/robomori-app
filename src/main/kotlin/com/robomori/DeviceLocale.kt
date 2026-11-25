package com.robomori

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Locale

internal data class DeviceLocale(val tag: String) {
    val label: String get() = Locale.forLanguageTag(tag).getDisplayName(Locale.ENGLISH)
    val nativeLabel: String get() = Locale.forLanguageTag(tag).let { it.getDisplayName(it) }

    fun matches(query: String): Boolean = listOf(tag, label, nativeLabel).any { it.contains(query.trim(), ignoreCase = true) }

    companion object {
        fun parse(tag: String): DeviceLocale? {
            if (!tag.matches(Regex("[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*"))) return null
            val locale = runCatching { Locale.Builder().setLanguageTag(tag).build() }.getOrNull() ?: return null
            return locale.toLanguageTag().takeUnless { it == "und" }?.let(::DeviceLocale)
        }
    }
}

internal data class DeviceLocaleInfo(val current: DeviceLocale, val available: List<DeviceLocale>)

internal interface DeviceLocaleAccess {
    fun read(serial: String): DeviceLocaleInfo
    fun apply(serial: String, locale: DeviceLocale): DeviceLocale
}

internal class DeviceLocaleService(
    settings: AppSettings,
    executor: CommandExecutor = ProcessExecutor(),
    private val helper: () -> File = { DeviceHelperJar.bundled.file },
    private val cache: DeviceLocaleCache? = null,
    private val deviceKey: String? = null,
) : DeviceLocaleAccess {
    private val android = AndroidService(settings, executor)

    override fun read(serial: String): DeviceLocaleInfo {
        val key = deviceKey ?: serial
        val cached = cache?.get(key)
        if (cached == null) {
            val sdk = android.command(android.toolchain.adb, listOf("-s", serial, "shell", "getprop", "ro.build.version.sdk")).trim().toIntOrNull()
            check(sdk != null && sdk >= 24) { "Device language controls require Android 7 or later." }
        }
        val output = execute(serial, "describe", *listOfNotNull(cached?.fingerprint).toTypedArray())
        val current = currentLocale(output)
        val fingerprint = output.lineSequence().firstOrNull { it.startsWith("ROBOMORI_BUILD\t") }?.substringAfter('\t')
        val discovered = output.lineSequence().filter { it.startsWith("ROBOMORI_LOCALE\t") }
            .mapNotNull { DeviceLocale.parse(it.substringAfter('\t')) }.toList()
        val available = discovered.ifEmpty { cached?.takeIf { it.fingerprint == fingerprint }?.info?.available.orEmpty() }
        check(available.isNotEmpty()) { "This device could not list its supported languages. Try again." }
        val choices = if (discovered.isEmpty() && current in available) available
            else (available + current).distinct().sortedBy { it.label.lowercase(Locale.ROOT) }
        val info = DeviceLocaleInfo(current, choices)
        if (fingerprint != null && fingerprint.matches(Regex("[a-f0-9]{64}"))) cache?.put(key, CachedDeviceLocales(fingerprint, info))
        return info
    }

    override fun apply(serial: String, locale: DeviceLocale): DeviceLocale {
        require(DeviceLocale.parse(locale.tag) == locale) { "Choose a supported device language." }
        val current = currentLocale(execute(serial, "set", locale.tag))
        check(current == locale) { "The device did not apply that language. Check its language settings and try again." }
        cache?.get(deviceKey ?: serial)?.let { cached ->
            cache.put(deviceKey ?: serial, cached.copy(info = cached.info.copy(current = current,
                available = (cached.info.available + current).distinct())))
        }
        return current
    }

    fun readPreferredLocales(serial: String): List<DeviceLocale> = preferredLocales(execute(serial, "describe"))

    fun applyPreferredLocales(serial: String, locales: List<DeviceLocale>): List<DeviceLocale> {
        require(locales.isNotEmpty() && locales.distinct() == locales && locales.all { DeviceLocale.parse(it.tag) == it }) {
            "Choose valid device locales."
        }
        val actual = preferredLocales(execute(serial, "set", locales.joinToString(",") { it.tag }))
        check(actual == locales) { "The device did not restore its previous locales. Try again." }
        return actual
    }

    private fun preferredLocales(output: String): List<DeviceLocale> {
        val tags = output.lineSequence().firstOrNull { it.startsWith("ROBOMORI_CURRENT\t") }
            ?.substringAfter('\t')?.split(',') ?: error("Could not read the device's locales. Try again.")
        return tags.map { DeviceLocale.parse(it) ?: error("The device returned an unsupported locale.") }
            .also { check(it.isNotEmpty()) { "The device has no preferred locale." } }
    }

    private fun execute(serial: String, vararg action: String): String {
        require(serial.isNotBlank()) { "Select a connected device." }
        val file = helper()
        // One copy per build of the helper, kept for next time, so an interrupted command leaves nothing extra behind.
        val remote = "/data/local/tmp/robomori-locale-${contentVersion(file)}.jar"
        fun shell(script: String) = android.command(android.toolchain.adb, listOf("-s", serial, "shell", script)).trim()
        if (shell(deviceFileMatches(remote, file.length())) != "ROBOMORI_PRESENT") {
            // Earlier builds' copies, and the one-per-command copies older builds left, are of no further use.
            shell("rm -f /data/local/tmp/robomori-locale-*.jar")
            android.command(android.toolchain.adb, listOf("-s", serial, "push", file.absolutePath, remote))
        }
        return run {
            val output = android.command(android.toolchain.adb, listOf("-s", serial, "shell", "CLASSPATH=$remote",
                "app_process", "/", "com.robomori.device.LocaleHelper") + action)
            check(!output.contains("ROBOMORI_ERROR\tDENIED")) {
                "This device does not allow changing its language remotely. Change it in the device's Settings instead."
            }
            check(!output.contains("ROBOMORI_ERROR\t")) {
                "This device does not support remote language controls. Change its language in the device's Settings."
            }
            output
        }
    }

    private fun currentLocale(output: String): DeviceLocale = output.lineSequence()
        .firstOrNull { it.startsWith("ROBOMORI_CURRENT\t") }?.substringAfter('\t')?.substringBefore(',')
        ?.let(DeviceLocale::parse) ?: error("Could not read the device's language. Try again.")
}

internal sealed interface DeviceLocaleState {
    data object Loading : DeviceLocaleState
    data class Ready(val info: DeviceLocaleInfo, val applied: Boolean = false) : DeviceLocaleState
    data class Applying(val info: DeviceLocaleInfo, val selection: DeviceLocale) : DeviceLocaleState
    data class Failed(val cause: Throwable, val info: DeviceLocaleInfo? = null) : DeviceLocaleState
}

internal class DeviceLocaleController(
    private val service: DeviceLocaleAccess,
    private val serial: String,
    private val scope: CoroutineScope,
    private val canEdit: () -> Boolean,
    private val onLocked: () -> Unit,
    initialInfo: DeviceLocaleInfo? = null,
) {
    var state: DeviceLocaleState by mutableStateOf(initialInfo?.let { DeviceLocaleState.Ready(it) } ?: DeviceLocaleState.Loading)
        private set
    private var revision = 0L
    private val writes = Mutex()

    fun refresh() {
        if (state is DeviceLocaleState.Applying) return
        val requested = ++revision
        val previous = when (val current = state) {
            is DeviceLocaleState.Ready -> current.info
            is DeviceLocaleState.Failed -> current.info
            else -> null
        }
        state = previous?.let { DeviceLocaleState.Ready(it) } ?: DeviceLocaleState.Loading
        scope.launch {
            val outcome = try { DeviceLocaleState.Ready(withContext(Dispatchers.IO) { service.read(serial) }) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { DeviceLocaleState.Failed(failure, previous) }
            if (requested == revision) state = outcome
        }
    }

    fun apply(locale: DeviceLocale) {
        if (!canEdit()) { onLocked(); return }
        val current = state
        val info = when (current) {
            is DeviceLocaleState.Ready -> current.info
            is DeviceLocaleState.Applying -> current.info
            is DeviceLocaleState.Failed -> current.info
            DeviceLocaleState.Loading -> null
        } ?: return
        val selection = (current as? DeviceLocaleState.Applying)?.selection ?: info.current
        if (locale == selection || locale !in info.available) return
        val requested = ++revision
        state = DeviceLocaleState.Applying(info, locale)
        scope.launch {
            writes.withLock {
                if (requested != revision) return@withLock
                val outcome = try {
                    val actual = withContext(Dispatchers.IO) { service.apply(serial, locale) }
                    DeviceLocaleState.Ready(info.copy(current = actual), applied = true)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { DeviceLocaleState.Failed(failure, info) }
                if (requested == revision) state = outcome
            }
        }
    }
}
