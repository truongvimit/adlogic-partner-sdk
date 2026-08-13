package io.onboardkit.ads

import android.app.Activity
import android.content.Context
import io.onboardkit.core.ObLog
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The transient reasons an app-resume ad must not show.
 *
 * Permanent ones (entitlement, remote flags, missing unit) are not duplicated here — app-resume
 * goes through [AdsGuard] like every other placement.
 */
class ObAppResume internal constructor(
    private val guard: AdsGuard,
    private val provider: OnboardingAdProvider?,
) {

    /** Counted rather than boolean: two overlapping full-screen ads must both hold it down. */
    private val depth = AtomicInteger(0)
    private val returningFromAdClick = AtomicBoolean(false)

    private val isSuppressed: Boolean get() = depth.get() > 0

    /** Balance every call with [release]; [ObInterstitial] already does this for interstitials. */
    fun suppress() {
        ObLog.d(ObLog.Section.RESUME, "suppress depth=${depth.incrementAndGet()}")
    }

    fun release() {
        val current = depth.updateAndGet { if (it > 0) it - 1 else 0 }
        ObLog.d(ObLog.Section.RESUME, "release depth=$current")
    }

    /**
     * The user tapped an ad and is about to leave. The next return to the foreground is the ad
     * network handing them back, not a fresh session.
     */
    fun onAdClicked() {
        ObLog.d(ObLog.Section.RESUME, "ad_clicked — next foreground is not a new session")
        returningFromAdClick.set(true)
    }

    /** `null` means an app-resume ad may show. One-shot state is consumed by asking. */
    fun skipReason(context: Context): AdSkipReason? {
        if (isSuppressed) return AdSkipReason.SUPPRESSED_BY_FLOW
        if (returningFromAdClick.getAndSet(false)) return AdSkipReason.RETURNING_FROM_AD_CLICK
        return guard.skipReason(context, AdPlacement.AppResume)
    }

    /**
     * Keeps app-resume off [activityClass] for as long as it exists.
     *
     * The SDK calls this for its own screens, so a partner never has to list them by hand —
     * forgetting one is invisible until an ad lands on the language picker in production.
     */
    fun excludeScreen(activityClass: Class<out Activity>) {
        provider?.suppressAppResume(activityClass)
    }
}
