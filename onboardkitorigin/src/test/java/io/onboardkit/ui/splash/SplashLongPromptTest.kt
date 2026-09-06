package io.onboardkit.ui.splash

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import com.ads.module.consent.ConsentCenter
import com.ads.module.consent.ConsentOptions
import com.google.android.ump.ConsentForm
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import io.onboardkit.OnboardingSdk
import io.onboardkit.ads.AdEventListener
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.AdSkipReason
import io.onboardkit.ads.NativeAdRequest
import io.onboardkit.ads.ObInterstitialCallback
import io.onboardkit.ads.OnboardingAdProvider
import io.onboardkit.config.AdsConfig
import io.onboardkit.config.BannerAdUnit
import io.onboardkit.config.ContentStepDefinition
import io.onboardkit.config.InterstitialAdUnit
import io.onboardkit.config.NativeAdUnit
import io.onboardkit.config.SplashConfig
import io.onboardkit.config.onboardKitConfig
import io.onboardkit.core.StepId
import io.onboardkit.core.OnboardingListener
import io.onboardkit.core.OnboardingOutcome
import io.onboardkit.paywall.PaywallGate
import io.onboardkit.paywall.PaywallOutcome
import io.onboardkit.paywall.PaywallPlacement
import io.onboardkit.core.analytics.AnalyticsEvent
import io.onboardkit.core.analytics.AnalyticsPlugin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import java.time.Duration

/** Real splash pipeline; only UMP and Android permission results are controlled externally. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, shadows = [LongPromptUmpShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class SplashLongPromptTest {
    private val main get() = shadowOf(Looper.getMainLooper())
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private var controller: ActivityController<LongPromptSplashActivity>? = null

    @Before
    fun setUp() {
        LongPromptFixture.reset()
        app.applicationInfo.targetSdkVersion = 34
        ConsentCenter.reset(app)
        ConsentCenter.configure(ConsentOptions(debug = false, timeoutMs = 20_000))
        OnboardingSdk.install(app) {
            adProvider = LongPromptFixture.provider
            listener = OnboardingListener { _, outcome -> LongPromptFixture.outcomes += outcome }
            paywallGate = object : PaywallGate {
                override suspend fun shouldShow(placement: PaywallPlacement): Boolean {
                    if (placement == PaywallPlacement.SPLASH_INTER) LongPromptFixture.splashPaywallChecks++
                    return true
                }
                override suspend fun present(activity: Activity, placement: PaywallPlacement) = PaywallOutcome.ContinueWithAds
            }
            trackkitAutoTracking(false)
            analyticsPlugin(AnalyticsPlugin {
                if (it is AnalyticsEvent.FlowStarted) LongPromptFixture.flowStarts++
            })
        }
        OnboardingSdk.setCanRequestAds(true)
        runBlocking { OnboardingSdk.reset() }
    }

    @After
    fun tearDown() {
        controller?.pause()?.stop()?.destroy()
        ConsentCenter.reset(app)
        main.idle()
    }

    @Test
    fun tenMinuteUmpAnswerStillStartsBothSplashLoadsBeforeDestinationPreload() {
        LongPromptFixture.ump.requireForm = true
        launch(notification = false)
        drainUntil("UMP form must actually be shown through ConsentCenter") { LongPromptFixture.form.dismiss != null }

        main.idleFor(Duration.ofMinutes(10))

        assertEquals("A live unanswered UMP form must not hand off the flow", 0, LongPromptFixture.flowStarts)
        assertEquals(0, LongPromptFixture.provider.bannerLoads)
        assertTrue(LongPromptFixture.provider.order.isEmpty())
        LongPromptFixture.ump.allowed = true
        requireNotNull(LongPromptFixture.form.dismiss).onConsentFormDismissed(null)
        drainUntil("Accept must start the normal splash load pair") { LongPromptFixture.provider.interstitialLoads == 1 }
        assertEquals(1, LongPromptFixture.provider.bannerLoads)
        assertTrue(LongPromptFixture.provider.order.isEmpty())
        main.idleFor(Duration.ofSeconds(30))
        completeInterstitialAndAssertNormalHandoff()
    }

    @Test
    fun resolvedConsentWithoutNotificationRunsNormalSplashLoadAndPreload() {
        launch(notification = false)
        drainUntil("Resolved consent must start splash loading") { LongPromptFixture.provider.interstitialLoads == 1 }
        assertEquals(1, LongPromptFixture.provider.bannerLoads)
        assertTrue(LongPromptFixture.provider.order.isEmpty())
        main.idleFor(Duration.ofSeconds(5))
        completeInterstitialAndAssertNormalHandoff()
    }

    @Test
    fun notificationWithoutSplashAdsKeepsEntryAndDestinationWhileSkippingAllSplashMonetization() {
        assertEntryWithoutSplashAds(SplashEntry.NOTIFICATION)
    }

    @Test
    fun widgetWithoutSplashAdsKeepsEntryAndDestinationWhileSkippingAllSplashMonetization() {
        assertEntryWithoutSplashAds(SplashEntry.WIDGET)
    }

    @Test
    fun uninstallWithoutSplashAdsKeepsEntryAndDestinationWhileSkippingAllSplashMonetization() {
        assertEntryWithoutSplashAds(SplashEntry.UNINSTALL)
    }

    private fun assertEntryWithoutSplashAds(entry: SplashEntry) {
        runBlocking { OnboardingSdk.markCompleted() }
        launch(notification = false, launchIntent = entry.intentWithoutSplashAds(app, LongPromptSplashActivity::class.java)
            .putExtra("feature_id", "translate").putExtra("entry_id", "one-entry"))
        drainUntil("Ad-free entry must still run the splash initialization") { LongPromptFixture.remoteHookCalled }
        main.idleFor(Duration.ofSeconds(30))
        drainUntil("Returning user must reach the existing outcome listener") { LongPromptFixture.outcomes.size == 1 }
        val outcome = LongPromptFixture.outcomes.single() as OnboardingOutcome.Skipped
        assertEquals(entry, SplashEntry.from(outcome.passthrough))
        assertEquals("translate", outcome.passthrough?.getString("feature_id"))
        assertEquals("one-entry", outcome.passthrough?.getString("entry_id"))
        assertEquals(0, LongPromptFixture.provider.bannerLoads)
        assertEquals(0, LongPromptFixture.provider.interstitialLoads)
        assertTrue(LongPromptFixture.provider.order.isEmpty())
        assertEquals(0, LongPromptFixture.splashPaywallChecks)
        main.idleFor(Duration.ofMinutes(1))
        assertEquals("No ad timeout callback may produce a second handoff", 1, LongPromptFixture.outcomes.size)
    }

    @Test
    fun adFreeSplashStillStartsRequiredOnboardingAndKeepsItsExistingAdPolicy() {
        launch(notification = false, launchIntent = SplashEntry.WIDGET.intentWithoutSplashAds(app, LongPromptSplashActivity::class.java))
        drainUntil("Initialization must finish") { LongPromptFixture.remoteHookCalled }
        main.idleFor(Duration.ofSeconds(30))
        drainUntil("First-open setup must still start") { LongPromptFixture.flowStarts == 1 }
        assertTrue("Setup was not falsely completed/skipped", LongPromptFixture.outcomes.isEmpty())
        assertEquals(listOf("native"), LongPromptFixture.provider.order)
        assertEquals(0, LongPromptFixture.provider.bannerLoads)
        assertEquals(0, LongPromptFixture.provider.interstitialLoads)
        assertEquals(0, LongPromptFixture.splashPaywallChecks)
    }

    @Test
    fun existingTaggedEntryRetainsSplashLoadsShowAndPaywallCheckpoint() {
        launch(notification = false, launchIntent = SplashEntry.NOTIFICATION.intent(app, LongPromptSplashActivity::class.java))
        drainUntil("Legacy tagged entry must still load its splash ad") { LongPromptFixture.provider.interstitialLoads == 1 }
        completeInterstitialAndAssertNormalHandoff()
        assertEquals(1, LongPromptFixture.splashPaywallChecks)
    }

    @Test
    fun homeDuringBillingDefersFreshSplashRequestsUntilResumeAndFocus() {
        LongPromptFixture.billing = CompletableDeferred()
        launch(notification = false)
        drainUntil("The real billing hook must be waiting") { LongPromptFixture.billingEntered }
        val host = requireNotNull(controller).get()
        host.onWindowFocusChanged(false)
        requireNotNull(controller).pause().stop()

        requireNotNull(LongPromptFixture.billing).complete(Unit)
        drainUntil("Pipeline must leave billing and reach the remote hook") { LongPromptFixture.remoteHookCalled }
        main.idleFor(Duration.ofSeconds(4))
        assertEquals("Home while billing must not dispatch the banner", 0, LongPromptFixture.provider.bannerLoads)
        assertEquals("Home while billing must not dispatch the interstitial", 0, LongPromptFixture.provider.interstitialLoads)
        assertEquals(0, LongPromptFixture.flowStarts)
        assertTrue(LongPromptFixture.provider.order.isEmpty())

        requireNotNull(controller).restart().start().resume().visible()
        host.onWindowFocusChanged(true)
        drainUntil("Returning focus must release the normal splash load pair") { LongPromptFixture.provider.interstitialLoads == 1 }
        completeInterstitialAndAssertNormalHandoff()
    }

    @Test
    fun tenMinuteNotificationAnswerGetsSplashWaitBeforeDestinationPreload() {
        notificationAnswerBeforeFill(promptMinutes = 10)
    }

    @Test
    fun tenMinuteNotificationAnswerStillReceivesFreshMinimumDisplay() {
        notificationAnswerBeforeFill(promptMinutes = 10, verifyFreshMinimum = true)
    }

    private fun notificationAnswerBeforeFill(promptMinutes: Long, verifyFreshMinimum: Boolean = false) {
        launch(notification = true)
        val activity = requireNotNull(controller).get()
        drainUntil("The real Activity permission request must have started") {
            shadowOf(activity).lastRequestedPermission != null
        }
        val permission = requireNotNull(shadowOf(activity).lastRequestedPermission)
        assertTrue(Manifest.permission.POST_NOTIFICATIONS in permission.requestedPermissions)
        activity.onWindowFocusChanged(false)
        main.idleFor(if (promptMinutes == 0L) Duration.ofSeconds(5) else Duration.ofMinutes(promptMinutes))
        assertEquals(0, LongPromptFixture.flowStarts)
        assertTrue("Pending notification must not preload destination", LongPromptFixture.provider.order.isEmpty())
        assertEquals("User reading time must precede the splash banner request", 0, LongPromptFixture.provider.bannerLoads)
        assertEquals("User reading time must precede the splash interstitial request", 0, LongPromptFixture.provider.interstitialLoads)

        activity.onRequestPermissionsResult(permission.requestCode, permission.requestedPermissions,
            IntArray(permission.requestedPermissions.size) { PackageManager.PERMISSION_DENIED })
        activity.onWindowFocusChanged(true)
        drainUntil("Permission result must start a fresh splash ad phase") { LongPromptFixture.provider.interstitialLoads == 1 }

        assertEquals("Answering permission must not skip an in-flight splash ad", 0, LongPromptFixture.flowStarts)
        assertTrue("Destination must wait for splash fill after a long notification prompt", LongPromptFixture.provider.order.isEmpty())
        if (!verifyFreshMinimum) main.idleFor(Duration.ofSeconds(30))
        completeInterstitialAndAssertNormalHandoff(verifyFreshMinimum = verifyFreshMinimum)
    }

    private fun launch(notification: Boolean, launchIntent: Intent? = null) {
        OnboardingSdk.configure(onboardKitConfig {
            splash = SplashConfig(noInternetPromptEnabled = false, notificationPermissionEnabled = notification,
                minDisplayTimeMs = 0, remoteFetchTimeoutMs = 100)
            step(ContentStepDefinition(StepId.OB1, title = "Introduction"))
            ads = AdsConfig(splashBanner = BannerAdUnit("host-banner"),
                splashInterstitial = InterstitialAdUnit("host-interstitial"),
                languageNative = NativeAdUnit("host-language"))
        }.getOrThrow()).getOrThrow()
        controller = Robolectric.buildActivity(LongPromptSplashActivity::class.java, launchIntent).setup().visible()
        requireNotNull(controller).get().onWindowFocusChanged(true)
        main.idle()
    }

    private fun completeInterstitialAndAssertNormalHandoff(verifyFreshMinimum: Boolean = false) {
        assertNotNull("The original public load callback must remain available", LongPromptFixture.provider.pending)
        LongPromptFixture.provider.ready = true
        requireNotNull(LongPromptFixture.provider.pending).onLoaded()
        if (verifyFreshMinimum) {
            main.idle()
            assertEquals("A fill after a long prompt still owes the splash minimum", 0, LongPromptFixture.flowStarts)
            // External Android work can advance Robolectric time while the test is awaiting
            // the request. Measure from the public provider dispatch, not from this test line.
            // Leave 20ms for UI work preceding the provider call in the same request turn.
            val beforeMinimum = LongPromptFixture.provider.interstitialRequestAtMs + 3_000 -
                SystemClock.elapsedRealtime() - 20
            assertTrue("Fixture must still be before the fresh splash deadline", beforeMinimum > 0)
            main.idleFor(Duration.ofMillis(beforeMinimum))
            assertEquals("Permission reading time must not consume the fresh 3-second minimum", 0, LongPromptFixture.flowStarts)
            assertTrue(LongPromptFixture.provider.order.isEmpty())
            main.idleFor(Duration.ofMillis(40))
        } else {
            // Permit the default 3-second minimum display before checking the completed handoff.
            main.idleFor(Duration.ofSeconds(4))
        }
        drainUntil("Ready splash must show and hand off once; order=${LongPromptFixture.provider.order}") { LongPromptFixture.flowStarts == 1 }
        assertEquals(listOf("native", "show"), LongPromptFixture.provider.order)
        assertEquals(1, LongPromptFixture.provider.bannerLoads)
        assertEquals(1, LongPromptFixture.provider.interstitialLoads)
    }

    private fun drainUntil(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            main.idle()
            Thread.sleep(5) // DataStore IO completion; virtual prompt durations use only the main looper.
        }
        assertTrue(message, condition())
    }
}

class LongPromptSplashActivity : ObSplashActivity() {
    override suspend fun onInitBilling() {
        LongPromptFixture.billingEntered = true
        LongPromptFixture.billing?.await()
    }
    override fun onRemoteFetched() { LongPromptFixture.remoteHookCalled = true }
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(com.google.android.material.R.style.Theme_MaterialComponents_DayNight_NoActionBar)
        super.onCreate(savedInstanceState)
    }
}

private object LongPromptFixture {
    val provider = LongPromptProvider()
    var ump = LongPromptConsentInformation()
    var form = LongPromptConsentForm()
    var flowStarts = 0
    var billing: CompletableDeferred<Unit>? = null
    var billingEntered = false
    var remoteHookCalled = false
    var splashPaywallChecks = 0
    val outcomes = mutableListOf<OnboardingOutcome>()
    fun reset() {
        splashPaywallChecks = 0
        outcomes.clear()
        billing = null
        billingEntered = false
        remoteHookCalled = false
        ump = LongPromptConsentInformation()
        form = LongPromptConsentForm()
        flowStarts = 0
        provider.bannerLoads = 0
        provider.interstitialLoads = 0
        provider.interstitialRequestAtMs = 0L
        provider.pending = null
        provider.ready = false
        provider.order.clear()
    }
}

private class LongPromptProvider : OnboardingAdProvider {
    var bannerLoads = 0
    var interstitialRequestAtMs = 0L
    var interstitialLoads = 0
    var pending: AdEventListener? = null
    var ready = false
    val order = mutableListOf<String>()
    override fun isPremium(context: Context) = false
    override fun preloadNative(activity: Activity, request: NativeAdRequest) { order += "native" }
    override fun isNativeReady(placement: AdPlacement) = false
    override fun isNativeLoading(placement: AdPlacement) = false
    override fun bindNative(activity: Activity, placement: AdPlacement, container: ViewGroup, shimmer: View?, listener: AdEventListener?) = false
    override fun releaseNative(placement: AdPlacement) = Unit
    override fun loadInterstitial(context: Context, placement: AdPlacement, unit: InterstitialAdUnit, listener: AdEventListener?) {
        interstitialLoads++
        interstitialRequestAtMs = SystemClock.elapsedRealtime()
        pending = listener
    }
    override fun isInterstitialReady(placement: AdPlacement) = ready
    override fun showInterstitial(activity: Activity, placement: AdPlacement, callback: ObInterstitialCallback) {
        order += "show"
        callback.onAdSkipped(AdSkipReason.NOT_READY)
    }
    override fun loadBanner(activity: Activity, unit: BannerAdUnit, listener: AdEventListener?) {
        bannerLoads++
        listener?.onFailedToLoad()
    }
    override fun suppressAppResume(activityClass: Class<out Activity>) = Unit
    override fun releaseAll() = Unit
}

@Implements(UserMessagingPlatform::class)
class LongPromptUmpShadow {
    companion object {
        @JvmStatic @Implementation
        fun getConsentInformation(context: Context): ConsentInformation = LongPromptFixture.ump
        @JvmStatic @Implementation
        fun loadConsentForm(context: Context, success: UserMessagingPlatform.OnConsentFormLoadSuccessListener,
            failure: UserMessagingPlatform.OnConsentFormLoadFailureListener) {
            success.onConsentFormLoadSuccess(LongPromptFixture.form)
        }
    }
}

private class LongPromptConsentForm : ConsentForm {
    var dismiss: ConsentForm.OnConsentFormDismissedListener? = null
    override fun show(activity: Activity, listener: ConsentForm.OnConsentFormDismissedListener) { dismiss = listener }
}

private class LongPromptConsentInformation : ConsentInformation {
    var requireForm = false
    var allowed = false
    override fun canRequestAds() = allowed
    override fun getConsentStatus() = if (allowed) ConsentInformation.ConsentStatus.OBTAINED else ConsentInformation.ConsentStatus.REQUIRED
    override fun getPrivacyOptionsRequirementStatus() = ConsentInformation.PrivacyOptionsRequirementStatus.NOT_REQUIRED
    override fun isConsentFormAvailable() = requireForm
    override fun requestConsentInfoUpdate(activity: Activity, parameters: ConsentRequestParameters,
        success: ConsentInformation.OnConsentInfoUpdateSuccessListener,
        failure: ConsentInformation.OnConsentInfoUpdateFailureListener) {
        if (!requireForm) allowed = true
        success.onConsentInfoUpdateSuccess()
    }
    override fun reset() { allowed = false }
}
