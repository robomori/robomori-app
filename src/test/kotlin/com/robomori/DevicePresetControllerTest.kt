package com.robomori

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import kotlin.test.*

class DevicePresetControllerTest {
    private val phone = DevicePresetTarget("phone-id", "usb-serial")
    private val other = DevicePresetTarget("other-phone", "other-serial")
    private val on = PresetValue.Toggle(true)
    private val off = PresetValue.Toggle(false)

    @Test fun `only included settings are written`() = runBlocking {
        val access = FakeAccess()
        val controller = DevicePresetController(access)

        controller.apply(phone, preset(PresetSetting.DarkMode to on))

        assertEquals(listOf(PresetSetting.DarkMode), access.writes.map { it.second })
    }

    @Test fun `all previous values are captured before the first write`() = runBlocking {
        val access = FakeAccess()

        DevicePresetController(access).apply(phone, preset(PresetSetting.DarkMode to on, PresetSetting.FontScale to PresetValue.Scale(1.5f)))

        assertEquals(listOf("capture:darkMode", "capture:fontScale", "apply:darkMode", "apply:fontScale"), access.events)
    }

    @Test fun `unsupported setting is skipped without capturing or writing it`() = runBlocking {
        val access = FakeAccess().apply { unsupported = setOf(PresetSetting.TalkBack) }

        val result = DevicePresetController(access).apply(phone, preset(PresetSetting.TalkBack to on))

        assertIs<PresetSettingOutcome.Skipped>(result.results.single().outcome)
        assertTrue(access.events.isEmpty())
    }

    @Test fun `preview exposes restoration limitations without changing the device`() = runBlocking {
        val access = FakeAccess().apply { locationLimit = "The previous GPS coordinate cannot be recovered." }

        val plan = DevicePresetController(access).preview(phone, preset(PresetSetting.Location to PresetValue.Location(null)))

        assertEquals(access.locationLimit, assertIs<PresetSupport.Supported>(plan.changes.single().support).restoreLimit)
        assertTrue(access.events.isEmpty())
    }

    @Test fun `capture failure leaves that setting untouched`() = runBlocking {
        val access = FakeAccess().apply { captureFailures = setOf(PresetSetting.FontScale) }

        val result = DevicePresetController(access).apply(phone, preset(PresetSetting.FontScale to PresetValue.Scale(1.5f)))

        assertIs<PresetSettingOutcome.Failed>(result.results.single().outcome)
        assertTrue(access.writes.isEmpty())
    }

    @Test fun `one failed setting does not hide successful settings`() = runBlocking {
        val access = FakeAccess().apply { applyFailures = setOf(PresetSetting.TalkBack) }

        val result = DevicePresetController(access).apply(phone, preset(PresetSetting.DarkMode to on, PresetSetting.TalkBack to on))

        assertEquals(PresetSettingOutcome.Applied, result.results[0].outcome)
        assertIs<PresetSettingOutcome.Failed>(result.results[1].outcome)
        assertFalse(result.successful)
    }

    @Test fun `unlicensed application does not inspect or mutate the device`() = runBlocking {
        val access = FakeAccess()

        val result = DevicePresetController(access) { false }.apply(phone, preset(PresetSetting.DarkMode to on))

        assertTrue(result.locked)
        assertTrue(access.supportCalls.isEmpty())
        assertTrue(access.events.isEmpty())
    }

    @Test fun `failed write retains its previous value because the device may already have changed`() = runBlocking {
        val access = FakeAccess().apply { applyFailures = setOf(PresetSetting.DarkMode) }
        val controller = DevicePresetController(access)

        controller.apply(phone, preset(PresetSetting.DarkMode to on))

        assertEquals(setOf(PresetSetting.DarkMode), controller.restoreSummaries[phone.identity]?.settings)
    }

    @Test fun `restore uses exact captured values rather than defaults`() = runBlocking {
        val access = FakeAccess().apply { values[phone.identity] = mutableMapOf(PresetSetting.FontScale to PresetValue.Scale(1.37f)) }
        val controller = DevicePresetController(access)
        controller.apply(phone, preset(PresetSetting.FontScale to PresetValue.Scale(1.5f)))

        controller.restore(phone)

        assertEquals(PresetValue.Scale(1.37f), access.values[phone.identity]?.get(PresetSetting.FontScale))
    }

    @Test fun `another preset retains the original restore baseline`() = runBlocking {
        val access = FakeAccess()
        val controller = DevicePresetController(access)
        controller.apply(phone, preset(PresetSetting.DarkMode to on))
        controller.apply(phone, preset(PresetSetting.DarkMode to off))
        controller.apply(phone, preset(PresetSetting.DarkMode to on))

        controller.restore(phone)

        assertEquals(off, access.values[phone.identity]?.get(PresetSetting.DarkMode))
        assertEquals(1, access.events.count { it == "capture:darkMode" })
    }

    @Test fun `restore never uses another device snapshot`() = runBlocking {
        val access = FakeAccess()
        val controller = DevicePresetController(access)
        controller.apply(phone, preset(PresetSetting.DarkMode to on))

        val result = controller.restore(other)

        assertTrue(result.results.isEmpty())
        assertTrue(access.restores.isEmpty())
        assertTrue(phone.identity in controller.restoreSummaries)
    }

    @Test fun `failed restore remains available for retry`() = runBlocking {
        val access = FakeAccess()
        val controller = DevicePresetController(access)
        controller.apply(phone, preset(PresetSetting.DarkMode to on))
        access.restoreFailures = setOf(PresetSetting.DarkMode)

        val result = controller.restore(phone)

        assertIs<PresetSettingOutcome.Failed>(result.results.single().outcome)
        assertEquals(setOf(PresetSetting.DarkMode), controller.restoreSummaries[phone.identity]?.settings)
    }

    @Test fun `successful restore removes its snapshot`() = runBlocking {
        val controller = DevicePresetController(FakeAccess())
        controller.apply(phone, preset(PresetSetting.DarkMode to on))

        controller.restore(phone)

        assertFalse(phone.identity in controller.restoreSummaries)
    }

    @Test fun `a disconnected device cannot be restored until it reconnects`() = runBlocking {
        val access = FakeAccess()
        val controller = DevicePresetController(access)
        controller.apply(phone, preset(PresetSetting.DarkMode to on))
        controller.markDisconnected(phone.identity)

        val result = controller.restore(phone)

        assertIs<PresetSettingOutcome.Skipped>(result.results.single().outcome)
        assertFalse(controller.restoreSummaries.getValue(phone.identity).available)
        assertTrue(access.restores.isEmpty())
    }

    @Test fun `reconnection uses the current serial for the same stable device`() = runBlocking {
        val access = FakeAccess()
        val controller = DevicePresetController(access)
        controller.apply(phone, preset(PresetSetting.DarkMode to on))
        controller.markDisconnected(phone.identity)
        val reconnected = phone.copy(serial = "wifi-serial")
        controller.markConnected(reconnected)

        controller.restore(reconnected)

        assertEquals("wifi-serial", access.restores.single().first.serial)
    }

    @Test fun `new controller has no restoration from a previous app session`() = runBlocking {
        val access = FakeAccess()
        DevicePresetController(access).apply(phone, preset(PresetSetting.DarkMode to on))

        assertTrue(DevicePresetController(access).restoreSummaries.isEmpty())
    }

    @Test fun `same device applications are serialized`() = runBlocking {
        val began = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val access = FakeAccess().apply { beforeApply = { if (!began.isCompleted) { began.complete(Unit); release.await() } } }
        val controller = DevicePresetController(access)
        val first = async { controller.apply(phone, preset(PresetSetting.DarkMode to on)) }
        try {
            withTimeout(5000) { began.await() }
            val second = async { controller.apply(phone, preset(PresetSetting.DarkMode to off)) }
            yield()
            assertTrue(access.writes.isEmpty())
            release.complete(Unit)
            withTimeout(5000) { first.await(); second.await() }
        } finally {
            release.complete(Unit)
        }

        assertEquals(listOf(on, off), access.writes.map { it.third })
    }

    @Test fun `cancellation is not reported as an ordinary preset failure`(): Unit = runBlocking {
        val access = FakeAccess().apply { beforeApply = { throw CancellationException("Closed") } }

        assertFailsWith<CancellationException> { DevicePresetController(access).apply(phone, preset(PresetSetting.DarkMode to on)) }
    }

    private fun preset(vararg values: Pair<PresetSetting, PresetValue>) = DevicePreset("Test", values.toMap())

    @Test fun `applying without undo does not capture previous settings`() = runBlocking {
        val access = FakeAccess()

        DevicePresetController(access, retainRestorePoints = false).apply(phone, preset(PresetSetting.DarkMode to on))

        assertEquals(listOf("apply:darkMode"), access.events)
    }

    @Test fun `independent settings start together when undo is disabled`() = runBlocking {
        val bothStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var started = 0
        val access = FakeAccess().apply { beforeApply = {
            if (++started == 2) bothStarted.complete(Unit)
            release.await()
        } }
        val job = async {
            DevicePresetController(access, retainRestorePoints = false).apply(phone,
                preset(PresetSetting.DarkMode to on, PresetSetting.FontScale to PresetValue.Scale(1.5f)))
        }
        try {
            withTimeout(5000) { bothStarted.await() }
            assertEquals(2, started)
        } finally { release.complete(Unit) }
        assertTrue(job.await().successful)
    }

    @Test fun `parallel application reports a failed setting without losing successful results`(): Unit = runBlocking {
        val access = FakeAccess().apply { applyFailures = setOf(PresetSetting.TalkBack) }

        val result = DevicePresetController(access, retainRestorePoints = false).apply(phone,
            preset(PresetSetting.DarkMode to on, PresetSetting.TalkBack to on))

        assertEquals(PresetSettingOutcome.Applied, result.results[0].outcome)
        assertIs<PresetSettingOutcome.Failed>(result.results[1].outcome)
    }

    private class FakeAccess : DevicePresetAccess {
        val values = mutableMapOf<String, MutableMap<PresetSetting, PresetValue>>()
        val events = mutableListOf<String>()
        val writes = mutableListOf<Triple<DevicePresetTarget, PresetSetting, PresetValue>>()
        val restores = mutableListOf<Pair<DevicePresetTarget, PresetSetting>>()
        val supportCalls = mutableListOf<PresetSetting>()
        var unsupported = emptySet<PresetSetting>()
        var captureFailures = emptySet<PresetSetting>()
        var applyFailures = emptySet<PresetSetting>()
        var restoreFailures = emptySet<PresetSetting>()
        var locationLimit: String? = null
        var beforeApply: suspend () -> Unit = {}

        override suspend fun support(target: DevicePresetTarget, setting: PresetSetting, value: PresetValue): PresetSupport {
            supportCalls += setting
            return if (setting in unsupported) PresetSupport.Unsupported("Not supported")
            else PresetSupport.Supported(if (setting == PresetSetting.Location) locationLimit else null)
        }

        override suspend fun capture(target: DevicePresetTarget, setting: PresetSetting): PresetRestorePoint {
            events += "capture:${setting.key}"
            check(setting !in captureFailures) { "Could not read" }
            return PresetPreviousValue(values[target.identity]?.get(setting) ?: when (setting) {
                PresetSetting.DarkMode, PresetSetting.TalkBack -> PresetValue.Toggle(false)
                PresetSetting.FontScale, PresetSetting.Animations -> PresetValue.Scale(1f)
                PresetSetting.Locale -> PresetValue.Locale("en-US")
                PresetSetting.Location -> PresetValue.Location(null)
            }, if (setting == PresetSetting.Location) locationLimit else null)
        }

        override suspend fun apply(target: DevicePresetTarget, setting: PresetSetting, value: PresetValue) {
            beforeApply()
            events += "apply:${setting.key}"
            check(setting !in applyFailures) { "Could not apply" }
            writes += Triple(target, setting, value)
            values.getOrPut(target.identity, ::mutableMapOf)[setting] = value
        }

        override suspend fun restore(target: DevicePresetTarget, setting: PresetSetting, previous: PresetRestorePoint) {
            check(setting !in restoreFailures) { "Could not restore" }
            restores += target to setting
            values.getOrPut(target.identity, ::mutableMapOf)[setting] = (previous as PresetPreviousValue).value
        }
    }
}
