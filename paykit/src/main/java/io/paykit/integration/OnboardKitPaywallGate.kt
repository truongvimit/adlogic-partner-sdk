package io.paykit.integration

import android.app.Activity
import io.onboardkit.paywall.PaywallGate
import io.onboardkit.paywall.PaywallOutcome
import io.paykit.PayKit
import io.paykit.PaywallListener
import io.paykit.PaywallResult
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import io.onboardkit.paywall.PaywallPlacement as OnboardPlacement
import io.paykit.PaywallPlacement as PayKitPlacement

/**
 * Drives PayKit from OnboardKit's paywall SPI, so the onboarding flow never learns about billing.
 *
 * Requires `:onboardkitorigin` on the **runtime** classpath. `:paykit` depends on it `compileOnly`,
 * so a host that ships the paywall without onboarding must never reference this class.
 */
class OnboardKitPaywallGate : PaywallGate {

    override suspend fun shouldShow(placement: OnboardPlacement): Boolean =
        PayKit.isReady() && !PayKit.isPremium() && PayKit.isEnabled(placement.toPayKit())

    // No pre-checks: launch reports every refusal through the listener, and a second copy of that
    // rule here would be one more thing to keep in step with it.
    override suspend fun present(activity: Activity, placement: OnboardPlacement): PaywallOutcome =
        suspendCancellableCoroutine { continuation ->
            PayKit.launch(activity, placement.toPayKit(), ResumeOnFinish(continuation))
        }
}

/** Resumes exactly once, even if a renderer manages to report two exits for one presentation. */
private class ResumeOnFinish(
    private val continuation: CancellableContinuation<PaywallOutcome>,
) : PaywallListener() {

    private val resumed = AtomicBoolean(false)

    override fun onFinished(placement: PayKitPlacement, result: PaywallResult) {
        if (resumed.compareAndSet(false, true)) continuation.resume(result.toOutcome())
    }
}

private fun OnboardPlacement.toPayKit(): PayKitPlacement = when (this) {
    OnboardPlacement.SPLASH_INTER -> PayKitPlacement.SPLASH
    OnboardPlacement.AFTER_ONBOARDING -> PayKitPlacement.AFTER_ONBOARDING
    // OnboardKit's returning-user checkpoint has no dedicated PayKit placement.
    OnboardPlacement.AFTER_QUESTION_OLD_USER -> PayKitPlacement.OTHER
}

private fun PaywallResult.toOutcome(): PaywallOutcome = when (this) {
    is PaywallResult.Purchased -> PaywallOutcome.Purchased
    is PaywallResult.ContinueWithAds -> PaywallOutcome.ContinueWithAds
    is PaywallResult.Dismissed -> PaywallOutcome.Dismissed
    // OnboardKit has no error outcome, and a failed paywall must not strand the user mid-flow.
    is PaywallResult.Error -> PaywallOutcome.Dismissed
}
