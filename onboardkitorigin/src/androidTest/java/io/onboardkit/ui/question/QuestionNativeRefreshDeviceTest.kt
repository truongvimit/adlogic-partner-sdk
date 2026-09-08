package io.onboardkit.ui.question

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
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
import io.onboardkit.ads.NativeAdRequest
import io.onboardkit.ads.ObInterstitialCallback
import io.onboardkit.ads.OnboardingAdProvider
import io.onboardkit.config.AdsConfig
import io.onboardkit.config.BannerAdUnit
import io.onboardkit.config.InterstitialAdUnit
import io.onboardkit.config.NativeAdUnit
import io.onboardkit.config.QuestionConfig
import io.onboardkit.config.QuestionOption
import io.onboardkit.config.onboardKitConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Run this class alone in a fresh, cleared instrumentation process with remote defaults. */
@RunWith(AndroidJUnit4::class)
class QuestionNativeRefreshDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val provider get() = Fixture.provider

    @Before
    fun installPublicHost() {
        instrumentation.runOnMainSync {
            if (!Fixture.installed) {
                assertFalse("Use a fresh instrumentation process", OnboardingSdk.isReady())
                OnboardingSdk.install(app) {
                    adProvider = provider
                    trackkitAutoTracking(false)
                }
                Fixture.installed = true
            }
            provider.reset()
            ConsentCenter.setHostConsent(canRequestAds = true, personalized = false)
            OnboardingSdk.setCanRequestAds(true)
        }
        runBlocking { OnboardingSdk.reset() }
    }

    @Test
    fun defaultOffKeepsTheBufferedAdWithoutAnUnsolicitedWarmupReplacement() {
        configureQuestion(QuestionConfig(options = options()))
        withScreen { screen ->
            assertEquals(0, provider.requests)
            assertEquals(1, provider.binds)
            val original = provider.boundView!!
            SystemClock.sleep(2_200)
            tapOption(screen, 0)
            tapOption(screen, 1)
            tapOption(screen, 0)
            assertVisibleAd(screen, original, "First native")
            assertEquals(0, provider.requests)
            assertEquals(1, provider.binds)
        }
    }

    @Test
    fun cooldownStartsAtDelayedInitialBindAndOnlyAddedSelectionsRefreshAfterIt() {
        configureQuestion(QuestionConfig(options = options(), refreshAdOnSelect = true))
        instrumentation.runOnMainSync { provider.clearInitialBuffer() }
        withScreen { screen ->
            assertEquals(1, provider.requests)
            // Waiting for a slow initial load must not spend the ad's post-bind cooldown.
            SystemClock.sleep(2_200)
            screen.onActivity { provider.completeRequest("First native") }
            val original = provider.boundView!!
            tapOption(screen, 0)
            assertEquals("Immediate first selection cannot replace a newly bound ad", 1, provider.requests)
            assertEquals(1, provider.binds)
            assertVisibleAd(screen, original, "First native")

            SystemClock.sleep(2_200)
            tapOption(screen, 0) // Deselect, even though the cooldown has elapsed.
            assertEquals(1, provider.requests)
            tapOption(screen, 1) // A new selection may request the replacement.
            tapOption(screen, 2) // Another selection while it is loading must join the same wait.
            assertEquals(2, provider.requests)
            assertEquals(1, provider.binds)
            assertVisibleAd(screen, original, "First native")
            assertEquals(0, provider.releases)

            screen.onActivity { provider.completeRequest("Replacement native") }
            assertEquals(2, provider.binds)
            assertVisibleAd(screen, provider.boundView!!, "Replacement native")
            assertEquals("Never release the same placement after its successful bind", 0, provider.releases)
            tapOption(screen, 0)
            assertEquals("Replacement bind also restarts cooldown", 2, provider.requests)
        }
    }

    @Test
    fun failedReplacementKeepsTheOriginalTextAndPreservesTheAttemptThrottle() {
        configureQuestion(QuestionConfig(options = options(), refreshAdOnSelect = true))
        withScreen { screen ->
            val original = provider.boundView!!
            SystemClock.sleep(2_200)
            tapOption(screen, 0)
            assertEquals(1, provider.requests)
            assertVisibleAd(screen, original, "First native")
            screen.onActivity { provider.failRequest() }
            assertVisibleAd(screen, original, "First native")
            assertEquals(0, provider.releases)
            assertEquals(1, provider.binds)
            tapOption(screen, 1)
            assertEquals("Fast failure cannot make the next tap request immediately", 1, provider.requests)
        }
    }

    private fun options() = listOf(
        QuestionOption("a", title = "Read"),
        QuestionOption("b", title = "Edit"),
        QuestionOption("c", title = "Share"),
    )

    private fun configureQuestion(question: QuestionConfig) = instrumentation.runOnMainSync {
        OnboardingSdk.configure(onboardKitConfig {
            this.question = question
            ads = AdsConfig(questionNative = NativeAdUnit("host-question-native"))
        }.getOrThrow()).getOrThrow()
    }

    private fun withScreen(block: (ActivityScenario<ObQuestionActivity>) -> Unit) {
        val screen = ActivityScenario.launch<ObQuestionActivity>(Intent(app, ObQuestionActivity::class.java))
        try {
            eventually("The options are laid out") {
                var ready = false
                screen.onActivity {
                    ready = it.findViewById<RecyclerView>(R.id.ob_question_list)
                        .findViewHolderForAdapterPosition(2) != null
                }
                ready
            }
            block(screen)
        } finally {
            screen.close()
            instrumentation.runOnMainSync { ConsentCenter.clearHostConsent() }
        }
    }

    private fun tapOption(screen: ActivityScenario<ObQuestionActivity>, position: Int) {
        screen.onActivity { activity ->
            val list = activity.findViewById<RecyclerView>(R.id.ob_question_list)
            assertTrue(list.findViewHolderForAdapterPosition(position)!!.itemView.performClick())
        }
    }

    private fun assertVisibleAd(screen: ActivityScenario<ObQuestionActivity>, expected: TextView, text: String) {
        screen.onActivity { activity ->
            assertSame(expected, provider.boundView)
            assertEquals(text, expected.text.toString())
            assertTrue("The current ad must stay visible while replacement is pending or failed", expected.isShown)
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.ob_ad_block).visibility)
        }
    }

    private fun eventually(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue(message, predicate())
    }

    private object Fixture {
        var installed = false
        val provider = BufferedQuestionHostProvider()
    }
}

/** Host-side native pool mirrors the public contract: bound ads are not returned by isNativeReady. */
private class BufferedQuestionHostProvider : OnboardingAdProvider {
    var requests = 0
    var binds = 0
    var releases = 0
    var boundView: TextView? = null
    private var bufferedText: String? = "First native"
    private var pending = false
    private var nativeListener: AdEventListener? = null

    fun reset() {
        requests = 0
        binds = 0
        releases = 0
        boundView = null
        bufferedText = "First native"
        pending = false
        nativeListener = null
    }
    fun clearInitialBuffer() { bufferedText = null }
    override fun isPremium(context: Context) = false
    override fun isNativeReady(placement: AdPlacement) = placement == AdPlacement.QuestionNative && bufferedText != null
    override fun isNativeLoading(placement: AdPlacement) = placement == AdPlacement.QuestionNative && pending
    override fun preloadNative(activity: Activity, request: NativeAdRequest) {
        if (request.placement == AdPlacement.QuestionNative && bufferedText == null && !pending) {
            requests++
            pending = true
        }
    }
    override fun bindNative(activity: Activity, placement: AdPlacement, container: ViewGroup, shimmer: View?, listener: AdEventListener?): Boolean {
        if (placement != AdPlacement.QuestionNative) return false
        if (listener != null) nativeListener = listener
        val text = bufferedText ?: return false
        bufferedText = null
        binds++
        val view = TextView(activity).apply { this.text = text }
        container.removeAllViews()
        container.addView(view)
        container.visibility = View.VISIBLE
        boundView = view
        nativeListener?.onImpression()
        return true
    }
    fun completeRequest(text: String) {
        assertTrue("A vendor fill needs an outstanding request", pending)
        pending = false
        bufferedText = text
        nativeListener?.onLoaded()
    }
    fun failRequest() {
        assertTrue("A vendor failure needs an outstanding request", pending)
        pending = false
        nativeListener?.onFailedToLoad()
    }
    override fun releaseNative(placement: AdPlacement) {
        if (placement == AdPlacement.QuestionNative) {
            releases++
            bufferedText = null
            pending = false
            nativeListener = null
        }
    }
    override fun loadInterstitial(context: Context, placement: AdPlacement, unit: InterstitialAdUnit, listener: AdEventListener?) = Unit
    override fun isInterstitialReady(placement: AdPlacement) = false
    override fun loadAndShowInterstitial(
        activity: androidx.appcompat.app.AppCompatActivity,
        placement: AdPlacement,
        unit: InterstitialAdUnit,
        callback: ObInterstitialCallback,
        timeoutMs: Long,
    ) {
        throw AssertionError("This fixture does not expect a loadAndShow request: ${placement.key}")
    }

    override fun showInterstitial(activity: Activity, placement: AdPlacement, callback: ObInterstitialCallback) = Unit
    override fun loadBanner(activity: Activity, unit: BannerAdUnit, listener: AdEventListener?) = Unit
    override fun suppressAppResume(activityClass: Class<out Activity>) = Unit
    override fun releaseAll() = Unit
}
