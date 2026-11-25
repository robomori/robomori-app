package com.robomori

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ApkInstallTest {
    @TempDir lateinit var directory: File

    @Test fun `installs with adb, replacing any installed version`() {
        val commands = mutableListOf<List<String>>()
        val service = InstalledAppsService(AppSettings("/sdk", "/avds"), CommandExecutor { command, _, _ ->
            commands += command.args
            "Performing Streamed Install\nSuccess"
        })
        val apk = File(directory, "notes.apk").apply { writeText("apk") }

        service.install("emulator-5554", apk)

        assertEquals(listOf("-s", "emulator-5554", "install", "-r", "-t", apk.absolutePath), commands.single())
    }

    @Test fun `says why Android turned an APK down`() {
        val service = InstalledAppsService(AppSettings("/sdk", "/avds"), CommandExecutor { _, _, _ ->
            throw AndroidCommandException("Performing Streamed Install\nadb: failed to install notes.apk: Failure " +
                "[INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package org.fossify.notes signatures do not match newer version]", 1)
        })

        val failure = assertFailsWith<ApkInstallException> { service.install("s", File(directory, "notes.apk")) }

        assertEquals(InstallFailure.SignedDifferently, failure.reason)
        assertEquals(InstallFailure.Downgrade, installFailure("Failure [INSTALL_FAILED_VERSION_DOWNGRADE]").first)
        assertEquals(InstallFailure.NewerAndroid, installFailure("Failure [INSTALL_FAILED_OLDER_SDK: Requires newer sdk version #36]").first)
        assertEquals(InstallFailure.OtherProcessor, installFailure("Failure [INSTALL_FAILED_NO_MATCHING_ABIS: Failed to extract native libraries]").first)
        assertEquals(InstallFailure.Other to "Failure [INSTALL_FAILED_ABORTED]", installFailure("Failure [INSTALL_FAILED_ABORTED]"))
    }

    @Test fun `takes only APK files`() {
        val apk = File(directory, "Notes.APK").apply { writeText("apk") }
        val text = File(directory, "notes.txt").apply { writeText("text") }
        assertEquals(listOf(apk), listOf(apk, text, directory).filter(::isApk))
    }
}
