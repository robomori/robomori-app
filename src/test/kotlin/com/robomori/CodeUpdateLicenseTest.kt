package com.robomori

import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodeUpdateLicenseTest {
    private val lastDay = Instant.parse("2027-11-07T00:00:00Z")

    @Test fun `code updates published within the update period are allowed`() {
        assertTrue(codeUpdateAllowed(Instant.parse("2027-06-01T00:00:00Z"), lastDay))
        assertTrue(codeUpdateAllowed(lastDay, lastDay))
    }

    @Test fun `code updates published after the update period are not`() {
        assertFalse(codeUpdateAllowed(Instant.parse("2027-11-08T00:00:00Z"), lastDay))
    }

    @Test fun `without a license every code update is allowed, as in the launch phase`() {
        assertTrue(codeUpdateAllowed(Instant.parse("2030-01-01T00:00:00Z"), null))
    }
}
