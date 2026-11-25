package com.robomori

/**
 * Turns a running device's screen a quarter turn. An emulator turns through its motion sensors, as if it were turned
 * in your hand, so auto-rotate keeps working. A physical device can't be turned for you, so its rotation setting is
 * fixed instead, which turns its auto-rotate off.
 */
internal fun AndroidService.rotateScreen(serial: String, clockwise: Boolean) {
    if (serial.startsWith("emulator-")) {
        // The emulator console turns the device a quarter turn clockwise each time.
        repeat(if (clockwise) 1 else 3) { command(toolchain.adb, listOf("-s", serial, "emu", "rotate")) }
        return
    }
    val current = displayRotation(serial)
    // Android counts rotations counterclockwise, so a clockwise turn goes one back.
    val target = Math.floorMod(current + if (clockwise) -1 else 1, 4)
    // Set the rotation before turning auto-rotate off. The other way round, the device would first turn to the rotation
    // it last saved, then to the new one.
    command(toolchain.adb, listOf("-s", serial, "shell", "settings", "put", "system", "user_rotation", target.toString()))
    command(toolchain.adb, listOf("-s", serial, "shell", "settings", "put", "system", "accelerometer_rotation", "0"))
}
