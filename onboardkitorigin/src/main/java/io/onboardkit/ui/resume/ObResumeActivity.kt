package io.onboardkit.ui.resume

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.ObNativeResume
import io.onboardkit.ads.showNativeAd
import io.onboardkit.remote.OnboardingSettings
import io.onboardkit.ui.base.BaseOnboardActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Native resume, opened when the app returns from the background. Start and back both run the
 * `inter_resume` load-and-show, then close it onto the screen the user left. Its shown ad outlives
 * it, so the next return shows that ad at once and binds a newer one over it when that loads. A return to this screen itself opens nothing: it keeps the
 * base exclusion from app-resume, like the splash and the ad screens.
 */
class ObResumeActivity : BaseOnboardActivity() {

    override val screenName: String = "ob_resume"

    // Restored over the app's own restored screen, not a restart of the whole flow.
    override val restartFlowAfterProcessDeath: Boolean get() = false

    private val startAction = ResumeStart(this)

    override fun onCreateSafe(savedInstanceState: Bundle?) {
        val views = ResumeScreenViews.inflate(layoutInflater)
        setContentView(views.root)
        val cfg = sdk.requireConfig().resumeScreen
        ResumeScreenViews.bindOptionalCopy(views, cfg.imageRes, cfg.title ?: getString(cfg.titleRes), cfg.subtitle ?: getString(cfg.subtitleRes), this)
        (views.start as? android.widget.TextView)?.let { OnboardingSettings.resumeStartColor()?.let(it::setTextColor) }
        views.start.setOnClickListener { startAction.press() }
        revealStart(views.start)
        startAction.preload()
        // Before the bind: a retained ad shows at once and the new request replaces it on fill.
        ObNativeResume.prepare(this)

        val placement = AdPlacement.ResumeNative
        var bound = false
        showNativeAd(
            placement = placement,
            unit = sdk.requireConfig().ads.nativeUnitFor(placement),
            container = views.ad,
            onLoading = { views.adBlock.isVisible = true },
            onBound = {
                bound = true
                views.adBlock.isVisible = true
            },
            onUnavailable = { if (!bound) views.adBlock.isVisible = false },
        )
    }

    private fun revealStart(start: android.view.View) {
        val delayMs = OnboardingSettings.resumeStartDelayMs()
        if (delayMs == 0L) {
            start.isVisible = true
            return
        }
        start.isVisible = false
        lifecycleScope.launch {
            delay(delayMs)
            if (!isFinishing && !isDestroyed) start.isVisible = true
        }
    }

    override fun handleBack() = startAction.press()

    internal companion object {
        fun start(host: Activity) {
            host.startActivity(Intent(host, ObResumeActivity::class.java))
        }
    }
}
