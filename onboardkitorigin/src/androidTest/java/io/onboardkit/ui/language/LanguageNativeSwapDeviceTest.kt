package io.onboardkit.ui.language

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ads.module.consent.ConsentCenter
import io.onboardkit.OnboardingSdk
import io.onboardkit.R
import io.onboardkit.ads.AdEventListener
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.AdSkipReason
import io.onboardkit.ads.NativeAdRequest
import io.onboardkit.ads.ObInterstitialCallback
import io.onboardkit.ads.OnboardingAdProvider
import io.onboardkit.config.AdsConfig
import io.onboardkit.config.BannerAdUnit
import io.onboardkit.config.InterstitialAdUnit
import io.onboardkit.config.LanguageConfig
import io.onboardkit.config.NativeAdUnit
import io.onboardkit.config.ObLanguages
import io.onboardkit.config.onboardKitConfig
import io.onboardkit.core.OnboardingListener
import io.onboardkit.core.OnboardingOutcome
import io.onboardkit.core.analytics.AnalyticsEvent
import io.onboardkit.core.analytics.AnalyticsPlugin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Run this class alone in a fresh instrumentation process with cleared test-APK data.
 * The real language Activity and SDK use the documented host-provider extension point;
 * remote defaults, selection gestures, native callbacks and the terminal route stay real.
 */
@RunWith(AndroidJUnit4::class)
class LanguageNativeSwapDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val provider get() = Fixture.provider

    @Before
    fun preparePublicHost() {
        instrumentation.runOnMainSync {
            if (!Fixture.installed) {
                assertFalse("Run this class alone in a fresh process", OnboardingSdk.isReady())
                OnboardingSdk.install(app) {
                    adProvider = provider
                    trackkitAutoTracking(false)
                    listener = OnboardingListener { _, outcome -> Fixture.outcomes += outcome }
                    analyticsPlugin(AnalyticsPlugin { Fixture.events += it })
                }
                Fixture.installed = true
            }
            provider.reset()
            Fixture.events.clear()
            Fixture.outcomes.clear()
            OnboardingSdk.configure(onboardKitConfig {
                language = LanguageConfig(
                    languages = listOf(ObLanguages.find("en-US")!!, ObLanguages.find("es")!!),
                    tapHintEnabled = false,
                    confirmDialogOnReselectEnabled = false,
                )
                ads = AdsConfig(
                    languageNative = NativeAdUnit("host-first-native"),
                    languageDupNative = NativeAdUnit("host-second-native"),
                    splashInterstitial = InterstitialAdUnit("host-exit-interstitial"),
                )
            }.getOrThrow()).getOrThrow()
            ConsentCenter.setHostConsent(canRequestAds = true, personalized = false)
            OnboardingSdk.setCanRequestAds(true)
        }
        runBlocking { OnboardingSdk.reset() }
    }

    @Test
    fun delayedSecondFillKeepsFirstAndCommitsOnceWithTheFirstTapLanguage() = withScreen { screen ->
        tapLanguage(screen, 0)
        tapLanguage(screen, 1)
        assertFirstStillVisible(screen)
        assertEquals(1, provider.secondRequests)
        assertEquals(1, provider.secondListenerRegistrations)
        assertTrue(secondViews().isEmpty())
        assertTrue(languageCompletions().isEmpty())

        screen.onActivity { provider.deliverSecondFill() }
        screen.onActivity { activity ->
            assertEquals(View.INVISIBLE, activity.findViewById<View>(R.id.ob_ad_block).visibility)
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.ob_ad_block_2).visibility)
            assertSame(provider.secondView, activity.findViewById<ViewGroup>(R.id.ob_native_container_2).getChildAt(0))
        }
        assertEquals(1, provider.firstReleases)
        assertEquals(1, secondViews().size)
        assertEquals("en-US", languageCompletions().single().code)
        assertEquals(1, languageCompletions().single().screenIndex)
        // A duplicate delivery cannot repeat the transition after the single fill was consumed.
        screen.onActivity { provider.lastSecondListener?.onLoaded() }
        assertEquals(1, secondViews().size)
        assertEquals(1, languageCompletions().size)
    }

    @Test
    fun secondNoFillKeepsTheOriginalAdAndDoesNotRetryOnLaterSelections() = withScreen { screen ->
        tapLanguage(screen, 0)
        screen.onActivity { provider.failSecond() }
        tapLanguage(screen, 1)
        assertFirstStillVisible(screen)
        assertEquals(1, provider.secondRequests)
        assertEquals(1, provider.secondListenerRegistrations)
        assertTrue(secondViews().isEmpty())
        assertTrue(languageCompletions().isEmpty())
    }

    @Test
    fun timedOutSwapCannotCommitWhenTheHostDeliversALateFill() = withScreen { screen ->
        tapLanguage(screen, 0)
        SystemClock.sleep(8_500)
        assertFirstStillVisible(screen)
        screen.onActivity { provider.deliverSecondFill() }
        assertFirstStillVisible(screen)
        assertTrue(secondViews().isEmpty())
        assertTrue(languageCompletions().isEmpty())
    }

    @Test
    fun confirmWhileWaitingCancelsTheSwapBeforeTheExistingExitInterstitialCompletes() = withScreen { screen ->
        tapLanguage(screen, 0)
        screen.onActivity { activity ->
            activity.findViewById<View>(R.id.ob_language_confirm).performClick()
            assertFalse("The existing interstitial holds this Activity alive", activity.isFinishing)
            assertNotNull(provider.exitInterstitial)
            provider.deliverSecondFill()
        }
        assertFirstStillVisible(screen)
        assertTrue(secondViews().isEmpty())
        assertEquals("en-US", languageCompletions().single().code)
        assertEquals(1, languageCompletions().single().screenIndex)
        screen.onActivity { provider.finishExitInterstitial() }
        eventually("The unchanged terminal route completes") { Fixture.outcomes.size == 1 }
        assertTrue(Fixture.outcomes.single() is OnboardingOutcome.Completed)
        assertTrue(secondViews().isEmpty())
    }

    @Test
    fun destroyedScreenIgnoresTheOutstandingFill() {
        withScreen { screen -> tapLanguage(screen, 0) }
        instrumentation.runOnMainSync { provider.deliverSecondFill() }
        assertTrue(secondViews().isEmpty())
        assertTrue(languageCompletions().isEmpty())
        assertEquals(0, provider.secondBinds)
    }

    private fun withScreen(block: (ActivityScenario<ObLanguageActivity>) -> Unit) {
        val screen = ActivityScenario.launch<ObLanguageActivity>(
            ObLanguageActivity.intentFor(app, LanguageScreenMode.FIRST_OPEN),
        )
        try {
            eventually("Both language rows are laid out") {
                var ready = false
                screen.onActivity {
                    ready = it.findViewById<RecyclerView>(R.id.ob_language_list)
                        .findViewHolderForAdapterPosition(1) != null
                }
                ready
            }
            block(screen)
        } finally {
            instrumentation.runOnMainSync { provider.finishExitInterstitial() }
            screen.close()
            instrumentation.runOnMainSync { ConsentCenter.clearHostConsent() }
        }
    }

    private fun tapLanguage(screen: ActivityScenario<ObLanguageActivity>, position: Int) {
        screen.onActivity { activity ->
            val list = activity.findViewById<RecyclerView>(R.id.ob_language_list)
            assertTrue(list.findViewHolderForAdapterPosition(position)!!.itemView.performClick())
        }
    }

    private fun assertFirstStillVisible(screen: ActivityScenario<ObLanguageActivity>) {
        screen.onActivity { activity ->
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.ob_ad_block).visibility)
            assertEquals(View.GONE, activity.findViewById<View>(R.id.ob_ad_block_2).visibility)
            assertSame(provider.firstView, activity.findViewById<ViewGroup>(R.id.ob_native_container).getChildAt(0))
            assertTrue(provider.firstView!!.isShown)
        }
        assertEquals("First ad is not destroyed before replacement", 0, provider.firstReleases)
    }

    private fun secondViews() = Fixture.events.filterIsInstance<AnalyticsEvent.LanguageViewed>()
        .filter { it.screenIndex == 2 }
    private fun languageCompletions() = Fixture.events.filterIsInstance<AnalyticsEvent.LanguageCompleted>()

    private fun eventually(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue(message, predicate())
    }

    private object Fixture {
        var installed = false
        val provider = DelayedHostNativeProvider()
        val events = CopyOnWriteArrayList<AnalyticsEvent>()
        val outcomes = CopyOnWriteArrayList<OnboardingOutcome>()
    }
}

/** Concrete host provider; only its external completion timing is controlled by the test. */
private class DelayedHostNativeProvider : OnboardingAdProvider {
    var firstView: TextView? = null
    var secondView: TextView? = null
    var firstReleases = 0
    var secondRequests = 0
    var secondBinds = 0
    var secondListenerRegistrations = 0
    var lastSecondListener: AdEventListener? = null
    var exitInterstitial: ObInterstitialCallback? = null
    private var secondReady = false
    private var secondLoading = false

    fun reset() {
        firstView = null
        secondView = null
        firstReleases = 0
        secondRequests = 0
        secondBinds = 0
        secondListenerRegistrations = 0
        lastSecondListener = null
        exitInterstitial = null
        secondReady = false
        secondLoading = false
    }

    override fun isPremium(context: Context) = false
    override fun isNativeReady(placement: AdPlacement) =
        placement == AdPlacement.Language1 || placement == AdPlacement.Language2 && secondReady
    override fun isNativeLoading(placement: AdPlacement) = placement == AdPlacement.Language2 && secondLoading

    override fun preloadNative(activity: Activity, request: NativeAdRequest) {
        if (request.placement == AdPlacement.Language2 && !secondLoading && !secondReady) {
            secondLoading = true
            secondRequests++
        }
    }

    override fun bindNative(activity: Activity, placement: AdPlacement, container: ViewGroup, shimmer: View?, listener: AdEventListener?): Boolean {
        if (placement == AdPlacement.Language2 && listener != null) {
            lastSecondListener = listener
            secondListenerRegistrations++
        }
        if (!isNativeReady(placement)) return false
        val view = TextView(activity).apply {
            text = if (placement == AdPlacement.Language1) "First native" else "Second native"
        }
        container.removeAllViews()
        container.addView(view)
        container.visibility = View.VISIBLE
        if (placement == AdPlacement.Language1) firstView = view
        else {
            secondView = view
            secondReady = false
            secondBinds++
        }
        listener?.onImpression()
        return true
    }

    fun deliverSecondFill() {
        secondLoading = false
        secondReady = true
        // Retain the old vendor delivery deliberately, even after the host released the request.
        lastSecondListener?.onLoaded()
    }

    fun failSecond() {
        secondLoading = false
        lastSecondListener?.onFailedToLoad()
    }

    override fun releaseNative(placement: AdPlacement) {
        if (placement == AdPlacement.Language1) firstReleases++
        if (placement == AdPlacement.Language2) {
            secondLoading = false
            secondReady = false
        }
    }
    override fun loadInterstitial(context: Context, placement: AdPlacement, unit: InterstitialAdUnit, listener: AdEventListener?) = Unit
    override fun isInterstitialReady(placement: AdPlacement) = placement == AdPlacement.SplashInterstitial
    override fun loadAndShowInterstitial(
        activity: androidx.appcompat.app.AppCompatActivity,
        placement: AdPlacement,
        unit: InterstitialAdUnit,
        callback: ObInterstitialCallback,
        timeoutMs: Long,
    ) {
        throw AssertionError("This fixture does not expect a loadAndShow request: ${placement.key}")
    }

    override fun showInterstitial(activity: Activity, placement: AdPlacement, callback: ObInterstitialCallback) { exitInterstitial = callback }
    fun finishExitInterstitial() {
        val pending = exitInterstitial
        exitInterstitial = null
        pending?.onAdSkipped(AdSkipReason.NOT_READY)
    }
    override fun loadBanner(activity: Activity, unit: BannerAdUnit, listener: AdEventListener?) = Unit
    override fun suppressAppResume(activityClass: Class<out Activity>) = Unit
    override fun releaseAll() = Unit
}
