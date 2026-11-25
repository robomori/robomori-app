package com.robomori

import androidx.compose.ui.graphics.Color
import com.robomori.components.CodeColors
import com.robomori.components.CodeLanguage
import com.robomori.components.highlightJson
import com.robomori.components.highlightScript
import com.robomori.components.highlightXml
import androidx.compose.ui.text.AnnotatedString
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class CodeHighlightTest {
    private val colors = CodeColors(tag = Color.Red, attribute = Color.Blue, value = Color.Green, punctuation = Color.Gray,
        comment = Color.LightGray)

    private fun AnnotatedString.colored() = spanStyles.map { text.substring(it.start, it.end) to it.item.color }.filter { it.first.isNotEmpty() }

    private fun spans(code: String) = highlightXml(code, colors).let { text ->
        text.spanStyles.map { text.text.substring(it.start, it.end) to it.item.color }
    }

    @Test fun `xml keeps its text and colors elements, attributes, values and punctuation`() {
        val code = """<domain includeSubdomains="false">localhost</domain>"""
        assertEquals(code, highlightXml(code, colors).text)
        assertEquals(listOf(
            "<" to Color.Gray, "domain" to Color.Red, "includeSubdomains" to Color.Blue, "=" to Color.Gray, "\"false\"" to Color.Green,
            ">" to Color.Gray, "</" to Color.Gray, "domain" to Color.Red, ">" to Color.Gray,
        ), spans(code).filter { it.first.isNotEmpty() })
    }

    @Test fun `namespaced attributes and self-closing tags are colored too`() {
        val spans = spans("""<application android:networkSecurityConfig="@xml/config" />""").filter { it.first.isNotEmpty() }
        assertEquals("android:networkSecurityConfig" to Color.Blue, spans[2])
        assertEquals("/>" to Color.Gray, spans.last())
    }

    @Test fun `json colors keys apart from strings, and numbers and literals apart from punctuation`() {
        val code = """{"name": "Ada", "age": 36, "admin": true, "pets": null}"""
        val text = highlightJson(code, colors)
        assertEquals(code, text.text)
        assertEquals(listOf("{" to Color.Gray, "\"name\"" to Color.Red, ":" to Color.Gray, "\"Ada\"" to Color.Green, "," to Color.Gray,
            "\"age\"" to Color.Red, ":" to Color.Gray, "36" to Color.Blue, "," to Color.Gray, "\"admin\"" to Color.Red, ":" to Color.Gray,
            "true" to Color.Blue, "," to Color.Gray, "\"pets\"" to Color.Red, ":" to Color.Gray, "null" to Color.Blue, "}" to Color.Gray),
            text.colored())
    }

    @Test fun `scripts color comments, strings, numbers and keywords, leaving names as they are`() {
        val code = "// greet\nconst name = 'Ada'; return name.length > 3"
        val text = highlightScript(code, colors)
        assertEquals(code, text.text)
        assertEquals(listOf("// greet" to Color.LightGray, "const" to Color.Red, "'Ada'" to Color.Green, "return" to Color.Red,
            "3" to Color.Blue), text.colored())
    }

    @Test fun `a body is colored as what its Content-Type says, or as JSON when it is JSON`() {
        assertEquals(CodeLanguage.Json, codeLanguageOf("[1, 2]", listOf("Content-Type" to "text/plain")))
        assertEquals(CodeLanguage.Json, codeLanguageOf("{}", listOf("content-type" to "application/problem+json")))
        assertEquals(CodeLanguage.Script, codeLanguageOf("let a = 1", listOf("Content-Type" to "text/javascript; charset=utf-8")))
        assertEquals(CodeLanguage.Script, codeLanguageOf("a { color: red }", listOf("Content-Type" to "text/css")))
        assertEquals(CodeLanguage.Xml, codeLanguageOf("<p>hi</p>", listOf("Content-Type" to "text/html")))
        assertEquals(CodeLanguage.Xml, codeLanguageOf("<svg/>", listOf("Content-Type" to "image/svg+xml")))
        assertEquals(CodeLanguage.Plain, codeLanguageOf("[not json", listOf("Content-Type" to "text/plain")))
    }
}
