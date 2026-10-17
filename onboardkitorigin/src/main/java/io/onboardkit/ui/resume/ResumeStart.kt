package io.onboardkit.ui.resume

import androidx.appcompat.app.AppCompatActivity
import com.ads.module.config.settings.AdBehavior
import io.onboardkit.OnboardingSdk
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.loadAndShowInterstitial
import io.onboardkit.remote.OnboardingSettings

/**
 * Start on either native resume screen: `inter_resume` load-and-show, then the screen closes. Its
 * closing is Start itself, and an inter that never shows (off, capped, no fill) closes at once.
 */
internal class ResumeStart(private val screen: AppCompatActivity) {

    private var pressed = false

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
            onFinished = { screen.finish() },
        )
    }
}
