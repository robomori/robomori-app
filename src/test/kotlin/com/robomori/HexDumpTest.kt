package com.robomori

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class HexDumpTest {
    @Test fun `lays out bytes with their offsets, in hex and as text, as a hex viewer does`() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A,
            0, 0, 0, 0x0D, 'I'.code.toByte(), 'H'.code.toByte(), 'D'.code.toByte(), 'R'.code.toByte(), 0, 1)
        assertEquals(
            "00000000  89 50 4e 47 0d 0a 1a 0a  00 00 00 0d 49 48 44 52  .PNG........IHDR\n" +
            "00000010  00 01                                             ..",
            hexDump(png))
    }

    @Test fun `stops at the limit`() {
        assertEquals(1, hexDump(ByteArray(100), limit = 16).lines().size)
    }
}
