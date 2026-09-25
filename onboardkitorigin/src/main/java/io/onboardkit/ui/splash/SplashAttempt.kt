package io.onboardkit.ui.splash

import com.ads.module.update.ForceUpdateConfig
import com.ads.module.helper.AdGate
import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import io.onboardkit.ads.NextScreenTiming
import io.onboardkit.flow.StartDecision
import io.onboardkit.remote.RemoteFlags
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow

/** One in-memory launch attempt. No Activity, View or ad objects survive through this owner. */
internal class SplashAttempt(application: Application) : AndroidViewModel(application) {
    // Unlike ProcessLifecycleOwner's delayed pause, this closes immediately on Home. An ad's
    // resumed Activity keeps UNDER_AD handoff eligible even though splash itself is paused.
    val foreground = MutableStateFlow(false)
    private var resumedActivities = 0
    private val visibility = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            resumedActivities++
            foreground.value = true
        }
        override fun onActivityPaused(activity: Activity) {
            resumedActivities = (resumedActivities - 1).coerceAtLeast(0)
            foreground.value = resumedActivities > 0
        }
        override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    init { application.registerActivityLifecycleCallbacks(visibility) }

    private var updateAdHold: AutoCloseable? = AdGate.holdRequests()

    fun allowAdRequests() {
        updateAdHold?.close()
        updateAdHold = null
    }

    override fun onCleared() {
        allowAdRequests()
        getApplication<Application>().unregisterActivityLifecycleCallbacks(visibility)
    }

    var startedAtMs = System.currentTimeMillis()
    val id = java.util.UUID.randomUUID().toString().take(8)
    var announced = false
    var consentAnswered: Boolean? = null
    var billingResolved = false
    var remoteResolved = false
    var updateConfig = ForceUpdateConfig()
    var updateGatePassed = false
    var remoteHookResolved = false
    var flags: RemoteFlags? = null
    var startDecision: StartDecision? = null
    /** Whether this launch spends the returning-user splash interstitial position. */
    var returningUser: Boolean? = null
    var adPhaseStartedAtMs = 0L
    var adsRequested = false
    var lfo1Scheduled = false
    var nativeScheduled = false
    var nativeScreenRequested = false
    var nativeScreenResolved = false
    val nativeScreenFinished = CompletableDeferred<Unit>()
    /** Settles whichever format `splash.ads.slot_format` gave the bottom slot — banner or native. */
    val bannerSettled = CompletableDeferred<Unit>()

    /** True once the slot has an ad to show. A slot that failed has nothing worth waiting on. */
    var slotFilled = false

    /**
     * When that ad arrived.
     *
     * The vendor impression is deliberately not used: a collapsible banner never reports one, so
     * anything waiting on it would be waiting for something that may never come.
     */
    var slotLoadedAtMs: Long? = null

    /**
     * When the splash last had the screen to itself, i.e. resumed and focused.
     *
     * The minimum-visible window runs from the later of this and the load, because both have to be
     * true before anyone can look at the ad: a banner renders behind the permission dialog, and a
     * native binds only once that dialog is gone.
     */
    var focusedAtMs: Long? = null

    fun markSlotLoaded() {
        slotFilled = true
        if (slotLoadedAtMs == null) slotLoadedAtMs = SystemClock.elapsedRealtime()
        bannerSettled.complete(Unit)
    }
    val interstitialSettled = CompletableDeferred<InterResult>()
    var budgetDeadlineMs: Long? = null
    /** Past this, a slot that has neither loaded nor failed no longer holds the interstitial. */
    var slotWaitDeadlineMs: Long? = null
    var notificationPermissionRequested = false
    val notificationOpen = MutableStateFlow(false)
    val notificationPermissionResult = CompletableDeferred<Unit>()
    var notificationAnsweredAtMs: Long? = null
    var showRequested = false
    var nextScreenTiming: NextScreenTiming? = null
    val showNext = CompletableDeferred<Unit>()
    val showFinished = CompletableDeferred<Unit>()
    var flowStarted = false
    var completed = false

    enum class InterResult(val lfoReason: String) {
        LOADED("inter_loaded"), FAILED("inter_failed_all"), SKIPPED("inter_skipped"),
        TIMED_OUT("splash_budget_expired")
    }

    fun settleInterstitial(result: InterResult) {
        val expired = budgetDeadlineMs?.let { SystemClock.elapsedRealtime() >= it } == true
        interstitialSettled.complete(if (expired) InterResult.TIMED_OUT else result)
    }

    fun notificationAnswered() {
        if (notificationPermissionResult.complete(Unit)) notificationAnsweredAtMs = SystemClock.elapsedRealtime()
        notificationOpen.value = false
    }
}
