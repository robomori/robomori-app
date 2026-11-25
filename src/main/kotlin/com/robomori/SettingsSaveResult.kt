package com.robomori

sealed interface SettingsSaveResult {
    data object Saved : SettingsSaveResult
    data object DownloadsActive : SettingsSaveResult
    data object WorkspaceBusy : SettingsSaveResult
    data object MissingFolders : SettingsSaveResult
    data object InvalidSdkFolder : SettingsSaveResult
    data object InvalidAvdFolder : SettingsSaveResult
    data class InvalidExecutables(val tools: Set<String>) : SettingsSaveResult
    data class Failed(val cause: Throwable) : SettingsSaveResult
}

/**
 * Checks SDK locations the way the SDK Locations page saves them: both folders exist and are writable, and every tool
 * given its own path is executable. Returns the problem, or null when [this] can be saved as [normalizedLocations].
 */
internal fun AppSettings.locationProblem(): SettingsSaveResult? {
    if (sdk.isBlank() || avds.isBlank()) return SettingsSaveResult.MissingFolders
    val sdkFolder = expandPath(sdk)
    if (!sdkFolder.isDirectory || !sdkFolder.canWrite()) return SettingsSaveResult.InvalidSdkFolder
    val overridden = mapOf("adb" to adb, "emulator" to emulator, "sdkmanager" to sdkmanager, "avdmanager" to avdmanager)
        .filterValues(String::isNotBlank).keys
    val missing = Toolchain(this).tools.filterKeys { it in overridden }.filterValues { !it.isFile || !it.canExecute() }.keys
    if (missing.isNotEmpty()) return SettingsSaveResult.InvalidExecutables(missing)
    val avdFolder = expandPath(avds)
    if (!avdFolder.isDirectory || !avdFolder.canWrite()) return SettingsSaveResult.InvalidAvdFolder
    return null
}

/** The same locations with every path expanded, as they are saved. */
internal fun AppSettings.normalizedLocations(): AppSettings {
    fun normalize(path: String) = if (path.isBlank()) "" else expandPath(path).path
    return copy(sdk = expandPath(sdk).path, avds = expandPath(avds).path, adb = normalize(adb), emulator = normalize(emulator),
        sdkmanager = normalize(sdkmanager), avdmanager = normalize(avdmanager))
}
