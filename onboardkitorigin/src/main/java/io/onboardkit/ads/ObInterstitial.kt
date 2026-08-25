package io.onboardkit.ads

import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import io.onboardkit.OnboardingSdk
import io.onboardkit.core.ObLog
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shows the interstitial for [placement].
 *
 * The presentation has two moments and they are not the same one, which is the whole reason this
 * takes two callbacks:
 *
 * | Callback | When | What belongs here |
 * |---|---|---|
 * | [onNext] | the ad is on screen, or it never will be | `startActivity(next)` |
 * | [onFinished] | the ad is gone | `finish()` |
 *
 * Starting the next screen at [onNext] lets it inflate and bind *underneath* the ad, so it is
 * already painted when the ad closes. Doing it in [onFinished] instead costs the user a visible
 * stall — but that is the right choice for a destination that must not exist behind the ad (one
 * that opens a camera or plays audio), so simply leave [onNext] out for those.
 *
 * Both run at most once, [onNext] always before [onFinished], on every path — including the ones
 * where no ad appears at all. Callers need no guard of their own.
 */
fun AppCompatActivity.showInterstitial(
    placement: AdPlacement,
    onNext: () -> Unit = {},
    onFinished: (AdSkipReason?) -> Unit = {},
) {
    ObInterstitial.show(this, placement, onNext, onFinished)
}

/**
 * One interstitial presentation at a time, process-wide.
 *
 * Stateless apart from that latch: everything else lives on the stack of a single [show] call,
 * which is what keeps a failed presentation from leaving anything behind to clean up.
 */
internal object ObInterstitial {

    /**
     * The module's own show window: it holds a loading dialog for 800 ms, then dismisses it
     * 1500 ms later. Anything still silent past that never started.
     *
     * Giving up finishes the host, so this must never expire while an ad is on screen. It is safe
     * because a provider hands back on the same tick as `show()` — that ordering is fixed in
     * `InterNextAction.UnderAd` for exactly this kind of reason.
     */
    private const val HANDSHAKE_TIMEOUT_MS = 800L + 1_500L

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var isShowing = false

    fun show(
        activity: AppCompatActivity,
        placement: AdPlacement,
        onNext: () -> Unit,
        onFinished: (AdSkipReason?) -> Unit,
    ) {
        ObLog.d(ObLog.Section.SHOW, "${placement.key} begin host=${activity.javaClass.simpleName}")
        val startedAtMs = System.currentTimeMillis()
        var suppressedAppResume = false
        val next = RunOnce<Unit> { onNext() }
        val finish = RunOnce<AdSkipReason?> { reason ->
            isShowing = false
            if (suppressedAppResume) OnboardingSdk.appResume().release()
            val elapsed = System.currentTimeMillis() - startedAtMs
            if (reason == null) {
                ObLog.d(ObLog.Section.SHOW, "${placement.key} end shown ms=$elapsed")
            } else {
                ObLog.w(ObLog.Section.SHOW, "${placement.key} end skipped=${reason.key} ms=$elapsed")
                placement.trackSkipped(reason)
            }
            // A presentation that never reached the screen still owes the caller its go-ahead,
            // or a screen wired to start its destination on onNext would simply never leave.
            next.run()
            onFinished(reason)
        }

        val blocked = blockReason(activity, placement)
        if (blocked != null) {
            finish.run(blocked)
            return
        }

        isShowing = true
        // An app-resume ad landing on top of our own full-screen ad is two stacked ads, which is
        // both unwatchable and a policy problem.
        OnboardingSdk.appResume().suppress()
        suppressedAppResume = true

        // The ads module drops show() without any callback when the process is not resumed, so
        // the call has to be gated rather than corrected afterwards.
        activity.whenResumed(
            // Without this the presentation would never end and the process-wide latch above
            // would block every later interstitial.
            onHostLost = { finish.run(AdSkipReason.NOT_READY) },
        ) {
            if (activity.isFinishing || activity.isDestroyed) {
                finish.run(AdSkipReason.NOT_READY)
                return@whenResumed
            }
            present(activity, placement, next, finish)
        }
    }

    private fun blockReason(activity: AppCompatActivity, placement: AdPlacement): AdSkipReason? {
        if (isShowing) return AdSkipReason.SUPPRESSED_BY_FLOW
        OnboardingSdk.guard().skipReason(activity, placement)?.let { return it }
        val provider = OnboardingSdk.provider() ?: return AdSkipReason.NO_PROVIDER
        if (!provider.isInterstitialReady(placement)) return AdSkipReason.NOT_READY
        return null
    }

    private fun present(
        activity: AppCompatActivity,
        placement: AdPlacement,
        next: RunOnce<Unit>,
        finish: RunOnce<AdSkipReason?>,
    ) {
        val provider = OnboardingSdk.provider() ?: run {
            finish.run(AdSkipReason.NO_PROVIDER)
            return
        }
        // The module has a branch that accepts show() and then reports nothing; without this the
        // flow would sit on this screen forever.
        val watchdog = Runnable { finish.run(AdSkipReason.NO_HANDSHAKE) }
        mainHandler.postDelayed(watchdog, HANDSHAKE_TIMEOUT_MS)

        ObLog.d(ObLog.Section.SHOW, "${placement.key} vendor_show")
        provider.showInterstitial(
            activity,
            placement,
            object : ObInterstitialCallback() {
                override fun onNextAction() {
                    mainHandler.removeCallbacks(watchdog)
                    ObLog.d(ObLog.Section.SHOW, "${placement.key} visible -> next")
                    next.run()
                }

                override fun onAdClosed() {
                    mainHandler.removeCallbacks(watchdog)
                    finish.run(null)
                }

                override fun onAdSkipped(reason: AdSkipReason) {
                    mainHandler.removeCallbacks(watchdog)
                    finish.run(reason)
                }
            },
        )
    }
}

/**
 * Runs [block] once this Activity is RESUMED, immediately if it already is, or [onHostLost] if it
 * is destroyed first. Exactly one of the two always runs.
 */
private fun AppCompatActivity.whenResumed(onHostLost: () -> Unit, block: () -> Unit) {
    if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
        block()
        return
    }
    if (lifecycle.currentState == Lifecycle.State.DESTROYED) {
        onHostLost()
        return
    }
    ObLog.d(ObLog.Section.SHOW, "holding for RESUMED state=${lifecycle.currentState}")
    lifecycle.addObserver(
        object : LifecycleEventObserver {
            override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                when (event) {
                    Lifecycle.Event.ON_RESUME -> {
                        source.lifecycle.removeObserver(this)
                        block()
                    }

                    Lifecycle.Event.ON_DESTROY -> {
                        source.lifecycle.removeObserver(this)
                        onHostLost()
                    }

                    else -> Unit
                }
            }
        },
    )
}

/**
 * Runs [block] for the first caller only.
 *
 * Both moments of a presentation can be reached from more than one callback, and the vendor is
 * free to report a duplicate; this is what keeps "start the next screen" and "finish this one"
 * happening exactly once each.
 */
private class RunOnce<T>(private val block: (T) -> Unit) {
    private val done = AtomicBoolean(false)

    fun run(value: T) {
        if (done.compareAndSet(false, true)) block(value)
    }
}

private fun RunOnce<Unit>.run() = run(Unit)
