package com.robomori

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.robomori.components.*
import com.robomori.iconography.AdjustmentsHorizontal
import com.robomori.iconography.Icons
import com.robomori.theme.RobomoriTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart

@Composable
internal fun DevicePresetControls(workspace: DevicePresetWorkspace, target: DevicePresetTarget?, controls: DeviceControlsState, type: DeviceType) {
    val scope = rememberCoroutineScope()
    var creating by remember(target) { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val busy = target?.let { workspace.busy[it.identity] } == true
    val values = workspace.currentValues(target, controls, type)
    val enabled = target != null && values != null && !saving && !busy
    val selected = target?.let { workspace.selectedFiles[it.identity] }
    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focused) { if (focused) workspace.refreshFiles() }

    Card(contentPadding = PaddingValues(horizontal = 12.dp), modifier = Modifier.testTag("PresetsCard")) {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AdjustmentsHorizontal, tint = RobomoriTheme.colors.textSecondary)
            Label("Preset")
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                Select(selected, workspace.presetFiles,
                    onChange = { file ->
                        if (target != null && file != null && values != null) scope.launch(start = CoroutineStart.UNDISPATCHED) {
                            try { workspace.loadFromFile(target, file, controls, type) }
                            catch (failure: Exception) {
                                if (failure is CancellationException) throw failure
                                workspace.fileFailed(false, failure)
                            }
                        }
                    }, label = { it?.nameWithoutExtension ?: "Current settings" }, tag = "ChoosePreset",
                    style = SelectStyle.Ghost, optionsEnabled = enabled, onOpen = workspace::refreshFiles,
                    actions = buildList {
                        if (selected != null) add(SelectAction("Update current preset", {
                            if (values != null && workspace.canSave()) {
                                saving = true
                                scope.launch {
                                    try { workspace.update(target, values) }
                                    catch (failure: Exception) {
                                        if (failure is CancellationException) throw failure
                                        workspace.fileFailed(true, failure)
                                    } finally { saving = false }
                                }
                            }
                        }, enabled))
                        add(SelectAction("Save current as preset…", { if (workspace.canSave()) creating = true }, enabled))
                        add(SelectAction("Show presets in ${HostPlatform().fileManagerName}…", workspace::showInFolder))
                    })
            }
        }
    }

    AppDialog(creating.takeIf { it }, onClose = { if (!saving) creating = false }) {
        val name = rememberTextFieldState()
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocus() }
        DialogScaffold("Save preset", "CreatePresetDialog", footer = {
            Spacer(Modifier.weight(1f))
            Button(onClick = { creating = false }, enabled = !saving, style = ButtonStyle.Secondary, tag = "CancelCreatePreset") { Label("Cancel") }
            Button(onClick = {
                if (target != null && values != null) {
                    saving = true
                    scope.launch {
                        try { workspace.create(target, name.text.toString(), values); creating = false }
                        catch (failure: Exception) {
                            if (failure is CancellationException) throw failure
                            workspace.fileFailed(true, failure)
                        } finally { saving = false }
                    }
                }
            }, style = ButtonStyle.Primary, tag = "SavePreset", enabled = !saving && name.text.isNotBlank() && name.text.toString().trim().length <= 80) { Label("Save") }
        }) {
            Label("Name")
            Field(name, "PresetName", Modifier.fillMaxWidth().focusRequester(focus))
        }
    }
}
