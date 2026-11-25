package com.robomori

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class FileDatesTest {
    private val zone = ZoneId.of("Asia/Bangkok")
    private val now = Instant.parse("2026-11-21T08:00:00Z") // 15:00 in Bangkok

    private fun format(time: String, hours24: Boolean = true) =
        formatModified(Instant.parse(time), now, zone, Locale.US, hours24)

    @Test fun `names today and yesterday, as Finder does`() {
        assertEquals("Today, 13:48", format("2026-11-21T06:48:00Z"))
        assertEquals("Yesterday, 20:35", format("2026-11-20T13:35:00Z"))
    }

    @Test fun `shows older days as a short date`() {
        assertEquals("11/17/26, 21:19", format("2026-11-17T14:19:00Z"))
    }

    @Test fun `uses a 12-hour clock when the Mac does`() {
        assertEquals("11/17/26, 9:19 PM", format("2026-11-17T14:19:00Z", hours24 = false))
    }
}
