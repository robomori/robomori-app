package com.robomori

import androidx.compose.foundation.rememberScrollState
import com.robomori.theme.verticalScroll
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.robomori.components.Icon
import com.robomori.components.Label
import com.robomori.theme.RobomoriTheme
import org.jetbrains.compose.resources.painterResource
import com.composeunstyled.Text
import robomori.generated.resources.Res
import robomori.generated.resources.app_icon

// The onboarding fills the window in the dark theme. Robomori blue marks what to act on.
private val OnboardingBlue = Color(0xFF3574F0)
/** The Android brand green, for what belongs to Android itself, such as its SDK. */
internal val AndroidGreen = Color(0xFF3DDC84)
private val ContentWidth = 620.dp

/**
 * A page of the onboarding, filling the window below its title bar ([topInset]): [title] and [subtitle], the page's
 * content, and its buttons, all in one column centered in the window.
 */
@Composable
internal fun OnboardingScaffold(title: String, subtitle: String, tag: String, topInset: Dp,
                                footer: @Composable RowScope.() -> Unit, corner: (@Composable () -> Unit)? = null,
                                content: @Composable ColumnScope.() -> Unit) {
    OnboardingPage(tag, topInset, footer, corner) {
        // The title stays on one line, even when it is wider than the content below it.
        Text(title, Modifier.wrapContentWidth(unbounded = true), textAlign = TextAlign.Center, maxLines = 1,
            style = RobomoriTheme.typography.largeTitle.copy(color = RobomoriTheme.colors.text))
        Spacer(Modifier.height(8.dp))
        Text(subtitle, textAlign = TextAlign.Center,
            style = RobomoriTheme.typography.title3.copy(color = RobomoriTheme.colors.textSecondary, fontWeight = FontWeight.Normal))
        Spacer(Modifier.height(28.dp))
        Column(verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

/** The welcome: the app icon glowing above who's welcoming, and the pitch. */
@Composable
internal fun OnboardingHero(tag: String, topInset: Dp, pitch: String, footer: @Composable RowScope.() -> Unit) {
    OnboardingPage(tag, topInset, footer) {
        // The glow spreads past the icon without taking space, so the page's content stays centered as one group.
        Image(painterResource(Res.drawable.app_icon), contentDescription = null, modifier = Modifier.size(132.dp).drawBehind {
            drawCircle(Brush.radialGradient(listOf(OnboardingBlue.copy(alpha = .38f), Color.Transparent), radius = 130.dp.toPx()),
                radius = 130.dp.toPx())
        })
        Spacer(Modifier.height(20.dp))
        Text("Welcome to", style = RobomoriTheme.typography.title1.copy(color = RobomoriTheme.colors.text, fontWeight = FontWeight.Normal))
        Text(APP_NAME, style = TextStyle(color = RobomoriTheme.colors.text, fontSize = 46.sp, fontWeight = FontWeight.Bold, lineHeight = 54.sp))
        Spacer(Modifier.height(14.dp))
        Text(pitch, textAlign = TextAlign.Center,
            style = RobomoriTheme.typography.title2.copy(color = RobomoriTheme.colors.text, fontWeight = FontWeight.Normal))
    }
}

@Composable
/** [corner] sits at the bottom right, where every page that can be skipped puts Skip. */
private fun OnboardingPage(tag: String, topInset: Dp, footer: @Composable RowScope.() -> Unit, corner: (@Composable () -> Unit)? = null,
                           content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(RobomoriTheme.colors.panelBackground).padding(top = topInset).testTag(tag),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.weight(1f))
            Column(Modifier.widthIn(max = ContentWidth).fillMaxWidth().padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally, content = content)
            Spacer(Modifier.height(36.dp))
            Row(Modifier.widthIn(max = ContentWidth).fillMaxWidth().padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically, content = footer)
            Spacer(Modifier.weight(1f))
        }
        corner?.let { Box(Modifier.align(Alignment.BottomEnd).padding(24.dp)) { it() } }
    }
}

/** A setup step: a tinted icon tile, what to do, its status, and the action on the right, with room for progress below. */
@Composable
internal fun SetupRow(icon: @Composable () -> Unit, title: String, status: String, progress: (@Composable () -> Unit)? = null,
                      statusAction: (@Composable () -> Unit)? = null, tint: Color = OnboardingBlue, action: @Composable () -> Unit) {
    // The progress bar grows in and out of the row, as in Settings → Platforms.
    Column(Modifier.fillMaxWidth().animateContentSize().padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(tint.copy(alpha = .18f)), contentAlignment = Alignment.Center) { icon() }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = RobomoriTheme.typography.title3.copy(color = RobomoriTheme.colors.text))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Label(status, color = RobomoriTheme.colors.textSecondary, maxLines = 1)
                    statusAction?.invoke()
                }
            }
            action()
        }
        progress?.invoke()
    }
}

/**
 * A page split in two, filling the window: [features] on the left, and the page's title, [subtitle] and [actions]
 * centered on the right, with [corner] in the bottom right corner.
 */
@Composable
internal fun OnboardingSplitPage(tag: String, topInset: Dp, title: String, subtitle: String, features: List<Feature>,
                                 corner: (@Composable () -> Unit)? = null, actions: @Composable ColumnScope.() -> Unit) {
    Row(Modifier.fillMaxSize().background(RobomoriTheme.colors.panelBackground).padding(top = topInset).testTag(tag)) {
        FeatureGrid(features, Modifier.weight(1f).fillMaxHeight().padding(horizontal = 48.dp))
        Box(Modifier.weight(1f).fillMaxHeight()) {
        // As wide as a dialog at most, centred, so the title and buttons don't stretch across a wide window.
        Column(Modifier.align(Alignment.Center).fillMaxHeight().widthIn(max = 600.dp).padding(horizontal = 48.dp),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, textAlign = TextAlign.Center, style = RobomoriTheme.typography.largeTitle.copy(color = RobomoriTheme.colors.text))
            Spacer(Modifier.height(8.dp))
            Text(subtitle, textAlign = TextAlign.Center,
                style = RobomoriTheme.typography.title3.copy(color = RobomoriTheme.colors.textSecondary, fontWeight = FontWeight.Normal))
            Spacer(Modifier.height(32.dp))
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp), content = actions)
        }
        corner?.let { Box(Modifier.align(Alignment.BottomEnd).padding(24.dp)) { it() } }
        }
    }
}

/** Something a license adds: a big icon, what it is, and one line about it. */
internal data class Feature(val icon: ImageVector, val title: String, val detail: String)

/** Features two to a row, each a big icon over its title and detail. */
@Composable
private fun FeatureGrid(features: List<Feature>, modifier: Modifier) {
    // Centered while the features fit, and scrolls when the window is too short for them.
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(36.dp)) {
            features.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    row.forEach { feature ->
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(feature.icon, tint = RobomoriTheme.colors.text, modifier = Modifier.size(36.dp))
                            Spacer(Modifier.height(8.dp))
                            Text(feature.title, style = RobomoriTheme.typography.title3.copy(color = RobomoriTheme.colors.text))
                            Text(feature.detail, style = RobomoriTheme.typography.title3.copy(color = RobomoriTheme.colors.textSecondary, fontWeight = FontWeight.Normal))
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** An icon in a setup row's tile, in the onboarding blue unless given a [tint]. */
@Composable internal fun SetupIcon(icon: ImageVector, tint: Color = OnboardingBlue) = Icon(icon, tint = tint, modifier = Modifier.size(20.dp))
