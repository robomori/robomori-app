package com.robomori

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/** Why Android turned down an APK, from the code `adb install` reports. */
internal enum class InstallFailure { SignedDifferently, Downgrade, NewerAndroid, OtherProcessor, NoSpace, NotAnApk, Other }

internal class ApkInstallException(val reason: InstallFailure, detail: String) : Exception(detail) {
    constructor(failure: Pair<InstallFailure, String>) : this(failure.first, failure.second)
}

/** Reads `Failure [INSTALL_FAILED_…: …]` from `adb install`'s output. */
internal fun installFailure(output: String): Pair<InstallFailure, String> {
    val line = output.lineSequence().map(String::trim).lastOrNull { it.startsWith("Failure") || "INSTALL_" in it }
        ?: output.lineSequence().map(String::trim).lastOrNull(String::isNotEmpty).orEmpty()
    val reason = when {
        "UPDATE_INCOMPATIBLE" in line -> InstallFailure.SignedDifferently
        "VERSION_DOWNGRADE" in line -> InstallFailure.Downgrade
        "OLDER_SDK" in line -> InstallFailure.NewerAndroid
        "NO_MATCHING_ABIS" in line -> InstallFailure.OtherProcessor
        "INSUFFICIENT_STORAGE" in line -> InstallFailure.NoSpace
        "INVALID_APK" in line || "NOT_APK" in line || "PARSE_FAILED" in line -> InstallFailure.NotAnApk
        else -> InstallFailure.Other
    }
    return reason to line
}

internal sealed interface ApkInstallStatus {
    data object Idle : ApkInstallStatus
    /** Installing [name], with [remaining] more to go after it. */
    data class Installing(val name: String, val remaining: Int) : ApkInstallStatus
    data class Failed(val name: String, val reason: InstallFailure, val detail: String) : ApkInstallStatus
}

/**
 * The package name in [apk]'s manifest, which APKs keep in Android's binary XML: a string pool, then each element with
 * its attributes as indexes into it. Null when the file isn't an APK.
 */
internal fun apkPackageName(apk: File): String? = runCatching {
    val manifest = ZipFile(apk).use { zip -> zip.getInputStream(zip.getEntry("AndroidManifest.xml") ?: return null).readBytes() }
    val data = ByteBuffer.wrap(manifest).order(ByteOrder.LITTLE_ENDIAN)
    var strings = emptyList<String>()
    var chunk = 8 // After the file's own header.
    while (chunk + 8 <= data.limit()) {
        val type = data.getShort(chunk).toInt() and 0xFFFF
        val headerSize = data.getShort(chunk + 2).toInt() and 0xFFFF
        val size = data.getInt(chunk + 4)
        when (type) {
            0x0001 -> strings = readStringPool(data, chunk)
            0x0102 -> {
                // The first element is <manifest>; its attributes follow the element's name and their layout.
                val extension = chunk + headerSize
                val attributeStart = data.getShort(extension + 8).toInt() and 0xFFFF
                val attributeSize = data.getShort(extension + 10).toInt() and 0xFFFF
                val attributeCount = data.getShort(extension + 12).toInt() and 0xFFFF
                for (index in 0 until attributeCount) {
                    val attribute = extension + attributeStart + index * attributeSize
                    if (strings.getOrNull(data.getInt(attribute + 4)) == "package") return strings.getOrNull(data.getInt(attribute + 8))
                }
                return null
            }
        }
        if (size <= 0) return null
        chunk += size
    }
    null
}.getOrNull()

private fun readStringPool(data: ByteBuffer, chunk: Int): List<String> {
    val count = data.getInt(chunk + 8)
    val utf8 = data.getInt(chunk + 16) and 0x100 != 0
    val stringsStart = chunk + data.getInt(chunk + 20)
    return (0 until count).map { index ->
        var at = stringsStart + data.getInt(chunk + 28 + index * 4)
        if (utf8) {
            // The length in characters, then in bytes, each in one byte or two.
            at += if (data.get(at).toInt() and 0x80 != 0) 2 else 1
            val high = data.get(at).toInt() and 0xFF
            val length = if (high and 0x80 != 0) ((high and 0x7F) shl 8) or (data.get(at + 1).toInt() and 0xFF) else high
            at += if (high and 0x80 != 0) 2 else 1
            String(ByteArray(length) { data.get(at + it) }, Charsets.UTF_8)
        } else {
            val high = data.getShort(at).toInt() and 0xFFFF
            val length = if (high and 0x8000 != 0) ((high and 0x7FFF) shl 16) or (data.getShort(at + 2).toInt() and 0xFFFF) else high
            at += if (high and 0x8000 != 0) 4 else 2
            String(CharArray(length) { data.getChar(at + it * 2) })
        }
    }
}

/** Installing the APKs dropped on a device's screen, one after another, then opening the last; or why one failed. */
internal class ApkInstallState(private val service: InstalledAppsService, val serial: String, private val scope: CoroutineScope) {
    var status by mutableStateOf<ApkInstallStatus>(ApkInstallStatus.Idle); private set
    private var job: Job? = null

    fun install(files: List<File>) {
        val apks = files.filter(::isApk).ifEmpty { return }
        if (job?.isActive == true) return
        job = scope.launch {
            for ((index, apk) in apks.withIndex()) {
                val name = apk.nameWithoutExtension
                status = ApkInstallStatus.Installing(name, apks.size - index - 1)
                try {
                    runInterruptible(Dispatchers.IO) { service.install(serial, apk) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    status = ApkInstallStatus.Failed(name, (failure as? ApkInstallException)?.reason ?: InstallFailure.Other,
                        failure.message.orEmpty())
                    return@launch
                }
            }
            // Opens the last app, as running it from Android Studio does. Apps without a screen to open stay closed.
            apkPackageName(apks.last())?.let { packageName ->
                runCatching { runInterruptible(Dispatchers.IO) { service.run(serial, packageName, AppAction.Launch) } }
            }
            status = ApkInstallStatus.Idle
        }
    }

    fun dismiss() { if (job?.isActive != true) status = ApkInstallStatus.Idle }
}

internal fun isApk(file: File) = file.isFile && file.extension.equals("apk", ignoreCase = true)
