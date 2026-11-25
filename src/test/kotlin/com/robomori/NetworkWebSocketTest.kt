package com.robomori

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetworkWebSocketTest {
    private val messages = mutableListOf<Pair<WebSocketOpcode, String>>()
    private val parser = WebSocketFrameParser({ opcode, data -> messages += opcode to data.bytes.decodeToString() })

    @Test fun `puts fragments together, unmasks them, and takes apart a ping between them`() {
        val wire = frame(1, "hel", fin = false, mask = byteArrayOf(1, 2, 3, 4)) +
            frame(9, "are you there", mask = byteArrayOf(9, 9, 9, 9)) +
            frame(0, "lo", mask = byteArrayOf(5, 6, 7, 8)) +
            frame(10, "pong") +
            frame(2, "binary")

        // Byte by byte, as bytes may arrive split anywhere.
        for (byte in wire) parser.feed(byteArrayOf(byte), 0, 1)

        assertEquals(listOf(WebSocketOpcode.Text to "hello", WebSocketOpcode.Binary to "binary"), messages)
    }

    @Test fun `reads frames with 16 and 64 bit lengths, and close frames`() {
        val medium = "m".repeat(300)
        val long = "l".repeat(70_000)
        val close = byteArrayOf(0x03, 0xE8.toByte()) + "bye".toByteArray()
        val wire = frame(1, medium) + frame(1, long, mask = byteArrayOf(7, 7, 7, 7)) + frame(8, close)

        parser.feed(wire, 0, wire.size)

        assertEquals(listOf(medium, long), messages.take(2).map { it.second })
        val recorded = WebSocketMessage(WebSocketDirection.Received, Instant.EPOCH, WebSocketOpcode.Close, CapturedBody(close, close.size.toLong()))
        assertEquals(1000, recorded.closeCode)
        assertEquals("bye", recorded.closeReason)
        assertEquals(WebSocketOpcode.Close, messages.last().first)
    }

    @Test fun `keeps only the start of a large message, counting the rest`() {
        var kept: CapturedBody? = null
        WebSocketFrameParser({ _, data -> kept = data }, limit = 4).feed(frame(1, "abcdefgh"), 0, frame(1, "abcdefgh").size)

        assertEquals("abcd", kept!!.bytes.decodeToString())
        assertEquals(8, kept!!.size)
    }

    @Test fun `stops reading, without failing, at bytes that aren't frames`() {
        val wire = byteArrayOf(0x83.toByte(), 0) + frame(1, "later")
        parser.feed(wire, 0, wire.size)
        assertEquals(emptyList(), messages)
    }

    @Test fun `records messages both ways as they pass, and when the socket closes`() {
        val sessions = mutableListOf<WebSocketSession>()
        var clock = Instant.EPOCH
        val recorder = WebSocketRecorder({ sessions += it }, now = { clock.also { clock = clock.plusMillis(5) } }, maxMessages = 2)
        val toSite = ByteArrayOutputStream()
        val toApp = ByteArrayOutputStream()

        recorder.sending(toSite).write(frame(1, "one", mask = byteArrayOf(1, 1, 1, 1)))
        recorder.receiving(toApp).write(frame(1, "two"))
        recorder.receiving(toApp).write(frame(2, "three"))
        recorder.close()

        // Relayed as they are.
        assertEquals(frame(1, "one", mask = byteArrayOf(1, 1, 1, 1)).toList(), toSite.toByteArray().toList())
        assertEquals((frame(1, "two") + frame(2, "three")).toList(), toApp.toByteArray().toList())
        val last = sessions.last()
        // The oldest message made room.
        assertEquals(listOf(WebSocketDirection.Received to "two", WebSocketDirection.Received to "three"),
            last.messages.map { it.direction to it.data.bytes.decodeToString() })
        assertEquals(listOf(Instant.EPOCH.plusMillis(5), Instant.EPOCH.plusMillis(10)), last.messages.map { it.at })
        assertEquals(Instant.EPOCH.plusMillis(15), last.closedAt)
        assertNull(sessions[2].closedAt)
    }

    @Test fun `leaves out the extensions a WebSocket request offers, so its messages aren't compressed`() {
        val upgrade = listOf("Host" to "example.com", "Upgrade" to "websocket", "Sec-WebSocket-Extensions" to "permessage-deflate")
        val plain = listOf("Host" to "example.com", "Sec-WebSocket-Extensions" to "x")

        assertEquals(listOf("Host" to "example.com", "Upgrade" to "websocket"), withoutWebSocketExtensions(upgrade))
        assertEquals(plain, withoutWebSocketExtensions(plain))
        assertTrue(isWebSocketUpgrade(listOf("upgrade" to " WebSocket ")))
    }

    @Test fun `writes a WebSocket's messages to HAR as Chrome does, and reads them back exactly`() {
        val started = Instant.parse("2026-11-22T10:00:00Z")
        val big = CapturedBody("abcd".toByteArray(), 4000)
        val socket = NetworkExchange(1, 3, started, "GET", "wss://example.com/chat", listOf("Upgrade" to "websocket"), CapturedBody.Empty,
            NetworkOutcome.Answered(NetworkResponse(101, "Switching Protocols", listOf("Upgrade" to "websocket"), CapturedBody.Empty, started.plusMillis(40))),
            webSocket = WebSocketSession(listOf(
                WebSocketMessage(WebSocketDirection.Sent, started.plusMillis(50), WebSocketOpcode.Text, body("""{"hi":1}""".toByteArray())),
                WebSocketMessage(WebSocketDirection.Received, started.plusMillis(60), WebSocketOpcode.Binary, body(byteArrayOf(0, 1, 2))),
                WebSocketMessage(WebSocketDirection.Received, started.plusMillis(70), WebSocketOpcode.Text, big),
            ), closedAt = started.plusMillis(90)))
        val open = socket.copy(id = 2, webSocket = WebSocketSession())

        val har = toHar(listOf(socket, open)) { null }

        val entry = Json.parseToJsonElement(har).jsonObject.getValue("log").jsonObject.getValue("entries").jsonArray[0].jsonObject
        assertEquals("websocket", entry.getValue("_resourceType").jsonPrimitive.content)
        val written = entry.getValue("_webSocketMessages").jsonArray.map { it.jsonObject }
        assertEquals(listOf("send", "receive", "receive"), written.map { it.getValue("type").jsonPrimitive.content })
        assertEquals(listOf(1, 2, 1), written.map { it.getValue("opcode").jsonPrimitive.int })
        assertEquals(listOf("""{"hi":1}""", "AAEC", "abcd"), written.map { it.getValue("data").jsonPrimitive.content })
        assertEquals(started.epochSecond + 0.05, written[0].getValue("time").jsonPrimitive.double, 1e-6)
        assertEquals(listOf(socket, open), parseHar(har).exchanges)
    }

    private fun body(bytes: ByteArray) = CapturedBody(bytes, bytes.size.toLong())

    private fun frame(opcode: Int, text: String, fin: Boolean = true, mask: ByteArray? = null) = frame(opcode, text.toByteArray(), fin, mask)

    /** A frame as RFC 6455 lays it out, [mask]ed as an app's frames are. */
    private fun frame(opcode: Int, payload: ByteArray, fin: Boolean = true, mask: ByteArray? = null): ByteArray {
        val out = ByteArrayOutputStream()
        out.write((if (fin) 0x80 else 0) or opcode)
        val maskBit = if (mask != null) 0x80 else 0
        when {
            payload.size < 126 -> out.write(maskBit or payload.size)
            payload.size < 65536 -> { out.write(maskBit or 126); out.write(payload.size shr 8); out.write(payload.size and 0xFF) }
            else -> { out.write(maskBit or 127); for (shift in 56 downTo 0 step 8) out.write(((payload.size.toLong() shr shift) and 0xFF).toInt()) }
        }
        if (mask != null) {
            out.write(mask)
            out.write(ByteArray(payload.size) { (payload[it].toInt() xor mask[it % 4].toInt()).toByte() })
        } else out.write(payload)
        return out.toByteArray()
    }
}
