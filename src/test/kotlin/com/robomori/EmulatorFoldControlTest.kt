package com.robomori

import org.junit.jupiter.api.Test
import kotlin.test.*

class EmulatorFoldControlTest {
    @Test fun `fold dismisses a forced keyguard after it appears`() {
        val commands = mutableListOf<List<String>>()
        val keyguard = ArrayDeque(listOf("false", "false", "true"))
        EmulatorFoldControl(adb = { args ->
            commands += args
            when (args) {
                disabled -> "true\n"
                policy -> "KeyguardServiceDelegate\n  showing=${keyguard.removeFirst()}\n"
                else -> ""
            }
        }, pause = {}).fold(true)

        assertEquals(listOf(disabled, policy, fold, policy, policy, dismiss), commands)
    }

    @Test fun `fold preserves a lock screen that was already showing`() {
        val commands = mutableListOf<List<String>>()
        EmulatorFoldControl(adb = { args ->
            commands += args
            if (args == disabled) "true" else "  showing=true\n"
        }, pause = {}).fold(true)

        assertEquals(listOf(disabled, policy, fold), commands)
    }

    @Test fun `fold preserves an enabled lock screen`() {
        val commands = mutableListOf<List<String>>()
        EmulatorFoldControl(adb = { args -> commands += args; "false" }, pause = {}).fold(true)

        assertEquals(listOf(disabled, fold), commands)
    }

    @Test fun `fold does not dismiss when keyguard state is unknown`() {
        val commands = mutableListOf<List<String>>()
        EmulatorFoldControl(adb = { args ->
            commands += args
            if (args == disabled) "true" else "Unknown output"
        }, pause = {}).fold(true)

        assertEquals(listOf(disabled, policy, fold), commands)
    }

    @Test fun `fold stops waiting when Android does not show a keyguard`() {
        val commands = mutableListOf<List<String>>()
        var pauses = 0
        EmulatorFoldControl(adb = { args ->
            commands += args
            if (args == disabled) "true" else "  showing=false\n"
        }, pause = { pauses++ }).fold(true)

        assertEquals(20, pauses)
        assertFalse(dismiss in commands)
    }

    @Test fun `unfold leaves keyguard alone`() {
        val commands = mutableListOf<List<String>>()
        EmulatorFoldControl(adb = { args -> commands += args; "" }, pause = {}).fold(false)

        assertEquals(listOf(listOf("emu", "unfold")), commands)
    }

    private val disabled = listOf("shell", "locksettings", "get-disabled")
    private val policy = listOf("shell", "dumpsys", "window", "policy")
    private val fold = listOf("emu", "fold")
    private val dismiss = listOf("shell", "wm", "dismiss-keyguard")
}
