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
import android.widget.ProgressBar
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
import io.onboardkit.ads.FakeAdProvider
import io.onboardkit.ads.NativeStatus
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
import io.onboardkit.paywall.PaywallGate
import io.onboardkit.paywall.PaywallOutcome
import io.onboardkit.paywall.PaywallPlacement
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import org.junit.After
import org.junit.Assert.assertFalse
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
        // Advance frames only with the test clock; auto-vsync would drain the whole animation.
        org.robolectric.shadows.ShadowChoreographer.setPaused(true)
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        LongPromptFixture.reset()
        app.applicationInfo.targetSdkVersion = 34

        ConsentCenter.reset(app)
        ConsentCenter.configure(ConsentOptions(debug = false, timeoutMs = 20_000))
        OnboardingSdk.install(app) {
            adProvider = LongPromptFixture.provider
            paywallGate = object : PaywallGate {
                override suspend fun shouldShow(placement: PaywallPlacement) = LongPromptFixture.paywallEnabled
                override suspend fun present(activity: Activity, placement: PaywallPlacement): PaywallOutcome {
                    LongPromptFixture.paywallCalls++
                    return LongPromptFixture.paywallWait?.await() ?: PaywallOutcome.ContinueWithAds
                }
            }
            trackkitAutoTracking(false)
            analyticsPlugin(AnalyticsPlugin {
                if (it is AnalyticsEvent.FlowStarted) LongPromptFixture.flowStarts++
                if (it is AnalyticsEvent.SplashCompleted) LongPromptFixture.splashHandoffs++
                if (it is AnalyticsEvent.SplashViewed) LongPromptFixture.splashViews++
            })
        }
        runBlocking { OnboardingSdk.reset() }
    }

    @After
    fun tearDown() {
        LongPromptFixture.provider.presentation?.onAdClosed()
        controller?.pause()?.stop()?.destroy()
        ConsentCenter.reset(app)
        org.robolectric.util.ReflectionHelpers.getField<kotlinx.coroutines.Deferred<Unit>?>(OnboardingSdk, "remoteRefresh")?.cancel()
        org.robolectric.util.ReflectionHelpers.setField(com.ads.module.config.AdConfig, "source", null)
        com.ads.module.config.AdRemoteConfig.reset()
        main.idle()
        assertEquals("Destroyed splash must release its request hold", false,
            com.ads.module.helper.AdGate.areRequestsHeld())
    }

    private fun mandatoryUpdate() = com.ads.module.update.ForceUpdateConfig(
        enabled = true, minVersionCode = Long.MAX_VALUE, force = true,
        storeLink = "https://play.google.com/store/apps/details?id=test",
    )

    private fun reachUpdateBoundary() {
        drainUntil("Mandatory update must appear before any ad request") {
            org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()?.isShowing == true
        }
    }

    private fun assertNoUpdateAdRequests() {
        assertEquals(0, LongPromptFixture.provider.interstitialLoads)
        assertEquals(0, LongPromptFixture.provider.bannerLoads)
        assertTrue(LongPromptFixture.provider.nativeRequests.isEmpty())
        assertTrue(LongPromptFixture.provider.order.isEmpty())
        assertEquals(0, LongPromptFixture.paywallCalls)
        assertEquals(true, com.ads.module.helper.AdGate.areRequestsHeld())
    }

    @Test
    fun currentVersionAndOptionalUpdateReleaseRequestsNormally() {
        val current = androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(
            app.packageManager.getPackageInfo(app.packageName, 0),
        )
        LongPromptFixture.updateConfig = mandatoryUpdate().copy(minVersionCode = current)
        launch(notification = false)
        drainUntil("Current version must be allowed to load") { LongPromptFixture.provider.pending != null }
        assertEquals(false, com.ads.module.helper.AdGate.areRequestsHeld())
        completeInterstitialAndAssertNormalHandoff()
        assertEquals(null, org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog())
    }

    @Test
    fun optionalUpdateDoesNotKeepTheRequestHold() {
        LongPromptFixture.updateConfig = mandatoryUpdate().copy(force = false)
        launch(notification = false)
        drainUntil("Optional update still allows loads") { LongPromptFixture.provider.pending != null }
        assertEquals(false, com.ads.module.helper.AdGate.areRequestsHeld())
        LongPromptFixture.provider.ready = true
        requireNotNull(LongPromptFixture.provider.pending).onLoaded()
        idleFrames(Duration.ofSeconds(4))
        reachUpdateBoundary()
        org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            .getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick()
        main.idle()
        assertEquals(1, LongPromptFixture.flowStarts)
    }

    @Test
    fun consentFinishingStartsBothAdsWhileRemoteIsPending() {
        LongPromptFixture.remoteWait = CompletableDeferred()
        launch(notification = false)
        drainUntil("Consent releases both splash requests without remote") {
            LongPromptFixture.remoteEntered && LongPromptFixture.provider.interstitialLoads == 1
        }
        assertEquals(1, LongPromptFixture.provider.bannerLoads)
        assertFalse(LongPromptFixture.remoteWait!!.isCompleted)
        assertFalse(LongPromptFixture.remoteHookCalled)
        completeInterstitialAndAssertNormalHandoff()
    }

    @Test
    fun lateRemotePolicyBlocksPresentationWithoutHoldingInitialRequests() {
        LongPromptFixture.remoteWait = CompletableDeferred()
        launch(notification = false)
        drainUntil("Consent releases loads") { LongPromptFixture.provider.pending != null }
        LongPromptFixture.updateConfig = mandatoryUpdate()
        LongPromptFixture.remoteWait!!.complete(Unit)
        drainUntil("Remote hook receives the late policy") { LongPromptFixture.remoteHookCalled }
        loadInterstitialNow()
        idleFrames(Duration.ofSeconds(4))
        reachUpdateBoundary()
        assertEquals(1, LongPromptFixture.provider.bannerLoads)
        assertEquals(1, LongPromptFixture.provider.interstitialLoads)
        assertTrue("show" !in LongPromptFixture.provider.order)
        assertEquals(0, LongPromptFixture.flowStarts)
    }

    @Test
    fun requiredUpdateArrivingDuringPaywallStillBlocksTheInterstitial() {
        LongPromptFixture.remoteWait = CompletableDeferred()
        LongPromptFixture.paywallEnabled = true
        LongPromptFixture.paywallWait = CompletableDeferred()
        launch(notification = false)
        drainUntil("Inter request starts") { LongPromptFixture.provider.pending != null }
        loadInterstitialNow()
        idleFrames(Duration.ofSeconds(4))
        drainUntil("Paywall holds presentation") { LongPromptFixture.paywallCalls == 1 }
        LongPromptFixture.updateConfig = mandatoryUpdate()
        LongPromptFixture.remoteWait!!.complete(Unit)
        drainUntil("Remote policy applies before paywall closes") { LongPromptFixture.remoteHookCalled }
        LongPromptFixture.paywallWait!!.complete(PaywallOutcome.ContinueWithAds)
        reachUpdateBoundary()
        assertTrue("show" !in LongPromptFixture.provider.order)
        assertEquals(0, LongPromptFixture.flowStarts)
    }

    @Test
    fun remoteAndUmpRunTogetherButUpdateWaitsForBoth() {
        LongPromptFixture.remoteTimeoutMs = 60_000
        LongPromptFixture.ump.holdUpdate = true
        LongPromptFixture.remoteWait = CompletableDeferred()
        LongPromptFixture.updateConfig = mandatoryUpdate()
        launch(notification = false)
        drainUntil("Remote must start while UMP is still unresolved") {
            LongPromptFixture.remoteEntered && LongPromptFixture.ump.pendingUpdate != null
        }
        assertEquals(null, org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog())
        LongPromptFixture.remoteWait!!.complete(Unit)
        main.idle()
        assertEquals(null, org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog())
        LongPromptFixture.ump.allowed = true
        requireNotNull(LongPromptFixture.ump.pendingUpdate).onConsentInfoUpdateSuccess()
        reachUpdateBoundary()
        assertEquals(0, LongPromptFixture.flowStarts)
    }

    @Test
    fun sameTimeDoesNotWaitForRemoteBeforeRequestsOrNotification() {
        LongPromptFixture.strategy = io.onboardkit.config.AdLoadStrategy.SAME_TIME
        LongPromptFixture.remoteWait = CompletableDeferred()
        launch(notification = true)
        val host = requireNotNull(controller).get()
        drainUntil("Prompt follows requests while remote is pending") {
            shadowOf(host).lastRequestedPermission != null
        }
        assertEquals(1, LongPromptFixture.provider.interstitialLoads)
        assertEquals(1, LongPromptFixture.provider.bannerLoads)
        assertFalse(LongPromptFixture.remoteHookCalled)
        assertFalse(LongPromptFixture.remoteWait!!.isCompleted)
    }

    @Test
    fun alternateSendsZeroRequestsDuringForceUpdateAndRecreation() {
        LongPromptFixture.updateConfig = mandatoryUpdate()
        launch(notification = false)
        reachUpdateBoundary()
        assertEquals(0, LongPromptFixture.provider.interstitialLoads)
        assertEquals(0, LongPromptFixture.provider.bannerLoads)
        assertEquals(true, LongPromptFixture.billingEntered)
        assertEquals(true, LongPromptFixture.ump.allowed)
        assertNoUpdateAdRequests()
        assertTrue("show" !in LongPromptFixture.provider.order)
        assertEquals(0, LongPromptFixture.flowStarts)
        drainUntil("Remote observer settles") { LongPromptFixture.remoteHookCalled }
        val readsBeforeRecreation = LongPromptFixture.updateReads
        val previous = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        recreateSplash().visible().get().onWindowFocusChanged(true)
        main.idle()
        assertEquals(false, previous.isShowing)
        assertTrue(org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().isShowing)
        assertEquals(0, LongPromptFixture.provider.interstitialLoads)
        LongPromptFixture.updateConfig = null
        recreateSplash().visible().get().onWindowFocusChanged(true)
        main.idle()
        assertTrue(org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().isShowing)
        assertEquals(readsBeforeRecreation, LongPromptFixture.updateReads)
        assertEquals(0, LongPromptFixture.flowStarts)
    }

    @Test
    fun remoteAppliesAfterSplashIsDestroyedWithoutCallingItsHook() {
        LongPromptFixture.remoteWait = CompletableDeferred()
        LongPromptFixture.remoteSettings = mapOf("onboarding_config" to
            """{"lfo":{"tap_hint":{"enabled":false}}}""")
        launch(notification = false)
        drainUntil("Fetch and requests start") {
            LongPromptFixture.remoteEntered && LongPromptFixture.provider.pending != null
        }
        assertTrue(OnboardingSdk.requireConfig().language.tapHintEnabled)
        completeInterstitialAndAssertNormalHandoff()
        requireNotNull(controller).pause().stop().destroy()
        controller = null
        LongPromptFixture.remoteWait!!.complete(Unit)
        drainUntil("SDK applies late settings after splash destruction") {
            !OnboardingSdk.requireConfig().language.tapHintEnabled
        }
        assertFalse(LongPromptFixture.remoteHookCalled)
        assertEquals(1, LongPromptFixture.provider.interstitialLoads)
    }

    @Test
    fun remoteChangesInsideAdDoNotAlterTheCurrentLaunchDecision() {
        LongPromptFixture.provider.successfulShow = true
        LongPromptFixture.provider.holdNext = true
        launch(notification = false)
        drainUntil("Inter loads") { LongPromptFixture.provider.pending != null }
        LongPromptFixture.provider.ready = true
        requireNotNull(LongPromptFixture.provider.pending).onLoaded()
        idleFrames(Duration.ofSeconds(4))
        drainUntil("Ad is open") { LongPromptFixture.provider.presentation != null }
        val readsBeforeAd = LongPromptFixture.updateReads
        LongPromptFixture.updateConfig = mandatoryUpdate()
        requireNotNull(LongPromptFixture.provider.presentation).onNextAction()
        main.idle()
        assertEquals(1, LongPromptFixture.flowStarts)
        assertEquals(null, org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog())
        assertEquals(readsBeforeAd, LongPromptFixture.updateReads)
    }

    @Test
    fun splashNativePreloadsWithLfoAfterInterLoadedAndFinishesBeforeLfoStarts() {
        LongPromptFixture.nativeConfigured = true
        LongPromptFixture.provider.nativeReady = false
        LongPromptFixture.provider.successfulShow = true
        launch(notification = false)
        drainUntil("Inter must start") { LongPromptFixture.provider.pending != null }
        assertTrue(LongPromptFixture.provider.nativeRequests.isEmpty())
        LongPromptFixture.provider.ready = true
        requireNotNull(LongPromptFixture.provider.pending).onLoaded()
        main.idle()
        assertEquals(setOf(AdPlacement.Language1, AdPlacement.SplashNative), LongPromptFixture.provider.nativeRequests.toSet())
        LongPromptFixture.provider.nativeReady = true
        idleFrames(Duration.ofSeconds(4))
        assertEquals("An optional splash native prevents UNDER_AD from opening LFO early", 0, LongPromptFixture.flowStarts)
        requireNotNull(LongPromptFixture.provider.presentation).onAdClosed()
        main.idle()
        val host = requireNotNull(controller).get()
        val request = requireNotNull(shadowOf(host).nextStartedActivityForResult)
        assertEquals(ObSplashNativeActivity::class.java.name, request.intent.component?.className)
        assertEquals(0, LongPromptFixture.flowStarts)
        host.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK, null)
        main.idle()
        assertEquals(1, LongPromptFixture.flowStarts)
        assertEquals(1, LongPromptFixture.splashHandoffs)
    }

    @Test
    fun pendingSplashNativeOpensToWaitWithoutAnotherPreloadAndBlocksLfo() {
        LongPromptFixture.nativeConfigured = true
        LongPromptFixture.provider.nativeReady = false
        LongPromptFixture.provider.successfulShow = true
        launch(notification = false)
        drainUntil("Inter must start") { LongPromptFixture.provider.pending != null }
        assertTrue(LongPromptFixture.provider.nativeRequests.isEmpty())
        LongPromptFixture.provider.ready = true
        requireNotNull(LongPromptFixture.provider.pending).onLoaded()
        main.idle()
        assertEquals(setOf(AdPlacement.Language1, AdPlacement.SplashNative), LongPromptFixture.provider.nativeRequests.toSet())
        idleFrames(Duration.ofSeconds(4))
        assertEquals("An optional splash native prevents UNDER_AD from opening LFO early", 0, LongPromptFixture.flowStarts)
        requireNotNull(LongPromptFixture.provider.presentation).onAdClosed()
        main.idle()
        val host = requireNotNull(controller).get()
        val request = requireNotNull(shadowOf(host).nextStartedActivityForResult)
        assertEquals(ObSplashNativeActivity::class.java.name, request.intent.component?.className)
        assertEquals(0, LongPromptFixture.flowStarts)
        assertEquals(listOf(AdPlacement.Language1, AdPlacement.SplashNative), LongPromptFixture.provider.nativeRequests)
        host.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK, null)
        main.idle()
        assertEquals(1, LongPromptFixture.flowStarts)
        assertEquals(1, LongPromptFixture.splashHandoffs)
    }

    @Test
    fun recreationWhileSplashNativeIsOpenWaitsForOneResultWithoutRelaunch() {
        LongPromptFixture.nativeConfigured = true
        LongPromptFixture.provider.successfulShow = true
        launch(notification = false)
        drainUntil("Inter must start") { LongPromptFixture.provider.pending != null }
        LongPromptFixture.provider.ready = true
        requireNotNull(LongPromptFixture.provider.pending).onLoaded()
        main.idle()
        LongPromptFixture.provider.nativeReady = true
        idleFrames(Duration.ofSeconds(4))
        requireNotNull(LongPromptFixture.provider.presentation).onAdClosed()
        main.idle()
        val request = requireNotNull(shadowOf(requireNotNull(controller).get()).nextStartedActivityForResult)
        recreateSplash().visible()
        val recreated = requireNotNull(controller).get()
        recreated.onWindowFocusChanged(true)
        main.idle()
        assertEquals(0, LongPromptFixture.flowStarts)
        assertEquals(null, shadowOf(recreated).nextStartedActivityForResult)
        recreated.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK, null)
        main.idle()
        assertEquals(1, LongPromptFixture.flowStarts)
        assertEquals(1, LongPromptFixture.splashHandoffs)
    }

    @Test
    fun failedSplashNativeDoesNotDelayLfoOrAddAnEmptyScreen() {
        LongPromptFixture.nativeConfigured = true
        LongPromptFixture.provider.successfulShow = true
        launch(notification = false)
        drainUntil("Inter must start") { LongPromptFixture.provider.pending != null }
        LongPromptFixture.provider.ready = true
        requireNotNull(LongPromptFixture.provider.pending).onLoaded()
        idleFrames(Duration.ofSeconds(4))
        LongPromptFixture.provider.failedNatives += AdPlacement.SplashNative
        requireNotNull(LongPromptFixture.provider.presentation).onAdClosed()
        main.idle()
        assertEquals(1, LongPromptFixture.flowStarts)
        assertEquals(io.onboardkit.ui.language.ObLanguageActivity::class.java.name,
            shadowOf(requireNotNull(controller).get()).nextStartedActivity.component?.className)
    }

    @Test
    fun failedInterstitialDoesNotPreloadSplashNative() {
        LongPromptFixture.nativeConfigured = true
        launch(notification = false)
        drainUntil("Inter must start") { LongPromptFixture.provider.pending != null }
        requireNotNull(LongPromptFixture.provider.pending).onFailedToLoad()
        idleFrames(Duration.ofSeconds(4))
        assertEquals(listOf(AdPlacement.Language1), LongPromptFixture.provider.nativeRequests)
        assertEquals(1, LongPromptFixture.flowStarts)
    }

    @Test
    fun progressCountsVisibleUmpNetworkWaitButPausesWhileTheFormIsOpen() {
        LongPromptFixture.ump.holdUpdate = true
        LongPromptFixture.ump.requireForm = true
        launch(notification = false)
        drainUntil("UMP request must be pending") { LongPromptFixture.ump.pendingUpdate != null }
        val bar = requireNotNull(controller).get()
            .findViewById<ProgressBar>(io.onboardkit.R.id.ob_splash_progress)

        idleFrames(Duration.ofMillis(250))
        assertFalse("Progress must be determinate before UMP returns", bar.isIndeterminate)
        assertTrue("Visible network wait advances progress", bar.progress > 0)
        assertTrue(bar.isShown)
        assertEquals(0, LongPromptFixture.provider.interstitialLoads)

        requireNotNull(LongPromptFixture.ump.pendingUpdate).onConsentInfoUpdateSuccess()
        drainUntil("UMP form must be open") { LongPromptFixture.form.dismiss != null }
        idleFrames(Duration.ofMillis(100))
        val beforeFormWait = bar.progress
        idleFrames(Duration.ofSeconds(70))
        assertEquals("A visible UMP form pauses progress and the budget", beforeFormWait, bar.progress)
        assertEquals(0, LongPromptFixture.flowStarts)
        assertEquals(0, LongPromptFixture.provider.bannerLoads)

        LongPromptFixture.ump.allowed = true
        requireNotNull(LongPromptFixture.form.dismiss).onConsentFormDismissed(null)
        drainUntil("Accept must release ad requests") { LongPromptFixture.provider.interstitialLoads == 1 }
        assertFalse("Ad loading keeps determinate progress", bar.isIndeterminate)
        completeInterstitialAndAssertNormalHandoff()
    }

    @Test
    fun tenMinuteUmpAnswerStillStartsBothSplashLoadsBeforeDestinationPreload() {
        LongPromptFixture.ump.requireForm = true
        launch(notification = false)
        drainUntil("UMP form must actually be shown through ConsentCenter") { LongPromptFixture.form.dismiss != null }

        idleFrames(Duration.ofMinutes(10))

        assertEquals("A live unanswered UMP form must not hand off the flow", 0, LongPromptFixture.flowStarts)
        assertEquals(0, LongPromptFixture.provider.bannerLoads)
        assertTrue(LongPromptFixture.provider.order.isEmpty())
        LongPromptFixture.ump.allowed = true
        requireNotNull(LongPromptFixture.form.dismiss).onConsentFormDismissed(null)
        drainUntil("Accept must start the normal splash load pair") { LongPromptFixture.provider.interstitialLoads == 1 }
        assertEquals(1, LongPromptFixture.provider.bannerLoads)
        assertTrue(LongPromptFixture.provider.order.isEmpty())
        idleFrames(Duration.ofSeconds(30))
        completeInterstitialAndAssertNormalHandoff()
    }

    @Test
    fun resolvedConsentWithoutNotificationRunsNormalSplashLoadAndPreload() {
        launch(notification = false)
        drainUntil("Resolved consent must start splash loading") { LongPromptFixture.provider.interstitialLoads == 1 }
        assertEquals(1, LongPromptFixture.provider.bannerLoads)
        assertTrue(LongPromptFixture.provider.order.isEmpty())
        idleFrames(Duration.ofSeconds(5))
        completeInterstitialAndAssertNormalHandoff()
    }

    @Test
    fun billingDoesNotHoldRequestsAndHomeStillDefersPresentation() {
        LongPromptFixture.billing = CompletableDeferred()
        launch(notification = false)
        drainUntil("Requests need no billing completion") { LongPromptFixture.provider.pending != null }
        assertTrue(LongPromptFixture.billingEntered)
        assertFalse(LongPromptFixture.billing!!.isCompleted)
        val host = requireNotNull(controller).get()
        host.onWindowFocusChanged(false)
        requireNotNull(controller).pause().stop()
        loadInterstitialNow()
        idleFrames(Duration.ofSeconds(6))
        assertTrue("show" !in LongPromptFixture.provider.order)
        requireNotNull(controller).start().resume().visible()
        host.onWindowFocusChanged(true)
        drainUntil("A ready interstitial shows after returning") { "show" in LongPromptFixture.provider.order }
    }

    @Test
    fun sequentialStartsLfoOnTerminalFailureUnderNotificationOnlyOnce() {
        LongPromptFixture.nativeConfigured = true
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
    fun allowNotificationReleasesFirstOpenWithLfoAlreadyPreloaded() {
        answerNotificationWithReadyInterstitial(intArrayOf(PackageManager.PERMISSION_GRANTED))
    }

    @Test
    fun denyNotificationReleasesFirstOpenWithLfoAlreadyPreloaded() {
        answerNotificationWithReadyInterstitial(intArrayOf(PackageManager.PERMISSION_DENIED))
    }

    @Test
    fun cancelledNotificationReleasesFirstOpenWithLfoAlreadyPreloaded() {
        answerNotificationWithReadyInterstitial(intArrayOf())
    }

    private fun answerNotificationWithReadyInterstitial(grants: IntArray) {
        LongPromptFixture.useDefaultTiming = true
        LongPromptFixture.provider.successfulShow = true
        launch(notification = true)
        drainUntil("Inter must start under notification") { LongPromptFixture.provider.pending != null }
        val host = requireNotNull(controller).get()
        val permission = requireNotNull(shadowOf(host).lastRequestedPermission)
        host.onWindowFocusChanged(false)
        LongPromptFixture.provider.ready = true
        requireNotNull(LongPromptFixture.provider.pending).onLoaded()
        idleFrames(Duration.ofSeconds(4))
        assertEquals("One LFO preload while the permission is unanswered", listOf("native"), LongPromptFixture.provider.order)
        assertEquals(0, LongPromptFixture.flowStarts)

        host.onRequestPermissionsResult(permission.requestCode,
            if (grants.isEmpty()) emptyArray() else permission.requestedPermissions, grants)
        main.idle()
        assertEquals("Permission result alone cannot show without window focus", listOf("native"), LongPromptFixture.provider.order)
        host.onWindowFocusChanged(true)
        drainUntil("Allow, denial and cancellation must all release show") { LongPromptFixture.provider.presentation != null }
        assertEquals(listOf("native", "show"), LongPromptFixture.provider.order)
        assertEquals("Default first open still waits for ad dismissal", 0, LongPromptFixture.flowStarts)
        requireNotNull(LongPromptFixture.provider.presentation).onAdClosed()
        drainUntil("Dismissal must open the first-open flow exactly once") { LongPromptFixture.flowStarts == 1 }
        assertEquals(1, LongPromptFixture.splashHandoffs)
        assertEquals(listOf("native", "show"), LongPromptFixture.provider.order)
    }

    @Test
    fun resultWhileHomeUnderNotificationCannotStartLfoUntilTheSplashIsVisible() {
        launch(notification = true)
        drainUntil("Inter must start") { LongPromptFixture.provider.interstitialLoads == 1 }
        val host = requireNotNull(controller).get()
        host.onWindowFocusChanged(false)
        requireNotNull(controller).pause().stop()
        requireNotNull(LongPromptFixture.provider.pending).onFailedToLoad()
        idleFrames(Duration.ofSeconds(2))
        assertTrue(LongPromptFixture.provider.order.isEmpty())
        requireNotNull(controller).restart().start().resume().visible()
        main.idle()
        assertEquals(listOf("native"), LongPromptFixture.provider.order)
        assertEquals(0, LongPromptFixture.flowStarts)
    }

    @Test
    fun backgroundTimeDoesNotSpendTheVisibleBudgetAndReadyFillStillShows() {
        LongPromptFixture.flags = io.onboardkit.remote.RemoteFlags(splashAdBudgetMs = 10_000, splashMinDisplayMs = 100)
        launch(notification = false)
        drainUntil("Inter must start") { LongPromptFixture.provider.interstitialLoads == 1 }
        idleFrames(Duration.ofMillis(200))
        val host = requireNotNull(controller).get()
        host.onWindowFocusChanged(false)
        requireNotNull(controller).pause().stop()
        idleFrames(Duration.ofSeconds(11))
        LongPromptFixture.provider.ready = true
        requireNotNull(LongPromptFixture.provider.pending).onLoaded()
        main.idle()
        assertTrue("Background must hold LFO: ${LongPromptFixture.provider.order}", LongPromptFixture.provider.order.isEmpty())
        assertEquals(0, LongPromptFixture.flowStarts)
        requireNotNull(controller).restart().start().resume().visible()
        host.onWindowFocusChanged(true)
        main.idle()
        assertEquals(listOf("native", "show"), LongPromptFixture.provider.order)
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
        idleFrames(Duration.ofSeconds(3))
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
        idleFrames(Duration.ofSeconds(4))
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
        idleFrames(Duration.ofSeconds(1))
        assertEquals("Parallel mode warms Welcome Back slot 1 instead of LFO1",
            listOf(AdPlacement.WelcomeBack1), LongPromptFixture.provider.nativeRequests)
    }

    @Test
    fun completedFlowWithWelcomeBackOffPreloadsNothing() {
        io.onboardkit.remote.OnboardingSettings.document.acceptSuccessfulFetch("""{"welcome_back":{"enabled":false}}""")
        try {
            kotlinx.coroutines.runBlocking { OnboardingSdk.markCompleted() }
            LongPromptFixture.flags = io.onboardkit.remote.RemoteFlags(splashLfoParallelPreloadEnabled = true)
            launch(notification = false)
            drainUntil("Inter must start for the resolved returning route") { LongPromptFixture.provider.interstitialLoads == 1 }
            idleFrames(Duration.ofSeconds(1))
            assertTrue(LongPromptFixture.provider.order.isEmpty())
        } finally {
            io.onboardkit.remote.OnboardingSettings.document.acceptSuccessfulFetch(null)
        }
    }

    @Test
    fun bannerAndInterstitialShareOneDeadlineAndLateFillCannotShow() {
        LongPromptFixture.nativeConfigured = true
        LongPromptFixture.flags = io.onboardkit.remote.RemoteFlags(splashAdBudgetMs = 5_000, splashSlotMinVisibleMs = 4_000)
        LongPromptFixture.provider.settleBanner = false
        launch(notification = false)
        drainUntil("Inter must start") { LongPromptFixture.provider.interstitialLoads == 1 }
        idleFrames(Duration.ofSeconds(6))
        assertEquals("Banner cannot add four more seconds to the inter budget", 1, LongPromptFixture.flowStarts)
        assertEquals(listOf("native"), LongPromptFixture.provider.order)
        LongPromptFixture.provider.ready = true
        requireNotNull(LongPromptFixture.provider.pending).onLoaded()
        main.idle()
        assertEquals(listOf("native"), LongPromptFixture.provider.order)
        assertEquals(1, LongPromptFixture.flowStarts)
    }

    @Test
    fun aLoadedInterstitialDoesNotWaitForASilentBanner() {
        LongPromptFixture.provider.settleBanner = false
        launch(notification = false)
        drainUntil("Inter starts") { LongPromptFixture.provider.pending != null }
        idleFrames(Duration.ofSeconds(15))
        loadInterstitialNow()
        main.idle()
        assertEquals(listOf("native", "show"), LongPromptFixture.provider.order)
        assertEquals(1, LongPromptFixture.flowStarts)
    }

    @Test
    fun remoteSlotTimersCannotHoldAReadyInterstitial() {
        io.onboardkit.remote.OnboardingSettings.document.acceptSuccessfulFetch(
            """{"splash":{"timing":{"slot_wait_after_inter_ms":60000,"slot_min_visible_ms":60000}}}""")
        try {
            LongPromptFixture.provider.settleBanner = false
            launch(notification = false)
            drainUntil("Inter starts") { LongPromptFixture.provider.pending != null }
            idleFrames(Duration.ofSeconds(4))
            loadInterstitialNow()
            main.idle()
            assertEquals(listOf("native", "show"), LongPromptFixture.provider.order)
        } finally {
            io.onboardkit.remote.OnboardingSettings.document.acceptSuccessfulFetch(null)
        }
    }

    @Test
    fun aFailedInterstitialLeavesWithoutWaitingForASilentSlot() {
        LongPromptFixture.provider.settleBanner = false
        launch(notification = false)
        drainUntil("Inter starts") { LongPromptFixture.provider.pending != null }
        requireNotNull(LongPromptFixture.provider.pending).onFailedToLoad()
        idleFrames(Duration.ofSeconds(4))
        assertEquals(1, LongPromptFixture.flowStarts)
        assertEquals(listOf("native"), LongPromptFixture.provider.order)
    }

    @Test
    fun splashSlotAndInterstitialGoOutBeforeTheNotificationPrompt() {
        val promptOpenAtRequest = mutableListOf<Boolean?>()
        LongPromptFixture.provider.onSplashRequest = {
            promptOpenAtRequest += controller?.get()?.let { shadowOf(it).lastRequestedPermission != null }
        }
        launch(notification = true)
        val host = requireNotNull(controller).get()
        drainUntil("The prompt still follows the requests") { shadowOf(host).lastRequestedPermission != null }
        assertEquals("Banner and inter both went out before the prompt", listOf(false, false), promptOpenAtRequest)
    }

    @Test
    fun aReadyInterstitialWaitsForThePromptButNotTheSilentSlot() {
        LongPromptFixture.provider.settleBanner = false
        launch(notification = true)
        val host = requireNotNull(controller).get()
        drainUntil("Prompt and inter must start") {
            shadowOf(host).lastRequestedPermission != null && LongPromptFixture.provider.interstitialLoads == 1
        }
        host.onWindowFocusChanged(false)
        loadInterstitialNow()
        idleFrames(Duration.ofSeconds(15))
        val permission = requireNotNull(shadowOf(host).lastRequestedPermission)
        host.onRequestPermissionsResult(permission.requestCode, permission.requestedPermissions,
            IntArray(permission.requestedPermissions.size) { PackageManager.PERMISSION_DENIED })
        val closedAt = SystemClock.elapsedRealtime()
        host.onWindowFocusChanged(true)
        assertEquals(listOf("native"), LongPromptFixture.provider.order)
        idleUntil(closedAt + 900)
        assertEquals(listOf("native"), LongPromptFixture.provider.order)
        idleUntil(closedAt + 1_000)
        assertEquals(listOf("native", "show"), LongPromptFixture.provider.order)
        assertNotificationSettle(closedAt)
    }

    @Test
    fun quicklyAnsweredNotificationShowsReadyInterstitialAfterOneSecondWithoutMinimumDisplay() {
        LongPromptFixture.flags = io.onboardkit.remote.RemoteFlags(splashMinDisplayMs = 30_000)
        LongPromptFixture.provider.settleBanner = false
        launch(notification = true)
        val host = requireNotNull(controller).get()
        drainUntil("Prompt and inter must start") {
            shadowOf(host).lastRequestedPermission != null && LongPromptFixture.provider.pending != null
        }
        loadInterstitialNow()
        val permission = requireNotNull(shadowOf(host).lastRequestedPermission)
        host.onRequestPermissionsResult(permission.requestCode, permission.requestedPermissions,
            IntArray(permission.requestedPermissions.size) { PackageManager.PERMISSION_DENIED })
        val closedAt = SystemClock.elapsedRealtime()
        idleUntil(closedAt + 900)
        assertEquals(listOf("native"), LongPromptFixture.provider.order)
        idleUntil(closedAt + 1_000)
        assertEquals(listOf("native", "show"), LongPromptFixture.provider.order)
        assertNotificationSettle(closedAt)
    }

    @Test
    fun progressReachesNinetyInTenVisibleSecondsAndTimeoutInSixty() {
        launch(notification = false)
        drainUntil("Inter starts") { LongPromptFixture.provider.pending != null }
        val host = requireNotNull(controller).get()
        val bar = host.findViewById<ProgressBar>(io.onboardkit.R.id.ob_splash_progress)
        idleFrames(Duration.ofSeconds(10))
        assertTrue("90% within one animation frame", bar.progress in 8_980..9_010)
        host.onWindowFocusChanged(false)
        idleFrames(Duration.ofSeconds(70))
        assertTrue("90% within one animation frame", bar.progress in 8_980..9_010)
        assertEquals(0, LongPromptFixture.flowStarts)
        host.onWindowFocusChanged(true)
        idleFrames(Duration.ofSeconds(40))
        assertTrue("Slow final segment", bar.progress in 9_950..9_999)
        assertEquals(0, LongPromptFixture.flowStarts)
        idleFrames(Duration.ofSeconds(10))
        assertEquals(10_000, bar.progress)
        assertEquals(1, LongPromptFixture.flowStarts)
    }

    @Test
    fun valueAnimatorMovesTheBarBetweenIntegerPercentages() {
        launch(notification = false)
        drainUntil("Inter starts") { LongPromptFixture.provider.pending != null }
        idleFrames(Duration.ofMillis(550))
        val host = requireNotNull(controller).get()
        val bar = host.findViewById<ProgressBar>(io.onboardkit.R.id.ob_splash_progress)
        assertEquals(10_000, bar.max)
        assertTrue("The bar has sub-percent precision", bar.progress % 100 != 0)
        val animator = org.robolectric.util.ReflectionHelpers.getField<android.animation.ValueAnimator>(host, "progressAnimator")
        assertTrue(animator.isRunning)
        host.onWindowFocusChanged(false)
        assertFalse("Hidden splash cancels animation frames", animator.isRunning)
        val paused = bar.progress
        idleFrames(Duration.ofSeconds(70))
        assertEquals(paused, bar.progress)
        assertEquals(0, LongPromptFixture.flowStarts)
    }

    @Test
    fun disablingAnimationsKeepsNumericProgressWithoutCompletingTheBudgetEarly() {
        org.robolectric.util.ReflectionHelpers.callStaticMethod<Void>(
            android.animation.ValueAnimator::class.java, "setDurationScale",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Float::class.javaPrimitiveType, 0f),
        )
        try {
            launch(notification = false)
            drainUntil("Inter starts") { LongPromptFixture.provider.pending != null }
            idleFrames(Duration.ofSeconds(10))
            val bar = requireNotNull(controller).get().findViewById<ProgressBar>(io.onboardkit.R.id.ob_splash_progress)
            assertTrue("Reduced motion still reports 90% after ten seconds", bar.progress in 9_000..9_010)
            idleFrames(Duration.ofSeconds(49))
            assertEquals(99, bar.progress / 100)
            assertEquals(0, LongPromptFixture.flowStarts)
            idleFrames(Duration.ofSeconds(1))
            assertEquals(1, LongPromptFixture.flowStarts)
        } finally {
            org.robolectric.util.ReflectionHelpers.callStaticMethod<Void>(
                android.animation.ValueAnimator::class.java, "setDurationScale",
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(Float::class.javaPrimitiveType, 1f),
            )
        }
    }

    @Test
    fun fasterSystemAnimationScaleKeepsTheTenSecondProgressCurve() {
        org.robolectric.util.ReflectionHelpers.callStaticMethod<Void>(
            android.animation.ValueAnimator::class.java, "setDurationScale",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Float::class.javaPrimitiveType, 0.5f),
        )
        try {
            launch(notification = false)
            drainUntil("Inter starts") { LongPromptFixture.provider.pending != null }
            idleFrames(Duration.ofSeconds(10))
            val bar = requireNotNull(controller).get().findViewById<ProgressBar>(io.onboardkit.R.id.ob_splash_progress)
            assertTrue("System animation scale must not speed up the progress curve", bar.progress in 8_980..9_010)
            assertEquals(0, LongPromptFixture.flowStarts)
        } finally {
            org.robolectric.util.ReflectionHelpers.callStaticMethod<Void>(
                android.animation.ValueAnimator::class.java, "setDurationScale",
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(Float::class.javaPrimitiveType, 1f),
            )
        }
    }

    @Test
    fun progressTextChangesOnlyWhenTheIntegerPercentChanges() {
        launch(notification = false)
        drainUntil("Inter starts") { LongPromptFixture.provider.pending != null }
        val host = requireNotNull(controller).get()
        val label = host.findViewById<android.widget.TextView>(io.onboardkit.R.id.ob_splash_progress_percent)
        val rendered = mutableListOf<String>()
        label.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                rendered += s.toString()
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        idleFrames(Duration.ofSeconds(10))
        host.onWindowFocusChanged(false)
        val beforePopup = rendered.size
        idleFrames(Duration.ofSeconds(70))
        assertEquals("No UI writes while the splash is hidden", beforePopup, rendered.size)
        host.onWindowFocusChanged(true)
        idleFrames(Duration.ofSeconds(50))
        assertEquals("No repeated percentage writes", rendered.distinct(), rendered)
        assertTrue("At most one write for each integer percent", rendered.size <= 100)
        assertEquals("100%", label.text.toString())
        assertEquals(1, LongPromptFixture.flowStarts)
    }

    @Test
    fun aFilledSlotAddsNoHoldAfterNotification() {
        LongPromptFixture.provider.fillBanner = true
        launch(notification = true)
        val host = requireNotNull(controller).get()
        drainUntil("Prompt and inter must start") {
            shadowOf(host).lastRequestedPermission != null && LongPromptFixture.provider.interstitialLoads == 1
        }
        host.onWindowFocusChanged(false)
        loadInterstitialNow()
        idleFrames(Duration.ofSeconds(5))
        val permission = requireNotNull(shadowOf(host).lastRequestedPermission)
        host.onRequestPermissionsResult(permission.requestCode, permission.requestedPermissions,
            IntArray(permission.requestedPermissions.size) { PackageManager.PERMISSION_DENIED })
        val closedAt = SystemClock.elapsedRealtime()
        host.onWindowFocusChanged(true)
        idleUntil(closedAt + 900)
        assertEquals(listOf("native"), LongPromptFixture.provider.order)
        idleUntil(closedAt + 1_000)
        assertEquals(listOf("native", "show"), LongPromptFixture.provider.order)
        assertNotificationSettle(closedAt)
    }

    @Test
    fun nativeSlotFormatStillGoesOutBeforeTheNotificationPrompt() {
        io.onboardkit.remote.OnboardingSettings.document.acceptSuccessfulFetch("""{"splash":{"ads":{"slot_format":"NATIVE"}}}""")
        try {
            val promptOpenAtRequest = mutableListOf<Boolean?>()
            LongPromptFixture.provider.onSplashRequest = {
                promptOpenAtRequest += controller?.get()?.let { shadowOf(it).lastRequestedPermission != null }
            }
            launch(notification = true)
            val host = requireNotNull(controller).get()
            drainUntil("The prompt still follows the requests") { shadowOf(host).lastRequestedPermission != null }
            assertEquals(listOf(AdPlacement.SplashInlineNative), LongPromptFixture.provider.nativeRequests)
            assertEquals(0, LongPromptFixture.provider.bannerLoads)
            assertEquals("Native slot and inter both went out before the prompt", listOf(false, false), promptOpenAtRequest)
        } finally {
            io.onboardkit.remote.OnboardingSettings.document.acceptSuccessfulFetch(null)
        }
    }

    @Test
    fun aLoadedInterstitialDoesNotWaitForASilentNativeSlot() {
        io.onboardkit.remote.OnboardingSettings.document.acceptSuccessfulFetch("""{"splash":{"ads":{"slot_format":"NATIVE"}}}""")
        try {
            launch(notification = false)
            drainUntil("Inter must start") { LongPromptFixture.provider.interstitialLoads == 1 }
            assertEquals(listOf(AdPlacement.SplashInlineNative), LongPromptFixture.provider.nativeRequests)
            idleFrames(Duration.ofSeconds(15))
            val loadedAt = loadInterstitialNow()
            idleUntil(loadedAt + 100)
            assertTrue("Not the 60s ad budget", "show" in LongPromptFixture.provider.order)
            assertEquals(1, LongPromptFixture.flowStarts)
        } finally {
            io.onboardkit.remote.OnboardingSettings.document.acceptSuccessfulFetch(null)
        }
    }

    @Test
    fun aBannerReloadDoesNotHoldTheInterstitial() {
        LongPromptFixture.flags = io.onboardkit.remote.RemoteFlags(splashSlotMinVisibleMs = 4_000)
        launch(notification = false)
        drainUntil("Inter must start") { LongPromptFixture.provider.interstitialLoads == 1 }
        idleFrames(Duration.ofSeconds(5))
        val reloadedAt = SystemClock.elapsedRealtime()
        requireNotNull(LongPromptFixture.provider.bannerListener).onLoaded()
        loadInterstitialNow()
        idleUntil(reloadedAt + 100)
        assertEquals(listOf("native", "show"), LongPromptFixture.provider.order)
    }

    @Test
    fun remoteNextScreenTimingOutranksTheHookOnLauncherStarts() {
        io.onboardkit.remote.OnboardingSettings.document.acceptSuccessfulFetch("""{"splash":{"navigation":{"next_screen_timing":"AFTER_AD"}}}""")
        try {
            LongPromptFixture.provider.successfulShow = true
            launch(notification = false)
            drainUntil("Inter must start") { LongPromptFixture.provider.interstitialLoads == 1 }
            loadInterstitialNow()
            idleFrames(Duration.ofSeconds(4))
            drainUntil("The inter shows") { "show" in LongPromptFixture.provider.order }
            assertEquals("Remote AFTER_AD outranks the hook's UNDER_AD", 0, LongPromptFixture.provider.flowStartsAtVendorShow)
            assertEquals(0, LongPromptFixture.flowStarts)
            requireNotNull(LongPromptFixture.provider.presentation).onAdClosed()
            main.idle()
            assertEquals(1, LongPromptFixture.flowStarts)
        } finally {
            io.onboardkit.remote.OnboardingSettings.document.acceptSuccessfulFetch(null)
        }
    }

    @Test
    fun anEntryLaunchUsesRemoteNextScreenTimingBeforeTheHook() {
        io.onboardkit.remote.OnboardingSettings.document.acceptSuccessfulFetch("""{"splash":{"navigation":{"next_screen_timing":"AFTER_AD"}}}""")
        try {
            LongPromptFixture.provider.successfulShow = true
            launch(notification = false, entry = SplashEntry.NOTIFICATION)
            drainUntil("Inter must start") { LongPromptFixture.provider.interstitialLoads == 1 }
            loadInterstitialNow()
            idleFrames(Duration.ofSeconds(4))
            drainUntil("The inter shows") { "show" in LongPromptFixture.provider.order }
            assertEquals("A valid remote timing outranks the entry hook", 0, LongPromptFixture.provider.flowStartsAtVendorShow)
        } finally {
            io.onboardkit.remote.OnboardingSettings.document.acceptSuccessfulFetch(null)
        }
    }

    @Test
    fun anEntryLaunchWithTheDefaultTimingWaitsForTheAdToCloseEvenAfterOnboarding() {
        runBlocking { OnboardingSdk.markCompleted() }
        LongPromptFixture.useDefaultTiming = true
        LongPromptFixture.provider.successfulShow = true
        launch(notification = false, entry = SplashEntry.WIDGET)
        drainUntil("Inter must start") { LongPromptFixture.provider.interstitialLoads == 1 }
        loadInterstitialNow()
        idleFrames(Duration.ofSeconds(4))
        drainUntil("The inter shows") { "show" in LongPromptFixture.provider.order }
        assertEquals("An entry never opens its destination under the ad", 0, LongPromptFixture.provider.handoffsAtVendorShow)
        requireNotNull(LongPromptFixture.provider.presentation).onAdClosed()
        main.idle()
        assertEquals(1, LongPromptFixture.splashHandoffs)
    }

    @Test
    fun oneSplashViewedAcrossRecreation() {
        launch(notification = false)
        drainUntil("Inter must start") { LongPromptFixture.provider.interstitialLoads == 1 }
        requireNotNull(controller).configurationChange(android.content.res.Configuration(
            requireNotNull(controller).get().resources.configuration).apply { fontScale += 0.1f })
        requireNotNull(controller).visible().get().onWindowFocusChanged(true)
        main.idle()
        assertEquals(1, LongPromptFixture.splashViews)
    }

    @Test
    fun recreationWhileTheLanguagePreloadWaitsForTheWindowStillRequestsItOnce() {
        launch(notification = false)
        drainUntil("Inter must start") { LongPromptFixture.provider.interstitialLoads == 1 }
        val host = requireNotNull(controller).get()
        host.onWindowFocusChanged(false)
        requireNotNull(controller).pause().stop()
        requireNotNull(LongPromptFixture.provider.pending).onFailedToLoad()
        idleFrames(Duration.ofSeconds(1))
        assertTrue(LongPromptFixture.provider.order.isEmpty())
        recreateSplash()
        idleFrames(Duration.ofSeconds(4))
        assertEquals("The waiting request goes out once", listOf("native"), LongPromptFixture.provider.order)
        assertTrue("From the new instance", LongPromptFixture.provider.nativeSenders.single() === requireNotNull(controller).get())
        assertEquals(1, LongPromptFixture.flowStarts)
    }

    @Test
    fun aLoadedResultWhileHomeStillMakesTheNativeScreenEligible() {
        LongPromptFixture.nativeConfigured = true
        LongPromptFixture.provider.successfulShow = true
        launch(notification = false)
        drainUntil("Inter must start") { LongPromptFixture.provider.interstitialLoads == 1 }
        val host = requireNotNull(controller).get()
        host.onWindowFocusChanged(false)
        requireNotNull(controller).pause().stop()
        loadInterstitialNow()
        idleFrames(Duration.ofSeconds(4))
        assertTrue(LongPromptFixture.provider.nativeRequests.isEmpty())
        requireNotNull(controller).restart().start().resume().visible()
        host.onWindowFocusChanged(true)
        idleFrames(Duration.ofSeconds(1))
        assertEquals(setOf(AdPlacement.Language1, AdPlacement.SplashNative), LongPromptFixture.provider.nativeRequests.toSet())
        drainUntil("The inter shows") { "show" in LongPromptFixture.provider.order }
        assertEquals("Both preloads go out before the show", listOf("native", "native", "show"), LongPromptFixture.provider.order)
        assertEquals("An eligible native_fs keeps UNDER_AD from opening LFO early", 0, LongPromptFixture.provider.flowStartsAtVendorShow)
    }

    @Test
    fun aResultWhileHomeAsksTheNextScreenHookOnceAfterTheSplashReturns() {
        launch(notification = false)
        drainUntil("Inter must start") { LongPromptFixture.provider.interstitialLoads == 1 }
        val host = requireNotNull(controller).get()
        host.onWindowFocusChanged(false)
        requireNotNull(controller).pause().stop()
        requireNotNull(LongPromptFixture.provider.pending).onFailedToLoad()
        idleFrames(Duration.ofSeconds(4))
        assertTrue("Not asked while the splash is away", LongPromptFixture.timingAskedIn.isEmpty())
        assertTrue(LongPromptFixture.provider.order.isEmpty())
        requireNotNull(controller).restart().start().resume().visible()
        host.onWindowFocusChanged(true)
        idleFrames(Duration.ofSeconds(1))
        assertEquals("Asked once, with the splash in front", listOf(Lifecycle.State.RESUMED), LongPromptFixture.timingAskedIn)
        assertEquals(1, LongPromptFixture.flowStarts)
        assertEquals("LFO1 goes out before the handoff", listOf(0), LongPromptFixture.provider.handoffsAtNative)
    }

    @Test
    fun recreationDuringTheMinimumKeepsTheFirstNextScreenTiming() {
        LongPromptFixture.provider.successfulShow = true
        LongPromptFixture.timing = io.onboardkit.ads.NextScreenTiming.AFTER_AD
        launch(notification = false)
        drainUntil("Inter must start") { LongPromptFixture.provider.interstitialLoads == 1 }
        loadInterstitialNow()
        main.idle()
        assertEquals(1, LongPromptFixture.timingAskedIn.size)
        LongPromptFixture.timing = io.onboardkit.ads.NextScreenTiming.UNDER_AD
        requireNotNull(controller).configurationChange(android.content.res.Configuration(
            requireNotNull(controller).get().resources.configuration).apply { fontScale += 0.1f })
        requireNotNull(controller).visible().get().onWindowFocusChanged(true)
        idleFrames(Duration.ofSeconds(4))
        drainUntil("The inter shows") { "show" in LongPromptFixture.provider.order }
        assertEquals("The hook is not asked again", 1, LongPromptFixture.timingAskedIn.size)
        assertEquals("The first answer, AFTER_AD, governs the show", 0, LongPromptFixture.provider.flowStartsAtVendorShow)
    }

    @Test
    fun destroyingTheSplashWhileOfflineDismissesTheOfflinePrompt() {
        LongPromptFixture.offlineGate = true
        launch(notification = false)
        val prompt = requireNotNull(org.robolectric.shadows.ShadowDialog.getLatestDialog())
        assertTrue("Robolectric reports no validated network, so the gate holds", prompt.isShowing)
        assertEquals(0, LongPromptFixture.provider.interstitialLoads)
        requireNotNull(controller).pause().stop().destroy()
        controller = null
        main.idle()
        assertTrue("The offline prompt must not outlive its splash", !prompt.isShowing)
    }

    @Test
    fun recreatingTheSplashWhileOfflineLeavesOnlyTheNewInstancesOfflinePromptShowing() {
        LongPromptFixture.offlineGate = true
        launch(notification = false)
        val first = requireNotNull(org.robolectric.shadows.ShadowDialog.getLatestDialog())
        assertTrue(first.isShowing)
        requireNotNull(controller).configurationChange(android.content.res.Configuration(
            requireNotNull(controller).get().resources.configuration).apply { fontScale += 0.1f })
        main.idle()
        val second = requireNotNull(org.robolectric.shadows.ShadowDialog.getLatestDialog())
        assertTrue("The recreated splash holds the gate again", second !== first && second.isShowing)
        assertTrue("The old instance's prompt is dismissed", !first.isShowing)
        assertEquals(0, LongPromptFixture.provider.interstitialLoads)
    }

    @Test
    fun aZeroSlotMinimumNeverWaitsForASilentSlot() {
        LongPromptFixture.flags = io.onboardkit.remote.RemoteFlags(splashSlotMinVisibleMs = 0)
        LongPromptFixture.provider.settleBanner = false
        launch(notification = false)
        drainUntil("Inter must start") { LongPromptFixture.provider.interstitialLoads == 1 }
        val loadedAt = loadInterstitialNow()
        idleUntil(loadedAt + 3_500)
        assertEquals("The minimum display, not the silent slot's ten seconds", listOf("native", "show"), LongPromptFixture.provider.order)
    }

    private fun loadInterstitialNow(): Long {
        LongPromptFixture.provider.ready = true
        requireNotNull(LongPromptFixture.provider.pending).onLoaded()
        return SystemClock.elapsedRealtime()
    }

    private fun assertNotificationSettle(closedAt: Long) {
        // View rendering can advance Robolectric's clock slightly past an idleFor deadline.
        // Assert the actual show timestamp so a late test wake cannot hide an early ad.
        val shownAt = requireNotNull(LongPromptFixture.provider.interstitialShowAtMs)
        assertTrue("Never show before the one-second settle", shownAt >= closedAt + 1_000)
        assertTrue("A ready ad shows within the next frame", shownAt <= closedAt + 1_016)
    }

    private fun idleFrames(duration: Duration) {
        val end = SystemClock.uptimeMillis() + duration.toMillis()
        while (SystemClock.uptimeMillis() < end) {
            main.idleFor(Duration.ofMillis(minOf(16, end - SystemClock.uptimeMillis())))
        }
        main.idle()
    }

    private fun recreateSplash(): org.robolectric.android.controller.ActivityController<LongPromptSplashActivity> {
        val current = requireNotNull(controller)
        val resumed = current.get().lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        current.configurationChange(android.content.res.Configuration(current.get().resources.configuration)
            .apply { fontScale += 0.1f })
        if (!resumed) current.start().resume()
        current.visible()
        idleFrames(Duration.ofMillis(16))
        current.get().onWindowFocusChanged(true)
        return current
    }

    private fun idleUntil(elapsedRealtimeMs: Long) {
        idleFrames(Duration.ofMillis((elapsedRealtimeMs - SystemClock.elapsedRealtime()).coerceAtLeast(0)))
    }

    @Test
    fun afterAdWaitsForTheMinimumBeforeShowingAndHandsOffWhenShowFails() {
        LongPromptFixture.timing = io.onboardkit.ads.NextScreenTiming.AFTER_AD
        launch(notification = false)
        drainUntil("Inter must start") { LongPromptFixture.provider.interstitialLoads == 1 }
        LongPromptFixture.provider.ready = true
        requireNotNull(LongPromptFixture.provider.pending).onLoaded()
        main.idle()
        assertEquals("A ready inter waits out the splash minimum", listOf("native"), LongPromptFixture.provider.order)
        assertEquals(0, LongPromptFixture.flowStarts)
        idleFrames(Duration.ofSeconds(4))
        drainUntil("Show after the minimum, then one handoff") { LongPromptFixture.flowStarts == 1 }
        assertEquals(listOf("native", "show"), LongPromptFixture.provider.order)
    }

    @Test
    fun recreationDoesNotRestartTheBannerWaitOrInterstitialBudget() {
        LongPromptFixture.flags = io.onboardkit.remote.RemoteFlags(splashAdBudgetMs = 10_000, splashSlotMinVisibleMs = 6_000)
        LongPromptFixture.provider.settleBanner = false
        launch(notification = false)
        drainUntil("Inter must start") { LongPromptFixture.provider.interstitialLoads == 1 }
        idleFrames(Duration.ofSeconds(4))
        requireNotNull(controller).configurationChange(android.content.res.Configuration(
            requireNotNull(controller).get().resources.configuration).apply { fontScale += 0.1f })
        requireNotNull(controller).visible().get().onWindowFocusChanged(true)
        LongPromptFixture.provider.ready = true
        requireNotNull(LongPromptFixture.provider.pending).onLoaded()
        idleFrames(Duration.ofMillis(2_100))
        assertEquals("The original banner deadline must release the ready inter", listOf("native", "show"), LongPromptFixture.provider.order)
        assertEquals(1, LongPromptFixture.provider.bannerLoads)
        assertEquals(1, LongPromptFixture.provider.interstitialLoads)
    }

    @Test
    fun defaultTimingOpensFirstOpenFlowOnlyAfterTheAdCloses() {
        LongPromptFixture.useDefaultTiming = true
        LongPromptFixture.provider.successfulShow = true
        launch(notification = false)
        drainUntil("Inter must start") { LongPromptFixture.provider.interstitialLoads == 1 }
        LongPromptFixture.provider.ready = true
        requireNotNull(LongPromptFixture.provider.pending).onLoaded()
        main.idle()
        assertTrue("The minimum is spent before the ad shows", "show" !in LongPromptFixture.provider.order)
        idleFrames(Duration.ofSeconds(4))
        drainUntil("The inter shows after the minimum") { "show" in LongPromptFixture.provider.order }
        assertEquals("LFO must not start under the ad", 0, LongPromptFixture.provider.handoffsAtVendorShow)
        assertEquals(0, LongPromptFixture.splashHandoffs)
        requireNotNull(LongPromptFixture.provider.presentation).onAdClosed()
        drainUntil("LFO opens as soon as the ad is dismissed") { LongPromptFixture.splashHandoffs == 1 }
        assertEquals(1, LongPromptFixture.flowStarts)
    }

    @Test
    fun defaultTimingOpensTheDestinationUnderTheAdOnceOnboardingIsDone() {
        io.onboardkit.remote.OnboardingSettings.document.acceptSuccessfulFetch("""{"welcome_back":{"enabled":false}}""")
        try {
            runBlocking { OnboardingSdk.markCompleted() }
            LongPromptFixture.useDefaultTiming = true
            LongPromptFixture.provider.successfulShow = true
            launch(notification = false)
            drainUntil("Inter must start") { LongPromptFixture.provider.interstitialLoads == 1 }
            LongPromptFixture.provider.ready = true
            requireNotNull(LongPromptFixture.provider.pending).onLoaded()
            idleFrames(Duration.ofSeconds(4))
            drainUntil("The inter shows after the minimum") { "show" in LongPromptFixture.provider.order }
            assertEquals("The destination starts inside the vendor callback", 1, LongPromptFixture.provider.handoffsAtVendorShow)
        } finally {
            io.onboardkit.remote.OnboardingSettings.document.acceptSuccessfulFetch(null)
        }
    }

    @Test
    fun returningLauncherLaunchOpensWelcomeBackAfterTheAdWithSlotOnePreloaded() {
        runBlocking { OnboardingSdk.markCompleted() }
        LongPromptFixture.useDefaultTiming = true
        LongPromptFixture.provider.successfulShow = true
        launch(notification = false)
        drainUntil("Inter must start") { LongPromptFixture.provider.interstitialLoads == 1 }
        assertTrue("Sequential: slot 1 waits for the inter result",
            AdPlacement.WelcomeBack1 !in LongPromptFixture.provider.nativeRequests)
        LongPromptFixture.provider.ready = true
        requireNotNull(LongPromptFixture.provider.pending).onLoaded()
        drainUntil("inter_splash_o loaded warms Welcome Back slot 1") {
            AdPlacement.WelcomeBack1 in LongPromptFixture.provider.nativeRequests
        }
        idleFrames(Duration.ofSeconds(4))
        drainUntil("The inter shows after the minimum") { "show" in LongPromptFixture.provider.order }
        assertEquals("Welcome Back must not start under the ad", 0, LongPromptFixture.provider.handoffsAtVendorShow)
        requireNotNull(LongPromptFixture.provider.presentation).onAdClosed()
        drainUntil("Welcome Back opens once the ad is dismissed") { LongPromptFixture.splashHandoffs == 1 }
        assertEquals(1, LongPromptFixture.provider.nativeRequests.count { it == AdPlacement.WelcomeBack1 })
        assertTrue("No LFO or native_fs for a returning user",
            LongPromptFixture.provider.nativeRequests.none { it == AdPlacement.Language1 || it == AdPlacement.SplashNative })
        val next = shadowOf(requireNotNull(controller).get()).nextStartedActivity
        assertEquals(io.onboardkit.ui.welcomeback.ObWelcomeBackActivity::class.java.name, next?.component?.className)
    }

    @Test
    fun entryLaunchOfAReturningUserNeverOpensWelcomeBack() {
        runBlocking { OnboardingSdk.markCompleted() }
        LongPromptFixture.provider.successfulShow = true
        launch(notification = false, entry = SplashEntry.NOTIFICATION)
        drainUntil("Inter must start") { LongPromptFixture.provider.interstitialLoads == 1 }
        LongPromptFixture.provider.ready = true
        requireNotNull(LongPromptFixture.provider.pending).onLoaded()
        idleFrames(Duration.ofSeconds(4))
        drainUntil("The inter shows after the minimum") { "show" in LongPromptFixture.provider.order }
        requireNotNull(LongPromptFixture.provider.presentation).onAdClosed()
        drainUntil("The entry reaches its destination") { LongPromptFixture.splashHandoffs == 1 }
        assertTrue(AdPlacement.WelcomeBack1 !in LongPromptFixture.provider.nativeRequests)
        val next = shadowOf(requireNotNull(controller).get()).nextStartedActivity
        assertTrue(next?.component?.className != io.onboardkit.ui.welcomeback.ObWelcomeBackActivity::class.java.name)
    }

    @Test
    fun underAdWaitsForMinimumThenNavigatesInsideCallbackBeforeVendorShow() {
        LongPromptFixture.provider.successfulShow = true
        launch(notification = false)
        drainUntil("Inter must start") { LongPromptFixture.provider.interstitialLoads == 1 }
        LongPromptFixture.provider.ready = true
        requireNotNull(LongPromptFixture.provider.pending).onLoaded()
        main.idle()
        assertEquals("No show before the minimum", listOf("native"), LongPromptFixture.provider.order)
        assertEquals(0, LongPromptFixture.flowStarts)
        idleFrames(Duration.ofSeconds(4))
        assertEquals("Navigation must finish inside onNext, before the vendor opens its Activity",
            1, LongPromptFixture.provider.flowStartsAtVendorShow)
        assertEquals(1, LongPromptFixture.flowStarts)
        requireNotNull(LongPromptFixture.provider.presentation).onNextAction()
        assertEquals("Repeated next callback cannot open a second destination", 1, LongPromptFixture.flowStarts)
        assertTrue("Splash stays alive until the real close callback", !requireNotNull(controller).get().isFinishing)
        requireNotNull(LongPromptFixture.provider.presentation).onAdClosed()
        main.idle()
        assertTrue(requireNotNull(controller).get().isFinishing)
    }

    @Test
    fun homeDuringUnderAdMinimumWaitDefersBothNavigationAndShowUntilSplashReturns() {
        LongPromptFixture.flags = io.onboardkit.remote.RemoteFlags(splashMinDisplayMs = 10_000)
        LongPromptFixture.provider.successfulShow = true
        launch(notification = false)
        drainUntil("Inter must start") { LongPromptFixture.provider.interstitialLoads == 1 }
        LongPromptFixture.provider.ready = true
        requireNotNull(LongPromptFixture.provider.pending).onLoaded()
        main.idle()
        val host = requireNotNull(controller).get()
        host.onWindowFocusChanged(false)
        requireNotNull(controller).pause().stop()
        idleFrames(Duration.ofSeconds(11))
        assertEquals(0, LongPromptFixture.flowStarts)
        assertEquals(listOf("native"), LongPromptFixture.provider.order)
        requireNotNull(controller).restart().start().resume().visible()
        host.onWindowFocusChanged(true)
        main.idle()
        assertEquals(1, LongPromptFixture.provider.flowStartsAtVendorShow)
        assertEquals(1, LongPromptFixture.flowStarts)
        assertEquals(1, LongPromptFixture.provider.interstitialLoads)
    }

    @Test
    fun recreationDuringMinimumDoesNotRepeatThePaywallCheckpoint() {
        LongPromptFixture.paywallEnabled = true
        launch(notification = false)
        drainUntil("Inter must start") { LongPromptFixture.provider.interstitialLoads == 1 }
        LongPromptFixture.provider.ready = true
        requireNotNull(LongPromptFixture.provider.pending).onLoaded()
        main.idle()
        requireNotNull(controller).configurationChange(android.content.res.Configuration(
            requireNotNull(controller).get().resources.configuration).apply { fontScale += 0.1f })
        requireNotNull(controller).visible().get().onWindowFocusChanged(true)
        idleFrames(Duration.ofSeconds(4))
        assertEquals("Minimum waiting must not reopen an already resolved paywall after recreation",
            1, LongPromptFixture.paywallCalls)
        assertEquals(1, LongPromptFixture.provider.interstitialLoads)
        assertEquals(1, LongPromptFixture.flowStarts)
    }

    @Test
    fun recreatedSplashDoesNotReplayLateUnderAdNavigationOverAnExistingAd() {
        LongPromptFixture.provider.successfulShow = true
        LongPromptFixture.provider.holdNext = true
        launch(notification = false)
        drainUntil("Inter must start") { LongPromptFixture.provider.interstitialLoads == 1 }
        LongPromptFixture.provider.ready = true
        requireNotNull(LongPromptFixture.provider.pending).onLoaded()
        idleFrames(Duration.ofSeconds(4))
        val old = requireNotNull(controller).get()
        val callback = requireNotNull(LongPromptFixture.provider.presentation)
        requireNotNull(controller).configurationChange(android.content.res.Configuration(old.resources.configuration)
            .apply { fontScale += 0.1f })
        requireNotNull(controller).visible().get().onWindowFocusChanged(true)
        callback.onNextAction()
        main.idle()
        assertTrue(old.isDestroyed)
        assertEquals("A stale owner's callback must not navigate over the ad", 0, LongPromptFixture.flowStarts)
        callback.onAdClosed()
        main.idle()
        assertEquals(1, LongPromptFixture.flowStarts)
        assertEquals(1, LongPromptFixture.provider.order.count { it == "show" })
        assertTrue(requireNotNull(controller).get().isFinishing)
    }

    @Test
    fun afterAdWaitsForCloseWithoutRepeatingAnAlreadyElapsedMinimum() {
        LongPromptFixture.timing = io.onboardkit.ads.NextScreenTiming.AFTER_AD
        LongPromptFixture.provider.successfulShow = true
        launch(notification = false)
        drainUntil("Inter must start") { LongPromptFixture.provider.interstitialLoads == 1 }
        LongPromptFixture.provider.ready = true
        requireNotNull(LongPromptFixture.provider.pending).onLoaded()
        idleFrames(Duration.ofSeconds(4))
        assertEquals(0, LongPromptFixture.flowStarts)
        requireNotNull(LongPromptFixture.provider.presentation).onAdClosed()
        main.idle()
        assertEquals(1, LongPromptFixture.flowStarts)
    }

    @Test
    fun recreationReadsNewFlagsWithoutRepeatingTheInterstitialRequest() {
        launch(notification = false)
        drainUntil("Inter must be in flight") { LongPromptFixture.provider.interstitialLoads == 1 }
        val pending = requireNotNull(LongPromptFixture.provider.pending)
        idleFrames(Duration.ofSeconds(5))
        val beforeRecreation = requireNotNull(controller).get()
            .findViewById<ProgressBar>(io.onboardkit.R.id.ob_splash_progress).progress
        LongPromptFixture.flags = io.onboardkit.remote.RemoteFlags(splashLfoParallelPreloadEnabled = true)
        OnboardingSdk.remoteOrNull()?.applySnapshot(LongPromptFixture.flags)
        requireNotNull(controller).configurationChange(android.content.res.Configuration(
            requireNotNull(controller).get().resources.configuration).apply { fontScale += 0.1f })
        requireNotNull(controller).visible().get().onWindowFocusChanged(true)
        idleFrames(Duration.ofMillis(100))
        val bar = requireNotNull(controller).get()
            .findViewById<ProgressBar>(io.onboardkit.R.id.ob_splash_progress)
        assertFalse(
            "Recreated splash keeps determinate progress during the retained request",
            bar.isIndeterminate
        )
        assertTrue(bar.isShown)
        assertTrue("Recreation must retain elapsed progress", bar.progress in beforeRecreation..(beforeRecreation + 200))
        assertEquals("Recreation must rejoin the original request", 1, LongPromptFixture.provider.interstitialLoads)
        drainUntil("Recreation reads the current preload mode") { "native" in LongPromptFixture.provider.order }
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
    fun parallelLoadedResultAddsTheNativeScreenWithoutASecondLanguageRequest() {
        LongPromptFixture.flags = io.onboardkit.remote.RemoteFlags(splashLfoParallelPreloadEnabled = true)
        LongPromptFixture.nativeConfigured = true
        LongPromptFixture.provider.successfulShow = true
        launch(notification = false)
        drainUntil("Parallel sends LFO1 with the splash requests") {
            LongPromptFixture.provider.nativeRequests == listOf(AdPlacement.Language1)
        }
        loadInterstitialNow()
        main.idle()
        assertEquals(listOf(AdPlacement.Language1, AdPlacement.SplashNative), LongPromptFixture.provider.nativeRequests)
        idleFrames(Duration.ofSeconds(4))
        drainUntil("The inter shows") { "show" in LongPromptFixture.provider.order }
        assertEquals("An eligible native_fs forces AFTER_AD", 0, LongPromptFixture.provider.flowStartsAtVendorShow)
        assertEquals(listOf(AdPlacement.Language1, AdPlacement.SplashNative), LongPromptFixture.provider.nativeRequests)
    }

    @Test
    fun parallelFailedResultDoesNotRequestTheLanguageNativeAgain() {
        LongPromptFixture.flags = io.onboardkit.remote.RemoteFlags(splashLfoParallelPreloadEnabled = true)
        launch(notification = false)
        drainUntil("Parallel sends LFO1 with the splash requests") {
            LongPromptFixture.provider.nativeRequests == listOf(AdPlacement.Language1)
        }
        requireNotNull(LongPromptFixture.provider.pending).onFailedToLoad()
        idleFrames(Duration.ofSeconds(4))
        assertEquals(listOf(AdPlacement.Language1), LongPromptFixture.provider.nativeRequests)
        assertEquals(1, LongPromptFixture.flowStarts)
    }

    @Test
    fun recreationAfterTheLanguageRequestWentOutDoesNotRequestItAgain() {
        recreateBeforeTheShowAfterTheLanguageRequest(failed = false)
    }

    @Test
    fun recreationAfterTheLanguageRequestFailedDoesNotRetryIt() {
        recreateBeforeTheShowAfterTheLanguageRequest(failed = true)
    }

    private fun recreateBeforeTheShowAfterTheLanguageRequest(failed: Boolean) {
        launch(notification = false)
        drainUntil("Inter must start") { LongPromptFixture.provider.interstitialLoads == 1 }
        requireNotNull(LongPromptFixture.provider.pending).onFailedToLoad()
        main.idle()
        assertEquals(listOf(AdPlacement.Language1), LongPromptFixture.provider.nativeRequests)
        if (failed) LongPromptFixture.provider.failedNatives += AdPlacement.Language1
        requireNotNull(controller).configurationChange(android.content.res.Configuration(
            requireNotNull(controller).get().resources.configuration).apply { fontScale += 0.1f })
        requireNotNull(controller).visible().get().onWindowFocusChanged(true)
        idleFrames(Duration.ofSeconds(4))
        assertEquals(1, LongPromptFixture.flowStarts)
        assertEquals(listOf(AdPlacement.Language1), LongPromptFixture.provider.nativeRequests)
    }

    @Test
    fun aLanguagePreloadRefusedBeforeRecreationIsJudgedOnceMoreByTheNewInstance() {
        io.onboardkit.remote.OnboardingSettings.document.acceptSuccessfulFetch("""{"splash":{"timing":{"min_display_ms":30000}}}""")
        try {
            LongPromptFixture.flags = io.onboardkit.remote.RemoteFlags(adsLanguageNative = false)
            launch(notification = false)
            drainUntil("Inter must start") { LongPromptFixture.provider.interstitialLoads == 1 }
            requireNotNull(LongPromptFixture.provider.pending).onFailedToLoad()
            main.idle()
            assertTrue("The guard refuses LFO1", LongPromptFixture.provider.nativeRequests.isEmpty())
            OnboardingSdk.remoteOrNull()?.applySnapshot(io.onboardkit.remote.RemoteFlags())
            repeat(2) {
                requireNotNull(controller).configurationChange(android.content.res.Configuration(
                    requireNotNull(controller).get().resources.configuration).apply { fontScale += 0.1f })
                requireNotNull(controller).visible().get().onWindowFocusChanged(true)
                main.idle()
            }
            assertEquals("Both recreations come before the show", 0, LongPromptFixture.flowStarts)
            assertEquals("Judged again once, then latched", listOf(AdPlacement.Language1), LongPromptFixture.provider.nativeRequests)
        } finally {
            io.onboardkit.remote.OnboardingSettings.document.acceptSuccessfulFetch(null)
        }
    }

    @Test
    fun aDestinationOtherThanLanguageGetsNeitherLanguageNorNativeScreenPreloads() {
        runBlocking { OnboardingSdk.markCompleted() }
        LongPromptFixture.nativeConfigured = true
        LongPromptFixture.provider.successfulShow = true
        launch(notification = false)
        drainUntil("Inter must start") { LongPromptFixture.provider.interstitialLoads == 1 }
        loadInterstitialNow()
        idleFrames(Duration.ofSeconds(4))
        drainUntil("The inter shows") { "show" in LongPromptFixture.provider.order }
        assertEquals("The hook's UNDER_AD, not native_fs's AFTER_AD", 1, LongPromptFixture.provider.flowStartsAtVendorShow)
        val splashPreloads = listOf(AdPlacement.Language1, AdPlacement.SplashNative)
        assertTrue(LongPromptFixture.provider.nativeRequests.none { it in splashPreloads })
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
        idleFrames(Duration.ofMinutes(2))
        assertEquals(0, LongPromptFixture.flowStarts)
        assertTrue(LongPromptFixture.provider.order.isEmpty())
        val permission = requireNotNull(shadowOf(activity).lastRequestedPermission)
        activity.onRequestPermissionsResult(permission.requestCode, permission.requestedPermissions,
            IntArray(permission.requestedPermissions.size) { PackageManager.PERMISSION_DENIED })
        activity.onWindowFocusChanged(true)
        idleFrames(Duration.ofSeconds(30))
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
        idleFrames(if (promptMinutes == 0L) Duration.ofSeconds(5) else Duration.ofMinutes(promptMinutes))
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
        if (!minimumAlreadyElapsed) idleFrames(Duration.ofSeconds(30))
        completeInterstitialAndAssertNormalHandoff(minimumAlreadyElapsed = minimumAlreadyElapsed)
    }

    private fun remoteAds(vararg units: Pair<String, Boolean>) = com.ads.module.config.AdRemoteConfig.update(
        com.ads.module.config.AdRemoteConfig(units.associate { (key, on) -> key to com.ads.module.config.AdUnitConfig(key, on) }),
        fromRemote = true,
    )

    private fun assertSpendsAndShows(key: String) {
        drainUntil("$key must be requested") { LongPromptFixture.provider.interstitialLoads == 1 }
        assertEquals(listOf(listOf(key)), LongPromptFixture.provider.loadedUnits)
        assertEquals(listOf<String?>(key), LongPromptFixture.provider.loadedKeys)
        LongPromptFixture.provider.ready = true
        requireNotNull(LongPromptFixture.provider.pending).onLoaded()
        idleFrames(Duration.ofSeconds(4))
        drainUntil("The show gate must pass the position the load spent") { "show" in LongPromptFixture.provider.order }
    }

    private fun assertShowsNothing() {
        drainUntil("The flow continues without a splash interstitial") { LongPromptFixture.flowStarts == 1 }
        assertEquals(0, LongPromptFixture.provider.interstitialLoads)
        assertTrue("show" !in LongPromptFixture.provider.order)
    }

    @Test
    fun returningUserSpendsTheOldUserPositionWhileTheRegularOneIsOff() {
        runBlocking { OnboardingSdk.markCompleted() }
        remoteAds("inter_splash" to false, "inter_splash_o" to true)
        launch(notification = false)
        assertSpendsAndShows("inter_splash_o")
    }

    @Test
    fun newUserShowsNothingWhileTheRegularPositionIsOff() {
        remoteAds("inter_splash" to false, "inter_splash_o" to true)
        launch(notification = false)
        assertShowsNothing()
    }

    @Test
    fun returningUserShowsNothingWhileTheOldUserPositionIsOff() {
        runBlocking { OnboardingSdk.markCompleted() }
        remoteAds("inter_splash" to true, "inter_splash_o" to false)
        launch(notification = false)
        assertShowsNothing()
    }

    @Test
    fun notificationEntrySpendsItsOwnPositionWhileNewUsersAreOn() {
        remoteAds("inter_splash" to true, "inter_noti" to true)
        launch(notification = false, entry = SplashEntry.NOTIFICATION)
        assertSpendsAndShows("inter_noti")
    }

    @Test
    fun regularPositionOffSilencesNewUsersEntriesToo() {
        remoteAds("inter_splash" to false, "inter_splash_o" to true, "inter_noti" to true)
        launch(notification = false, entry = SplashEntry.NOTIFICATION)
        assertShowsNothing()
    }

    @Test
    fun oldUserPositionOffSilencesReturningUsersEntriesToo() {
        runBlocking { OnboardingSdk.markCompleted() }
        remoteAds("inter_splash" to true, "inter_splash_o" to false, "inter_noti" to true)
        launch(notification = false, entry = SplashEntry.NOTIFICATION)
        assertShowsNothing()
    }

    @Test
    fun returningUsersEntryStaysOnWhileOnlyTheRegularPositionIsOff() {
        runBlocking { OnboardingSdk.markCompleted() }
        remoteAds("inter_splash" to false, "inter_splash_o" to true, "inter_noti" to true)
        launch(notification = false, entry = SplashEntry.NOTIFICATION)
        assertSpendsAndShows("inter_noti")
    }

    @Test
    fun switchedOffEntryFallsBackToTheNewUserPosition() {
        remoteAds("inter_splash" to true, "inter_splash_o" to true, "inter_noti" to false)
        launch(notification = false, entry = SplashEntry.NOTIFICATION)
        assertSpendsAndShows("inter_splash")
    }

    @Test
    fun switchedOffEntryFallsBackToTheOldUserPosition() {
        runBlocking { OnboardingSdk.markCompleted() }
        remoteAds("inter_splash" to true, "inter_splash_o" to true, "inter_noti" to false)
        launch(notification = false, entry = SplashEntry.NOTIFICATION)
        assertSpendsAndShows("inter_splash_o")
    }

    @Test
    fun switchingOneEntryOffLeavesOtherEntriesAndPlainLaunchesAlone() {
        remoteAds("inter_splash" to true, "inter_noti" to false, "inter_widget" to true)
        launch(notification = false, entry = SplashEntry.WIDGET)
        assertSpendsAndShows("inter_widget")
    }

    @Test
    fun plainLaunchIgnoresASwitchedOffEntry() {
        remoteAds("inter_splash" to true, "inter_noti" to false)
        launch(notification = false)
        assertSpendsAndShows("inter_splash")
    }

    @Test
    fun returningUsersUndeclaredEntryFallsBackToTheOldUserPosition() {
        runBlocking { OnboardingSdk.markCompleted() }
        remoteAds("inter_splash" to true, "inter_splash_o" to true)
        launch(notification = false, entry = SplashEntry.UNINSTALL)
        assertSpendsAndShows("inter_splash_o")
    }

    @Test
    fun returningUserWithoutAnOldUserKeyFollowsTheRegularPosition() {
        runBlocking { OnboardingSdk.markCompleted() }
        remoteAds("inter_splash" to false, "inter_noti" to true)
        launch(notification = false, entry = SplashEntry.NOTIFICATION)
        assertShowsNothing()
    }

    @Test
    fun legacySplashInterSwitchSilencesAnEntryPosition() {
        LongPromptFixture.flags = io.onboardkit.remote.RemoteFlags(adsSplashInter = false)
        remoteAds("inter_splash" to true, "inter_noti" to true)
        launch(notification = false, entry = SplashEntry.NOTIFICATION)
        assertShowsNothing()
    }

    @Test
    fun legacySplashInterSwitchSilencesTheOldUserPosition() {
        runBlocking { OnboardingSdk.markCompleted() }
        LongPromptFixture.flags = io.onboardkit.remote.RemoteFlags(adsSplashInter = false)
        remoteAds("inter_splash" to true, "inter_splash_o" to true)
        launch(notification = false)
        assertShowsNothing()
    }

    @Test
    fun legacySplashInterSwitchSilencesTheRegularPosition() {
        LongPromptFixture.flags = io.onboardkit.remote.RemoteFlags(adsSplashInter = false)
        remoteAds("inter_splash" to true)
        launch(notification = false)
        assertShowsNothing()
    }

    @Test
    fun aBufferedFillFromAnotherPositionIsDroppedBeforeLoading() {
        LongPromptFixture.provider.ready = true
        LongPromptFixture.provider.readyUnit = "inter_splash_o"
        remoteAds("inter_splash" to true, "inter_noti" to true)
        launch(notification = false, entry = SplashEntry.NOTIFICATION)
        drainUntil("inter_noti must be requested") { LongPromptFixture.provider.interstitialLoads == 1 }
        assertEquals(1, LongPromptFixture.provider.releases)
        LongPromptFixture.provider.readyUnit = "inter_noti"
        assertSpendsAndShows("inter_noti")
    }

    @Test
    fun entryThatConfigNeverDeclaresSharesTheRegularPosition() {
        remoteAds("inter_splash" to true)
        launch(notification = false, entry = SplashEntry.UNINSTALL)
        assertSpendsAndShows("inter_splash")
    }

    private fun launch(notification: Boolean, entry: SplashEntry? = null) {
        OnboardingSdk.remoteOrNull()?.applySnapshot(LongPromptFixture.flags)
        val remoteWait = LongPromptFixture.remoteWait
        if (remoteWait != null) com.ads.module.config.AdConfig.install(
            object : com.ads.module.config.AdConfigSource, com.ads.module.config.settings.SettingsConfigSource {
                override val id = "update-test"
                override suspend fun fetchSettings(timeoutMs: Long): Map<String, String?> {
                    LongPromptFixture.remoteEntered = true
                    remoteWait.await()
                    return LongPromptFixture.remoteSettings
                }
                override suspend fun fetch(timeoutMs: Long): String? = null
            },
        )
        OnboardingSdk.configure(onboardKitConfig {
            splash = SplashConfig(noInternetPromptEnabled = LongPromptFixture.offlineGate, notificationPermissionEnabled = notification,
                remoteFetchTimeoutMs = LongPromptFixture.remoteTimeoutMs, adLoadStrategy = LongPromptFixture.strategy)
            step(ContentStepDefinition(StepId.OB1, title = "Introduction"))
            ads = AdsConfig(splashBanner = BannerAdUnit("host-banner"),
                splashInterstitial = InterstitialAdUnit("host-interstitial"),
                languageNative = NativeAdUnit("host-language"),
                welcomeBackNative = NativeAdUnit("host-welcome1"),
                welcomeBackDupNative = NativeAdUnit("host-welcome2"),
                splashInlineNative = NativeAdUnit("host-splash-inline"),
                splashNative = NativeAdUnit("host-splash-native").takeIf { LongPromptFixture.nativeConfigured })
        }.getOrThrow()).getOrThrow()
        controller = Robolectric.buildActivity(LongPromptSplashActivity::class.java,
            entry?.intent(app, LongPromptSplashActivity::class.java)).setup().visible()
        requireNotNull(controller).get().onWindowFocusChanged(true)
        idleFrames(Duration.ofMillis(16))
    }

    private fun completeInterstitialAndAssertNormalHandoff(minimumAlreadyElapsed: Boolean = false) {
        assertNotNull("The original public load callback must remain available", LongPromptFixture.provider.pending)
        LongPromptFixture.provider.ready = true
        requireNotNull(LongPromptFixture.provider.pending).onLoaded()
        if (minimumAlreadyElapsed) {
            // Past the notification settle but far short of the 3s minimum display, which the long
            // prompt already consumed — that distinction is what this case exists to prove.
            idleFrames(Duration.ofSeconds(1))
            assertEquals("Long prompt time already consumed the minimum", 1, LongPromptFixture.flowStarts)
        } else idleFrames(Duration.ofSeconds(4))
        drainUntil("Ready splash must show and hand off once; order=${LongPromptFixture.provider.order}") { LongPromptFixture.flowStarts == 1 }
        assertEquals(listOf("native", "show"), LongPromptFixture.provider.order)
        assertEquals(1, LongPromptFixture.provider.bannerLoads)
        assertEquals(1, LongPromptFixture.provider.interstitialLoads)
    }

    private fun drainUntil(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            // Virtual time, not just pending work: the splash now settles for a beat after the
            // notification result, and a bare idle() would never reach a scheduled delay.
            idleFrames(Duration.ofMillis(50))
            Thread.sleep(5) // DataStore IO completion; virtual prompt durations use only the main looper.
        }
        assertTrue(message, condition())
    }
}

class LongPromptVendorActivity : Activity()

class LongPromptSplashActivity : ObSplashActivity() {
    override fun readForceUpdateConfig(): com.ads.module.update.ForceUpdateConfig {
        LongPromptFixture.updateReads++
        return LongPromptFixture.updateConfig ?: com.ads.module.update.ForceUpdateConfig()
    }
    override fun nextScreenTiming(): io.onboardkit.ads.NextScreenTiming {
        LongPromptFixture.timingAskedIn += lifecycle.currentState
        return if (LongPromptFixture.useDefaultTiming) super.nextScreenTiming() else LongPromptFixture.timing
    }
    override suspend fun onInitBilling() {
        LongPromptFixture.billingEntered = true
        LongPromptFixture.billing?.await()
    }
    override fun onRemoteFetched() {
        LongPromptFixture.remoteHookCalled = true
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(io.onboardkit.R.style.ob_Theme_OnboardKit)
        super.onCreate(savedInstanceState)
    }

    // Robolectric's own window focus does not follow onWindowFocusChanged, which these tests drive.
    private val focus = kotlinx.coroutines.flow.MutableStateFlow(false)
    override fun onResume() {
        super.onResume()
        focus.value = hasWindowFocus()
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        focus.value = hasFocus
    }
    fun inRequestWindow(allowWhileVisible: Boolean) = io.onboardkit.ads.isRequestWindowOpen(
        !isFinishing && !isDestroyed, lifecycle.currentState, focus.value, allowWhileVisible,
    )
    fun requestWindow(allowWhileVisible: Boolean) =
        kotlinx.coroutines.flow.combine(lifecycle.currentStateFlow, focus) { _, _ -> inRequestWindow(allowWhileVisible) }
}

private object LongPromptFixture {
    var updateConfig: com.ads.module.update.ForceUpdateConfig? = null
    var remoteWait: CompletableDeferred<Unit>? = null
    var remoteSettings: Map<String, String?> = emptyMap()
    var remoteEntered = false
    var remoteTimeoutMs = 100L
    var updateReads = 0
    val provider = LongPromptProvider()
    var ump = LongPromptConsentInformation()
    var form = LongPromptConsentForm()
    var flowStarts = 0
    var nativeConfigured = false
    var offlineGate = false
    var paywallEnabled = false
    var paywallCalls = 0
    var paywallWait: CompletableDeferred<PaywallOutcome>? = null
    var timing = io.onboardkit.ads.NextScreenTiming.UNDER_AD
    var useDefaultTiming = false
    val timingAskedIn = mutableListOf<Lifecycle.State>()
    var splashHandoffs = 0
    var splashViews = 0
    var flags = io.onboardkit.remote.RemoteFlags()
    var strategy = io.onboardkit.config.AdLoadStrategy.ALTERNATE
    var billing: CompletableDeferred<Unit>? = null
    var billingEntered = false
    var remoteHookCalled = false
    fun reset() {
        com.ads.module.helper.Entitlement.install(object : com.ads.module.helper.EntitlementSource {
            override fun isPremium(context: Context) = provider.premium
        })
        updateConfig = null
        remoteWait = null
        remoteSettings = emptyMap()
        remoteEntered = false
        remoteTimeoutMs = 100L
        updateReads = 0
        flags = io.onboardkit.remote.RemoteFlags()
        strategy = io.onboardkit.config.AdLoadStrategy.ALTERNATE
        billing = null
        billingEntered = false
        remoteHookCalled = false
        ump = LongPromptConsentInformation()
        form = LongPromptConsentForm()
        flowStarts = 0
        nativeConfigured = false
        offlineGate = false
        provider.nativeReady = false
        provider.clearNatives()
        paywallEnabled = false
        paywallCalls = 0
        paywallWait = null
        timing = io.onboardkit.ads.NextScreenTiming.UNDER_AD
        useDefaultTiming = false
        timingAskedIn.clear()
        splashHandoffs = 0
        splashViews = 0
        provider.handoffsAtVendorShow = -1
        provider.successfulShow = false
        provider.holdNext = false
        provider.flowStartsAtVendorShow = -1
        provider.presentation = null
        provider.immediateInterResult = 0
        provider.premium = false
        provider.settleBanner = true
        provider.bannerLoads = 0
        provider.interstitialLoads = 0
        provider.loadedUnits.clear()
        provider.readyUnit = null
        provider.releases = 0
        provider.loadedKeys.clear()
        provider.interstitialRequestAtMs = 0L
        provider.onSplashRequest = null
        provider.fillBanner = false
        provider.bannerListener = null
        provider.interstitialShowAtMs = null
        provider.pending = null
        provider.ready = false
        provider.order.clear()
    }
}

private class LongPromptProvider : FakeAdProvider() {
    var bannerLoads = 0
    var successfulShow = false
    var holdNext = false
    var flowStartsAtVendorShow = -1
    var handoffsAtVendorShow = -1
    var presentation: ObInterstitialCallback? = null
    var settleBanner = true
    var immediateInterResult = 0
    var premium = false
    var interstitialRequestAtMs = 0L
    var interstitialShowAtMs: Long? = null
    var onSplashRequest: (() -> Unit)? = null
    var fillBanner = false
    var bannerListener: AdEventListener? = null
    var interstitialLoads = 0
    var pending: AdEventListener? = null
    var ready = false
    val order = mutableListOf<String>()
    val nativeRequests = mutableListOf<AdPlacement>()
    val nativeSenders = mutableListOf<Activity>()
    val handoffsAtNative = mutableListOf<Int>()
    val failedNatives = mutableSetOf<AdPlacement>()
    var nativeReady = false
    private val queued = mutableMapOf<AdPlacement, kotlinx.coroutines.Job>()

    /** Sends like the real provider: at once inside the request window, else once it opens. */
    override fun preloadNative(activity: Activity, request: NativeAdRequest) {
        queued.remove(request.placement)?.cancel()
        val host = activity as LongPromptSplashActivity
        if (host.inRequestWindow(request.allowWhileVisible)) return send(host, request)
        val job = host.lifecycleScope.launch {
            host.requestWindow(request.allowWhileVisible).first { it }
            send(host, request)
        }
        queued[request.placement] = job
        job.invokeOnCompletion { if (queued[request.placement] === job) queued.remove(request.placement) }
    }
    private fun send(host: Activity, request: NativeAdRequest) {
        if (request.placement == AdPlacement.SplashInlineNative) onSplashRequest?.invoke()
        order += "native"
        nativeRequests += request.placement
        nativeSenders += host
        handoffsAtNative += LongPromptFixture.splashHandoffs
    }
    override fun nativeStatus(placement: AdPlacement) = when {
        placement == AdPlacement.SplashNative && nativeReady -> NativeStatus.READY
        placement in failedNatives -> NativeStatus.FAILED
        placement in nativeRequests || queued[placement]?.isActive == true -> NativeStatus.LOADING
        else -> NativeStatus.IDLE
    }
    fun clearNatives() {
        queued.values.forEach { it.cancel() }
        queued.clear()
        nativeRequests.clear()
        nativeSenders.clear()
        handoffsAtNative.clear()
        failedNatives.clear()
    }
    val loadedUnits = mutableListOf<List<String>>()
    val loadedKeys = mutableListOf<String?>()
    override fun loadInterstitial(activity: Activity, placement: AdPlacement, unit: InterstitialAdUnit, adConfigKey: String?, listener: AdEventListener?) {
        loadedKeys += adConfigKey
        loadedUnits += unit.loadOrder
        interstitialLoads++
        interstitialRequestAtMs = SystemClock.elapsedRealtime()
        onSplashRequest?.invoke()
        pending = listener
        if (immediateInterResult == 1) { ready = true; listener?.onLoaded() }
        if (immediateInterResult == 2) listener?.onFailedToLoad()
    }
    override fun isInterstitialReady(placement: AdPlacement) = ready
    var readyUnit: String? = null
    var releases = 0
    override fun readyInterstitialUnitId(placement: AdPlacement) = readyUnit.takeIf { ready }
    override fun releaseInterstitial(placement: AdPlacement) {
        releases++
        ready = false
        readyUnit = null
    }
    override fun loadAndShowInterstitial(
        activity: androidx.appcompat.app.AppCompatActivity,
        placement: AdPlacement,
        unit: InterstitialAdUnit,
        callback: ObInterstitialCallback,
        timeoutMs: Long,
    ) {
        throw AssertionError("This fixture does not expect a loadAndShow request: ${placement.key}")
    }

    override fun showInterstitial(activity: Activity, placement: AdPlacement, callback: ObInterstitialCallback) {
        interstitialShowAtMs = SystemClock.elapsedRealtime()
        order += "show"
        if (successfulShow) {
            presentation = callback
            if (!holdNext) {
                callback.onNextAction()
                flowStartsAtVendorShow = LongPromptFixture.flowStarts
                handoffsAtVendorShow = LongPromptFixture.splashHandoffs
            }
        }
        else callback.onAdSkipped(AdSkipReason.NOT_READY)
    }
    override fun loadBanner(activity: androidx.appcompat.app.AppCompatActivity, unit: BannerAdUnit, listener: AdEventListener) {
        bannerLoads++
        bannerListener = listener
        onSplashRequest?.invoke()
        if (fillBanner) listener.onLoaded() else if (settleBanner) listener.onFailedToLoad()
    }
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
    var holdUpdate = false
    var pendingUpdate: ConsentInformation.OnConsentInfoUpdateSuccessListener? = null
    var requireForm = false
    var allowed = false
    override fun canRequestAds() = allowed
    override fun getConsentStatus() = if (allowed) ConsentInformation.ConsentStatus.OBTAINED else ConsentInformation.ConsentStatus.REQUIRED
    override fun getPrivacyOptionsRequirementStatus() = ConsentInformation.PrivacyOptionsRequirementStatus.NOT_REQUIRED
    override fun isConsentFormAvailable() = requireForm
    override fun requestConsentInfoUpdate(activity: Activity, parameters: ConsentRequestParameters,
        success: ConsentInformation.OnConsentInfoUpdateSuccessListener,
        failure: ConsentInformation.OnConsentInfoUpdateFailureListener) {
        if (holdUpdate) {
            pendingUpdate = success
            return
        }
        if (!requireForm) allowed = true
        success.onConsentInfoUpdateSuccess()
    }
    override fun reset() { allowed = false }
}
