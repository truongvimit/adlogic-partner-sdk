package io.onboardkit.ads

/**
 * When a screen starts its destination, relative to the interstitial it just showed.
 *
 * This is a property of the destination, not of the app: it answers "may this screen exist behind
 * the ad?", and only the launch that picked the destination knows. See
 * [io.onboardkit.ui.splash.ObSplashActivity.nextScreenTiming].
 */
enum class NextScreenTiming {

    /**
     * The destination starts while the ad is on screen, so it is inflated and painted by the time
     * the ad closes. The default, and the right answer for a screen the user simply lands on.
     */
    UNDER_AD,

    /**
     * The destination starts once the ad is gone.
     *
     * Costs a visible stall, and it is the only safe answer when the destination opens a screen of
     * its own on entry: GMA's ad Activity lives in the host's task, so a `startActivity` issued
     * while the ad is up is stacked on top of it and covers the impression. A notification or
     * widget tap that names a feature to open is exactly that case.
     *
     * It also gives up the head start the other mode buys: if the vendor accepts `show()`, never
     * takes the screen, and reports nothing, no destination is already running to fall back on.
     */
    AFTER_AD,
}
