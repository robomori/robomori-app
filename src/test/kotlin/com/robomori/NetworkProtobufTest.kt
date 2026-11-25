package com.robomori

import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NetworkProtobufTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
    private fun body(bytes: ByteArray) = CapturedBody(bytes, bytes.size.toLong())

    /** A Google API's error: code 16, and why. */
    private val error: ByteArray = ByteArrayOutputStream().apply {
        write(bytes(0x08, 0x10, 0x12, 0x1d))
        write("Request is missing credential".toByteArray())
    }.toByteArray()

    @Test fun `reads a message's fields by number, as protoc --decode_raw does`() {
        assertEquals("1: 16\n2: \"Request is missing credential\"",
            protobufText(body(error), listOf("Content-Type" to "application/x-protobuf")))
    }

    @Test fun `indents nested messages and writes other bytes escaped`() {
        val nested = bytes(0x1a, 0x06, 0x08, 0x01, 0x22, 0x02, 0x00, 0xff, 0x2d, 0x01, 0x00, 0x00, 0x00)
        assertEquals("3 {\n  1: 1\n  4: \"\\000\\377\"\n}\n5: 0x00000001", decodeProtobuf(nested))
    }

    @Test fun `reads each message of a gRPC body after its prefix`() {
        val framed = ByteArrayOutputStream().apply {
            repeat(2) { write(bytes(0, 0, 0, 0, error.size)); write(error) }
        }.toByteArray()
        assertEquals("1: 16\n2: \"Request is missing credential\"\n\n1: 16\n2: \"Request is missing credential\"",
            protobufText(body(framed), listOf("content-type" to "application/grpc+proto")))
    }

    @Test fun `leaves alone bodies that aren't protobuf, or aren't whole`() {
        assertNull(protobufText(body(error), listOf("Content-Type" to "application/octet-stream")))
        assertNull(protobufText(body(error.copyOf(10)), listOf("Content-Type" to "application/x-protobuf")))
        assertNull(protobufText(CapturedBody(error, 5000), listOf("Content-Type" to "application/x-protobuf")))
    }
}
