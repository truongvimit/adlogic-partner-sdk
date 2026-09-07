package io.onboardkit.ui.splash

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import io.onboardkit.ads.NextScreenTiming
import io.onboardkit.flow.StartDecision
import io.onboardkit.remote.RemoteFlags
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow

/** One in-memory launch attempt. No Activity, View or ad objects survive through this owner. */
internal class SplashAttempt : ViewModel() {
    var startedAtMs = System.currentTimeMillis()
    val id = java.util.UUID.randomUUID().toString().take(8)
    var announced = false
    var consentAnswered: Boolean? = null
    var billingResolved = false
    var remoteResolved = false
    var remoteHookResolved = false
    var flags: RemoteFlags? = null
    var startDecision: StartDecision? = null
    var adPhaseStartedAtMs = 0L
    var adsRequested = false
    var lfo1Scheduled = false
    val bannerSettled = CompletableDeferred<Unit>()
    val interstitialSettled = CompletableDeferred<InterResult>()
    var budgetDeadlineMs: Long? = null
    var bannerDeadlineMs: Long? = null
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
