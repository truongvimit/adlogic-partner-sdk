package io.onboardkit.ads.erain

import com.ads.module.admob.Admob

/**
 * The one place the ads module's process-wide switches are set.
 *
 * They are globals that change what a *callback means*, so the only safe way to use them is to
 * pick a value once and never touch it again. Toggling them per screen — the pattern this
 * replaces — leaves the process in whatever state the last screen died in, and a screen that
 * dies between the two toggles leaves it wrong forever.
 */
object ERainTuning {

    /**
     * Pins the module to the semantics the flow is written against.
     *
     * Call once from `Application.onCreate`, after `ERainAd.init`.
     */
    fun install() {
        // Pinned ON because it is the module's only safe prewarm point.
        //
        // `Admob.showInterstitialAd` shows a loading dialog, waits 800 ms, re-checks that the host
        // Activity is still RESUMED, and only then calls `onNextAction` and `show()`. Starting the
        // next screen any earlier — on `onInterstitialShow`, say — pauses the host before that
        // re-check, and the module drops the ad with "show fail in background after show loading
        // ad". So the head start has to come from inside that window, which is what this flag does.
        //
        // What makes it safe here is that `onNextAction` is mapped to "the ad is on screen, start
        // the destination", never to "finish this screen": ERainAdProvider finishes only on the
        // terminal `onAdClosed` / `onAdFailedToShow`, which the module still delivers with this
        // flag on. Wiring `onNextAction` straight to `startActivity + finish()` is what kills the
        // ad, not the flag itself.
        Admob.getInstance().setOpenActivityAfterShowInterAds(true)

        // A tap on an ad takes the user out of the app; the return is the network handing them
        // back, not a new session, so app-resume must not fire on it.
        Admob.getInstance().setDisableAdResumeWhenClickAds(true)
    }
}
