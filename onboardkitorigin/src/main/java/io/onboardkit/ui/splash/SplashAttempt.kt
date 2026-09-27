package io.onboardkit.ui.splash

import com.ads.module.update.ForceUpdateConfig
import com.ads.module.helper.AdGate
import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.NextScreenTiming
import io.onboardkit.flow.StartDecision
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference

internal class SplashAttempt(application: Application) : AndroidViewModel(application) {
    private var updateAdHold: AutoCloseable? = AdGate.holdRequests()
    private var host = WeakReference<ObSplashActivity>(null)

    var startedAtMs = System.currentTimeMillis()
    val id = java.util.UUID.randomUUID().toString().take(8)
    var announced = false
    var consentAnswered: Boolean? = null
    var billingResolved = false
    var updateConfig: ForceUpdateConfig? = null
    var updateGatePassed = false
    var remoteRefresh: Deferred<Unit>? = null
    var remoteHookResolved = false
    var startDecision: StartDecision? = null
    var returningUser: Boolean? = null
    var adPhaseStartedAtMs: Long? = null
    val preloadsRequested = mutableSetOf<AdPlacement>()
    val interstitialSettled = CompletableDeferred<InterResult>()
    var budgetDeadlineMs: Long? = null
    private val _prompt = MutableStateFlow<SplashPrompt>(SplashPrompt.NotAsked)
    val prompt: StateFlow<SplashPrompt> = _prompt.asStateFlow()
    var nextScreenTiming: NextScreenTiming? = null
    var showRequested = false
    val showNext = CompletableDeferred<Unit>()
    val showFinished = CompletableDeferred<Unit>()
    var nativeScreenRequested = false
    var nativeScreenResolved = false
    val nativeScreenFinished = CompletableDeferred<Unit>()
    var flowStarted = false

    val promptAnsweredAtMs: Long? get() = (prompt.value as? SplashPrompt.Answered)?.atMs

    enum class InterResult(val lfoReason: String) {
        LOADED("inter_loaded"), FAILED("inter_failed_all"), SKIPPED("inter_skipped"),
        TIMED_OUT("splash_budget_expired")
    }

    fun attach(splash: ObSplashActivity) {
        host = WeakReference(splash)
    }

    fun allowAdRequests() {
        updateAdHold?.close()
        updateAdHold = null
    }

    override fun onCleared() {
        allowAdRequests()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun settledInterOrNull(): InterResult? =
        if (interstitialSettled.isCompleted) interstitialSettled.getCompleted() else null

    fun onInterResult(result: InterResult) {
        if (interstitialSettled.isCompleted) return
        val now = SystemClock.elapsedRealtime()
        val expired = budgetDeadlineMs?.let { now >= it } == true
        val settled = if (expired) InterResult.TIMED_OUT else result
        // Queue preloads before completing the result and waking the presentation path.
        host.get()?.takeUnless { it.isFinishing || it.isDestroyed }?.requestSplashPreloads(settled)
        interstitialSettled.complete(settled)
    }

    fun promptOpened() {
        if (_prompt.value == SplashPrompt.NotAsked) _prompt.value = SplashPrompt.Open
    }

    fun promptAnswered() {
        if (_prompt.value is SplashPrompt.Answered) return
        _prompt.value = SplashPrompt.Answered(SystemClock.elapsedRealtime())
    }
}

internal sealed interface SplashPrompt {
    data object NotAsked : SplashPrompt
    data object Open : SplashPrompt
    data class Answered(val atMs: Long) : SplashPrompt
}
