package io.onboardkit.ui.resume

import androidx.appcompat.app.AppCompatActivity
import com.ads.module.config.settings.AdBehavior
import io.onboardkit.OnboardingSdk
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.loadAndShowInterstitial
import io.onboardkit.ads.whenResumed
import io.onboardkit.remote.OnboardingSettings

/**
 * Start, or back, on either native resume screen: `inter_resume` load-and-show, AFTER_AD. The ad
 * reports its close while it still covers the screen, so the screen first comes back in front and
 * only then closes: its native, possibly one that filled behind the ad, is on screen for the exit.
 * An inter that never shows (off, capped, no fill) closes it at once.
 */
internal class ResumeStart(private val screen: AppCompatActivity) {

    private var pressed = false

    /** From the moment inter_resume is on screen: the screen comes back only to close. */
    var closingAfterInter = false
        private set

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
