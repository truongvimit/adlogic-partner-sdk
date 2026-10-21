package io.onboardkit.ui.resume

import androidx.appcompat.app.AppCompatActivity
import com.ads.module.config.settings.AdBehavior
import io.onboardkit.OnboardingSdk
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.loadAndShowInterstitial
import io.onboardkit.ads.whenResumed
import io.onboardkit.core.ObLog
import io.onboardkit.remote.OnboardingSettings

/**
 * Start, or back, on either native resume screen: `inter_resume` load-and-show, AFTER_AD. The ad
 * reports its close while it still covers the screen, so the screen first comes back in front and
 * only then closes: its native, possibly one that filled behind the ad, is on screen for the exit.
 * An inter that never shows (off, capped, no fill) closes it at once.
 *
 * System back does the same once the ad on the screen has made its impression, or when nothing is
 * loading for it; while it loads, or a newly bound ad is not yet seen, back does nothing.
 */
internal class ResumeStart(private val screen: AppCompatActivity) {

    private var pressed = false

    /** From the moment inter_resume is on screen: the screen comes back only to close. */
    var closingAfterInter = false
        private set

    /** The ad on screen made its impression; a newly bound ad starts over. */
    private var nativeShown = false

    /** Loading, or bound and waiting for its impression. A screen starts here. */
    private var nativePending = true

    fun onNativeLoading() {
        nativePending = true
        nativeShown = false
    }

    /** [alreadyShown]: the ad made its impression before this bind, here or on another screen. */
    fun onNativeBound(alreadyShown: Boolean) {
        nativePending = true
        nativeShown = alreadyShown
    }

    fun onNativeShown() {
        nativeShown = true
    }

    fun onNativeUnavailable() {
        nativePending = false
    }

    fun pressBack() {
        if (!nativeShown && nativePending) {
            ObLog.d(ObLog.Section.SHOW, "native_resume back held until its native is shown")
            return
        }
        press()
    }

    /** Warms the fill when the screen opens, so Start rarely waits on the loading dialog. */
    fun preload() {
        val placement = AdPlacement.ResumeInterstitial
        val unit = OnboardingSdk.configOrNull()?.ads?.resumeInterstitial ?: return
        if (OnboardingSdk.guard().skipReason(screen, placement, unit) != null) return
        OnboardingSdk.provider()?.loadInterstitial(screen, placement, unit)
    }

    fun press() {
        // A second tap under the loading dialog would be refused as overlapping and close the screen.
        if (pressed) return
        pressed = true
        screen.loadAndShowInterstitial(
            AdPlacement.ResumeInterstitial,
            timeoutMs = OnboardingSettings.behavior(AdPlacement.ResumeInterstitial)
                .long("load_and_show.wait_timeout_ms", AdBehavior.defaultNumber("interstitial.load_and_show.wait_timeout_ms")),
            onNext = { closingAfterInter = true },
            onFinished = { skipped ->
                if (skipped != null) screen.finish()
                else screen.whenResumed(onHostLost = {}) { screen.finish() }
            },
        )
    }
}
