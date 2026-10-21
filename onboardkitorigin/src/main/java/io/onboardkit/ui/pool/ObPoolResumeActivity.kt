package io.onboardkit.ui.pool

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.core.view.isVisible
import io.onboardkit.OnboardingSdk
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.pool.OnboardingPool
import io.onboardkit.ads.pool.PoolNativeSlot
import io.onboardkit.ads.pool.SCREEN_SLOT_MIN_STATE
import io.onboardkit.flow.NativeFlowSnapshot
import io.onboardkit.remote.OnboardingSettings
import io.onboardkit.ui.base.BaseOnboardActivity
import io.onboardkit.ui.resume.ResumeScreenViews
import io.onboardkit.ui.resume.ResumeStart
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Native resume while a SHARED_POOL run routes it (DashDrama ResumeActivity). Its slot never owns:
 * it shows the owner of the screen underneath, or after the flow the newest leftover, and asks for
 * its own native only POLITE, a refresh included. Start and back both run the `inter_resume`
 * load-and-show ([ResumeStart]); the screen stays up after that ad and the next Start or back closes it. A config refusal of native_resume hides it, as in DashDrama and the
 * classic screen. A return to it opens nothing: it keeps the base exclusion from app-resume, and its
 * own onResume refreshes the ad. It never holds the shared suppression; an ad click is the only mark.
 *
 * A click pauses the slot while the landing page owns the window. When the user returns, the
 * held selection is drawn again instead of collapsing the ad block; the click itself still marks
 * the next foreground return as an ad return through the shared resume guard.
 */
internal class ObPoolResumeActivity : BaseOnboardActivity() {

    override val screenName: String = "ob_resume"

    // Restored over the app's own restored screen, not a restart of the whole flow.
    override val restartFlowAfterProcessDeath: Boolean get() = false

    private var slot: PoolNativeSlot? = null
    private val startAction = ResumeStart(this)

    /** DashDrama's f23721c; saved, as its click flag survives a screen destroyed under the landing page. */
    private var adClicked = false

    override fun onCreateSafe(savedInstanceState: Bundle?) {
        // Restored after the run went classic, or in a process whose pool is gone: nothing to borrow.
        if (!OnboardingPool.isActive) {
            finish()
            return
        }
        adClicked = savedInstanceState?.getBoolean(KEY_AD_CLICKED) == true
        val views = ResumeScreenViews.inflate(layoutInflater)
        setContentView(views.root)
        val cfg = sdk.requireConfig().resumeScreen
        ResumeScreenViews.bindOptionalCopy(views, cfg.imageRes, cfg.title ?: getString(cfg.titleRes), cfg.subtitle ?: getString(cfg.subtitleRes), this)
        (views.start as? android.widget.TextView)?.let { OnboardingSettings.resumeStartColor()?.let(it::setTextColor) }
        views.start.setOnClickListener { startAction.press() }
        revealStart(views.start)
        startAction.preload()

        val flow = runSnapshot()
        slot = PoolNativeSlot(
            owner = this,
            placement = AdPlacement.ResumeNative,
            // OWNER only for visibility: like DashDrama's p(k()), the frame collapses when native_resume
            // itself is refused instead of lending another screen's ad. The screen still never claims.
            kind = PoolNativeSlot.Kind.OWNER,
            minState = SCREEN_SLOT_MIN_STATE,
            container = views.ad,
            callbacks = StepSlotCallbacks(
                onLoading = { if (!adClicked) views.adBlock.isVisible = true },
                onBound = { if (!adClicked) views.adBlock.isVisible = true },
                onUnavailable = { views.adBlock.isVisible = false },
                onAdEngaged = { onAdClicked() },
            ),
            destroyOnTeardown = flow.viewDestroyOnTeardown,
        ).also { it.start() }
        if (adClicked) slot?.setFrozen(true)
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

    /**
     * Frozen at the click, which may be reported once the landing page has paused the screen: the
     * slot's re-collect on the way back (at STARTED) must wait
     * until onResume explicitly redraws the held selection.
     */
    private fun onAdClicked() {
        adClicked = true
        slot?.setFrozen(true)
    }

    private fun restoreAfterAdClick() {
        adClicked = false
        slot?.setFrozen(false)
    }

    /**
     * Every request of this screen is POLITE: a held native_resume is refreshed only while no other
     * request is in flight, otherwise it is asked for POLITE. Returning from its ad's landing page
     * only redraws the held selection; it must not start a replacement request.
     */
    override fun onResume() {
        super.onResume()
        if (isFinishing || !OnboardingPool.isActive) return
        if (adClicked) {
            restoreAfterAdClick()
            return
        }
        // A native_resume that filled behind inter_resume has not been seen yet: draw it, don't replace it.
        if (startAction.consumeBackFromInter()) {
            OnboardingPool.preload(AdPlacement.ResumeNative)
            return
        }
        if (!OnboardingPool.refreshPolite(AdPlacement.ResumeNative)) OnboardingPool.preload(AdPlacement.ResumeNative)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(KEY_AD_CLICKED, adClicked)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        slot = null
        super.onDestroy()
    }

    override fun handleBack() = startAction.press()

    internal companion object {
        private const val KEY_AD_CLICKED = "ob_pool_resume_ad_clicked"

        fun start(host: Activity) {
            host.startActivity(Intent(host, ObPoolResumeActivity::class.java))
        }

        /** The run's latched knobs; a later classic or Welcome Back start clears the latch, not the routing. */
        fun runSnapshot(): NativeFlowSnapshot =
            OnboardingSdk.session.nativeFlow ?: OnboardingSettings.nativeFlowSnapshot()
    }
}
