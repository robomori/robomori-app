package com.robomori

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WelcomeNoticeTest {
    @Test fun `shows until dismissed, then never again`() {
        val file = Files.createTempDirectory("robomori-welcome").resolve("welcome-shown").toFile()
        assertTrue(WelcomeNotice(file).pending)
        WelcomeNotice(file).dismiss()
        assertFalse(WelcomeNotice(file).pending)
    }
}
