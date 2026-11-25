package com.robomori

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals

class OnboardingFunnelTest {
    @TempDir lateinit var root: File

    private class Recorder : Analytics {
        val events = mutableListOf<Pair<String, Map<String, String>>>()
        val pageviews = mutableListOf<String>()
        override fun track(event: String, metadata: Map<String, String>) { events += event to metadata }
        override fun pageview(path: String) { pageviews += path }
    }

    @Test fun `each onboarding page and skipping are funnel events`() {
        // The app keeps background work running, so it gets a scope of its own, cancelled at the end.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val fake = FakeAndroid(root)
        val analytics = Recorder()
        val app = AppState(scope, SettingsStore(File(root, "settings")), fake.settings, fake,
            license = AppLicense(scope, File(root, "license")), analytics = analytics)
        val onboarding = Onboarding(app, scope, pickFolder = { null }, downloadSize = { 0 })

        listOf(OnboardingAction.Next, OnboardingAction.Next, OnboardingAction.Next, OnboardingAction.Next, OnboardingAction.HaveLicense,
            OnboardingAction.Back, OnboardingAction.ContinueFree).forEach(onboarding::onAction)

        assertEquals(listOf(FunnelEvent.OnboardingStarted, FunnelEvent.OnboardingSdk, FunnelEvent.OnboardingPlatforms,
            FunnelEvent.OnboardingCommandLine, FunnelEvent.OnboardingLicense, FunnelEvent.OnboardingActivate, FunnelEvent.OnboardingSkipped),
            analytics.events.map { it.first })
        assertEquals(listOf("/", "/onboarding/welcome", "/onboarding/sdk", "/onboarding/platforms", "/onboarding/cli", "/onboarding/license",
            "/onboarding/activate", "/onboarding/license", "/"), analytics.pageviews)
        scope.cancel()
    }

    @Test fun `opening settings, switching pages and going back are pageviews`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val fake = FakeAndroid(root)
        val analytics = Recorder()
        val app = AppState(scope, SettingsStore(File(root, "settings")), fake.settings, fake,
            license = AppLicense(scope, File(root, "license")), analytics = analytics)

        app.openSettings(SettingsSection.License)
        app.openSettings(SettingsSection.Updates)
        app.closeSettings()
        app.closeAllFocus()

        assertEquals(listOf("/", "/settings/license", "/settings/updates", "/"), analytics.pageviews)
        scope.cancel()
    }

    @Test fun `each emulator that comes up is a device_started event, and one that fails to start is not`() = runBlocking {
        val fake = FakeAndroid(root)
        val devices = listOf("A", "B", "Broken").map { fake.device(it) }
        val executor = CommandExecutor { command, cancelled, output ->
            if (command.detached && command.args[1] == "Broken") error("Cannot start")
            fake.run(command, cancelled, output).also {
                if (command.detached) fake.active[command.args[1]] = "emulator-${5554 + 2 * fake.active.size}"
            }
        }
        val analytics = Recorder()
        val state = AppState(this, initial = fake.settings, executor = executor, license = licensedForTest(this, File(root, "license")),
            analytics = analytics)
        state.refresh()

        state.launch(devices)
        coroutineContext.job.children.toList().joinAll()

        assertEquals(2, analytics.events.count { it.first == FunnelEvent.DeviceStarted })
    }
}
