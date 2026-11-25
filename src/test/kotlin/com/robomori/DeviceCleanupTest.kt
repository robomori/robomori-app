package com.robomori

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeviceCleanupTest {
    private val settings = AppSettings("/sdk", "/avds")

    /** Runs a cleanup against a device that answers [answers], and returns what it was asked to do, in order. */
    private fun clean(answers: (String) -> String): Pair<DeviceCleanup, List<String>> {
        val commands = mutableListOf<String>()
        val service = DeviceCleanupService(settings, CommandExecutor { command, _, _ ->
            val args = command.args.drop(2)
            val line = args.joinToString(" ")
            commands += line
            answers(line)
        })
        return service.clean("emulator-5556") to commands
    }

    @Test fun `takes off a rooted device its helper, certificate, proxy setting and files`() {
        val (result, commands) = clean { command ->
            when {
                command == "shell pm list packages" -> "package:com.android.chrome\npackage:com.robomori.helper\n"
                command == "shell settings get global http_proxy" -> "127.0.0.1:$NetworkProxyDevicePort\n"
                command == "shell su 0 id" -> "uid=0(root) gid=0(root)"
                else -> ""
            }
        }

        assertEquals(DeviceCleanup.Removed, result)
        assertEquals(listOf(
            "shell pm list packages",
            "shell pm uninstall com.robomori.helper",
            "shell settings get global http_proxy",
            "shell settings put global http_proxy :0",
            "reverse --remove tcp:$NetworkProxyDevicePort",
            "shell su 0 id",
        ), commands.take(6))
        assertTrue(commands[6].startsWith("shell su 0 sh -c ") && "umount" in commands[6], commands[6])
        assertEquals("shell rm -rf /data/local/tmp/robomori-*", commands[7])
        assertEquals(8, commands.size)
    }

    @Test fun `leaves another proxy and absent helpers alone, and goes on past steps that fail`() {
        val (result, commands) = clean { command ->
            when (command) {
                "shell pm list packages" -> "package:com.android.chrome\n"
                "shell settings get global http_proxy" -> "10.0.0.2:8888\n"
                "reverse --remove tcp:$NetworkProxyDevicePort" -> throw AndroidCommandException("listener not found", 1)
                "shell su 0 id" -> throw AndroidCommandException("su: not found", 127)
                else -> ""
            }
        }

        // Without root, what Robomori laid over the system's certificates in memory stays until the device restarts.
        assertEquals(DeviceCleanup.RemovedUntilRestart, result)
        assertEquals(listOf(
            "shell pm list packages",
            "shell settings get global http_proxy",
            "reverse --remove tcp:$NetworkProxyDevicePort",
            "shell su 0 id",
            "shell rm -rf /data/local/tmp/robomori-*",
        ), commands)
    }
}
