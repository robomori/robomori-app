package com.robomori

import java.io.File

/** The onboarding shows once per computer, on the first launch, until it is finished or skipped. */
class WelcomeNotice(private val file: File = File(HostPlatform().configDirectory, "welcome-shown")) {
    val pending: Boolean get() = !file.exists()

    fun dismiss() {
        runCatching { atomicWrite(file, System.currentTimeMillis().toString()) }
    }
}
