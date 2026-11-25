package com.robomori

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.Properties

/**
 * How the window was laid out, kept between runs: whether the device list shows and how wide it is, which panel is open
 * on the right, if any, and how wide the panels are. Null widths are the defaults.
 */
internal data class WorkspaceLayout(
    val deviceListShown: Boolean = true,
    val deviceListWidth: Dp? = null,
    val openPanel: InspectorPage? = null,
    val panelWidth: Dp? = null,
)

internal interface WorkspaceLayoutStore {
    fun load(): WorkspaceLayout
    fun save(layout: WorkspaceLayout)
}

internal class FileWorkspaceLayoutStore(
    private val file: File = File(HostPlatform().configDirectory, "workspace.properties"),
) : WorkspaceLayoutStore {
    override fun load(): WorkspaceLayout {
        val properties = Properties().apply { if (file.isFile) runCatching { file.inputStream().use(::load) } }
        fun width(name: String) = properties.getProperty(name)?.toFloatOrNull()?.takeIf { it > 0 }?.dp
        return WorkspaceLayout(
            deviceListShown = properties.getProperty("deviceList.shown")?.toBooleanStrictOrNull() ?: true,
            deviceListWidth = width("deviceList.width"),
            openPanel = properties.getProperty("panel.open")?.let { name -> InspectorPage.entries.firstOrNull { it.name == name } },
            panelWidth = width("panel.width"),
        )
    }

    override fun save(layout: WorkspaceLayout) {
        val properties = Properties().apply {
            setProperty("deviceList.shown", layout.deviceListShown.toString())
            layout.deviceListWidth?.let { setProperty("deviceList.width", it.value.toString()) }
            layout.openPanel?.let { setProperty("panel.open", it.name) }
            layout.panelWidth?.let { setProperty("panel.width", it.value.toString()) }
        }
        file.parentFile.mkdirs()
        val temporary = File.createTempFile("workspace-", ".tmp", file.parentFile)
        try {
            temporary.outputStream().use { properties.store(it, null) }
            Files.move(temporary.toPath(), file.toPath(), REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
}

/**
 * The window's layout as the user left it, read once at startup and saved shortly after each change, so dragging a
 * panel's edge writes the file once it stops rather than on every step.
 */
internal class WorkspaceLayoutState(private val store: WorkspaceLayoutStore, private val scope: CoroutineScope) {
    var layout by mutableStateOf(runCatching { store.load() }.getOrDefault(WorkspaceLayout())); private set
    private var saving: Job? = null

    fun showDeviceList(shown: Boolean) = update(layout.copy(deviceListShown = shown))
    fun resizeDeviceList(width: Dp) = update(layout.copy(deviceListWidth = width))
    fun openPanel(panel: InspectorPage?) = update(layout.copy(openPanel = panel))
    fun resizePanel(width: Dp) = update(layout.copy(panelWidth = width))

    private fun update(new: WorkspaceLayout) {
        if (new == layout) return
        layout = new
        saving?.cancel()
        saving = scope.launch(Dispatchers.IO) {
            delay(SaveDelayMillis)
            runCatching { store.save(new) }
        }
    }

    private companion object {
        const val SaveDelayMillis = 300L
    }
}
