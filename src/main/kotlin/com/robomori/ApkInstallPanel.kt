package com.robomori

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.robomori.components.*
import com.robomori.theme.RobomoriTheme
import com.composeunstyled.Text

/**
 * The device's screen, which installs APKs dropped on it. The drop panel stays up while they install, and says why
 * when one can't be; once they're in, the last app opens.
 */
@Composable
internal fun ApkDropArea(state: AppState, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val serial = reachableSerial(state)
    if (serial == null) return Box(modifier) { content() }
    val install = state.apkInstall(serial)
    val status = install.status
    FileDropArea("Drop to install", "ApkDropArea", enabled = status is ApkInstallStatus.Idle,
        accepts = { files -> files.any(::isApk) }, onDrop = install::install, modifier = modifier,
        working = when (status) {
            ApkInstallStatus.Idle -> null
            is ApkInstallStatus.Installing -> { ->
                Spinner(Modifier.size(28.dp))
                Text("Installing ${status.name}…", style = RobomoriTheme.typography.heading, textAlign = TextAlign.Center)
                if (status.remaining > 0) Label("${status.remaining} more after it", color = RobomoriTheme.colors.textSecondary)
            }
            is ApkInstallStatus.Failed -> { ->
                Text("Couldn't install ${status.name}", style = RobomoriTheme.typography.heading, textAlign = TextAlign.Center)
                Text(status.reason.explanation(status.detail), color = RobomoriTheme.colors.textSecondary, textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 360.dp))
                Button(onClick = install::dismiss, style = ButtonStyle.Primary, tag = "DismissApkInstall") { Label("OK") }
            }
        }) { content() }
}

private fun InstallFailure.explanation(detail: String) = when (this) {
    InstallFailure.SignedDifferently -> "A copy signed with another key is installed. Uninstall it first."
    InstallFailure.Downgrade -> "A newer version is installed. Uninstall it first."
    InstallFailure.NewerAndroid -> "It needs a newer version of Android."
    InstallFailure.OtherProcessor -> "It has no code for this device's processor."
    InstallFailure.NoSpace -> "The device is out of storage."
    InstallFailure.NotAnApk -> "The file isn't a valid APK."
    InstallFailure.Other -> detail.ifBlank { "The device turned it down." }
}
