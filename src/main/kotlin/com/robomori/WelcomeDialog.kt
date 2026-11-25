@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.robomori

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.LocalAwtWindow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.robomori.components.*
import com.robomori.iconography.*
import com.robomori.theme.RobomoriTheme
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitDialogParent
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.openDirectoryPicker
import com.composeunstyled.Text

/**
 * The first-launch onboarding: a welcome, the Android SDK, the platforms to develop for, then what a license adds.
 * [Onboarding] holds its state and does the work; these pages render it and report what the person does.
 */
@Composable
internal fun WelcomeDialog(state: AppState, topInset: Dp) {
    val scope = rememberCoroutineScope()
    val window = LocalAwtWindow.current
    val onboarding = remember {
        Onboarding(state, scope, pickFolder = { start ->
            FileKit.openDirectoryPicker(directory = start?.let(::PlatformFile),
                dialogSettings = FileKitDialogSettings(title = "Choose your Android SDK folder", parent = window?.let { FileKitDialogParent.awt(it) }))?.file
        })
    }
    LaunchedEffect(state.license.licensed) { onboarding.licenseChanged(state.license.licensed) }
    val onAction = onboarding::onAction
    // Activating opens a dialog over the license page, so both are the same page here.
    val page = if (onboarding.step == OnboardingStep.Activate) OnboardingStep.License else onboarding.step
    val distance = with(androidx.compose.ui.platform.LocalDensity.current) { SharedAxisDistance.roundToPx() }
    // Material's shared-axis transition: a short slide with a fade-through, forward to the left and back to the right.
    // Pages fade through this background, the one they draw too, so nothing behind them shows mid-way.
    AnimatedContent(page, Modifier.fillMaxSize().background(RobomoriTheme.colors.panelBackground), transitionSpec = {
        val offset = if (onboardingPages.indexOf(targetState) > onboardingPages.indexOf(initialState)) distance else -distance
        (fadeIn(tween(SharedAxisMillis, easing = FadeThroughIn)) +
            slideInHorizontally(tween(SharedAxisMillis, easing = EmphasizedEasing)) { offset }) togetherWith
            (fadeOut(tween(SharedAxisMillis, easing = FadeThroughOut)) +
                slideOutHorizontally(tween(SharedAxisMillis, easing = EmphasizedEasing)) { -offset })
    }, label = "OnboardingPage") { shown ->
        when (shown) {
            OnboardingStep.Welcome -> WelcomePage(topInset, onAction)
            OnboardingStep.Sdk -> SdkPage(onboarding.sdk, onboarding.sdkChoice, topInset, onAction)
            OnboardingStep.Platforms -> PlatformsPage(onboarding, topInset, onAction)
            OnboardingStep.CommandLine -> CommandLineSetupPage(onboarding.commandLine, onboarding.agentSkill, topInset, onAction)
            OnboardingStep.License, OnboardingStep.Activate -> LicenseOfferPage(topInset, onAction)
        }
    }
    if (onboarding.step == OnboardingStep.Activate) ActivateDialog(state.license, onAction)
}

/** The onboarding's pages in order, for the direction they slide in. */
private val onboardingPages = listOf(OnboardingStep.Welcome, OnboardingStep.Sdk, OnboardingStep.Platforms, OnboardingStep.CommandLine,
    OnboardingStep.License)

// Material 3's shared axis, as in MaterialSharedAxis: duration.long1, easing.emphasized, a 30dp slide, and a fade-through
// that switches pages at 35% of the eased progress.
private const val SharedAxisMillis = 450
private val SharedAxisDistance = 30.dp
private const val FadeThreshold = 0.35f
private val EmphasizedEasing = androidx.compose.animation.core.PathEasing(androidx.compose.ui.graphics.Path().apply {
    moveTo(0f, 0f)
    cubicTo(0.05f, 0f, 0.133333f, 0.06f, 0.166666f, 0.4f)
    cubicTo(0.208333f, 0.82f, 0.25f, 1f, 1f, 1f)
})
private val FadeThroughIn = androidx.compose.animation.core.Easing {
    ((EmphasizedEasing.transform(it) - FadeThreshold) / (1f - FadeThreshold)).coerceIn(0f, 1f)
}
private val FadeThroughOut = androidx.compose.animation.core.Easing { (EmphasizedEasing.transform(it) / FadeThreshold).coerceIn(0f, 1f) }

/** Installs the robomori command, then the skill that teaches coding agents to use it. */
@Composable
internal fun CommandLineSetupPage(command: CommandLineToolState, skill: AgentSkillState, topInset: Dp, onAction: (OnboardingAction) -> Unit) {
    AgentSkillDialog(skill)
    // Optional: Skip moves on without the command, Continue once it's installed.
    OnboardingScaffold("$APP_NAME for agentic workflows",
        "Install the $COMMAND_NAME command, so you and your coding agents can start emulators, read the screen and tap through apps from the terminal.",
        "WelcomeCommandLine", topInset,
        footer = {
            Button(onClick = { onAction(OnboardingAction.Next) }, style = ButtonStyle.Primary, size = ButtonSize.ExtraLarge,
                enabled = command.installed, tag = "WelcomeContinue") { Label("Continue") }
        },
        corner = {
            Button(onClick = { onAction(OnboardingAction.Next) }, style = ButtonStyle.Ghost, size = ButtonSize.ExtraLarge,
                tag = "WelcomeSkipCommandLine") { Label("Skip") }
        }) {
        val status = command.error ?: when {
            command.installed -> "Installed. Run $COMMAND_NAME help in a new terminal window."
            !command.available -> "Available once $APP_NAME is installed."
            else -> "Adds $COMMAND_NAME to your PATH."
        }
        Card(contentPadding = PaddingValues(0.dp)) {
            SetupRow(icon = { SetupIcon(Icons.AppWindow) }, title = "Add $COMMAND_NAME to path", status = status) {
                when {
                    command.installed -> Icon(Icons.Check, tint = RobomoriTheme.colors.success)
                    command.working -> com.robomori.components.Spinner(Modifier.size(16.dp))
                    else -> Button(onClick = { onAction(OnboardingAction.InstallCommandLine) }, style = ButtonStyle.Primary,
                        enabled = command.available, tag = "WelcomeInstallCommandLine") { Label("Install") }
                }
            }
            CardDivider()
            val skillStatus = when {
                skill.installedIn.isNotEmpty() -> "Installed for " + skill.installedIn.joinToString { it.name }
                !command.installed -> "Install the command first."
                else -> "Teaches your coding agent every $COMMAND_NAME command."
            }
            SetupRow(icon = { SetupIcon(Icons.Sparkles) }, title = "Install the $AGENT_SKILL_NAME skill", status = skillStatus) {
                if (skill.installedIn.isNotEmpty()) Icon(Icons.Check, tint = RobomoriTheme.colors.success)
                // The skill teaches the command, so it installs once the command does.
                else Button(onClick = { onAction(OnboardingAction.InstallSkill) }, style = ButtonStyle.Primary,
                    enabled = command.installed, tag = "WelcomeInstallSkill") { Label("Install") }
            }
        }
    }
}

@Composable
internal fun WelcomePage(topInset: Dp, onAction: (OnboardingAction) -> Unit) {
    OnboardingHero("WelcomeDialog", topInset,
        pitch = "Run and control your Android emulators and devices, without opening Android Studio.") {
        Button(onClick = { onAction(OnboardingAction.Next) }, style = ButtonStyle.Primary, size = ButtonSize.ExtraLarge, tag = "WelcomeGetStarted") { Label("Get started") }
    }
}

/** Finds the Android SDK, or points to where to get one. Everything else in Robomori runs on its tools. */
@Composable
internal fun SdkPage(sdk: SdkStatus, choice: SdkChoice, topInset: Dp, onAction: (OnboardingAction) -> Unit) {
    OnboardingScaffold("Set up the Android SDK",
        "$APP_NAME runs your emulators with the Android SDK's tools: the emulator, adb and the command-line tools.", "WelcomeSdk", topInset,
        footer = { ContinueButton(onAction) }) {
        Card(contentPadding = PaddingValues(0.dp)) { SdkRow(sdk, choice, onAction) }
        when (sdk) {
            SdkStatus.Ready -> {}
            SdkStatus.Missing, is SdkStatus.Incomplete -> Card(contentPadding = PaddingValues(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Don't have the Android SDK yet?", style = RobomoriTheme.typography.title3)
                    Label("Download Google's command-line tools and unzip them into a folder for your SDK, then choose that folder above. " +
                        "If you have Android Studio, its SDK is usually in ${HostPlatform().defaultSdk.path.replace(System.getProperty("user.home"), "~")}.",
                        color = RobomoriTheme.colors.textSecondary)
                    Row { Button(onClick = { onAction(OnboardingAction.DownloadSdk) }, style = ButtonStyle.Outlined, tag = "WelcomeDownloadSdk") { Label("Download the Android SDK") } }
                }
            }
        }
    }
}

@Composable
private fun SdkRow(sdk: SdkStatus, choice: SdkChoice, onAction: (OnboardingAction) -> Unit) {
    // A problem choosing the folder explains itself first; otherwise the row says what the SDK has.
    val status = when (choice) {
        SdkChoice.PickerUnavailable -> "Could not open the folder picker. Try again."
        is SdkChoice.NotSaved -> choice.result.errorMessage ?: "Could not use that folder. Try another one."
        SdkChoice.Idle, SdkChoice.Picking -> when (sdk) {
            SdkStatus.Ready -> "Android SDK selected"
            SdkStatus.Missing -> "Connects $APP_NAME to your emulators and devices."
            is SdkStatus.Incomplete -> "Found the SDK, but not ${sdk.missing.joinToString(", ")}."
        }
    }
    val ready = sdk == SdkStatus.Ready
    val picking = choice == SdkChoice.Picking
    // Choose until the SDK is ready, then a tick; changing it is a link beside the status.
    SetupRow(icon = { SetupIcon(Icons.Android, tint = AndroidGreen) }, title = "Select Android SDK", status = status, tint = AndroidGreen,
        statusAction = if (ready) ({ Link("Change…", { onAction(OnboardingAction.ChooseSdk) }, enabled = !picking, tag = "WelcomeChangeSdk") }) else null) {
        if (ready) Icon(Icons.Check, tint = RobomoriTheme.colors.success)
        else Button(onClick = { onAction(OnboardingAction.ChooseSdk) }, style = ButtonStyle.Primary, enabled = !picking,
            tag = "WelcomeChooseSdk") { Label("Choose…") }
    }
}

private val PlatformNames = mapOf(DeviceType.PHONE to "Phones, Tablets and Foldables", DeviceType.WEAR to "Watches", DeviceType.TV to "TVs")

/** Like Xcode's first launch: get the platforms to develop for. Each one downloads in place from its Get button. */
@Composable
private fun PlatformsPage(onboarding: Onboarding, topInset: Dp, onAction: (OnboardingAction) -> Unit) {
    LaunchedEffect(onboarding.versions) { onboarding.loadSizes() }
    PlatformsPage(topInset, onAction) { type ->
        // Download progress arrives as a flow. Reading the collected value is what redraws the row as it changes.
        onboarding.download(type)?.progress?.collectAsState()?.value
        onboarding.platform(type)
    }
}

/** The platforms page for each platform's [status]. */
@Composable
internal fun PlatformsPage(topInset: Dp, onAction: (OnboardingAction) -> Unit, status: @Composable (DeviceType) -> PlatformStatus) {
    OnboardingScaffold("Choose the platforms you want to develop for",
        "You can always add more via the Settings later.", "WelcomePlatforms", topInset,
        // Downloads keep going after the onboarding closes; Settings → Platforms shows their progress.
        footer = { ContinueButton(onAction) }) {
        Card(contentPadding = PaddingValues(0.dp)) {
            OnboardingPlatforms.forEachIndexed { index, type ->
                if (index > 0) CardDivider()
                PlatformRow(type, status(type), onAction)
            }
        }
    }
}

@Composable
private fun PlatformRow(type: DeviceType, status: PlatformStatus, onAction: (OnboardingAction) -> Unit) {
    val text = when (status) {
        PlatformStatus.NeedsSdk -> "Select your Android SDK first"
        PlatformStatus.Loading -> "Checking the latest version…"
        PlatformStatus.ListFailed -> "Couldn't load the available versions"
        PlatformStatus.NoCompatibleVersion -> "No version runs on this computer"
        is PlatformStatus.Available -> listOfNotNull(status.image.platformTitle, status.size?.let(::storageSize)).joinToString(" · ")
        is PlatformStatus.Outdated -> listOfNotNull("${status.installed.platformTitle} installed", "${status.image.platformTitle} available",
            status.size?.let(::storageSize)).joinToString(" · ")
        is PlatformStatus.Downloading -> "${status.image.platformTitle} · ${if (status.installing) "Installing" else "Downloading"} ${status.percent}%"
        is PlatformStatus.DownloadFailed -> "${status.image.platformTitle} · Download failed"
        is PlatformStatus.Installed -> "${status.image.platformTitle} · Installed"
    }
    val progress: (@Composable () -> Unit)? = when (status) {
        is PlatformStatus.Downloading -> { { ProgressBar(status.percent / 100f, Modifier.fillMaxWidth()) } }
        else -> null
    }
    SetupRow(icon = { DeviceGlyph(type, running = status is PlatformStatus.Installed, size = 22.dp) }, title = PlatformNames.getValue(type),
        status = text, progress = progress) {
        when (status) {
            PlatformStatus.NeedsSdk, PlatformStatus.NoCompatibleVersion -> {}
            PlatformStatus.Loading -> ProgressBar(null, Modifier.width(48.dp))
            PlatformStatus.ListFailed -> Button(onClick = { onAction(OnboardingAction.RetryPlatformList) }, tag = "WelcomeRetryList") { Label("Try again") }
            is PlatformStatus.Available -> Button(onClick = { onAction(OnboardingAction.Get(type)) }, style = ButtonStyle.Primary, tag = "WelcomeGet-${type.name}") { Label("Get") }
            is PlatformStatus.Outdated -> Button(onClick = { onAction(OnboardingAction.Get(type)) }, style = ButtonStyle.Primary, tag = "WelcomeUpdate-${type.name}") { Label("Update") }
            is PlatformStatus.Downloading -> Button(onClick = { onAction(OnboardingAction.Cancel(type)) }, enabled = !status.cancelling, tag = "WelcomeCancel-${type.name}") {
                Label(if (status.cancelling) "Cancelling…" else "Cancel")
            }
            is PlatformStatus.DownloadFailed -> Button(onClick = { onAction(OnboardingAction.Get(type)) }, style = ButtonStyle.Outlined, tag = "WelcomeRetry-${type.name}") { Label("Retry") }
            is PlatformStatus.Installed -> Icon(Icons.Check, tint = RobomoriTheme.colors.success)
        }
    }
}

/** What Robomori offers, newest first, then what a license adds that people tend to miss. */
private val LicensedFeatures = listOf(
    Feature(Icons.Webcam, "Your webcam and mic", "Emulators see your computer's camera and hear the microphone you choose."),
    Feature(Icons.Keyboard, "Type straight into emulators", "Use your computer's keyboard in any app, without the on-screen keyboard."),
    Feature(Icons.RecordCircle, "Screen recording and screenshots", "Capture any device, saved to your Desktop."),
    Feature(Icons.QrCode, "Your phone over Wi-Fi", "Pair with a QR code and mirror it without a cable."),
    Feature(Icons.AdjustmentsHorizontal, "Presets", "Save dark mode, font size, language and location, then apply them to any device."),
    Feature(Icons.Contrast, "System settings, one click away", "Dark mode, TalkBack, animations, language and location, without the emulator's menus."),
    Feature(Icons.ViewColumns, "Many devices at once", "Run several emulators side by side, and start, stop or wipe them together."),
)

/** After setup: everything works for the free trial; buy, activate a key, or start the trial. */
@Composable
internal fun LicenseOfferPage(topInset: Dp, onAction: (OnboardingAction) -> Unit) {
    OnboardingSplitPage("WelcomeLicense", topInset, "Unlock all PRO features",
        "Free for $TRIAL_DAYS days, then buy a license to keep using $APP_NAME.\n$UpsellGuarantee",
        features = LicensedFeatures,
        corner = {
            Button(onClick = { onAction(OnboardingAction.ContinueFree) }, style = ButtonStyle.Ghost, size = ButtonSize.ExtraLarge,
                tag = "WelcomeSkip") { Label("Start Free Trial") }
        }) {
        Button(onClick = { onAction(OnboardingAction.BuyLicense) }, Modifier.fillMaxWidth(), style = ButtonStyle.Primary,
            size = ButtonSize.ExtraLarge, tag = "WelcomeBuyLicense") { Label("Buy a license") }
        Button(onClick = { onAction(OnboardingAction.HaveLicense) }, Modifier.fillMaxWidth(), style = ButtonStyle.Ghost,
            size = ButtonSize.ExtraLarge, tag = "WelcomeHaveLicense") { Label("I have a license") }
    }
}

/**
 * After the free trial, without a license: what Robomori does, and buying or activating a license. Nothing else in the
 * app opens until one of them does.
 */
@Composable
internal fun TrialEndedScreen(state: AppState, topInset: Dp) {
    var activating by remember { mutableStateOf(false) }
    OnboardingSplitPage("TrialEnded", topInset, "Your free trial has ended", "Buy a license to keep using $APP_NAME.\n$UpsellGuarantee",
        features = LicensedFeatures, corner = {
            if (developmentBuild) Button(onClick = { state.openSettings(SettingsSection.Debug) }, style = ButtonStyle.Ghost,
                tag = "TrialEndedDebug") { Label("Debug Settings") }
        }) {
        Button(onClick = { state.purchaseLicense(BuySource.TrialEnded) }, Modifier.fillMaxWidth(), style = ButtonStyle.Primary,
            size = ButtonSize.ExtraLarge, tag = "TrialEndedBuy") { Label("Buy a license") }
        Button(onClick = { activating = true }, Modifier.fillMaxWidth(), style = ButtonStyle.Ghost,
            size = ButtonSize.ExtraLarge, tag = "TrialEndedHaveLicense") { Label("I have a license") }
    }
    if (activating) ActivateDialog(state.license) { action ->
        if (action == OnboardingAction.BuyLicense) state.purchaseLicense(BuySource.TrialEnded) else activating = false
    }
}

/** Activates a key from a purchase email, over the license page. Activating finishes the onboarding; Cancel goes back. */
@Composable
internal fun ActivateDialog(license: AppLicense, onAction: (OnboardingAction) -> Unit) {
    AppDialog(Unit, onClose = { onAction(OnboardingAction.Back) }) {
        AppDialogPanel("Activate $APP_NAME", "WelcomeActivate", Modifier.width(460.dp)) {
            val key = rememberTextFieldState()
            Column(Modifier.background(RobomoriTheme.colors.popupBackground).padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Activate $APP_NAME", style = RobomoriTheme.typography.title2.copy(color = RobomoriTheme.colors.text))
                Label("Paste the license key from your purchase email.", color = RobomoriTheme.colors.textSecondary)
                Field(key, "LicenseKey", Modifier.fillMaxWidth())
                LicenseMessage(license, textAlign = TextAlign.Center)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { onAction(OnboardingAction.Back) }, style = ButtonStyle.Secondary, tag = "WelcomeActivateCancel") { Label("Cancel") }
                    Button(onClick = { license.activate(key.text.toString()) }, style = ButtonStyle.Primary,
                        enabled = !license.working && key.text.isNotBlank(), tag = "Activate") { Label("Activate") }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Label("No license yet?", color = RobomoriTheme.colors.textSecondary)
                    Link("Buy now", { onAction(OnboardingAction.BuyLicense) }, tag = "WelcomeActivateBuy")
                }
            }
        }
    }
}

@Composable
private fun ContinueButton(onAction: (OnboardingAction) -> Unit) {
    Button(onClick = { onAction(OnboardingAction.Next) }, style = ButtonStyle.Primary, size = ButtonSize.ExtraLarge, tag = "WelcomeContinue") { Label("Continue") }
}
