package com.robomori

internal class EmulatorFoldControl(
    private val adb: (List<String>) -> String,
    private val pause: () -> Unit = { Thread.sleep(50) },
) {
    fun fold(folded: Boolean) {
        val keepUnlocked = folded && runCatching {
            adb(listOf("shell", "locksettings", "get-disabled")).trim() == "true" && keyguardShowing() == false
        }.getOrDefault(false)

        adb(listOf("emu", if (folded) "fold" else "unfold"))
        if (!keepUnlocked) return

        // Android can force a dismissible keyguard on fold even with screen lock disabled.
        // Wait for that asynchronous request, rather than dismissing before it arrives.
        repeat(20) {
            when (keyguardShowing()) {
                true -> {
                    adb(listOf("shell", "wm", "dismiss-keyguard"))
                    return
                }
                null -> return
                false -> pause()
            }
        }
    }

    private fun keyguardShowing(): Boolean? =
        Regex("(?m)^\\s*showing=(true|false)\\s*$")
            .find(adb(listOf("shell", "dumpsys", "window", "policy")))
            ?.groupValues?.get(1)?.toBooleanStrict()
}
