package com.robomori

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.zip.GZIPInputStream

/**
 * A protobuf or gRPC body laid out without its schema, as `protoc --decode_raw` does: each field by its number, nested
 * messages indented, strings quoted. Null when [headers] don't name protobuf or gRPC, or the bytes aren't a whole message.
 */
internal fun protobufText(body: CapturedBody, headers: List<Pair<String, String>>): String? {
    if (body.truncated || body.size == 0L) return null
    val mime = headers.firstOrNull { it.first.equals("Content-Type", true) }?.second?.substringBefore(';')?.trim()?.lowercase()
        ?: return null
    val bytes = body.decoded(headers) ?: return null
    return when {
        mime.startsWith("application/grpc") && !mime.endsWith("json") -> grpcText(bytes)
        "protobuf" in mime || mime.endsWith("+proto") || mime.endsWith("/proto") -> decodeProtobuf(bytes)
        else -> null
    }
}

/** A gRPC body's messages, each after its 5-byte prefix: a compressed flag, then the message's length. */
private fun grpcText(bytes: ByteArray): String? {
    val messages = mutableListOf<String>()
    var at = 0
    while (at < bytes.size) {
        if (bytes.size - at < 5) return null
        val compressed = bytes[at].toInt() == 1
        val length = ByteBuffer.wrap(bytes, at + 1, 4).int
        if (length < 0 || at + 5 + length > bytes.size) return null
        var message = bytes.copyOfRange(at + 5, at + 5 + length)
        // gRPC's usual compression; another one leaves the message unreadable.
        if (compressed) message = runCatching { GZIPInputStream(ByteArrayInputStream(message)).readBytes() }.getOrNull() ?: return null
        messages += decodeProtobuf(message) ?: return null
        at += 5 + length
    }
    return messages.joinToString("\n\n")
}

/** [bytes] as protobuf fields, one to a line, or null when they aren't a whole protobuf message. */
internal fun decodeProtobuf(bytes: ByteArray): String? {
    val fields = parseFields(bytes, depth = 0) ?: return null
    return buildString { write(fields, 0) }.trimEnd()
}

private sealed interface ProtoField {
    val number: Int
    data class Number(override val number: Int, val text: String) : ProtoField
    data class Text(override val number: Int, val value: String) : ProtoField
    data class Bytes(override val number: Int, val value: ByteArray) : ProtoField
    data class Message(override val number: Int, val fields: List<ProtoField>) : ProtoField
}

/** The fields [bytes] hold, or null when they don't end exactly at the end of a field. */
private fun parseFields(bytes: ByteArray, depth: Int): List<ProtoField>? {
    val fields = mutableListOf<ProtoField>()
    var at = 0
    fun varint(): Long? {
        var result = 0L
        var shift = 0
        while (at < bytes.size && shift < 64) {
            val byte = bytes[at++].toInt()
            result = result or ((byte and 0x7F).toLong() shl shift)
            if (byte and 0x80 == 0) return result
            shift += 7
        }
        return null
    }
    while (at < bytes.size) {
        val key = varint() ?: return null
        val number = (key ushr 3).toInt()
        if (number <= 0 || number > MaxFieldNumber) return null
        fields += when ((key and 7).toInt()) {
            0 -> ProtoField.Number(number, (varint() ?: return null).toULong().toString())
            1 -> {
                if (bytes.size - at < 8) return null
                val value = ByteBuffer.wrap(bytes, at, 8).order(ByteOrder.LITTLE_ENDIAN).long
                at += 8
                ProtoField.Number(number, "0x%016x".format(value))
            }
            2 -> {
                val length = varint()?.takeIf { it >= 0 && it <= bytes.size - at }?.toInt() ?: return null
                val value = bytes.copyOfRange(at, at + length)
                at += length
                lengthDelimited(number, value, depth)
            }
            5 -> {
                if (bytes.size - at < 4) return null
                val value = ByteBuffer.wrap(bytes, at, 4).order(ByteOrder.LITTLE_ENDIAN).int
                at += 4
                ProtoField.Number(number, "0x%08x".format(value))
            }
            else -> return null
        }
    }
    return fields
}

/**
 * A length-delimited field read as the schema most likely has it: readable text as a string, then a message when its
 * bytes are whole fields, then raw bytes. Text goes first, as short strings often happen to parse as fields too.
 */
private fun lengthDelimited(number: Int, value: ByteArray, depth: Int): ProtoField {
    readableText(value)?.let { return ProtoField.Text(number, it) }
    if (value.isNotEmpty() && depth < MaxDepth) parseFields(value, depth + 1)?.let { return ProtoField.Message(number, it) }
    return ProtoField.Bytes(number, value)
}

/** [value] as UTF-8 text, when it is that and has no control characters besides line breaks and tabs. */
private fun readableText(value: ByteArray): String? {
    val text = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(value)).toString()
    } catch (_: CharacterCodingException) { return null }
    // A message's first byte is a field's key, often a line break's, which strings rarely start with.
    if (text.firstOrNull()?.isISOControl() == true) return null
    return text.takeIf { it.none { char -> char.isISOControl() && char != '\n' && char != '\r' && char != '\t' } }
}

private fun StringBuilder.write(fields: List<ProtoField>, indent: Int) {
    val pad = "  ".repeat(indent)
    for (field in fields) {
        when (field) {
            is ProtoField.Number -> append(pad).append(field.number).append(": ").append(field.text).append('\n')
            is ProtoField.Text -> append(pad).append(field.number).append(": ").append(quoted(field.value)).append('\n')
            is ProtoField.Bytes -> append(pad).append(field.number).append(": ").append(escaped(field.value)).append('\n')
            is ProtoField.Message -> {
                append(pad).append(field.number).append(" {\n")
                write(field.fields, indent + 1)
                append(pad).append("}\n")
            }
        }
    }
}

private fun quoted(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
    .replace("\r", "\\r").replace("\t", "\\t") + "\""

/** Bytes as protoc writes them: printable ASCII as it is, the rest as octal escapes. */
private fun escaped(bytes: ByteArray) = "\"" + bytes.joinToString("") { byte ->
    val value = byte.toInt() and 0xFF
    when {
        value == '"'.code -> "\\\""
        value == '\\'.code -> "\\\\"
        value in 0x20..0x7E -> value.toChar().toString()
        else -> "\\%03o".format(value)
    }
} + "\""

/** The largest field number protobuf allows. */
private const val MaxFieldNumber = (1 shl 29) - 1
/** How deep nested messages are read, so a body can't make the reader recurse without end. */
private const val MaxDepth = 64
