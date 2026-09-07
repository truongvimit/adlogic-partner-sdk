package io.onboardkit.ui.splash

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
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
        LongPromptFixture.provider.presentation?.onAdClosed()
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
    fun sequentialStartsLfoOnTerminalFailureUnderNotificationOnlyOnce() {
        launch(notification = true)
        drainUntil("Inter must start") { LongPromptFixture.provider.interstitialLoads == 1 }
        val host = requireNotNull(controller).get()
        host.onWindowFocusChanged(false)
        requireNotNull(LongPromptFixture.provider.pending).onFailedToLoad()
        main.idle()
        assertEquals(listOf("native"), LongPromptFixture.provider.order)
        requireNotNull(LongPromptFixture.provider.pending).onLoaded()
        main.idle()
        assertEquals(listOf("native"), LongPromptFixture.provider.order)
        assertEquals(0, LongPromptFixture.flowStarts)
    }

    @Test
    fun resultWhileHomeUnderNotificationCannotStartLfoUntilTheSplashIsVisible() {
        launch(notification = true)
        drainUntil("Inter must start") { LongPromptFixture.provider.interstitialLoads == 1 }
        val host = requireNotNull(controller).get()
        host.onWindowFocusChanged(false)
        requireNotNull(controller).pause().stop()
        requireNotNull(LongPromptFixture.provider.pending).onFailedToLoad()
        main.idleFor(Duration.ofSeconds(2))
        assertTrue(LongPromptFixture.provider.order.isEmpty())
        requireNotNull(controller).restart().start().resume().visible()
        main.idle()
        assertEquals(listOf("native"), LongPromptFixture.provider.order)
        assertEquals(0, LongPromptFixture.flowStarts)
    }

    @Test
    fun deadlineElapsedInBackgroundIsNotRenewedAndLateFillIsNotShown() {
        LongPromptFixture.flags = io.onboardkit.remote.RemoteFlags(splashAdBudgetMs = 10_000, splashMinDisplayMs = 100)
        launch(notification = false)
        drainUntil("Inter must start") { LongPromptFixture.provider.interstitialLoads == 1 }
        main.idleFor(Duration.ofMillis(200))
        val host = requireNotNull(controller).get()
        host.onWindowFocusChanged(false)
        requireNotNull(controller).pause().stop()
        main.idleFor(Duration.ofSeconds(11))
        LongPromptFixture.provider.ready = true
        requireNotNull(LongPromptFixture.provider.pending).onLoaded()
        main.idle()
        assertTrue("Background must hold LFO: ${LongPromptFixture.provider.order}", LongPromptFixture.provider.order.isEmpty())
        assertEquals(0, LongPromptFixture.flowStarts)
        requireNotNull(controller).restart().start().resume().visible()
        host.onWindowFocusChanged(true)
        main.idle()
        assertEquals(listOf("native"), LongPromptFixture.provider.order)
        assertEquals(1, LongPromptFixture.flowStarts)
    }

    @Test
    fun notificationSettleCountsFromTheResultRatherThanFromRestoredFocus() {
        LongPromptFixture.flags = io.onboardkit.remote.RemoteFlags(splashNotificationSettleMs = 2_000, splashMinDisplayMs = 100)
        launch(notification = true)
        val host = requireNotNull(controller).get()
        drainUntil("Permission and inter must start") {
            shadowOf(host).lastRequestedPermission != null && LongPromptFixture.provider.interstitialLoads == 1
        }
        host.onWindowFocusChanged(false)
        LongPromptFixture.provider.ready = true
        requireNotNull(LongPromptFixture.provider.pending).onLoaded()
        val permission = requireNotNull(shadowOf(host).lastRequestedPermission)
        host.onRequestPermissionsResult(permission.requestCode, permission.requestedPermissions,
            IntArray(permission.requestedPermissions.size) { PackageManager.PERMISSION_DENIED })
        main.idleFor(Duration.ofSeconds(3))
        assertEquals(listOf("native"), LongPromptFixture.provider.order)
        host.onWindowFocusChanged(true)
        main.idle()
        assertEquals(listOf("native", "show"), LongPromptFixture.provider.order)
        assertEquals(1, LongPromptFixture.flowStarts)
    }

    @Test
    fun disabledInterstitialStillPreloadsLanguageWhenItsOwnGuardAllowsIt() {
        LongPromptFixture.flags = io.onboardkit.remote.RemoteFlags(adsSplashInter = false)
        launch(notification = false)
        drainUntil("LFO1 must not depend on an enabled inter slot") { LongPromptFixture.provider.order == listOf("native") }
        assertEquals(0, LongPromptFixture.provider.interstitialLoads)
    }

    @Test
    fun premiumBlocksBothSplashAndLanguageRequests() {
        LongPromptFixture.provider.premium = true
        LongPromptFixture.flags = io.onboardkit.remote.RemoteFlags(splashLfoParallelPreloadEnabled = true)
        launch(notification = false)
        main.idleFor(Duration.ofSeconds(4))
        assertEquals(0, LongPromptFixture.provider.interstitialLoads)
        assertEquals(0, LongPromptFixture.provider.bannerLoads)
        assertTrue(LongPromptFixture.provider.order.isEmpty())
    }

    @Test
    fun sameTimeKeepsAnInterstitialResultWhichArrivedBeforeTheRemoteHookAndRoute() {
        LongPromptFixture.strategy = io.onboardkit.config.AdLoadStrategy.SAME_TIME
        LongPromptFixture.provider.immediateInterResult = 1
        launch(notification = false)
        drainUntil("An early terminal result must still trigger LFO1") { "native" in LongPromptFixture.provider.order }
        assertEquals(1, LongPromptFixture.provider.interstitialLoads)
        assertEquals(1, LongPromptFixture.provider.order.count { it == "native" })
    }

    @Test
    fun completedFlowDoesNotPreloadLanguageInParallelMode() {
        kotlinx.coroutines.runBlocking { OnboardingSdk.markCompleted() }
        LongPromptFixture.flags = io.onboardkit.remote.RemoteFlags(splashLfoParallelPreloadEnabled = true)
        launch(notification = false)
        drainUntil("Inter must start for the resolved returning route") { LongPromptFixture.provider.interstitialLoads == 1 }
        main.idleFor(Duration.ofSeconds(1))
        assertTrue(LongPromptFixture.provider.order.isEmpty())
    }

    @Test
    fun bannerAndInterstitialShareOneDeadlineAndLateFillCannotShow() {
        LongPromptFixture.flags = io.onboardkit.remote.RemoteFlags(splashAdBudgetMs = 5_000, splashBannerWaitMs = 4_000)
        LongPromptFixture.provider.settleBanner = false
        launch(notification = false)
        drainUntil("Inter must start") { LongPromptFixture.provider.interstitialLoads == 1 }
        main.idleFor(Duration.ofSeconds(6))
        assertEquals("Banner cannot add four more seconds to the inter budget", 1, LongPromptFixture.flowStarts)
        assertEquals(listOf("native"), LongPromptFixture.provider.order)
        LongPromptFixture.provider.ready = true
        requireNotNull(LongPromptFixture.provider.pending).onLoaded()
        main.idle()
        assertEquals(listOf("native"), LongPromptFixture.provider.order)
        assertEquals(1, LongPromptFixture.flowStarts)
    }

    @Test
    fun readyInterstitialShowsBeforeTheMinimumButFailedShowStillWaitsBeforeHandoff() {
        launch(notification = false)
        drainUntil("Inter must start") { LongPromptFixture.provider.interstitialLoads == 1 }
        LongPromptFixture.provider.ready = true
        requireNotNull(LongPromptFixture.provider.pending).onLoaded()
        main.idle()
        assertEquals("Min time must not delay showing a ready inter", listOf("native", "show"), LongPromptFixture.provider.order)
        assertEquals("An immediately skipped show still owes the remaining splash minimum", 0, LongPromptFixture.flowStarts)
        main.idleFor(Duration.ofSeconds(4))
        drainUntil("One handoff after the remaining minimum") { LongPromptFixture.flowStarts == 1 }
    }

    @Test
    fun recreationDoesNotRestartTheBannerWaitOrInterstitialBudget() {
        LongPromptFixture.flags = io.onboardkit.remote.RemoteFlags(splashAdBudgetMs = 10_000, splashBannerWaitMs = 6_000)
        LongPromptFixture.provider.settleBanner = false
        launch(notification = false)
        drainUntil("Inter must start") { LongPromptFixture.provider.interstitialLoads == 1 }
        main.idleFor(Duration.ofSeconds(4))
        requireNotNull(controller).configurationChange(android.content.res.Configuration(
            requireNotNull(controller).get().resources.configuration).apply { fontScale += 0.1f })
        requireNotNull(controller).visible().get().onWindowFocusChanged(true)
        LongPromptFixture.provider.ready = true
        requireNotNull(LongPromptFixture.provider.pending).onLoaded()
        main.idleFor(Duration.ofMillis(2_100))
        assertEquals("The original banner deadline must release the ready inter", listOf("native", "show"), LongPromptFixture.provider.order)
        assertEquals(1, LongPromptFixture.provider.bannerLoads)
        assertEquals(1, LongPromptFixture.provider.interstitialLoads)
    }

    @Test
    fun underAdHandsOffAfterRemainingMinimumWhilePresentationContinues() {
        LongPromptFixture.provider.successfulShow = true
        launch(notification = false)
        drainUntil("Inter must start") { LongPromptFixture.provider.interstitialLoads == 1 }
        LongPromptFixture.provider.ready = true
        requireNotNull(LongPromptFixture.provider.pending).onLoaded()
        main.idle()
        assertNotNull(LongPromptFixture.provider.presentation)
        assertEquals(0, LongPromptFixture.flowStarts)
        main.idleFor(Duration.ofSeconds(4))
        assertEquals(1, LongPromptFixture.flowStarts)
        assertTrue("Splash stays alive until the real close callback", !requireNotNull(controller).get().isFinishing)
        requireNotNull(LongPromptFixture.provider.presentation).onAdClosed()
        main.idle()
        assertTrue(requireNotNull(controller).get().isFinishing)
    }

    @Test
    fun homeDuringUnderAdMinimumWaitCannotNavigateUntilTheAdReturnsToForeground() {
        LongPromptFixture.flags = io.onboardkit.remote.RemoteFlags(splashMinDisplayMs = 10_000)
        LongPromptFixture.provider.successfulShow = true
        launch(notification = false)
        drainUntil("Inter must start") { LongPromptFixture.provider.interstitialLoads == 1 }
        LongPromptFixture.provider.ready = true
        requireNotNull(LongPromptFixture.provider.pending).onLoaded()
        main.idle()
        requireNotNull(controller).get().onWindowFocusChanged(false)
        requireNotNull(controller).pause().stop()
        val ad = Robolectric.buildActivity(LongPromptVendorActivity::class.java).setup().visible()
        try {
            main.idleFor(Duration.ofSeconds(2))
            ad.pause().stop() // Home while the vendor Activity still owns the presentation.
            main.idleFor(Duration.ofSeconds(11))
            assertEquals("UNDER_AD cannot navigate from Home when minimum expires", 0, LongPromptFixture.flowStarts)
            ad.restart().start().resume().visible()
            main.idle()
            assertEquals(1, LongPromptFixture.flowStarts)
            requireNotNull(LongPromptFixture.provider.presentation).onAdClosed()
            main.idle()
        } finally {
            LongPromptFixture.provider.presentation?.onAdClosed()
            ad.pause().stop().destroy()
        }
    }

    @Test
    fun afterAdWaitsForCloseWithoutRepeatingAnAlreadyElapsedMinimum() {
        LongPromptFixture.timing = io.onboardkit.ads.NextScreenTiming.AFTER_AD
        LongPromptFixture.provider.successfulShow = true
        launch(notification = false)
        drainUntil("Inter must start") { LongPromptFixture.provider.interstitialLoads == 1 }
        LongPromptFixture.provider.ready = true
        requireNotNull(LongPromptFixture.provider.pending).onLoaded()
        main.idleFor(Duration.ofSeconds(4))
        assertEquals(0, LongPromptFixture.flowStarts)
        requireNotNull(LongPromptFixture.provider.presentation).onAdClosed()
        main.idle()
        assertEquals(1, LongPromptFixture.flowStarts)
    }

    @Test
    fun recreationKeepsTheOriginalModeAndPendingInterstitialRequest() {
        launch(notification = false)
        drainUntil("Inter must be in flight") { LongPromptFixture.provider.interstitialLoads == 1 }
        val pending = requireNotNull(LongPromptFixture.provider.pending)
        LongPromptFixture.flags = io.onboardkit.remote.RemoteFlags(splashLfoParallelPreloadEnabled = true)
        requireNotNull(controller).configurationChange(android.content.res.Configuration(
            requireNotNull(controller).get().resources.configuration).apply { fontScale += 0.1f })
        requireNotNull(controller).visible().get().onWindowFocusChanged(true)
        main.idleFor(Duration.ofMillis(100))
        assertEquals("Recreation must rejoin the original request", 1, LongPromptFixture.provider.interstitialLoads)
        assertTrue("A late remote mode cannot turn this sequential attempt into parallel", LongPromptFixture.provider.order.isEmpty())
        LongPromptFixture.provider.ready = true
        pending.onLoaded()
        drainUntil("The retained callback must release LFO1") { "native" in LongPromptFixture.provider.order }
        assertEquals(1, LongPromptFixture.provider.order.count { it == "native" })
    }

    @Test
    fun parallelStartsLanguageWhileInterstitialAndNotificationArePending() {
        LongPromptFixture.flags = io.onboardkit.remote.RemoteFlags(splashLfoParallelPreloadEnabled = true)
        launch(notification = true)
        drainUntil("Parallel must start LFO1 without the interstitial outcome") {
            LongPromptFixture.provider.order == listOf("native")
        }
        assertEquals(1, LongPromptFixture.provider.interstitialLoads)
        assertEquals(0, LongPromptFixture.flowStarts)
        assertNotNull(shadowOf(requireNotNull(controller).get()).lastRequestedPermission)
    }

    @Test
    fun splashLoadsStartWhileNotificationIsOpenAndDoNotSpendTheBudget() {
        launch(notification = true)
        val activity = requireNotNull(controller).get()
        drainUntil("Notification must start") { shadowOf(activity).lastRequestedPermission != null }
        activity.onWindowFocusChanged(false)
        drainUntil("Splash must preload under its own notification prompt") {
            LongPromptFixture.provider.interstitialLoads == 1
        }
        main.idleFor(Duration.ofMinutes(2))
        assertEquals(0, LongPromptFixture.flowStarts)
        assertTrue(LongPromptFixture.provider.order.isEmpty())
        val permission = requireNotNull(shadowOf(activity).lastRequestedPermission)
        activity.onRequestPermissionsResult(permission.requestCode, permission.requestedPermissions,
            IntArray(permission.requestedPermissions.size) { PackageManager.PERMISSION_DENIED })
        activity.onWindowFocusChanged(true)
        main.idleFor(Duration.ofSeconds(30))
        assertEquals("The 60-second budget starts after the prompt", 0, LongPromptFixture.flowStarts)
        completeInterstitialAndAssertNormalHandoff()
    }

    @Test
    fun tenMinuteNotificationAnswerGetsSplashWaitBeforeDestinationPreload() {
        notificationAnswerBeforeFill(promptMinutes = 10)
    }

    @Test
    fun tenMinuteNotificationAnswerDoesNotRestartTheMinimumDisplay() {
        notificationAnswerBeforeFill(promptMinutes = 10, minimumAlreadyElapsed = true)
    }

    private fun notificationAnswerBeforeFill(promptMinutes: Long, minimumAlreadyElapsed: Boolean = false) {
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
        assertEquals("Splash banner loads while the notification is open", 1, LongPromptFixture.provider.bannerLoads)
        assertEquals("Splash interstitial loads while the notification is open", 1, LongPromptFixture.provider.interstitialLoads)

        activity.onRequestPermissionsResult(permission.requestCode, permission.requestedPermissions,
            IntArray(permission.requestedPermissions.size) { PackageManager.PERMISSION_DENIED })
        activity.onWindowFocusChanged(true)
        drainUntil("Permission result must start a fresh splash ad phase") { LongPromptFixture.provider.interstitialLoads == 1 }

        assertEquals("Answering permission must not skip an in-flight splash ad", 0, LongPromptFixture.flowStarts)
        assertTrue("Destination must wait for splash fill after a long notification prompt", LongPromptFixture.provider.order.isEmpty())
        if (!minimumAlreadyElapsed) main.idleFor(Duration.ofSeconds(30))
        completeInterstitialAndAssertNormalHandoff(minimumAlreadyElapsed = minimumAlreadyElapsed)
    }

    private fun launch(notification: Boolean) {
        OnboardingSdk.configure(onboardKitConfig {
            splash = SplashConfig(noInternetPromptEnabled = false, notificationPermissionEnabled = notification,
                minDisplayTimeMs = 0, remoteFetchTimeoutMs = 100, adLoadStrategy = LongPromptFixture.strategy)
            step(ContentStepDefinition(StepId.OB1, title = "Introduction"))
            ads = AdsConfig(splashBanner = BannerAdUnit("host-banner"),
                splashInterstitial = InterstitialAdUnit("host-interstitial"),
                languageNative = NativeAdUnit("host-language"))
        }.getOrThrow()).getOrThrow()
        controller = Robolectric.buildActivity(LongPromptSplashActivity::class.java).setup().visible()
        requireNotNull(controller).get().onWindowFocusChanged(true)
        main.idle()
    }

    private fun completeInterstitialAndAssertNormalHandoff(minimumAlreadyElapsed: Boolean = false) {
        assertNotNull("The original public load callback must remain available", LongPromptFixture.provider.pending)
        LongPromptFixture.provider.ready = true
        requireNotNull(LongPromptFixture.provider.pending).onLoaded()
        if (minimumAlreadyElapsed) {
            main.idle()
            assertEquals("Long prompt time already consumed the minimum", 1, LongPromptFixture.flowStarts)
        } else main.idleFor(Duration.ofSeconds(4))
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

class LongPromptVendorActivity : Activity()

class LongPromptSplashActivity : ObSplashActivity() {
    override fun nextScreenTiming() = LongPromptFixture.timing
    override suspend fun onInitBilling() {
        LongPromptFixture.billingEntered = true
        LongPromptFixture.billing?.await()
    }
    override fun onRemoteFetched() {
        OnboardingSdk.remoteOrNull()?.applySnapshot(LongPromptFixture.flags)
        LongPromptFixture.remoteHookCalled = true
    }
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
    var timing = io.onboardkit.ads.NextScreenTiming.UNDER_AD
    var flags = io.onboardkit.remote.RemoteFlags()
    var strategy = io.onboardkit.config.AdLoadStrategy.ALTERNATE
    var billing: CompletableDeferred<Unit>? = null
    var billingEntered = false
    var remoteHookCalled = false
    fun reset() {
        flags = io.onboardkit.remote.RemoteFlags()
        strategy = io.onboardkit.config.AdLoadStrategy.ALTERNATE
        billing = null
        billingEntered = false
        remoteHookCalled = false
        ump = LongPromptConsentInformation()
        form = LongPromptConsentForm()
        flowStarts = 0
        timing = io.onboardkit.ads.NextScreenTiming.UNDER_AD
        provider.successfulShow = false
        provider.presentation = null
        provider.immediateInterResult = 0
        provider.premium = false
        provider.settleBanner = true
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
    var successfulShow = false
    var presentation: ObInterstitialCallback? = null
    var settleBanner = true
    var immediateInterResult = 0
    var premium = false
    var interstitialRequestAtMs = 0L
    var interstitialLoads = 0
    var pending: AdEventListener? = null
    var ready = false
    val order = mutableListOf<String>()
    override fun isPremium(context: Context) = premium
    override fun preloadNative(activity: Activity, request: NativeAdRequest) { order += "native" }
    override fun isNativeReady(placement: AdPlacement) = false
    override fun isNativeLoading(placement: AdPlacement) = false
    override fun bindNative(activity: Activity, placement: AdPlacement, container: ViewGroup, shimmer: View?, listener: AdEventListener?) = false
    override fun releaseNative(placement: AdPlacement) = Unit
    override fun loadInterstitial(context: Context, placement: AdPlacement, unit: InterstitialAdUnit, listener: AdEventListener?) {
        interstitialLoads++
        interstitialRequestAtMs = SystemClock.elapsedRealtime()
        pending = listener
        if (immediateInterResult == 1) { ready = true; listener?.onLoaded() }
        if (immediateInterResult == 2) listener?.onFailedToLoad()
    }
    override fun isInterstitialReady(placement: AdPlacement) = ready
    override fun showInterstitial(activity: Activity, placement: AdPlacement, callback: ObInterstitialCallback) {
        order += "show"
        if (successfulShow) { presentation = callback; callback.onNextAction() }
        else callback.onAdSkipped(AdSkipReason.NOT_READY)
    }
    override fun loadBanner(activity: Activity, unit: BannerAdUnit, listener: AdEventListener?) {
        bannerLoads++
        if (settleBanner) listener?.onFailedToLoad()
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
