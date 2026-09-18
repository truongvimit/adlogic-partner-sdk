package io.onboardkit.remote

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.swipeLeft
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.viewpager2.widget.ViewPager2
import com.ads.module.config.settings.AdBehavior
import com.ads.module.config.settings.SettingsDocument
import com.ads.module.consent.ConsentCenter
import com.ads.module.helper.banner.BannerAdConfig
import io.onboardkit.OnboardingSdk
import io.onboardkit.R
import io.onboardkit.ads.AdEventListener
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.NativeAdRequest
import io.onboardkit.ads.ObInterstitialCallback
import io.onboardkit.ads.OnboardingAdProvider
import io.onboardkit.config.AdFullScreenStepDefinition
import io.onboardkit.config.AdLoadStrategy
import io.onboardkit.config.AdsConfig
import io.onboardkit.config.BannerAdUnit
import io.onboardkit.config.BehaviorConfig
import io.onboardkit.config.ContentStepDefinition
import io.onboardkit.config.InterstitialAdUnit
import io.onboardkit.config.LanguageConfig
import io.onboardkit.config.NativeAdUnit
import io.onboardkit.config.SplashConfig
import io.onboardkit.config.onboardKitConfig
import io.onboardkit.core.StepId
import io.onboardkit.core.analytics.AnalyticsEvent
import io.onboardkit.core.analytics.AnalyticsPlugin
import io.onboardkit.ui.onboarding.ObOnboardingHostActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/** Each method/mode runs in a fresh process, in the isolated io.onboardkit.test package. */
@RunWith(AndroidJUnit4::class)
class GroupedSettingsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun sparseRemotePreservesHostOptionsAndLegacyFlags() = runBlocking {
        OnboardingSettings.initialize(app)
        OnboardingSettings.document.acceptSuccessfulFetch(null)
        AdBehavior.document.acceptSuccessfulFetch(null)
        val host = onboardKitConfig {
            splash = SplashConfig(minDisplayTimeMs = 4_321, remoteFetchTimeoutMs = 2_345,
                adLoadStrategy = AdLoadStrategy.SAME_TIME)
            language = LanguageConfig(tapHintEnabled = false, confirmVisibleBeforeSelect = true)
            behavior = BehaviorConfig(lockPagerSwipe = false, backNavigatesBack = false)
            ads = AdsConfig(enabled = false, afterOnboardingInterstitialEnabled = false)
            step(AdFullScreenStepDefinition(StepId.OB3, skipButtonDelaySec = 4,
                autoNextEnabled = false, autoNextDelayMs = 9_876))
        }.getOrThrow()
        val initial = OnboardingSettings.resolve(host)
        assertEquals(host.splash, initial.splash)
        assertEquals(host.language, initial.language)
        assertEquals(host.behavior, initial.behavior)
        assertFalse(initial.ads.enabled)
        assertEquals(9_876L, (initial.steps.single() as AdFullScreenStepDefinition).autoNextDelayMs)
        val legacy = RemoteFlags(splashMinDisplayMs = 4_567, enableLanguageNative2 = false)
        assertEquals(legacy, OnboardingSettings.resolveFlags(legacy))

        val banner = BannerAdConfig("controlled-banner", true, false).apply { autoReloadTime = 22_345 }
        assertEquals(22_345L, banner.autoReloadTime)
        assertFalse(banner.canReloadAds)
        assertTrue(AdBehavior.document.acceptSuccessfulFetch(
            """{"banner":{"reload":{"allowed":true,"interval_ms":12000}}}""",
        ))
        assertEquals(22_345L, banner.autoReloadTime) // Removed alias is ignored.
        assertTrue(banner.canReloadAds)
        AdBehavior.document.acceptSuccessfulFetch(null)
        assertEquals(22_345L, banner.autoReloadTime)
        assertFalse(banner.canReloadAds)

        assertTrue(OnboardingSettings.document.acceptSuccessfulFetch(
            """{"schema_version":1,"flow":{"ads_enabled":true},"splash":{"timing":{"min_display_ms":0}},"onboarding":{"navigation":{"lock_pager_swipe":true}},"lfo":{"native2":{"enabled":true}}}""",
        ))
        val updated = OnboardingSettings.resolve(host)
        assertEquals(0L, updated.splash.minDisplayTimeMs)
        assertEquals(2_345L, updated.splash.remoteFetchTimeoutMs)
        assertEquals(AdLoadStrategy.SAME_TIME, updated.splash.adLoadStrategy)
        assertTrue(updated.behavior.lockPagerSwipe)
        assertFalse(updated.behavior.backNavigatesBack)
        assertFalse("Remote cannot undo the host ads veto", updated.ads.enabled)
        assertEquals(0L, OnboardingSettings.resolveFlags(legacy).splashMinDisplayMs)
        assertTrue(OnboardingSettings.resolveFlags(legacy).enableLanguageNative2)
    }

    @Test
    fun invalidMissingAndFailedFetchKeepFallbacks() = runBlocking {
        val document = cacheDocument().apply { initialize(app) }
        assertTrue(document.acceptSuccessfulFetch("""{"banner":{"reload":{"enabled":true,"interval_ms":72000}}}"""))
        assertFalse(document.acceptSuccessfulFetch("{broken"))
        assertFalse(document.acceptSuccessfulFetch("""{"schema_version":99,"banner":{"reload":{"interval_ms":12000}}}"""))
        assertEquals(72_000L, document.snapshot.long("banner.reload.interval_ms"))
        assertTrue(document.snapshot.boolean("banner.reload.enabled"))

        // A completed document may omit fields or contain invalid leaves; both inherit local values.
        assertTrue(document.acceptSuccessfulFetch("""{"banner":{"reload":{"enabled":false,"interval_ms":-1}},"unknown":true}"""))
        assertFalse(document.snapshot.boolean("banner.reload.enabled"))
        assertEquals(30_000L, document.snapshot.long("banner.reload.interval_ms"))
        assertTrue(document.acceptSuccessfulFetch("""{"banner":{"reload":{"enabled":null}}}"""))
        assertFalse(document.snapshot.boolean("banner.reload.enabled"))
        assertEquals(30_000L, document.snapshot.long("banner.reload.interval_ms"))

        OnboardingSettings.initialize(app)
        assertTrue(OnboardingSettings.document.acceptSuccessfulFetch("""{"splash":{"timing":{"min_display_ms":4321}}}"""))
        val legacy = RemoteFlags(adsSplashInter = false, splashSlotMinVisibleMs = 123)
        val syncer = RemoteConfigSyncer(app) { null }
        syncer.applySnapshot(legacy)
        assertFalse(syncer.fetchAndSync(100))
        assertEquals(legacy, syncer.flags.value)
        assertEquals(4_321L, OnboardingSettings.number("splash.timing.min_display_ms"))
        assertTrue(OnboardingSettings.document.acceptSuccessfulFetch(null))
        assertEquals(3_000L, OnboardingSettings.number("splash.timing.min_display_ms"))
    }

    @Test
    fun acceptedDocumentSurvivesColdProcess() = runBlocking {
        val metadata = app.getSharedPreferences("grouped_device_process", Context.MODE_PRIVATE)
        val document = cacheDocument().apply { initialize(app) }
        if (InstrumentationRegistry.getArguments().getString("groupedCachePhase") == "read") {
            val writer = metadata.getInt("writer_pid", -1)
            assertNotEquals("Run the write phase first", -1, writer)
            assertNotEquals("Read must run in a fresh Android process", writer, Process.myPid())
            assertEquals(72_000L, document.snapshot.long("banner.reload.interval_ms"))
            assertTrue(document.snapshot.boolean("banner.reload.enabled"))
        } else {
            assertTrue(document.acceptSuccessfulFetch("""{"banner":{"reload":{"enabled":true,"interval_ms":72000}}}"""))
            assertTrue(app.getSharedPreferences("adlogic_settings_grouped_device_cache", Context.MODE_PRIVATE).edit().commit())
            assertTrue(metadata.edit().putInt("writer_pid", Process.myPid()).commit())
        }
    }

    @Test
    fun pagerSwipeAndFullscreenSkipUseResolvedRemoteWithoutChangingCompletion() = runBlocking {
        val remote = InstrumentationRegistry.getArguments().getString("groupedMode") == "remote"
        val completed = CopyOnWriteArrayList<AnalyticsEvent.StepCompleted>()
        instrumentation.runOnMainSync {
            assertFalse("Use a fresh instrumentation process", OnboardingSdk.isReady())
            OnboardingSdk.install(app) {
                adProvider = ReadyNativeProvider()
                trackkitAutoTracking(false)
                analyticsPlugin(AnalyticsPlugin { if (it is AnalyticsEvent.StepCompleted) completed += it })
            }
            OnboardingSdk.configure(onboardKitConfig {
                behavior = BehaviorConfig(lockPagerSwipe = true)
                ads = AdsConfig(fullScreenStepNative = NativeAdUnit("controlled-fullscreen"),
                    afterOnboardingInterstitialEnabled = false)
                step(ContentStepDefinition(StepId.OB2, title = "Swipe source"))
                step(AdFullScreenStepDefinition(StepId.OB3, autoNextEnabled = false,
                    skipButtonDelaySec = 3))
                step(ContentStepDefinition(StepId.OB4, title = "Skip destination"))
            }.getOrThrow()).getOrThrow()
            ConsentCenter.setHostConsent(true, false)
            OnboardingSdk.setCanRequestAds(true)
        }
        OnboardingSdk.reset()
        OnboardingSettings.document.acceptSuccessfulFetch(if (remote)
            """{"onboarding":{"navigation":{"lock_pager_swipe":false},"fullscreen":{"skip":{"delay_ms":0}}}}""" else null)
        // Explicitly preserve the host skip delay when no legacy remote assignment exists.
        OnboardingSdk.remoteOrNull()?.applySnapshot(RemoteFlags(skipButtonDelaySec = -1))
        try {
            ActivityScenario.launch<ObOnboardingHostActivity>(
                Intent(app, ObOnboardingHostActivity::class.java),
            ).use { screen ->
                eventually("Content page is selected") {
                    var ready = false
                    screen.onActivity { ready = it.findViewById<ViewPager2>(R.id.ob_step_pager).currentItem == 0 }
                    ready
                }
                screen.onActivity {
                    assertEquals(remote, it.findViewById<ViewPager2>(R.id.ob_step_pager).isUserInputEnabled)
                }
                onView(withId(R.id.ob_step_pager)).perform(swipeLeft())
                if (!remote) {
                    screen.onActivity { assertEquals(0, it.currentIndex.value); it.next("next") }
                }
                eventually("Fullscreen page is selected") {
                    var selected = false
                    screen.onActivity { selected = it.currentIndex.value == 1 && it.findViewById<View>(R.id.ob_skip_button) != null }
                    selected
                }
                if (!remote) {
                    // Outwait the bundled 1-second default: the explicit host 3 seconds must win.
                    SystemClock.sleep(1_200)
                    screen.onActivity {
                        assertEquals("Explicit host delay still hides Skip after 1.2 seconds", View.GONE,
                            it.findViewById<View>(R.id.ob_skip_button).visibility)
                    }
                }
                eventually("Skip becomes available", timeoutMs = if (remote) 1_000 else 6_000) {
                    var visible = false
                    screen.onActivity { visible = it.findViewById<View>(R.id.ob_skip_button)?.isShown == true }
                    visible
                }
                screen.onActivity { assertTrue(it.findViewById<View>(R.id.ob_skip_button).performClick()) }
                eventually("Skip advances exactly one page") {
                    var atDestination = false
                    screen.onActivity { atDestination = it.currentIndex.value == 2 }
                    atDestination
                }
                SystemClock.sleep(500)
                // Existing middle-page swipes select the next page without emitting CTA completion.
                val expectedCompletions = if (remote) listOf(StepId.OB3) else listOf(StepId.OB2, StepId.OB3)
                assertEquals(expectedCompletions, completed.map { it.stepId })
                assertEquals("skip", completed.last().exitReason)
            }
        } finally {
            instrumentation.runOnMainSync { ConsentCenter.clearHostConsent() }
        }
    }

    private fun cacheDocument() = SettingsDocument("grouped_device_cache",
        """{"schema_version":1,"banner":{"reload":{"enabled":false,"interval_ms":30000}}}""")

    private fun eventually(message: String, timeoutMs: Long = 6_000, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(40)
        assertTrue(message, predicate())
    }
}

private class ReadyNativeProvider : OnboardingAdProvider {
    override fun isPremium(context: Context) = false
    override fun isNativeReady(placement: AdPlacement) = true
    override fun isNativeLoading(placement: AdPlacement) = false
    override fun preloadNative(activity: Activity, request: NativeAdRequest) = Unit
    override fun bindNative(activity: Activity, placement: AdPlacement, container: ViewGroup,
        shimmer: View?, listener: AdEventListener?): Boolean {
        container.removeAllViews()
        container.addView(TextView(activity).apply { text = "Controlled native" })
        container.visibility = View.VISIBLE
        listener?.onImpression()
        return true
    }
    override fun releaseNative(placement: AdPlacement) = Unit
    override fun loadInterstitial(context: Context, placement: AdPlacement,
        unit: InterstitialAdUnit, listener: AdEventListener?) = Unit
    override fun isInterstitialReady(placement: AdPlacement) = false
    override fun loadAndShowInterstitial(activity: AppCompatActivity, placement: AdPlacement,
        unit: InterstitialAdUnit, callback: ObInterstitialCallback, timeoutMs: Long) {
        error("Unexpected interstitial in a native-only fixture: $placement")
    }
    override fun showInterstitial(activity: Activity, placement: AdPlacement, callback: ObInterstitialCallback) = Unit
    override fun loadBanner(activity: Activity, unit: BannerAdUnit, listener: AdEventListener?) = Unit
    override fun suppressAppResume(activityClass: Class<out Activity>) = Unit
    override fun releaseAll() = Unit
}
