package com.robomori

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Preset values persist; restore points intentionally last only for this application session. */
internal class DevicePresetController(
    private val access: DevicePresetAccess,
    private val retainRestorePoints: Boolean = true,
    private val canEdit: () -> Boolean = { true },
) {
    var restoreSummaries: Map<String, PresetRestoreSummary> by mutableStateOf(emptyMap())
        private set
    private val previous = mutableMapOf<String, LinkedHashMap<PresetSetting, PresetRestorePoint>>()
    private val operations = mutableMapOf<String, Mutex>()
    private val disconnected = mutableSetOf<String>()

    suspend fun preview(target: DevicePresetTarget, preset: DevicePreset): PresetPlan = PresetPlan(
        target, preset, ordered(preset).map { (setting, value) ->
            PresetPlannedChange(setting, value, support(target, setting, value))
        },
    )

    suspend fun apply(target: DevicePresetTarget, preset: DevicePreset): PresetOperationResult =
        mutex(target).withLock {
            if (!canEdit()) return@withLock PresetOperationResult(emptyList(), locked = true)
            if (!retainRestorePoints) return@withLock applyWithoutRestore(target, preset)
            val results = mutableListOf<PresetSettingResult>()
            val prepared = mutableListOf<Triple<PresetSetting, PresetValue, PresetRestorePoint>>()
            for ((setting, value) in ordered(preset)) {
                when (val capability = support(target, setting, value)) {
                    is PresetSupport.Unsupported -> results += PresetSettingResult(setting, PresetSettingOutcome.Skipped(capability.reason))
                    is PresetSupport.Supported -> try {
                        val point = previous[target.identity]?.get(setting) ?: access.capture(target, setting)
                        prepared += Triple(setting, value, point)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        results += PresetSettingResult(setting, PresetSettingOutcome.Failed(
                            failure.message ?: "Could not read the previous value. This setting was not changed."))
                    }
                }
            }
            for ((setting, value, point) in prepared) {
                if (!canEdit() || target.identity in disconnected) {
                    results += PresetSettingResult(setting, PresetSettingOutcome.Skipped(
                        if (!canEdit()) "A license is required to change this setting." else "This device is disconnected."))
                    continue
                }
                // A device command may take effect before its readback fails or is cancelled.
                previous.getOrPut(target.identity, ::linkedMapOf).putIfAbsent(setting, point)
                publish(target.identity)
                try {
                    access.apply(target, setting, value)
                    results += PresetSettingResult(setting, PresetSettingOutcome.Applied)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    results += PresetSettingResult(setting, PresetSettingOutcome.Failed(
                        failure.message ?: "Could not change this setting. Try again."))
                }
            }
            PresetOperationResult(orderedResults(preset, results))
        }

    suspend fun restore(target: DevicePresetTarget): PresetOperationResult = mutex(target).withLock {
        if (!canEdit()) return@withLock PresetOperationResult(emptyList(), locked = true)
        val points = previous[target.identity]?.toMap().orEmpty()
        val results = mutableListOf<PresetSettingResult>()
        for ((setting, point) in points) {
            if (target.identity in disconnected || !canEdit()) {
                results += PresetSettingResult(setting, PresetSettingOutcome.Skipped(
                    if (target.identity in disconnected) "This device is disconnected." else "A license is required to restore this setting."))
                continue
            }
            try {
                access.restore(target, setting, point)
                previous[target.identity]?.remove(setting)
                publish(target.identity)
                results += PresetSettingResult(setting, PresetSettingOutcome.Restored)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                results += PresetSettingResult(setting, PresetSettingOutcome.Failed(
                    failure.message ?: "Could not restore this setting. Try again."))
            }
        }
        PresetOperationResult(results)
    }

    fun markDisconnected(identity: String) {
        disconnected += identity
        publish(identity)
    }

    private suspend fun applyWithoutRestore(target: DevicePresetTarget, preset: DevicePreset): PresetOperationResult = coroutineScope {
        PresetOperationResult(ordered(preset).map { (setting, value) -> async {
            val outcome = when (val capability = support(target, setting, value)) {
                is PresetSupport.Unsupported -> PresetSettingOutcome.Skipped(capability.reason)
                is PresetSupport.Supported -> if (!canEdit() || target.identity in disconnected) {
                    PresetSettingOutcome.Skipped("This device is disconnected or can no longer be edited.")
                } else try {
                    access.apply(target, setting, value)
                    PresetSettingOutcome.Applied
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { PresetSettingOutcome.Failed(failure.message ?: "Could not change this setting. Try again.") }
            }
            PresetSettingResult(setting, outcome)
        } }.awaitAll())
    }

    fun markConnected(target: DevicePresetTarget) {
        disconnected -= target.identity
        publish(target.identity)
    }

    fun discardRestore(identity: String) {
        previous.remove(identity)
        restoreSummaries = restoreSummaries - identity
    }

    private suspend fun support(target: DevicePresetTarget, setting: PresetSetting, value: PresetValue): PresetSupport {
        if (target.identity in disconnected) return PresetSupport.Unsupported("This device is disconnected.")
        return try { access.support(target, setting, value) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { PresetSupport.Unsupported(failure.message ?: "Could not check this setting. Try again.") }
    }

    private fun publish(identity: String) {
        val points = previous[identity].orEmpty()
        restoreSummaries = if (points.isEmpty()) restoreSummaries - identity else restoreSummaries + (identity to
            PresetRestoreSummary(points.keys.toSet(), points.mapNotNull { (setting, point) ->
                point.limitation?.let { setting to it }
            }.toMap(), available = identity !in disconnected))
    }

    private fun mutex(target: DevicePresetTarget): Mutex = operations.getOrPut(target.identity) { Mutex() }
    private fun ordered(preset: DevicePreset): List<Pair<PresetSetting, PresetValue>> =
        PresetSetting.all.mapNotNull { setting -> preset.values[setting]?.let { setting to it } }
    private fun orderedResults(preset: DevicePreset, results: List<PresetSettingResult>) =
        ordered(preset).mapNotNull { (setting, _) -> results.firstOrNull { it.setting == setting } }
}
