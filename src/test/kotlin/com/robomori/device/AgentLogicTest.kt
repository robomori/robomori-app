package com.robomori.device

import android.graphics.Rect
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The device helper's logic that doesn't need a device: refs, diffs, text, logs and crashes. */
class AgentLogicTest {
    private fun button(label: String, node: String, top: Int, key: String = "button\u0001$label\u0001\u00010") =
        Agent.Element("button", label, "", Rect(0, top, 1080, top + 100), false, key).also { it.node = node }

    private fun text(label: String, top: Int) = Agent.Element(null, label, "", Rect(0, top, 1080, top + 50), false, label)

    private fun screen(vararg elements: Agent.Element) =
        Agent.Screen("com.example 1080x2424", elements.toMutableList(), Rect(0, 0, 1080, 2424), false, false)

    private fun refOf(screen: Agent.Screen, node: String) = screen.elements.first { it.node == node }.ref

    @Test fun `a button whose text changes doesn't hand its ref to the next button that had the same text`() {
        val agent = Agent("test")
        val before = screen(button("Select ingredients", "w/1", 100, "button\u0001Select ingredients\u0001\u00010"),
            button("Select ingredients", "w/2", 300, "button\u0001Select ingredients\u0001\u00011"))
        agent.number(before, true)
        val proteins = refOf(before, "w/1")
        val carbs = refOf(before, "w/2")

        // The first one now says more, which makes the second the first with the old text.
        val after = screen(button("Select ingredients Add to Box", "w/1", 100, "button\u0001Select ingredients Add to Box\u0001\u00010"),
            button("Select ingredients", "w/2", 300, "button\u0001Select ingredients\u0001\u00010"))
        agent.number(after, true)
        assertEquals(carbs, refOf(after, "w/2"), "The second keeps its own ref")
        assertNotEquals(proteins, refOf(after, "w/2"))
        assertNotEquals(carbs, refOf(after, "w/1"))
    }

    @Test fun `a list row reused for the next item gives the item's ref, not the row's`() {
        val agent = Agent("test")
        val before = screen(button("Delete Rice", "w/1", 100), button("Delete Feta", "w/2", 200), button("Delete Oats", "w/3", 300))
        agent.number(before, true)
        val feta = refOf(before, "w/2")
        val oats = refOf(before, "w/3")

        // Rice deleted: Compose reuses the first two rows' nodes for Feta and Oats.
        val after = screen(button("Delete Feta", "w/1", 100), button("Delete Oats", "w/2", 200))
        agent.number(after, true)
        assertEquals(feta, refOf(after, "w/1"))
        assertEquals(oats, refOf(after, "w/2"))
    }

    @Test fun `only what changed shows, not what moved a few pixels`() {
        val agent = Agent("test")
        val before = screen(button("Dark theme", "w/1", 1391), button("Brightness", "w/2", 500))
        agent.number(before, true)
        val after = screen(button("Dark theme", "w/1", 1396).also { it.node = "w/1" }, button("Brightness", "w/2", 505))
        after.elements[0] = Agent.Element("switch", "Dark theme", " on", Rect(0, 1396, 1080, 1496), false,
            "button\u0001Dark theme\u0001\u00010").also { it.node = "w/1" }
        agent.number(after, true)
        val changes = Agent.changes(before, after)
        assertContains(changes, "Dark theme")
        assertFalse("Brightness" in changes, "Moved 5 pixels: not a change")
    }

    @Test fun `a field that moved a little shows when what it holds changed`() {
        val agent = Agent("test")
        fun field(value: String, top: Int) = Agent.Element("field", "Phone Number", "", Rect(0, top, 1080, top + 100), false, "field")
            .also { it.node = "w/1"; it.value = value }
        val before = screen(field("75", 1114))
        agent.number(before, true)
        val after = screen(field("751", 1110))
        agent.number(after, true)
        assertEquals("+ @1 field \"Phone Number\" = \"751\" (540,1160)", Agent.changes(before, after))
    }

    @Test fun `a field shows its name and what it holds, and a heading shows as one`() {
        val field = Agent.Element("field", "Quantity", "", Rect(0, 0, 100, 100), false, "field").also { it.value = "4"; it.ref = 2 }
        assertEquals("@2 field \"Quantity\" = \"4\" (50,50)", field.line())
        val heading = text("Units", 0).also { it.heading = true }
        assertEquals("# Units", heading.line())
    }

    @Test fun `the scroll hint looks down first`() {
        val up = Agent.Element("list", "", " more ↑", Rect(0, 0, 1080, 300), true, "a")
        val down = Agent.Element("list", "", " more ↓", Rect(0, 300, 1080, 2000), true, "b")
        assertContains(Agent.scrollHint(screen(up, down), "About"), "scroll down --until \"About\"")
        assertEquals("", Agent.scrollHint(screen(text("Nothing scrolls", 0)), "About"))
        val withKeyboard = Agent.Screen("com.example 1080x2424 keyboard", mutableListOf(down), Rect(0, 0, 1080, 2424), false, false)
        assertContains(Agent.scrollHint(withKeyboard, "About"), "The keyboard is up and may cover it")
    }

    @Test fun `radio buttons and switches are told apart by the state they announce, in any language`() {
        assertEquals("radio", Agent.checkableRole("Selected"))
        assertEquals("radio", Agent.checkableRole("Nicht ausgewählt"))
        assertEquals("switch", Agent.checkableRole("On"))
        assertEquals("switch", Agent.checkableRole("An"))
        assertEquals("checkbox", Agent.checkableRole(""))
        assertEquals("checkbox", Agent.checkableRole("Checked"))
    }

    private val crashLog = """
        --------- beginning of crash
        E/AndroidRuntime( 9141): FATAL EXCEPTION: main
        E/AndroidRuntime( 9141): Process: com.example.app, PID: 9141
        E/AndroidRuntime( 9141): java.lang.ArithmeticException: divide by zero
        E/AndroidRuntime( 9141): 	at com.example.app.ui.StatsScreenKt.StatsScreen(StatsScreen.kt:18)
        E/AndroidRuntime( 9141): 	at androidx.compose.runtime.Recomposer.run(Recomposer.kt:100)
    """.trimIndent()

    @Test fun `a crash names the exception and the line in the app's own code`() {
        val summary = Agent.crashSummary(crashLog)
        assertContains(summary, "crash com.example.app: java.lang.ArithmeticException: divide by zero")
        assertContains(summary, "at com.example.app.ui.StatsScreenKt.StatsScreen(StatsScreen.kt:18)")
        assertContains(summary, "robomori app logs com.example.app")
        assertEquals("", Agent.crashSummary("E/Other( 1): nothing crashed"))
    }

    @Test fun `logs show each stack trace once, the app's own frames, and no emulator noise`() {
        val log = """
            W/HWUI    ( 9141): Failed to choose config with EGL_SWAP_BEHAVIOR_PRESERVED
            W/robomori.app( 9141): Accessing hidden method Landroid/view/View;->getViewRootImpl() (greylist, reflection, allowed)
            E/ComposeInternal( 9141): java.lang.ArithmeticException: divide by zero
            E/ComposeInternal( 9141): 	at com.example.app.ui.StatsScreenKt.StatsScreen(StatsScreen.kt:18)
            E/ComposeInternal( 9141): 	at androidx.compose.runtime.A.b(A.kt:1)
            E/ComposeInternal( 9141): 	at androidx.compose.runtime.C.d(C.kt:2)
        """.trimIndent() + "\n" + crashLog
        val condensed = Agent.condensed(log, "com.example.app", false)
        assertFalse("HWUI" in condensed)
        assertFalse("Accessing hidden" in condensed)
        assertEquals(1, Regex("divide by zero").findAll(condensed).count(), condensed)
        assertContains(condensed, "… 2 framework frames")
        assertTrue(Agent.condensed(log, "com.example.app", true).contains("HWUI"), "--all shows everything")
        assertEquals("Nothing new from com.example.app at warning level or above.", Agent.condensed("", "com.example.app", false))
        val repeated = (List(500) { "W/Choreographer( 99): Skipped frames" } + "E/App( 99): boom").joinToString("\n")
        assertEquals("W/Choreographer( 99): Skipped frames\n    … 499 more times\nE/App( 99): boom",
            Agent.condensed(repeated, "com.example.app", false), "A line logged again and again shows once")
    }

    @Test fun `a JavaScript stack trace keeps the frames nearest the error`() {
        val log = (listOf("E/AndroidRuntime( 1): com.facebook.react.common.JavascriptException: Error: boom, stack:") +
            (1..10).map { "E/AndroidRuntime( 1): frame$it@1:${it * 100}" }).joinToString("\n")
        val condensed = Agent.condensed(log, "com.example.app", false)
        assertContains(condensed, "frame3@1:300")
        assertFalse("frame4@" in condensed)
        assertContains(condensed, "… 7 framework frames")
    }

    @Test fun `on older Android, an app's lines are picked by its processes`() {
        val log = "E/AndroidRuntime( 9141): boom\nW/Other( 77): not ours\nI/flutter ( 9141): ours too\n"
        assertEquals("E/AndroidRuntime( 9141): boom\nI/flutter ( 9141): ours too\n", Agent.onlyProcesses(log, setOf("9141")))
    }
}
