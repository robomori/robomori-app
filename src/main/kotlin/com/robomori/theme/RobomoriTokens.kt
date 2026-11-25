package com.robomori.theme

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.robomori.fonts.GoogleFonts

/** The app's colors. The dark ones are used by the onboarding. */
@Immutable
data class RobomoriColors(
    val text: Color,
    val textSecondary: Color,
    val textDisabled: Color,
    val danger: Color,
    val success: Color,
    /** For something that needs attention but didn't fail, such as a request the site turned down. */
    val warning: Color,
    /** For something neutral worth telling apart, such as a redirect. */
    val info: Color,
    /** The main action's fill, a slider's filled track and the focus ring. */
    val accent: Color,
    /** The option under the pointer or keyboard in a menu, and a selected row in a sidebar. */
    val highlight: Color,
    /** Lines between groups, such as in a menu or between sections. */
    val separator: Color,
    val link: Color,
    /** The empty part of a slider or progress bar, and a disabled control's border. */
    val controlTrack: Color,
    val panelBackground: Color,
    val shelfBackground: Color,
    val shelfBorder: Color,
    val deviceIconBackground: Color,
    val runningIconBackground: Color,
    val runningIndicator: Color,
    val installedIndicator: Color,
    val deviceFrame: Color,
    val deviceFrameBorder: Color,
    val deviceScreen: Color,
    val popupBackground: Color,
    val popupBorder: Color,
    val popupShadow: Color,
    val toastBackground: Color,
    val toastText: Color,
    val cardBackground: Color,
    val cardShadow: Color,
    /** Every other row of a table, as Finder shades them, so a row is easy to follow across. */
    val tableStripe: Color,
    /** Folders in a list of files, light blue like Finder's. */
    val folderIcon: Color,
    /** The key marking a table's primary key column, in gold as keys are drawn. */
    val keyIcon: Color,
    val codeTag: Color,
    val codeAttribute: Color,
    val codeValue: Color,
    val codePunctuation: Color,
    val textSelection: Color,
    val textSelectionHandle: Color,
) {
    // Without these, selected text has no highlight.
    val textSelectionColors get() = TextSelectionColors(handleColor = textSelectionHandle, backgroundColor = textSelection)
}

internal val LightColors = RobomoriColors(
    text = Color(0xFF27272A),
    textSecondary = Color(0xFF818594),
    textDisabled = Color(0xFFA8ADBD),
    danger = Color(0xFFDB3B4B),
    success = Color(0xFF16A34A),
    warning = Color(0xFFEA580C),
    info = Color(0xFF2563EB),
    accent = Color(0xFF3574F0),
    highlight = Color(0xFFD4E2FF),
    separator = Color(0xFFEBECF0),
    link = Color(0xFF315FBD),
    controlTrack = Color(0xFFDFE1E5),
    panelBackground = Color(0xFFF7F8FA),
    shelfBackground = Color(0xFFF1F1F1),
    shelfBorder = Color(0xFFD6D6D6),
    deviceIconBackground = Color(0xFFE1E1E1),
    runningIconBackground = Color(0xFFFFFFFF),
    runningIndicator = Color(0xFF2E7D32),
    installedIndicator = Color(0xFF2E7D32),
    deviceFrame = Color(0xFF101011),
    deviceFrameBorder = Color(0xFF666366),
    deviceScreen = Color(0xFF202022),
    popupBackground = Color(0xFFFFFFFF),
    popupBorder = Color(0xFFC9CCD6),
    popupShadow = Color(0x78919191),
    toastBackground = Color(0xFF27272A),
    toastText = Color(0xFFFFFFFF),
    cardBackground = Color(0xFFFFFFFF),
    cardShadow = Color(0x24000000),
    tableStripe = Color(0xFFF4F5F5),
    folderIcon = Color(0xFF6CB4F5),
    keyIcon = Color(0xFFD99A00),
    codeTag = Color(0xFF0033B3),
    codeAttribute = Color(0xFF174AD4),
    codeValue = Color(0xFF067D17),
    codePunctuation = Color(0xFF080808),
    textSelection = Color(0xFFB3D7FF),
    textSelectionHandle = Color(0xFF3574F0),
)

internal val DarkColors = RobomoriColors(
    text = Color(0xFFEDEDF0),
    textSecondary = Color(0xFF9A9DAA),
    textDisabled = Color(0xFF5E6270),
    danger = Color(0xFFF16A77),
    success = Color(0xFF4ADE80),
    warning = Color(0xFFFB923C),
    info = Color(0xFF60A5FA),
    accent = Color(0xFF3574F0),
    highlight = Color(0xFF2E436E),
    separator = Color(0xFF393B40),
    link = Color(0xFF548AF7),
    controlTrack = Color(0xFF43454A),
    panelBackground = Color(0xFF161619),
    shelfBackground = Color(0xFF1C1C20),
    shelfBorder = Color(0xFF2E2F36),
    deviceIconBackground = Color(0xFF2A2B31),
    runningIconBackground = Color(0xFF34353C),
    runningIndicator = Color(0xFF4CBB5E),
    installedIndicator = Color(0xFF4CBB5E),
    deviceFrame = Color(0xFF0A0A0B),
    deviceFrameBorder = Color(0xFF55545A),
    deviceScreen = Color(0xFF202022),
    popupBackground = Color(0xFF1E1F24),
    popupBorder = Color(0xFF34363F),
    popupShadow = Color(0x99000000),
    toastBackground = Color(0xFFEDEDF0),
    toastText = Color(0xFF161619),
    cardBackground = Color(0xFF28282C),
    cardShadow = Color(0x00000000),
    tableStripe = Color(0xFF2F2F34),
    folderIcon = Color(0xFF5AA2E6),
    keyIcon = Color(0xFFF2C14E),
    codeTag = Color(0xFFD5B778),
    codeAttribute = Color(0xFFBCBEC4),
    codeValue = Color(0xFF6AAB73),
    codePunctuation = Color(0xFFBCBEC4),
    textSelection = Color(0xFF3F638B),
    textSelectionHandle = Color(0xFF548AF7),
)

@Immutable
data class RobomoriTypography(
    val body: TextStyle,
    val small: TextStyle,
    val heading: TextStyle,
    // Apple's macOS built-in text styles, with their tracking.
    val largeTitle: TextStyle,
    val title1: TextStyle,
    val title2: TextStyle,
    val title3: TextStyle,
    // Code, logs and commands.
    val code: TextStyle,
    val codeSmall: TextStyle,
)

/** The type styles, in Inter and JetBrains Mono, bundled with the app. */
@Composable
internal fun rememberRobomoriTypography(): RobomoriTypography {
    val inter = GoogleFonts.Inter
    val mono = GoogleFonts.JetBrainsMono
    return remember(inter, mono) { robomoriTypography(inter, mono) }
}

private fun robomoriTypography(inter: FontFamily, mono: FontFamily) = RobomoriTypography(
    body = TextStyle(fontFamily = inter, fontSize = 13.sp, fontWeight = FontWeight.Normal, lineHeight = 18.sp),
    small = TextStyle(fontFamily = inter, fontSize = 11.sp, fontWeight = FontWeight.Normal, lineHeight = 16.sp),
    heading = TextStyle(fontFamily = inter, fontSize = 14.sp, fontWeight = FontWeight.Bold, lineHeight = 20.sp),
    largeTitle = TextStyle(fontFamily = inter, fontSize = 26.sp, fontWeight = FontWeight.Bold, lineHeight = 32.sp, letterSpacing = 0.22.sp),
    title1 = TextStyle(fontFamily = inter, fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 26.sp, letterSpacing = (-0.26).sp),
    title2 = TextStyle(fontFamily = inter, fontSize = 17.sp, fontWeight = FontWeight.Bold, lineHeight = 22.sp, letterSpacing = (-0.43).sp),
    title3 = TextStyle(fontFamily = inter, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp, letterSpacing = (-0.23).sp),
    code = TextStyle(fontFamily = mono, fontSize = 12.sp, fontWeight = FontWeight.Normal, lineHeight = 18.sp),
    codeSmall = TextStyle(fontFamily = mono, fontSize = 11.sp, fontWeight = FontWeight.Normal, lineHeight = 15.sp),
)

/** The app's shadows, read with `RobomoriTheme.shadows`, drawn with Compose's `Modifier.dropShadow`. */
@Immutable
data class RobomoriShadows(
    /** Raised a little off what's under it, as macOS's toolbar buttons are: a soft, wide shadow, faint and a little low. */
    val elevated: Shadow,
)

internal val LightShadows = RobomoriShadows(
    elevated = Shadow(radius = 12.dp, color = Color.Black, offset = DpOffset(0.dp, 2.dp), alpha = 0.08f),
)

// Darker for the dark colors, where a faint shadow wouldn't show.
internal val DarkShadows = RobomoriShadows(
    elevated = Shadow(radius = 12.dp, color = Color.Black, offset = DpOffset(0.dp, 2.dp), alpha = 0.35f),
)
