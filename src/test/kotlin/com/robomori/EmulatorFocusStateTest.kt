package com.robomori

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmulatorFocusStateTest {
    private val available = DpSize(1440.dp, 1000.dp)
    private val portrait = DpSize(100.dp, 200.dp)
    private val landscape = DpSize(200.dp, 100.dp)

    @Test fun `rotation preserves the displayed device scale`() {
        val window = focusWindowSize(portrait, DpSize(800.dp, 800.dp))
        val rotated = focusWindowSize(landscape, available, portrait, window)
        assertEquals(window.height.value - 130, rotated.width.value - 24, .001f)
        assertEquals(window.width.value - 24, rotated.height.value - 130, .001f)
    }

    @Test fun `rotation is constrained by the available monitor`() {
        val size = focusWindowSize(landscape, DpSize(600.dp, 400.dp), portrait, DpSize(500.dp, 1000.dp))
        assertTrue(size.width <= 600.dp && size.height <= 400.dp)
    }

    @Test fun `rotation uses the scale after a manual window resize`() {
        val rotated = focusWindowSize(landscape, available, portrait, DpSize(324.dp, 730.dp))
        assertEquals(DpSize(624.dp, 430.dp), rotated)
    }

    @Test fun `folding resizes the compact window for the new device proportions`() {
        val folded = focusWindowSize(portrait, available, DpSize(200.dp, 200.dp), DpSize(624.dp, 730.dp))
        assertEquals(DpSize(324.dp, 730.dp), folded)
    }

    @Test fun `unchanged device dimensions preserve a manual window resize`() {
        val window = WindowState()
        val focus = EmulatorFocusState(window)
        focus.enter(portrait, available, WindowPosition(20.dp, 30.dp))
        window.size = DpSize(500.dp, 900.dp)
        focus.resize(portrait, available)
        assertEquals(DpSize(500.dp, 900.dp), window.size)
    }

    @Test fun `exiting closes focus state`() {
        val focus = EmulatorFocusState(WindowState())
        focus.enter(portrait, available, WindowPosition(20.dp, 30.dp))
        assertTrue(focus.active)
        focus.exit()
        assertFalse(focus.active)
    }

    @Test fun `reentering focus starts from the current device instead of the previous session`() {
        val window = WindowState()
        val focus = EmulatorFocusState(window)
        focus.enter(portrait, available, WindowPosition(20.dp, 30.dp))
        focus.exit()
        focus.enter(landscape, available, WindowPosition(50.dp, 60.dp))
        assertEquals(focusWindowSize(landscape, DpSize(800.dp, 800.dp)), window.size)
        assertEquals(WindowPosition(50.dp, 60.dp), window.position)
    }

    @Test fun `repeated entry does not reset a moved and resized focus window`() {
        val window = WindowState()
        val focus = EmulatorFocusState(window)
        focus.enter(portrait, available, WindowPosition(20.dp, 30.dp))
        window.size = DpSize(500.dp, 900.dp)
        window.position = WindowPosition(100.dp, 110.dp)
        focus.enter(portrait, available, WindowPosition(20.dp, 30.dp))
        assertEquals(DpSize(500.dp, 900.dp), window.size)
        assertEquals(WindowPosition(100.dp, 110.dp), window.position)
    }
}
