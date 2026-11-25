package com.robomori

import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals

class WorkspaceLayoutTest {
    @TempDir lateinit var directory: File

    @Test fun `a new install starts with the device list shown, no panel open and default widths`() {
        assertEquals(WorkspaceLayout(), FileWorkspaceLayoutStore(File(directory, "workspace.properties")).load())
    }

    @Test fun `the layout the user left is there next time`() = runBlocking {
        val file = File(directory, "workspace.properties")
        val state = WorkspaceLayoutState(FileWorkspaceLayoutStore(file), this)

        state.showDeviceList(false)
        state.resizeDeviceList(240.dp)
        state.openPanel(InspectorPage.Network)
        state.resizePanel(720.5f.dp)
        val expected = WorkspaceLayout(deviceListShown = false, deviceListWidth = 240.dp, openPanel = InspectorPage.Network, panelWidth = 720.5f.dp)
        withTimeout(5000) { while (FileWorkspaceLayoutStore(file).load() != expected) delay(20) }

        assertEquals(expected, WorkspaceLayoutState(FileWorkspaceLayoutStore(file), this).layout)
        state.openPanel(null)
        withTimeout(5000) { while (FileWorkspaceLayoutStore(file).load().openPanel != null) delay(20) }
    }

    @Test fun `a file from a newer or damaged version falls back to the defaults for what it can't read`() {
        val file = File(directory, "workspace.properties").apply {
            writeText("deviceList.shown=maybe\ndeviceList.width=-3\npanel.open=Profiler\npanel.width=500\n")
        }
        assertEquals(WorkspaceLayout(panelWidth = 500.dp), FileWorkspaceLayoutStore(file).load())
    }
}
