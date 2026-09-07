package com.itg.template.retention

import android.app.Application
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import com.itg.template.R
import com.itg.template.app.OnboardKitSetup
import com.itg.template.ui.component.splash.SplashActivity
import io.onboardkit.OnboardingSdk
import io.onboardkit.ui.splash.SplashEntry
import io.retentionkit.RetentionKit
import io.retentionkit.core.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Actual app Splash and real capture ledger. The paused fixture stops before network/ad transport. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class RetentionSplashEntryTest {
    private lateinit var app: Application
    private lateinit var kit: RetentionKit
    private val screens = mutableListOf<ActivityController<SplashActivity>>()
    @Before fun prepare() {
        app = ApplicationProvider.getApplicationContext()
        OnboardingSdk.install(app)
        OnboardKitSetup.configure()
        kit = ExampleQa.prepare(app)
    }
    @After fun close() {
        screens.forEach { runCatching { it.destroy() } }
        RetentionRuntime.uninstallForTests()
    }
    private fun create(intent: Intent, saved: Bundle? = null): ActivityController<SplashActivity> =
        Robolectric.buildActivity(SplashActivity::class.java, intent).also {
            it.get().setTheme(R.style.Theme_Main)
            screens.add(it)
            it.create(saved)
        }
    private fun entry(intent: Intent) = (RetentionEntryCodec.read(intent) as RetentionEntryDecodeResult.Valid).entry

    @Test fun readyFixtureStillCreatesActualSplashAndRecreationKeepsMaterializedWidgetToken() {
        val reusable = RetentionEntry(RetentionEntrySource.WIDGET, "guide", "open_guide", mode = RetentionEntryMode.REUSABLE)
        val osTemplate = checkNotNull(kit.runtime.createEntryIntent(reusable))
        assertEquals(SplashActivity::class.java.name, osTemplate.component?.className)
        val first = create(Intent(osTemplate))
        val captured = entry(first.get().intent)
        assertEquals(RetentionEntryMode.ONCE, captured.mode)
        assertNotEquals(reusable.token, captured.token)
        assertEquals(SplashEntry.WIDGET, SplashEntry.from(first.get().intent.extras))
        assertFalse(first.get().intent.getBooleanExtra("ob_without_splash_ads", false))
        assertNull("Ready setup cannot start the feature before the real splash flow", shadowOf(first.get()).nextStartedActivity)
        val saved = Bundle()
        first.saveInstanceState(saved).destroy()
        screens.remove(first)
        val restored = create(Intent(osTemplate), saved)
        assertEquals(captured, entry(restored.get().intent))
        assertEquals(listOf(captured.token), kit.runtime.entries.pending().map { it.token })
    }

    @Test fun newIntentStartsAnotherSplashAttemptWithItsOwnSourceInsteadOfOpeningFeatureDirectly() {
        val first = create(checkNotNull(kit.runtime.createEntryIntent(RetentionEntry(RetentionEntrySource.DAILY, "notes", "open_notes"))))
        val next = RetentionEntry(RetentionEntrySource.WIDGET, "text_tools", "open_text")
        first.newIntent(checkNotNull(kit.runtime.createEntryIntent(next)))
        val launched = checkNotNull(shadowOf(first.get()).nextStartedActivity)
        assertEquals(SplashActivity::class.java.name, launched.component?.className)
        assertEquals(next, entry(launched))
        assertEquals(SplashEntry.WIDGET, SplashEntry.from(launched.extras))
        assertTrue(first.get().isFinishing)
    }

    @Test fun actualLifecycleCreatedSeesReusableTemplateBeforeSameSplashResumesWithOnceEntry() {
        val observations = mutableListOf<Triple<Activity, String, RetentionEntry>>()
        val observer = object : Application.ActivityLifecycleCallbacks {
            private fun record(activity: Activity, phase: String) {
                if (activity is SplashActivity) observations.add(Triple(activity, phase, entry(activity.intent)))
            }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = record(activity, "created")
            override fun onActivityResumed(activity: Activity) = record(activity, "resumed")
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        app.registerActivityLifecycleCallbacks(observer)
        try {
            val reusable = RetentionEntry(RetentionEntrySource.PINNED, "notes", "notes",
                campaignId = "pinned", instanceId = "pinned_fixture", mode = RetentionEntryMode.REUSABLE)
            val screen = create(checkNotNull(kit.runtime.createEntryIntent(reusable))).start().resume()
            val created = observations.single { it.second == "created" }
            val resumed = observations.single { it.second == "resumed" }
            assertSame(screen.get(), created.first)
            assertSame(created.first, resumed.first)
            assertEquals(reusable, created.third)
            assertEquals(RetentionEntryMode.ONCE, resumed.third.mode)
            assertNotEquals(created.third.token, resumed.third.token)
            assertEquals(created.third, resumed.third.copy(token = created.third.token,
                createdAtMillis = created.third.createdAtMillis, mode = RetentionEntryMode.REUSABLE))
            assertEquals(resumed.third, kit.runtime.entries.pending(resumed.third.token))
        } finally {
            app.unregisterActivityLifecycleCallbacks(observer)
        }
    }
}
