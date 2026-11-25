package com.robomori

import java.io.File
import java.security.MessageDigest

/**
 * The app Robomori installs on a device to carry its apps' traffic to the Network tab: a VPN that hands each TCP
 * connection to [NetworkProxy] as SOCKS5, through `adb reverse`. Its source is in `app/vpn-helper`.
 *
 * It turns itself off when Robomori's control connection to it closes or goes quiet, so a device is never left
 * without a network when Robomori quits, crashes or is unplugged.
 */
internal object VpnHelper {
    const val packageName = "com.robomori.helper"

    val apk: ByteArray by lazy {
        VpnHelper::class.java.classLoader.getResourceAsStream("vpn-helper/robomori-helper.apk")?.use { it.readBytes() }
            ?: error("Robomori Network, the app Robomori installs on devices to record their traffic, is missing. Reinstall Robomori and try again.")
    }

    /** The bundled APK's SHA-256, to tell whether the copy on a device is the same build. */
    val sha256: String by lazy { MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) } }

    /** The bundled APK in a file to install, which the caller deletes. */
    fun stage(): File = File.createTempFile("robomori-helper", ".apk").apply { writeBytes(apk); deleteOnExit() }

    /**
     * Turns the VPN on, sending its connections to Robomori at [devicePort] on the device. A broadcast that only adb's
     * shell may send, since Android starts a VPN service only from the app itself. The 0x20 flag reaches the app even
     * while it's stopped, as it is just after installing, which broadcasts skip otherwise.
     */
    fun startCommand(devicePort: Int) = "am broadcast -f 0x20 -n $packageName/.Starter --ei port $devicePort"

    /** Turns the VPN off now, rather than when it notices Robomori's control connection is gone. */
    const val stopCommand = "am broadcast -f 0x20 -n $packageName/.Starter --ez stop true"
}
