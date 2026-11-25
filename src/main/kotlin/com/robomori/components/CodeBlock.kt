package com.robomori.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.robomori.theme.RobomoriTheme
import kotlinx.coroutines.delay
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/** How [CodeBlock] colors code. [Xml] also suits HTML and SVG; [Script] suits JavaScript and CSS. */
enum class CodeLanguage { Plain, Xml, Json, Script }

/**
 * The colors of highlighted code: element names, keywords and JSON keys as [tag]; attribute names, numbers and literals
 * such as `true` as [attribute]; attribute values and strings as [value]; punctuation such as `<` and `=`; and comments.
 */
data class CodeColors(val tag: Color, val attribute: Color, val value: Color, val punctuation: Color, val comment: Color = punctuation)

/** Code people paste into their project, such as a config file, with a Copy button. The code can be selected, all of it with ⌘A; long lines scroll sideways. */
@Composable
fun CodeBlock(code: String, modifier: Modifier = Modifier, language: CodeLanguage = CodeLanguage.Plain, tag: String? = null) {
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) { delay(2000); copied = false }
    }
    val colors = CodeColors(RobomoriTheme.colors.codeTag, RobomoriTheme.colors.codeAttribute, RobomoriTheme.colors.codeValue,
        RobomoriTheme.colors.codePunctuation, RobomoriTheme.colors.textSecondary)
    val text = remember(code, language, colors) {
        // Very long code, such as a minified script, stays plain so it shows at once.
        if (code.length > HighlightLimit) AnnotatedString(code)
        else when (language) {
            CodeLanguage.Plain -> AnnotatedString(code)
            CodeLanguage.Xml -> highlightXml(code, colors)
            CodeLanguage.Json -> highlightJson(code, colors)
            CodeLanguage.Script -> highlightScript(code, colors)
        }
    }
    Box(modifier.then(if (tag != null) Modifier.testTag(tag) else Modifier).fillMaxWidth().clip(RoundedCornerShape(8.dp))
        .background(RobomoriTheme.colors.shelfBackground)) {
        // A read-only text field, so the code can be selected, including all of it with ⌘A, but not changed.
        var value by remember(text) { mutableStateOf(TextFieldValue(text)) }
        BasicTextField(value, { value = it.copy(annotatedString = text) },
            Modifier.horizontalScroll(rememberScrollState()).padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 88.dp),
            readOnly = true, cursorBrush = SolidColor(Color.Transparent),
            textStyle = RobomoriTheme.typography.code.copy(color = RobomoriTheme.colors.text))
        Button(onClick = {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(code), null)
            copied = true
        }, Modifier.align(Alignment.TopEnd).padding(6.dp), size = ButtonSize.Small,
            tag = if (tag != null) "$tag-Copy" else null) { Label(if (copied) "Copied" else "Copy") }
    }
}

/**
 * Code or other text people edit, such as a file from a device, in the code font on the code block's background. It
 * fills the height it's given and scrolls; long lines wrap, so it never needs to scroll sideways.
 */
@Composable
fun CodeBlock(state: TextFieldState, modifier: Modifier = Modifier, tag: String? = null) {
    val scroll = rememberScrollState()
    Box(modifier.then(if (tag != null) Modifier.testTag(tag) else Modifier).clip(RoundedCornerShape(8.dp))
        .background(RobomoriTheme.colors.shelfBackground)) {
        BasicTextField(state, Modifier.fillMaxSize().padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 16.dp),
            scrollState = scroll, cursorBrush = SolidColor(RobomoriTheme.colors.text),
            textStyle = RobomoriTheme.typography.code.copy(color = RobomoriTheme.colors.text))
        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

private val xmlToken = Regex("""<!--[\s\S]*?-->|(</?)([\w:.-]+)|([\w:.-]+)(\s*=\s*)("[^"]*"|'[^']*')|(/?>)""")

/** XML with its element names, attribute names and values, and punctuation in [colors]. Everything else stays as it is. */
internal fun highlightXml(code: String, colors: CodeColors): AnnotatedString = buildAnnotatedString {
    var end = 0
    fun styled(text: String, color: Color) = withStyle(SpanStyle(color = color)) { append(text) }
    for (match in xmlToken.findAll(code)) {
        append(code, end, match.range.first)
        val (open, element, attribute, equals, value, close) = match.destructured
        when {
            match.value.startsWith("<!--") -> styled(match.value, colors.punctuation)
            element.isNotEmpty() -> { styled(open, colors.punctuation); styled(element, colors.tag) }
            attribute.isNotEmpty() -> { styled(attribute, colors.attribute); styled(equals, colors.punctuation); styled(value, colors.value) }
            else -> styled(close, colors.punctuation)
        }
        end = match.range.last + 1
    }
    append(code, end, code.length)
}

private const val HighlightLimit = 200_000

private val jsonToken = Regex("""("(?:[^"\\]|\\.)*")(\s*:)?|(-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?|\btrue\b|\bfalse\b|\bnull\b)|([{}\[\],])""")

/** JSON with its keys, strings, numbers and literals, and punctuation in [colors]. */
internal fun highlightJson(code: String, colors: CodeColors): AnnotatedString = buildAnnotatedString {
    var end = 0
    fun styled(text: String, color: Color) = withStyle(SpanStyle(color = color)) { append(text) }
    for (match in jsonToken.findAll(code)) {
        append(code, end, match.range.first)
        val (string, colon, literal, punctuation) = match.destructured
        when {
            colon.isNotEmpty() -> { styled(string, colors.tag); styled(colon, colors.punctuation) }
            string.isNotEmpty() -> styled(string, colors.value)
            literal.isNotEmpty() -> styled(literal, colors.attribute)
            else -> styled(punctuation, colors.punctuation)
        }
        end = match.range.last + 1
    }
    append(code, end, code.length)
}

private val scriptKeywords = listOf("async", "await", "break", "case", "catch", "class", "const", "continue", "default",
    "delete", "do", "else", "export", "extends", "false", "finally", "for", "function", "if", "import", "in",
    "instanceof", "let", "new", "null", "of", "return", "static", "super", "switch", "this", "throw", "true", "try",
    "typeof", "undefined", "var", "void", "while", "yield", "important")

private val scriptToken = Regex("""(//[^\n]*|/\*[\s\S]*?\*/)|("(?:[^"\\\n]|\\.)*"|'(?:[^'\\\n]|\\.)*'|`(?:[^`\\]|\\.)*`)|(\b\d+(?:\.\d+)?\b)|\b(${scriptKeywords.joinToString("|")})\b""")

/** JavaScript or CSS with its comments, strings, numbers and keywords in [colors]. */
internal fun highlightScript(code: String, colors: CodeColors): AnnotatedString = buildAnnotatedString {
    var end = 0
    fun styled(text: String, color: Color) = withStyle(SpanStyle(color = color)) { append(text) }
    for (match in scriptToken.findAll(code)) {
        append(code, end, match.range.first)
        val (comment, string, number, keyword) = match.destructured
        when {
            comment.isNotEmpty() -> styled(comment, colors.comment)
            string.isNotEmpty() -> styled(string, colors.value)
            number.isNotEmpty() -> styled(number, colors.attribute)
            else -> styled(keyword, colors.tag)
        }
        end = match.range.last + 1
    }
    append(code, end, code.length)
}
