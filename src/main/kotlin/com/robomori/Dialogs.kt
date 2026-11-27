@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package com.robomori

import robomori.generated.resources.Res
import robomori.generated.resources.agent_claude
import robomori.generated.resources.agent_codex
import robomori.generated.resources.agent_cursor
import robomori.generated.resources.agent_gemini
import org.jetbrains.compose.resources.painterResource
import com.robomori.components.*
import com.robomori.theme.verticalScroll
import com.robomori.iconography.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.*
import com.robomori.theme.RobomoriTheme
import java.io.File
import androidx.compose.ui.*
import androidx.compose.ui.awt.LocalAwtWindow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.*
import com.composeunstyled.Text
import androidx.compose.ui.text.withStyle
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitDialogParent
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.openDirectoryPicker
import io.github.vinceglb.filekit.dialogs.openFilePicker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable fun SdkLocationsPage(state:AppState) {
    val sdkField = rememberTextFieldState(state.settings.sdk)
    val sdk = sdkField.text.toString()
    val avdField = rememberTextFieldState(state.settings.avds)
    val avds = avdField.text.toString()
    val adbField = rememberTextFieldState(state.settings.adb)
    val emulatorField = rememberTextFieldState(state.settings.emulator)
    val sdkmanagerField = rememberTextFieldState(state.settings.sdkmanager)
    val avdmanagerField = rememberTextFieldState(state.settings.avdmanager)
    val draft = AppSettings(sdk, avds, adbField.text.toString(), emulatorField.text.toString(),
        sdkmanagerField.text.toString(), avdmanagerField.text.toString())
    var failure by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }
    LaunchedEffect(draft) { saved = false; failure = null }
    val defaults = Toolchain(expandPath(sdk))
    SettingsPageScaffold("SDK Locations","SdkLocationsPage") {
        Column(Modifier.settingsInset(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            if (state.downloads.activeCount > 0) SettingsNote("Finish or cancel Android downloads before saving SDK locations.")
            LocationField("Android SDK folder", sdkField, "SdkPath",
                onFailure = { failure = it })
            SettingsNote("Choose each tool’s executable file. Leave a tool blank to use its SDK location.")
            listOf(Triple("adb", adbField, defaults.adb), Triple("emulator", emulatorField, defaults.emulator),
                Triple("sdkmanager", sdkmanagerField, defaults.sdkmanager), Triple("avdmanager", avdmanagerField, defaults.avdmanager))
                .forEach { (name, field, fallback) ->
                    LocationField(name, field, "${name}Path", placeholder = fallback.path, selectFile = true,
                        onFailure = { failure = it })
                }
            LocationField("Emulator storage folder", avdField, "AvdPath", hint = "Changing this folder leaves existing emulators in place.",
                onFailure = { failure = it })
            failure?.let{Label(it,color=RobomoriTheme.colors.danger,modifier=Modifier.testTag("SettingsError"))}
            if (saved) SettingsNote("Settings saved.")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = {
                    val result = state.saveSettings(draft)
                    failure = result.errorMessage
                    saved = result == SettingsSaveResult.Saved
                }, style = ButtonStyle.Primary, tag = "SaveSettings", enabled = !state.busy && state.downloads.activeCount == 0) { Label("Save") }
                Button(onClick = {
                    sdkField.setTextAndPlaceCursorAtEnd(state.settings.sdk)
                    avdField.setTextAndPlaceCursorAtEnd(state.settings.avds)
                    adbField.setTextAndPlaceCursorAtEnd(state.settings.adb)
                    emulatorField.setTextAndPlaceCursorAtEnd(state.settings.emulator)
                    sdkmanagerField.setTextAndPlaceCursorAtEnd(state.settings.sdkmanager)
                    avdmanagerField.setTextAndPlaceCursorAtEnd(state.settings.avdmanager)
                    failure = null; saved = false
                }, style = ButtonStyle.Secondary, tag = "ResetSettings") { Label("Discard changes") }
            }
        }
    }
}

@Composable private fun LocationField(label: String, field: androidx.compose.foundation.text.input.TextFieldState,
    tag: String, hint: String? = null, placeholder: String = "", selectFile: Boolean = false, onFailure: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val window = LocalAwtWindow.current
    var picking by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Label(label)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            com.robomori.components.TextField(state = field, modifier = Modifier.weight(1f).testTag(tag),
                placeholder = { Text(placeholder) })
            Button(onClick = {
                scope.launch {
                    picking = true
                    try {
                        val path = expandPath(field.text.toString().ifBlank { placeholder })
                        val folder = generateSequence(path) { it.parentFile }.firstOrNull { it.isDirectory }
                        val settings = FileKitDialogSettings(title = if (selectFile) "Choose $label" else "Choose folder",
                            parent = window?.let { FileKitDialogParent.awt(it) })
                        val directory = folder?.let(::PlatformFile)
                        val selected = if (selectFile) FileKit.openFilePicker(directory = directory, dialogSettings = settings)
                            else FileKit.openDirectoryPicker(directory = directory, dialogSettings = settings)
                        selected?.let { field.setTextAndPlaceCursorAtEnd(it.file.absolutePath) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        onFailure("Could not open the picker. Try again or enter the path directly.")
                    } finally {
                        picking = false
                    }
                }
            }, style = ButtonStyle.Secondary, tag = "Browse$tag", enabled = !picking) { Label("Browse…") }
        }
        hint?.let { SettingsNote(it) }
    }
}

@Composable fun CreateDeviceDialog(state: AppState) {
    val profiles = remember(state.createType) { HardwareProfiles.forType(state.createType) }
    val firstProfile = profiles.firstOrNull()
    if (firstProfile == null) {
        AppDialogPanel("Create emulator", "CreateDeviceDialog", Modifier.width(510.dp)) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Label("This emulator profile is unavailable. Restart $APP_NAME and try again.")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(onClick = { state.showCreate = false }, style = ButtonStyle.Secondary, tag = "CloseCreate") { Label("Close") }
                }
            }
        }
        return
    }
    var profile by remember(profiles) { mutableStateOf(firstProfile) }
    val title = "Create ${when (state.createType) { DeviceType.WEAR, DeviceType.TV -> state.createType.label; else -> state.createType.label.lowercase() }} emulator"
    var playStore by remember { mutableStateOf(false) }
    val images = remember(state.creationImages, state.createType, state.deletingImagePaths, playStore) {
        installedCreationImages(state.creationImages, type = state.createType, playStore = playStore).filter { it.path !in state.deletingImagePaths }
    }
    // Kept by version, because turning the Play Store on or off swaps each version's image.
    var selectedApi by remember { mutableStateOf<String?>(null) }
    val release = images.firstOrNull { it.api == selectedApi } ?: images.firstOrNull()
    val image = release?.let { if (state.createType.offersPlayStore) phoneCreationImage(it, playStore, state.images) else it }
    val nameField = rememberTextFieldState()
    val suggestedName = suggestedDeviceName(profile.name, state.visibleDevices.map(Device::displayName))
    val name = nameField.text.toString().trim().ifBlank { suggestedName }
    val nameIssue = creationNameError(name, state.visibleDevices.map(Device::displayName))
    val spec = image?.let { profile.createSpec(name, it, state.visibleDevices.map(Device::name)) }
    var review by remember { mutableStateOf<LoadState<StoragePlan>>(LoadState.Loading) }
    val plan = (review as? LoadState.Ready)?.value
    val planError = (review as? LoadState.Failed)?.cause?.let { it.message ?: "Could not check available storage." }.orEmpty()
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(profile.id, image?.path, image?.installed, state.settings) {
        review = LoadState.Loading
        if (spec != null) review = state.reviewStorage(spec)
    }
    fun close() { state.showCreate = false }
    fun manageImages() {
        close()
        state.openSettings(SettingsSection.Images)
    }
    AppDialogPanel(title, "CreateDeviceDialog", Modifier.width(510.dp).heightIn(max = 650.dp)) {
        LaunchedEffect(Unit) { focus.requestFocus() }
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 14.dp, top = 16.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Label(title)
                Spacer(Modifier.weight(1f))
                Button(onClick = { close() }, style = ButtonStyle.Ghost, size = ButtonSize.Icon, contentDescription = "Close create emulator", tag = "CloseCreate") { Icon(Icons.XMark) }
            }
            Rule()
            Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Label("Name", color = RobomoriTheme.colors.textSecondary)
                    com.robomori.components.TextField(
                        state = nameField,
                        modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("DeviceName"),
                        placeholder = { Text(suggestedName) },
                    )
                    if (nameIssue != null) Label(nameIssue.message, color = RobomoriTheme.colors.danger)
                }
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Label("Device", color = RobomoriTheme.colors.textSecondary)
                    Select(profile, profiles, { profile = it }, { it.name }, "DeviceProfile", Modifier.fillMaxWidth())
                }
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Label(when (state.createType) {
                        DeviceType.WEAR -> "Wear OS version"
                        DeviceType.TV -> "Android TV version"
                        else -> "Android version"
                    },
                        color = RobomoriTheme.colors.textSecondary)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (release == null) {
                            Button(onClick = ::manageImages, style = ButtonStyle.Outlined,
                                modifier = Modifier.weight(1f).testTag("SeeAvailableImages")) {
                                Text("See available images")
                            }
                        } else {
                            Select(release, images, { selectedApi = it.api },
                                { "${it.platformTitle} · API ${creationApiLabel(it)}" },
                                "AndroidVersion", Modifier.weight(1f))
                            // With nothing installed, "See available images" already opens the same page.
                            Button(onClick = ::manageImages, style = ButtonStyle.Outlined, size = ButtonSize.Icon, contentDescription = "Manage images", tag = "ManageAndroidImages") { Icon(Icons.Cog6Tooth) }
                        }
                    }
                    when {
                        state.creationImagesLoading && images.isEmpty() -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            com.robomori.components.Spinner(Modifier.size(16.dp))
                            Label("Finding installed Android versions…", color = RobomoriTheme.colors.textSecondary)
                        }
                        state.creationImagesError.isNotBlank() -> {
                            Label(state.creationImagesError, color = RobomoriTheme.colors.danger)
                            Button(onClick = { state.resetCreate() }, style = ButtonStyle.Secondary, tag = "RetryInstalledImages") { Label("Try again") }
                        }
                    }
                }
                if (state.createType.offersPlayStore) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        CheckboxRow("Play Store", playStore, { playStore = it }, tag = "CreatePlayStore")
                        Label("With the Play Store, the Network tab can only read your own debug apps.", color = RobomoriTheme.colors.textSecondary)
                    }
                }
                if (image != null) {
                    when {
                        planError.isNotBlank() -> {
                            Label(planError, color = RobomoriTheme.colors.danger)
                        }
                        plan?.canCreate == false -> {
                            Label("Not enough storage. Free space or change the location in Settings.", color = RobomoriTheme.colors.danger)
                        }
                    }
                }
            }
            Rule()
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                Button(onClick = { close() }, style = ButtonStyle.Secondary, tag = "CancelCreate") { Label("Cancel") }
                Button(onClick = {
                    spec?.let(state::create)
                }, style = ButtonStyle.Primary, tag = "ConfirmCreate", enabled = !state.busy && !state.creationImagesLoading && state.creationImagesError.isBlank() && spec != null && nameIssue == null && plan?.canCreate == true && planError.isBlank()) { Label("Create") }
            }
        }
    }
}

@Composable fun RenameDeviceDialog(state:AppState,device:Device) {
    val nameField = rememberTextFieldState(device.name)
    val name = nameField.text.toString().trim()
    val issue = deviceNameError(name, state.devices.filter { it.name != device.name }.map { it.name })
    var failure by remember { mutableStateOf<String?>(null) }
    val running = device.name in state.running || device.name in state.starting
    DialogScaffold("Rename emulator","RenameDeviceDialog",modifier=Modifier.heightIn(max=350.dp),footer={
        Spacer(Modifier.weight(1f));Button(onClick = {state.renameDevice=null}, style = ButtonStyle.Secondary, tag = "Cancel", enabled = !state.busy) { Label("Cancel") }
        Button(onClick = {
            failure=null
            state.rename(device,nameField.text.toString()) { error -> if(error==null)state.renameDevice=null else failure=error.message ?: "The operation could not be completed." }
        }, style = ButtonStyle.Primary, tag = "ConfirmRename", enabled = !state.busy && device.name !in state.deviceOperations && issue == null && name != device.name && !running) { Label("Rename") }
    }) {
        Label("Give ${device.displayName} a new name.",color=RobomoriTheme.colors.textSecondary)
        Field(nameField,"RenameName",Modifier.fillMaxWidth())
        val hint = when {
            state.busy -> "Renaming emulator… This dialog will close when finished."
            device.name in state.deviceOperations -> state.deviceOperations.getValue(device.name).label
            running -> "Stop this emulator before renaming it."
            issue != null -> issue.message
            name == device.name -> "Enter a different name to rename this emulator."
            else -> "Use letters, numbers, dots, underscores or hyphens."
        }
        Label(hint,color=if(running||issue!=null) RobomoriTheme.colors.danger else RobomoriTheme.colors.textSecondary)
        failure?.let { Label(it,color=RobomoriTheme.colors.danger) }
    }
}
@Composable internal fun DemoDialog(state: AppState) {
    DialogScaffold("You are using a demo version of $APP_NAME", "DemoDialog",
        modifier = Modifier.sizeIn(maxWidth = 460.dp, maxHeight = 300.dp), scrollableContent = false,
        footer = {
            Spacer(Modifier.weight(1f))
            Button(style = ButtonStyle.Outlined, onClick = {
                state.showDemoDialog = false
                state.openSettings(SettingsSection.License)
            }, modifier = Modifier.testTag("RestoreDemoLicense")) { Text("Restore License") }
            Button(onClick = {
                state.showDemoDialog = false
                state.purchaseLicense(BuySource.Upsell)
            }, style = ButtonStyle.Primary, tag = "BuyDemoLicense") { Label("Buy License") }
        }) {
        Label(UpsellPitch)
        Label(UpsellGuarantee)
    }
}

/** The upsell's pitch and guarantee, the same wherever the app asks for a license. */
internal const val UpsellPitch = "Please consider buying a license to unlock all features."
internal const val UpsellGuarantee = "30-day money back guarantee. No questions asked."

@Composable fun LicensePage(license: AppLicense, onManage: () -> Unit, onPurchase: () -> Unit) {
    var confirmingDeactivate by remember { mutableStateOf(false) }
    LaunchedEffect(license.receipt) { confirmingDeactivate = false }
    SettingsPageScaffold("Your License", "LicensePage") {
        if (license.licensed) {
            Card(contentPadding = PaddingValues(16.dp)) {
                StatusHeader(Icons.Check, RobomoriTheme.colors.success, "$APP_NAME is licensed on this computer", licenseUpdatesText(license), Modifier.testTag("LicenseStatus"))
            }
            Row(Modifier.settingsInset(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onManage, style = ButtonStyle.Secondary, tag = "Manage license") { Label("Manage license") }
                Spacer(Modifier.weight(1f))
                Button(onClick = { license.clearOutcome(); confirmingDeactivate = true }, style = ButtonStyle.Ghost, tag = "DeactivateLicense") { Label("Unlink this computer") }
            }
        } else {
            Card(contentPadding = PaddingValues(16.dp)) {
                StatusHeader(Icons.Key, accentColor(), "Activate $APP_NAME", "Paste the license key from your purchase email to activate your license.",
                    Modifier.testTag("LicenseStatus"))
                Spacer(Modifier.height(16.dp))
                LicenseKeyForm(license, onManage)
            }
            Card(contentPadding = PaddingValues(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Label("Don't have a license yet?", Modifier.weight(1f))
                    Button(onClick = onPurchase, style = ButtonStyle.Primary, tag = "BuyLicense") { Label("Buy a license") }
                }
            }
        }
    }
    fun closeDeactivate() { if (!license.working) { confirmingDeactivate = false; license.clearOutcome() } }
    AppDialog(confirmingDeactivate.takeIf { it }, onClose = ::closeDeactivate) {
        AppDialogPanel("Unlink this computer?", "DeactivateLicenseDialog", Modifier.width(440.dp)) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Unlink this computer?", style = RobomoriTheme.typography.heading)
                Label("You can use your license on another computer after unlinking. To use it here again, just enter your key.")
                LicenseMessage(license)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { closeDeactivate() }, style = ButtonStyle.Secondary, tag = "CancelDeactivateLicense", enabled = !license.working) { Label("Cancel") }
                    Button(onClick = { license.deactivate() }, style = ButtonStyle.Destructive, tag = "ConfirmDeactivateLicense", enabled = !license.working) { Label("Unlink") }
                }
            }
        }
    }
}

/** Development runs only: see the app as someone without a license does, without unlinking the real one. */
@Composable internal fun DebugPage(state: AppState) {
    val license = state.license
    SettingsPageScaffold(SettingsSection.Debug.title, "DebugPage") {
        Card(contentPadding = PaddingValues(horizontal = 12.dp)) {
            SwitchRow("Pretend unlicensed", license.pretendUnlicensed, "PretendUnlicensed",
                { license.pretendUnlicensed = it }, icon = Icons.Key)
            SwitchRow("Pretend the trial ended", license.pretendTrialEnded, "PretendTrialEnded",
                { license.pretendTrialEnded = it }, icon = Icons.Key)
        }
        Card(contentPadding = PaddingValues(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Label("Onboarding", Modifier.weight(1f))
                Button(onClick = state::showOnboardingForDevelopment, style = ButtonStyle.Secondary, tag = "ShowOnboarding") {
                    Label("Show onboarding")
                }
            }
        }
    }
}

private fun licenseUpdatesText(license: AppLicense): String {
    val until = license.updatesUntil ?: return ""
    val date = java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.LONG)
        .withZone(java.time.ZoneOffset.UTC).format(until)
    return if (until.isAfter(java.time.Instant.now())) "Includes updates through $date."
    else "Updates ended on $date. You can keep using versions released before then."
}

@Composable private fun accentColor(): Color = RobomoriTheme.colors.accent

/** Tinted icon badge with a title and secondary line, used at the top of settings cards. */
@Composable private fun StatusHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, title: String, subtitle: String,
                                     modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).background(tint.copy(alpha = .12f), androidx.compose.foundation.shape.CircleShape),
            contentAlignment = Alignment.Center) {
            Icon(icon, tint = tint, modifier = Modifier.size(20.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = RobomoriTheme.typography.heading)
            if (subtitle.isNotEmpty()) SettingsNote(subtitle)
        }
    }
}

@Composable internal fun LicenseKeyForm(license: AppLicense, onManage: () -> Unit) {
    val key = rememberTextFieldState()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        com.robomori.components.TextField(
            state = key,
            modifier = Modifier.fillMaxWidth().testTag("LicenseKey")
                .semantics { contentDescription = "License key" },
            placeholder = { Text("LIC-XXXXXXXX-XXXXXXXX-XXXXXXXX-XXXXXXXX-XXXXXXXX") },
        )
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            // The portal is where keys are recovered and other computers are unlinked.
            Button(onClick = onManage, style = ButtonStyle.Secondary, tag = "Manage license") { Label("Manage license") }
            // Status sits beside the button that caused it, so the form needs no empty line reserved for it.
            LicenseMessage(license, Modifier.weight(1f), androidx.compose.ui.text.style.TextAlign.End)
            Button(onClick = { license.activate(key.text.toString()) }, style = ButtonStyle.Primary, tag = "Activate", enabled = !license.working) { Label("Activate") }
        }
    }
}

/** One fixed-height line for progress and errors, so they don't shift the buttons. Success shows as the page changing state. */
@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun LicenseMessage(license: AppLicense, modifier: Modifier = Modifier.fillMaxWidth(),
                                       textAlign: androidx.compose.ui.text.style.TextAlign = androidx.compose.ui.text.style.TextAlign.Start) {
    val message = license.message.takeIf { license.working || license.messageIsError }.orEmpty()
    val messageStyle = RobomoriTheme.typography.body
    val messageHeight = with(androidx.compose.ui.platform.LocalDensity.current) { messageStyle.lineHeight.toDp() }
    com.robomori.components.Tooltip(message, modifier, enabled = message.isNotEmpty()) {
        Text(message, modifier = Modifier.fillMaxWidth().height(messageHeight).testTag("LicenseMessage"), textAlign = textAlign,
            color = if (license.messageIsError) RobomoriTheme.colors.danger else RobomoriTheme.colors.textSecondary,
            style = messageStyle, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

@Composable fun UpdatesPage(state: AppState) {
    val updates = state.updates
    val codeUpdate = state.codeUpdates.readyPatch
    LaunchedEffect(state.license.receipt) { updates.check(); state.codeUpdates.check() }
    SettingsPageScaffold("Updates", "UpdatesPage") {
        Card(contentPadding = PaddingValues(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusHeader(Icons.ArrowDownTray, accentColor(), "$APP_NAME $APP_DISPLAY_VERSION", updateMessage(updates, state.codeUpdates),
                    Modifier.weight(1f).testTag("UpdateStatus"))
                when {
                    updates.downloaded != null && updates.canInstall -> Button(onClick = { updates.restart(state.busy) }, style = ButtonStyle.Primary, tag = if (updates.restoring) "Restart and restore" else "Restart and install", enabled = !updates.working && !state.busy) { Label(if (updates.restoring) "Restart and restore" else "Restart and install") }
                    updates.available != null && updates.canInstall -> Button(onClick = { updates.download() }, style = ButtonStyle.Primary, tag = if (updates.restoring) "Restore" else "Download update", enabled = !updates.working) { Label(if (updates.restoring) "Restore" else "Download update") }
                    codeUpdate != null -> Button(onClick = ::restartToUpdate, style = ButtonStyle.Primary, tag = "Restart and update", enabled = !state.busy) { Label("Restart and update") }
                    else -> Button(onClick = { state.checkUpdates() }, style = ButtonStyle.Secondary, tag = "CheckForUpdates", enabled = !updates.working && !state.codeUpdates.checking && updates.unavailableReason == null && updates.downloaded == null) { Label("Check for updates") }
                }
            }
            if ((updates.downloaded != null || codeUpdate != null) && state.busy) {
                Spacer(Modifier.height(12.dp))
                SettingsNote("Finish the current emulator operation before restarting.")
            }
        }
        Card(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
            CheckboxRow("Automatically check for updates", updates.automatic, updates::changeAutomatic)
        }
    }
}

@Composable fun LogsPage(state: AppState) {
    SettingsPageScaffold(SettingsSection.Logs.title, "LogsPage") {
        Card(contentPadding = PaddingValues(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Application Logs")
                    state.logsExport.failure?.let { failure ->
                        Label(failure.message ?: "Could not export the logs. Try again.",
                            color = RobomoriTheme.colors.danger, modifier = Modifier.testTag("ExportLogsError"))
                    }
                }
                Button(onClick = { chooseLogsExport()?.let(state::exportLogs) }, style = ButtonStyle.Secondary, tag = "ExportLogs",
                    enabled = !state.logsExport.isRunning) { Label("Export Logs…") }
            }
        }
    }
}

/** Asks where to save the exported logs, on the Desktop to start with. */
private fun chooseLogsExport(): File? {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Export Logs", java.awt.FileDialog.SAVE).apply {
        directory = File(userHome, "Desktop").takeIf(File::isDirectory)?.path
        file = "robomori-logs-${java.time.LocalDate.now()}.zip"
        isVisible = true
    }
    val chosen = dialog.file ?: return null
    return File(dialog.directory, chosen)
}

@Composable internal fun CommandLinePage(command: CommandLineToolState, skill: AgentSkillState) {
    AgentSkillDialog(skill)
    LaunchedEffect(Unit) { command.load(); skill.load() }
    SettingsPageScaffold(SettingsSection.CommandLine.title, "CommandLinePage") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.settingsInset()) { SettingsCardTitle("Command Line Tool") }
            Card(contentPadding = PaddingValues(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(androidx.compose.ui.text.buildAnnotatedString {
                            append("Add ")
                            withStyle(androidx.compose.ui.text.SpanStyle(fontFamily = RobomoriTheme.typography.code.fontFamily)) { append(COMMAND_NAME) }
                            append(" to path")
                        })
                        SettingsNote(if (command.available) "Use Robomori from your terminal, and let your coding agents make full use of it"
                            else "Not available when running from Gradle")
                    }
                    // A fixed width keeps the button still while its label changes or the spinner shows.
                    Button(onClick = { if (command.installed) command.uninstall() else command.install() }, Modifier.width(112.dp),
                        style = if (command.installed) ButtonStyle.Secondary else ButtonStyle.Primary,
                        tag = "CommandLineInstall", enabled = command.available && !command.working) {
                        if (command.working) com.robomori.components.Spinner(Modifier.size(16.dp))
                        else Label(if (command.installed) "Uninstall" else "Install")
                    }
                }
                Spacer(Modifier.height(12.dp))
                // Always laid out, so an error doesn't move the page.
                Label(command.error.orEmpty(), color = RobomoriTheme.colors.danger, modifier = Modifier.testTag("CommandLineError"))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.settingsInset()) { SettingsCardTitle("Agent Skill") }
            Card(contentPadding = PaddingValues(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(AGENT_SKILL_NAME, fontFamily = RobomoriTheme.typography.code.fontFamily)
                        SettingsNote(skill.installedIn.takeIf { it.isNotEmpty() }?.let { "Installed for " + it.joinToString { d -> d.name } }
                            ?: "Teaches your coding agent every robomori command, kept up to date with the app")
                    }
                    Button(onClick = skill::choose, Modifier.width(112.dp),
                        style = if (skill.installed.isEmpty()) ButtonStyle.Primary else ButtonStyle.Secondary,
                        // The skill teaches the robomori command, so it installs once the command does.
                        tag = "AgentSkillInstall", enabled = skill.installed.isNotEmpty() || command.installed) {
                        Label(if (skill.installed.isEmpty()) "Install" else "Manage")
                    }
                }
            }
        }
    }
}

@Composable private fun SkillLogoImage(logo: SkillLogo) {
    val modifier = Modifier.size(24.dp)
    when (logo) {
        SkillLogo.Claude -> androidx.compose.foundation.Image(painterResource(Res.drawable.agent_claude), "Claude", modifier)
        SkillLogo.Codex -> androidx.compose.foundation.Image(painterResource(Res.drawable.agent_codex), "Codex", modifier)
        // Cursor's mark is drawn in the text color, so it shows in light and dark mode.
        SkillLogo.Cursor -> androidx.compose.foundation.Image(painterResource(Res.drawable.agent_cursor), "Cursor", modifier,
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(RobomoriTheme.contentColor))
        SkillLogo.Gemini -> androidx.compose.foundation.Image(painterResource(Res.drawable.agent_gemini), "Gemini", modifier)
        SkillLogo.AnyAgent -> Box(modifier, contentAlignment = Alignment.Center) { Icon(Icons.Sparkles) }
        SkillLogo.Folder -> Box(modifier, contentAlignment = Alignment.Center) { Icon(Icons.Folder) }
    }
}

/** Asks which coding agent to install the skill for: each agent reads skills from its own folder. */
@Composable internal fun AgentSkillDialog(skill: AgentSkillState) {
    val window = LocalAwtWindow.current
    AppDialog(skill.choosing.takeIf { it }, onClose = skill::close) {
        DialogScaffold("Which coding agent do you use?", "AgentSkillDialog", Modifier.width(480.dp), footer = {
            Button(onClick = {
                skill.chooseFolder { start ->
                    FileKit.openDirectoryPicker(directory = start?.let(::PlatformFile), dialogSettings = FileKitDialogSettings(
                        title = "Choose a skills folder", parent = window?.let { FileKitDialogParent.awt(it) }))?.file
                }
            }, style = ButtonStyle.Secondary, tag = "AgentSkillChooseFolder", enabled = skill.working.isEmpty()) { Label("Choose Folder…") }
            Spacer(Modifier.weight(1f))
            Button(onClick = skill::close, style = ButtonStyle.Primary, tag = "AgentSkillDone", enabled = skill.working.isEmpty()) { Label("Done") }
        }) {
            Label("Robomori puts the $AGENT_SKILL_NAME skill where your agent looks for skills, and keeps it up to date.",
                color = RobomoriTheme.colors.textSecondary)
            Column {
                skill.destinations.forEachIndexed { index, destination ->
                    if (index > 0) Rule()
                    val installed = destination.folder in skill.installed
                    // Space only between rows, so the list sits on the dialog's own padding.
                    Row(Modifier.padding(top = if (index > 0) 10.dp else 0.dp, bottom = if (index < skill.destinations.lastIndex) 10.dp else 0.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        SkillLogoImage(destination.logo)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Label(destination.name)
                            SettingsNote(destination.path)
                        }
                        Button(onClick = { if (installed) skill.uninstall(destination) else skill.install(destination) }, Modifier.width(104.dp),
                            style = ButtonStyle.Secondary, tag = "AgentSkill-${destination.name}", enabled = skill.working.isEmpty()) {
                            if (destination.folder in skill.working) com.robomori.components.Spinner(Modifier.size(16.dp))
                            else Label(if (installed) "Uninstall" else "Install")
                        }
                    }
                }
            }
            skill.error?.let { Label(it, color = RobomoriTheme.colors.danger, modifier = Modifier.testTag("AgentSkillError")) }
        }
    }
}
