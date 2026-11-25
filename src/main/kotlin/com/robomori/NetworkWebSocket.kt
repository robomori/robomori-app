package com.robomori

import java.io.OutputStream
import java.time.Instant

internal enum class WebSocketDirection { Sent, Received }

/** The kinds of WebSocket message recorded, by their opcode. Pings and pongs aren't recorded, as in Chrome. */
internal enum class WebSocketOpcode(val code: Int) {
    Text(1), Binary(2), Close(8);

    companion object { fun of(code: Int) = entries.firstOrNull { it.code == code } }
}

/** One whole message, its fragments put together, with its payload unmasked and kept up to a limit. */
internal data class WebSocketMessage(val direction: WebSocketDirection, val at: Instant, val opcode: WebSocketOpcode, val data: CapturedBody) {
    /** The message's text, for a text message. */
    val text: String? get() = if (opcode == WebSocketOpcode.Text) data.bytes.decodeToString() else null
    /** The status code a close message gives, when it gives one. */
    val closeCode: Int? get() =
        if (opcode == WebSocketOpcode.Close && data.bytes.size >= 2) ((data.bytes[0].toInt() and 0xFF) shl 8) or (data.bytes[1].toInt() and 0xFF) else null
    /** The reason a close message gives, if any. */
    val closeReason: String get() =
        if (opcode == WebSocketOpcode.Close && data.bytes.size > 2) data.bytes.copyOfRange(2, data.bytes.size).decodeToString() else ""
}

/** The messages of a request that became a WebSocket, oldest first, and when it closed, or null while it's open. */
internal data class WebSocketSession(val messages: List<WebSocketMessage> = emptyList(), val closedAt: Instant? = null)

/** Whether a request asks to become a WebSocket. */
internal fun isWebSocketUpgrade(headers: List<Pair<String, String>>) =
    headers.any { (name, value) -> name.equals("Upgrade", true) && value.trim().equals("websocket", true) }

/**
 * The headers of a request to [isWebSocketUpgrade], without the extensions it offers. The one apps offer is
 * permessage-deflate, which compresses each message with a window carried over from the ones before it; without it, the
 * site sends messages as they are, so Robomori reads every one from its own frames, and the app works the same.
 */
internal fun withoutWebSocketExtensions(headers: List<Pair<String, String>>): List<Pair<String, String>> =
    if (!isWebSocketUpgrade(headers)) headers else headers.filterNot { it.first.equals("Sec-WebSocket-Extensions", true) }

/**
 * Reads the frames of one direction of a WebSocket from bytes [feed] as they pass, and calls [onMessage] with each
 * whole message. A message's fragments are put together, client frames are unmasked, and control frames between
 * fragments are taken apart from them. Bytes it can't make sense of stop the reading, never the connection.
 */
internal class WebSocketFrameParser(
    private val onMessage: (WebSocketOpcode, CapturedBody) -> Unit,
    private val limit: Int = 1024 * 1024,
) {
    private val header = ByteArray(14)
    private var headerRead = 0
    private var payloadLeft = -1L
    private var masked = false
    private val mask = ByteArray(4)
    private var maskAt = 0L
    private var fin = false
    private var opcode = 0
    /** The data message being put together, and its opcode. */
    private var message: BodyCapture? = null
    private var messageOpcode = 0
    /** The control frame being read, which isn't fragmented and holds at most 125 bytes. */
    private var control: BodyCapture? = null
    private var broken = false
    private val scratch = ByteArray(16 * 1024)

    fun feed(bytes: ByteArray, offset: Int, length: Int) {
        if (broken) return
        try {
            var at = offset
            val end = offset + length
            while (at < end && !broken) {
                if (payloadLeft < 0) {
                    header[headerRead++] = bytes[at++]
                    if (headerComplete()) startPayload()
                } else {
                    val count = minOf(payloadLeft, (end - at).toLong(), scratch.size.toLong()).toInt()
                    payload(bytes, at, count)
                    at += count
                    payloadLeft -= count
                    if (payloadLeft == 0L) endFrame()
                }
            }
        } catch (_: RuntimeException) {
            broken = true
        }
    }

    /** Whether [header] holds the whole of a frame's header, which its first two bytes give the length of. */
    private fun headerComplete(): Boolean {
        if (headerRead < 2) return false
        val length7 = header[1].toInt() and 0x7F
        val needed = 2 + (if (length7 == 126) 2 else if (length7 == 127) 8 else 0) + (if (header[1].toInt() and 0x80 != 0) 4 else 0)
        return headerRead >= needed
    }

    private fun startPayload() {
        fin = header[0].toInt() and 0x80 != 0
        opcode = header[0].toInt() and 0x0F
        masked = header[1].toInt() and 0x80 != 0
        var at = 2
        payloadLeft = when (val length7 = header[1].toInt() and 0x7F) {
            126 -> (((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)).toLong().also { at = 4 }
            127 -> (0 until 8).fold(0L) { total, i -> (total shl 8) or (header[2 + i].toLong() and 0xFF) }.also { at = 10 }
            else -> length7.toLong()
        }
        if (masked) header.copyInto(mask, 0, at, at + 4)
        maskAt = 0
        headerRead = 0
        when {
            opcode >= 8 -> { if (payloadLeft > 125 || !fin) broken = true else control = BodyCapture(limit) }
            opcode == 0 -> if (message == null) broken = true
            opcode == 1 || opcode == 2 -> { message = BodyCapture(limit); messageOpcode = opcode }
            else -> broken = true
        }
        if (!broken && payloadLeft == 0L) endFrame()
    }

    private fun payload(bytes: ByteArray, offset: Int, count: Int) {
        val target = if (opcode >= 8) control else message
        if (!masked) return target!!.add(bytes, offset, count)
        for (i in 0 until count) scratch[i] = (bytes[offset + i].toInt() xor mask[((maskAt + i) % 4).toInt()].toInt()).toByte()
        maskAt += count
        target!!.add(scratch, 0, count)
    }

    private fun endFrame() {
        payloadLeft = -1
        if (opcode >= 8) {
            val payload = control!!.captured
            control = null
            if (opcode == WebSocketOpcode.Close.code) onMessage(WebSocketOpcode.Close, payload)
            return
        }
        if (!fin) return
        val whole = message!!.captured
        message = null
        onMessage(WebSocketOpcode.of(messageOpcode)!!, whole)
    }
}

/**
 * Records a WebSocket's messages both ways as [sending] and [receiving] pass its bytes on, and hands the session to
 * [onChange] after each message and once it closes. It keeps the latest [maxMessages].
 */
internal class WebSocketRecorder(
    private val onChange: (WebSocketSession) -> Unit,
    private val now: () -> Instant = Instant::now,
    private val maxMessages: Int = 5000,
) {
    private var session = WebSocketSession()
    private val sent = WebSocketFrameParser({ opcode, data -> add(WebSocketDirection.Sent, opcode, data) })
    private val received = WebSocketFrameParser({ opcode, data -> add(WebSocketDirection.Received, opcode, data) })

    /** [target] with the app's frames to the site read as they're written to it. */
    fun sending(target: OutputStream): OutputStream = Tap(target, sent)
    /** [target] with the site's frames to the app read as they're written to it. */
    fun receiving(target: OutputStream): OutputStream = Tap(target, received)

    @Synchronized private fun add(direction: WebSocketDirection, opcode: WebSocketOpcode, data: CapturedBody) {
        val messages = session.messages + WebSocketMessage(direction, now(), opcode, data)
        session = session.copy(messages = if (messages.size > maxMessages) messages.drop(messages.size - maxMessages) else messages)
        onChange(session)
    }

    @Synchronized fun close() {
        session = session.copy(closedAt = now())
        onChange(session)
    }

    /**
     * Reads bytes as frames, then passes them on to [target]. Reading first records a message before the other side
     * can answer it, so the messages stay in the order they happened.
     */
    private class Tap(private val target: OutputStream, private val parser: WebSocketFrameParser) : OutputStream() {
        override fun write(b: Int) { parser.feed(byteArrayOf(b.toByte()), 0, 1); target.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) { parser.feed(b, off, len); target.write(b, off, len) }
        override fun flush() = target.flush()
        override fun close() = target.close()
    }
}

/** When the request ended: its response arrived whole, its WebSocket closed, or its connection passed through closed. */
internal val NetworkExchange.finishedAt: Instant? get() = when (val outcome = outcome) {
    is NetworkOutcome.Answered -> if (webSocket != null) webSocket.closedAt else outcome.response.finishedAt
    is NetworkOutcome.Encrypted -> outcome.finishedAt
    else -> null
}

/** Whether the request is still going: waiting for its response, or a WebSocket or unread connection still open. */
internal val NetworkExchange.inProgress: Boolean get() = when (val outcome = outcome) {
    NetworkOutcome.Pending -> true
    is NetworkOutcome.Answered -> webSocket != null && webSocket.closedAt == null
    is NetworkOutcome.Encrypted -> outcome.finishedAt == null
    else -> false
}
