package com.robomori

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.*

class AndroidDevicePresetAccessTest {
    private val target = DevicePresetTarget("phone-id", "phone-serial")
    private val settings = AppSettings("/sdk", "/avds")

    @Test fun `known locale capabilities do not query the device again`() = runBlocking {
        val shell = FakeShell()
        val info = DeviceLocaleInfo(DeviceLocale("en-US"), listOf(DeviceLocale("en-US"), DeviceLocale("ar-EG")))
        val access = AndroidDevicePresetAccess({ settings }, FakeLocation(), shell, knownLocales = { info })

        val support = access.support(target, PresetSetting.Locale, PresetValue.Locale("ar-EG"))

        assertIs<PresetSupport.Supported>(support)
        assertTrue(shell.commands.isEmpty())
    }

    @Test fun `known TalkBack availability does not reread all device settings`() = runBlocking {
        val shell = FakeShell()
        val cached = DeviceSettings(false, AnimationScaleSetting.entries.associateWith { 1f }, 1f, true, false)
        val access = AndroidDevicePresetAccess({ settings }, FakeLocation(), shell, knownSettings = { cached })

        val support = access.support(target, PresetSetting.TalkBack, PresetValue.Toggle(true))

        assertIs<PresetSupport.Supported>(support)
        assertTrue(shell.commands.isEmpty())
    }

    @Test fun `restoring appearance restores automatic mode rather than its visible boolean`() = runBlocking {
        val shell = FakeShell()
        val access = access(shell)
        val point = access.capture(target, PresetSetting.DarkMode)
        access.apply(target, PresetSetting.DarkMode, PresetValue.Toggle(true))

        access.restore(target, PresetSetting.DarkMode, point)

        assertEquals("auto", shell.nightMode)
    }

    @Test fun `font restoration preserves an arbitrary original scale`() = runBlocking {
        val shell = FakeShell().apply { values["system/font_scale"] = "1.370" }
        val access = access(shell)
        val point = access.capture(target, PresetSetting.FontScale)
        access.apply(target, PresetSetting.FontScale, PresetValue.Scale(1.5f))

        access.restore(target, PresetSetting.FontScale, point)

        assertEquals("1.370", shell.values["system/font_scale"])
    }

    @Test fun `animations set all three Android scales and restore each one as it was`() = runBlocking {
        val shell = FakeShell()
        shell.values["global/animator_duration_scale"] = "2.0"
        val access = access(shell)
        val point = access.capture(target, PresetSetting.Animations)
        access.apply(target, PresetSetting.Animations, PresetValue.Scale(0f))

        assertEquals(listOf("0.0", "0.0", "0.0"), AnimationScaleSetting.entries.map { shell.values["global/${it.key}"] })

        access.restore(target, PresetSetting.Animations, point)

        assertFalse("global/window_animation_scale" in shell.values)
        assertFalse("global/transition_animation_scale" in shell.values)
        assertEquals("2.0", shell.values["global/animator_duration_scale"])
    }

    @Test fun `every device command includes the selected serial`() = runBlocking {
        val shell = FakeShell()
        val access = access(shell)
        val point = access.capture(target, PresetSetting.DarkMode)
        access.apply(target, PresetSetting.DarkMode, PresetValue.Toggle(true))
        access.restore(target, PresetSetting.DarkMode, point)

        assertTrue(shell.commands.isNotEmpty())
        assertTrue(shell.commands.all { it.args.take(3) == listOf("-s", target.serial, "shell") })
    }

    @Test fun `a disconnected target cannot be mutated`(): Unit = runBlocking {
        val shell = FakeShell()
        val access = access(shell, available = false)

        assertFailsWith<IllegalStateException> { access.apply(target, PresetSetting.DarkMode, PresetValue.Toggle(true)) }

        assertTrue(shell.commands.isEmpty())
    }

    @Test fun `wear devices report appearance as unsupported without device writes`() = runBlocking {
        val shell = FakeShell()
        val access = access(shell, type = DeviceType.WEAR)

        val support = access.support(target, PresetSetting.DarkMode, PresetValue.Toggle(false))

        assertIs<PresetSupport.Unsupported>(support)
        assertTrue(shell.commands.isEmpty())
    }

    @Test fun `an unconfirmed scale is not reported as successfully applied`(): Unit = runBlocking {
        val shell = FakeShell().apply { ignoreSettingsWrites = true }

        assertFailsWith<IllegalStateException> {
            access(shell).apply(target, PresetSetting.FontScale, PresetValue.Scale(1.5f))
        }
    }

    @Test fun `saving an arbitrary current scale can be applied to another device`() = runBlocking {
        val shell = FakeShell()

        access(shell).apply(target, PresetSetting.FontScale, PresetValue.Scale(1.37f))

        assertEquals("1.37", shell.values["system/font_scale"])
    }

    @Test fun `talkback restoration recovers the previous accessibility configuration`() = runBlocking {
        val shell = FakeShell().apply {
            values["secure/enabled_accessibility_services"] = "other.app/.Reader"
            values["secure/accessibility_enabled"] = "0"
        }
        val access = access(shell)
        val point = access.capture(target, PresetSetting.TalkBack)
        access.apply(target, PresetSetting.TalkBack, PresetValue.Toggle(true))

        access.restore(target, PresetSetting.TalkBack, point)

        assertEquals("other.app/.Reader", shell.values["secure/enabled_accessibility_services"])
        assertEquals("0", shell.values["secure/accessibility_enabled"])
    }

    @Test fun `unsupported locale is identified before application`() = runBlocking {
        val locale = FakeLocale()
        val access = access(FakeShell(), locale)

        val support = access.support(target, PresetSetting.Locale, PresetValue.Locale("ja-JP"))

        assertIs<PresetSupport.Unsupported>(support)
        assertTrue(locale.writes.isEmpty())
    }

    @Test fun `a primary-only locale connection exposes its restoration limit`(): Unit = runBlocking {
        val support = access(FakeShell()).support(target, PresetSetting.Locale, PresetValue.Locale("ar-EG"))

        assertNotNull(assertIs<PresetSupport.Supported>(support).restoreLimit)
    }

    @Test fun `a primary-only locale connection restores the captured primary locale`() = runBlocking {
        val locale = FakeLocale()
        val access = access(FakeShell(), locale)
        val point = access.capture(target, PresetSetting.Locale)
        access.apply(target, PresetSetting.Locale, PresetValue.Locale("ar-EG"))

        access.restore(target, PresetSetting.Locale, point)

        assertEquals(DeviceLocale("en-US"), locale.current)
        assertTrue(locale.writes.all { it.first == target.serial })
    }

    @Test fun `location is delegated to the shared location owner`() = runBlocking {
        val location = FakeLocation()
        val access = AndroidDevicePresetAccess({ settings }, location, FakeShell())
        val value = PresetValue.Location(DeviceCoordinates(35.6, 139.7))
        val point = access.capture(target, PresetSetting.Location)
        access.apply(target, PresetSetting.Location, value)
        access.restore(target, PresetSetting.Location, point)

        assertEquals(listOf("capture", "apply", "restore"), location.calls.map { it.first })
        assertTrue(location.calls.all { it.second == target })
        assertEquals(value, location.lastValue)
    }

    private fun access(shell: FakeShell, locale: FakeLocale = FakeLocale(), available: Boolean = true, type: DeviceType? = null) =
        AndroidDevicePresetAccess({ settings }, FakeLocation(), shell, { locale }, { type }, { available })

    private class FakeLocale : DeviceLocaleAccess {
        var current = DeviceLocale("en-US")
        val writes = mutableListOf<Pair<String, DeviceLocale>>()
        override fun read(serial: String) = DeviceLocaleInfo(current, listOf(DeviceLocale("en-US"), DeviceLocale("ar-EG")))
        override fun apply(serial: String, locale: DeviceLocale): DeviceLocale {
            writes += serial to locale
            current = locale
            return locale
        }
    }

    private class FakeLocation : PresetLocationAccess {
        val calls = mutableListOf<Pair<String, DevicePresetTarget>>()
        var lastValue: PresetValue.Location? = null
        override suspend fun support(target: DevicePresetTarget, value: PresetValue.Location) = PresetSupport.Supported()
        override suspend fun capture(target: DevicePresetTarget): PresetRestorePoint {
            calls += "capture" to target
            return PresetPreviousValue(PresetValue.Location(null))
        }
        override suspend fun apply(target: DevicePresetTarget, value: PresetValue.Location) {
            calls += "apply" to target
            lastValue = value
        }
        override suspend fun restore(target: DevicePresetTarget, previous: PresetRestorePoint) { calls += "restore" to target }
    }

    private class FakeShell : CommandExecutor {
        val commands = mutableListOf<Command>()
        val values = mutableMapOf<String, String>()
        var nightMode = "auto"
        var ignoreSettingsWrites = false
        override fun run(command: Command, cancelled: java.util.concurrent.atomic.AtomicBoolean, output: (String) -> Unit): String {
            commands += command
            val args = command.args.drop(3)
            if (args.take(3) == listOf("cmd", "uimode", "night")) {
                if (args.size == 4) nightMode = args[3]
                return "Night mode: $nightMode"
            }
            if (args.take(2) == listOf("am", "get-config")) return "config: en-rUS-notnight-420dpi"
            if (args.firstOrNull() == "pm") return "com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService"
            check(args.firstOrNull() == "settings") { "Unexpected command: $args" }
            val operationIndex = args.indexOfFirst { it in setOf("get", "put", "delete") }
            check(operationIndex >= 0)
            val key = args[operationIndex + 1] + "/" + args[operationIndex + 2]
            return when (args[operationIndex]) {
                "get" -> values[key] ?: "null"
                "put" -> {
                    if (!ignoreSettingsWrites) values[key] = args[operationIndex + 3].removeSurrounding("'")
                    ""
                }
                else -> { if (!ignoreSettingsWrites) values.remove(key); "" }
            }
        }
    }
}
