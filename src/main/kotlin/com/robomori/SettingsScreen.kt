package com.robomori

import com.robomori.theme.RobomoriTheme
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import com.robomori.theme.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.robomori.components.*
import com.robomori.iconography.*

sealed class SettingsSection(val title: String, val id: String) {
    data object License : SettingsSection("Your License", "License")
    data object Images : SettingsSection("Platforms", "Images")
    data object Storage : SettingsSection("SDK Locations", "Storage")
    data object Media : SettingsSection("Camera & Microphone", "Media")
    data object Graphics : SettingsSection("Graphics", "Graphics")
    data object Network : SettingsSection("Network", "Network")
    data object Updates : SettingsSection("Updates", "Updates")
    data object CommandLine : SettingsSection("CLI & Agents", "CommandLine")
    /** Development runs only: switches for seeing the app as people do. */
    data object Debug : SettingsSection("Debug", "Debug")
}

@Composable
fun SettingsScreen(state: AppState, topInset: Dp) {
    val pages = rememberSaveableStateHolder()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val modifier = Modifier.testTag("SettingsScreen").onKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
            state.closeSettings()
            true
        } else false
    }.focusRequester(focus).focusable()
    AppScaffold(topInset, modifier, sidebarPinned = true, contentTopInset = 0.dp, sidebar = { sidebarInset ->
        Column(Modifier.fillMaxSize().padding(top = sidebarInset).padding(horizontal = 8.dp, vertical = 12.dp)) {
            SidebarListItem(selected = false, height = 36.dp,
                modifier = Modifier.fillMaxWidth().testTag("BackToEmulators")
                    .clickable(role = Role.Button) { state.closeSettings() }) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.ArrowLeft, tint = RobomoriTheme.colors.textSecondary, modifier = Modifier.size(16.dp))
                    Label("Back to app", color = RobomoriTheme.colors.textSecondary)
                }
            }
            Spacer(Modifier.height(16.dp))
            (listOf(SettingsSection.License, SettingsSection.Storage, SettingsSection.Images, SettingsSection.CommandLine, SettingsSection.Media, SettingsSection.Graphics, SettingsSection.Network,
                SettingsSection.Updates) + listOfNotNull(SettingsSection.Debug.takeIf { developmentBuild }))
                .forEach { section ->
                    val icon = when (section) {
                        SettingsSection.License -> Icons.Key
                        SettingsSection.Images -> Icons.Android
                        SettingsSection.Storage -> Icons.Cog6Tooth
                        SettingsSection.Media -> Icons.Camera
                        SettingsSection.Graphics -> Icons.Sparkles
                        SettingsSection.Network -> Icons.ArrowRightLeft
                        SettingsSection.Updates -> Icons.ArrowDownTray
                        SettingsSection.CommandLine -> Icons.AppWindow
                        SettingsSection.Debug -> Icons.Bug
                    }
                    SidebarListItem(selected = state.settingsSection == section, height = 32.dp,
                        modifier = Modifier.fillMaxWidth().testTag("SettingsNav-${section.id}")
                            .semantics { selected = state.settingsSection == section }
                            .clickable(role = Role.Tab) { state.openSettings(section) }) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(icon, modifier = Modifier.size(16.dp))
                            Label(section.title)
                            if (section == SettingsSection.Images && state.downloads.activeCount > 0) {
                                Spacer(Modifier.weight(1f))
                                Spinner(Modifier.size(16.dp).testTag("AndroidImagesDownloadSpinner").semantics {
                                    contentDescription = "Downloading Android images"
                                    progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                                })
                            }
                        }
                    }
                }
        }
    }) {
        pages.SaveableStateProvider(state.settingsSection.id) {
            when (state.settingsSection) {
                SettingsSection.License -> LicensePage(state.license, state::manageLicense) { state.purchaseLicense(BuySource.Settings) }
                SettingsSection.Images -> AndroidImagesPage(state)
                SettingsSection.Storage -> SdkLocationsPage(state)
                SettingsSection.Media -> EmulatorMediaPage(state)
                SettingsSection.Graphics -> GraphicsPage(state)
                SettingsSection.Network -> NetworkPage(state.localhostPorts)
                SettingsSection.Updates -> UpdatesPage(state)
                SettingsSection.CommandLine -> CommandLinePage(state.commandLine, state.agentSkill)
                SettingsSection.Debug -> DebugPage(state)
            }
        }
    }
}

@Composable
fun SettingsPageScaffold(title: String, tag: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().testTag(tag)) {
        Box(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 24.dp), contentAlignment = Alignment.CenterStart) {
            com.composeunstyled.Text(title, style = com.robomori.theme.RobomoriTheme.typography.heading,
                modifier = Modifier.testTag("SettingsPageTitle"))
        }
        Rule()
        Box(Modifier.weight(1f)) {
            val scroll = rememberScrollState()
            // The scroll area spans the page so the wheel works anywhere; only the content is capped.
            // Cards sit 16dp further out than the title, so the content inside them lines up with it. Anything outside a
            // card takes Modifier.settingsInset() to line up too.
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 24.dp - SettingsCardPadding, vertical = 24.dp)) {
                Column(Modifier.widthIn(max = 800.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(18.dp), content = content)
            }
            VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

/** A Settings card's title, in macOS's title 3 style. */
@Composable
internal fun SettingsCardTitle(text: String) {
    com.composeunstyled.Text(text, style = com.robomori.theme.RobomoriTheme.typography.title3)
}

/** Gray explanatory text on a Settings page. Smaller than body text, like the help text in macOS System Settings. */
@Composable
internal fun SettingsNote(text: String, modifier: Modifier = Modifier) {
    com.composeunstyled.Text(text, modifier, color = com.robomori.theme.RobomoriTheme.colors.textSecondary,
        style = com.robomori.theme.RobomoriTheme.typography.small)
}

/** How far a Settings card's content sits inside its edge. */
internal val SettingsCardPadding = 16.dp

/** Lines up content outside a card with the page title and the content inside cards. */
internal fun Modifier.settingsInset() = padding(horizontal = SettingsCardPadding)
