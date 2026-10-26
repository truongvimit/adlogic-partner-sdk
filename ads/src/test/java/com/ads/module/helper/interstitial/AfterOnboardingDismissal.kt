package com.ads.module.helper.interstitial

internal const val AFTER_OB = "inter_after_ob3"

/** The onboarding exit ad closing now: the dismissal that starts a session's first buffer interval. */
internal fun dismissAfterOnboarding() {
    InterstitialAutoBuffer.countTowardInterval(AFTER_OB)
    InterstitialFrequency.beginShow(AFTER_OB)
    InterstitialFrequency.endShow(AFTER_OB, closed = true)
}
