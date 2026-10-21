package io.onboardkit.ui.resume

import androidx.appcompat.app.AppCompatActivity
import com.ads.module.config.settings.AdBehavior
import io.onboardkit.OnboardingSdk
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.loadAndShowInterstitial
import io.onboardkit.remote.OnboardingSettings

/**
 * Start, or back, on either native resume screen: `inter_resume` load-and-show, AFTER_AD. Once the
 * ad is closed the screen stays up, so a native_resume that filled behind the ad is seen; the next
 * Start or back closes it. An inter that never shows (off, capped, no fill) closes it at once.
 */
internal class ResumeStart(private val screen: AppCompatActivity) {

    private var pressed = false
    private var interShown = false
    private var backFromInter = false

    /** Warms the fill when the screen opens, so Start rarely waits on the loading dialog. */
    fun preload() {
        val placement = AdPlacement.ResumeInterstitial
        val unit = OnboardingSdk.configOrNull()?.ads?.resumeInterstitial ?: return
        if (OnboardingSdk.guard().skipReason(screen, placement, unit) != null) return
        OnboardingSdk.provider()?.loadInterstitial(screen, placement, unit)
    }

    fun press() {
        if (interShown) {
            screen.finish()
            return
        }
        // A second tap under the loading dialog would be refused as overlapping and close the screen.
        if (pressed) return
        pressed = true
        screen.loadAndShowInterstitial(
            AdPlacement.ResumeInterstitial,
            timeoutMs = OnboardingSettings.behavior(AdPlacement.ResumeInterstitial)
                .long("load_and_show.wait_timeout_ms", AdBehavior.defaultNumber("interstitial.load_and_show.wait_timeout_ms")),
            // On screen, before the host can resume under it: the close may be reported after onResume.
            onNext = { backFromInter = true },
            onFinished = { skipped ->
                if (skipped != null) screen.finish() else interShown = true
            },
        )
    }

    /** True once, for the resume that follows the closed inter_resume. */
    fun consumeBackFromInter(): Boolean = backFromInter.also { backFromInter = false }
}
