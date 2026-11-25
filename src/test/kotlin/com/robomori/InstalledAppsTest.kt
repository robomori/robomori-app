package com.robomori

import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Base64
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class InstalledAppsTest {
    @Test fun `reads each app's name, package, version and icon, ordered by name`() {
        val png = byteArrayOf(1, 2, 3)
        val output = listOf(
            "ROBOMORI_APP\tcom.example.weight\tWeight Control\t1.0\t1\t",
            "ROBOMORI_APP\tcom.example.pantry\tpantry\t2.1\t7\t" + Base64.getEncoder().encodeToString(png),
            "ROBOMORI_APP\tcom.example.unnamed\t\t\t3\t",
        ).joinToString("\r\n")

        val apps = parseInstalledApps(output)

        assertEquals(listOf("com.example.unnamed", "pantry", "Weight Control"), apps.map { it.name })
        assertEquals(listOf("com.example.unnamed", "com.example.pantry", "com.example.weight"), apps.map { it.packageName })
        assertEquals(listOf(null, "2.1", "1.0"), apps.map { it.versionName })
        assertEquals(listOf("3", "7", "1"), apps.map { it.versionCode })
        assertContentEquals(png, apps[1].icon)
        assertEquals(null, apps[2].icon)
    }

    @Test fun `reads each app's user and whether it's debuggable or the system's, listing the user's apps first`() {
        val apps = parseInstalledApps(listOf(
            "ROBOMORI_APP\tcom.android.settings\tAndroid Settings\t17\t1\t\t1000\t0\t1",
            "ROBOMORI_APP\tcom.example.release\tRelease\t1.0\t1\t\t10233\t0\t0",
            // A helper from before these fields listed debuggable apps only.
            "ROBOMORI_APP\tcom.example.pantry\tPantry\t1.0\t1\t",
        ).joinToString("\r\n"))
        assertEquals(listOf("Pantry", "Release", "Android Settings"), apps.map { it.name })
        assertEquals(listOf(null, 10233, 1000), apps.map { it.uid })
        assertEquals(listOf(true, false, false), apps.map { it.debuggable })
        assertEquals(listOf(false, false, true), apps.map { it.system })
    }

    @Test fun `ignores anything else the device prints`() {
        assertEquals(emptyList(), parseInstalledApps("WARNING: linker: something\nROBOMORI_APP\t\tNo package"))
    }

    @Test fun `each action runs its command on every selected app`() = runBlocking {
        val device = FakeDevice(PANTRY, CAMERA)
        val apps = InstalledAppsState(device.service, "emulator-5554", this)
        apps.refresh()
        val (camera, pantry) = apps.loaded

        apps.select(camera)
        apps.select(pantry, toggle = true)
        apps.request(AppAction.Launch, apps.contextTargets(pantry))
        coroutineContext.job.children.forEach { it.join() }
        apps.request(AppAction.ForceStop, apps.contextTargets(pantry))
        coroutineContext.job.children.forEach { it.join() }
        apps.request(AppAction.ClearCache, apps.contextTargets(camera))
        coroutineContext.job.children.forEach { it.join() }

        assertEquals(listOf(
            "monkey -p com.example.camera -c android.intent.category.LAUNCHER 1",
            "monkey -p com.example.pantry -c android.intent.category.LAUNCHER 1",
            "am force-stop com.example.camera", "am force-stop com.example.pantry",
            "run-as com.example.camera rm -rf cache code_cache", "run-as com.example.pantry rm -rf cache code_cache",
        ), device.actions)
        assertEquals(AppActionState.Done(AppActionRequest(AppAction.ClearCache, listOf(camera, pantry))), apps.action)
    }

    @Test fun `a menu opened outside the selection acts on that app alone`() = runBlocking {
        val apps = InstalledAppsState(FakeDevice(PANTRY, CAMERA).service, "emulator-5554", this)
        apps.refresh()
        val (camera, pantry) = apps.loaded
        apps.select(camera)

        assertEquals(listOf(pantry), apps.contextTargets(pantry))
        assertEquals(setOf("com.example.pantry"), apps.selection)
    }

    @Test fun `shift selects the apps between the last one clicked and this one`() = runBlocking {
        val apps = InstalledAppsState(FakeDevice(PANTRY, CAMERA, WEIGHT).service, "emulator-5554", this)
        apps.refresh()
        val (camera, _, weight) = apps.loaded

        apps.select(camera)
        apps.select(weight, extend = true)

        assertEquals(apps.loaded.map { it.packageName }.toSet(), apps.selection)
    }

    @Test fun `clearing data waits for confirmation`() = runBlocking {
        val device = FakeDevice(PANTRY)
        val apps = InstalledAppsState(device.service, "emulator-5554", this)
        apps.refresh()

        apps.request(AppAction.ClearData, apps.loaded)
        assertEquals(AppAction.ClearData, apps.confirming?.action)
        assertEquals(emptyList(), device.actions)

        apps.confirm()
        coroutineContext.job.children.forEach { it.join() }
        assertEquals(listOf("pm clear com.example.pantry"), device.actions)
    }

    @Test fun `uninstalled apps leave the list and the selection`() = runBlocking {
        val device = FakeDevice(PANTRY, CAMERA)
        val apps = InstalledAppsState(device.service, "emulator-5554", this)
        apps.refresh()
        val (camera, pantry) = apps.loaded
        apps.select(camera)
        apps.select(pantry, toggle = true)

        apps.request(AppAction.Uninstall, apps.contextTargets(camera))
        apps.confirm()
        coroutineContext.job.children.forEach { it.join() }

        assertEquals(listOf("pm uninstall com.example.camera", "pm uninstall com.example.pantry"), device.actions)
        assertEquals(emptyList(), apps.loaded)
        assertEquals(emptySet(), apps.selection)
    }

    @Test fun `a failure stops at that app and says why`() = runBlocking {
        val device = FakeDevice(PANTRY, CAMERA, uninstallOutput = "Failure [DELETE_FAILED_INTERNAL_ERROR]")
        val apps = InstalledAppsState(device.service, "emulator-5554", this)
        apps.refresh()
        val (camera, _) = apps.loaded

        apps.request(AppAction.Uninstall, apps.loaded)
        apps.confirm()
        coroutineContext.job.children.forEach { it.join() }

        val failed = apps.action as AppActionState.Failed
        assertEquals(camera, failed.app)
        assertEquals("Failure [DELETE_FAILED_INTERNAL_ERROR]", failed.cause.message)
        assertEquals(listOf("pm uninstall com.example.camera"), device.actions)

        apps.dismissFailure()
        assertEquals(AppActionState.Idle, apps.action)
    }

    private val InstalledAppsState.loaded get() = (snapshot as InstalledAppsSnapshot.Loaded).apps

    /** A device with the helper installed and [installed] on it, as helper lines. */
    private class FakeDevice(vararg installed: String, private val uninstallOutput: String = "Success") {
        private val listing = installed.toMutableList()
        val actions = mutableListOf<String>()
        val service = InstalledAppsService(AppSettings("/sdk", "/avds"), CommandExecutor { command, _, _ ->
            val script = command.args.last()
            when {
                "AppsHelper" in script -> (listing + "ROBOMORI_DONE").joinToString("\n")
                script.startsWith("pm uninstall") -> {
                    actions += script
                    if (uninstallOutput == "Success") listing.removeAll { "\t${script.substringAfterLast(' ')}\t" in it }
                    uninstallOutput
                }
                script.startsWith("pm clear") -> { actions += script; "Success" }
                script.startsWith("monkey") -> { actions += script; "Events injected: 1" }
                else -> { actions += script; "" }
            }
        }, helper = { DeviceHelperJar(File("device-helper.jar"), "test") })
    }

    private companion object {
        const val CAMERA = "ROBOMORI_APP\tcom.example.camera\tCamera\t1.0\t1\t"
        const val PANTRY = "ROBOMORI_APP\tcom.example.pantry\tPantry\t1.0\t1\t"
        const val WEIGHT = "ROBOMORI_APP\tcom.example.weight\tWeight Control\t1.0\t1\t"
    }
}
