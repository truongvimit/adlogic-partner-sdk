package io.onboardkit.ui.pool

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.core.view.isVisible
import io.onboardkit.OnboardingSdk
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.pool.OnboardingPool
import io.onboardkit.ads.pool.PoolNativeSlot
import io.onboardkit.ads.pool.screenSlotMinState
import io.onboardkit.databinding.ObResumeScreenBinding
import io.onboardkit.flow.NativeFlowSnapshot
import io.onboardkit.remote.OnboardingSettings
import io.onboardkit.ui.base.BaseOnboardActivity

/**
 * Native resume while a SHARED_POOL run routes it (DashDrama ResumeActivity). Its slot never owns:
 * it shows the owner of the screen underneath, or after the flow the newest leftover, and asks for
 * its own native only POLITE, a refresh included. A config refusal of native_resume hides it, as in DashDrama and the
 * classic screen. It never suppresses app-resume either; an ad click is the only mark.
 *
 * Once its ad is clicked the screen keeps no ad for the rest of its life and requests nothing
 * (DashDrama's f23721c): the user comes back from the landing page to the Start button alone.
 */
internal class ObPoolResumeActivity : BaseOnboardActivity() {

    override val screenName: String = "ob_resume"

    // An ordinary screen to every resume type, so its own return renews its ad.
    override val excludeFromAppResume: Boolean = false

    // Restored over the app's own restored screen, not a restart of the whole flow.
    override val restartFlowAfterProcessDeath: Boolean get() = false

    private var binding: ObResumeScreenBinding? = null
    private var slot: PoolNativeSlot? = null

    /** DashDrama's f23721c; saved, as its click flag survives a screen destroyed under the landing page. */
    private var adClicked = false

    override fun onCreateSafe(savedInstanceState: Bundle?) {
        // Restored after the run went classic, or in a process whose pool is gone: nothing to borrow.
        if (!OnboardingPool.isActive) {
            finish()
            return
        }
        adClicked = savedInstanceState?.getBoolean(KEY_AD_CLICKED) == true
        OnboardingPool.attachScreen(this)
        val b = ObResumeScreenBinding.inflate(layoutInflater).also { binding = it }
        setContentView(b.root)
        val cfg = sdk.requireConfig().resumeScreen
        b.obResumeImage.setImageResource(cfg.imageRes)
        b.obResumeTitle.text = cfg.title ?: getString(cfg.titleRes)
        b.obResumeSubtitle.text = cfg.subtitle ?: getString(cfg.subtitleRes)
        OnboardingSettings.resumeStartColor()?.let(b.obResumeStart::setTextColor)
        b.obResumeStart.setOnClickListener { finish() }

        val flow = runSnapshot()
        slot = PoolNativeSlot(
            owner = this,
            placement = AdPlacement.ResumeNative,
            // OWNER only for visibility: like DashDrama's p(k()), the frame collapses when native_resume
            // itself is refused instead of lending another screen's ad. The screen still never claims.
            kind = PoolNativeSlot.Kind.OWNER,
            minState = flow.screenSlotMinState,
            container = b.obResumeAd,
            callbacks = StepSlotCallbacks(
                onLoading = { if (!adClicked) b.obResumeAdBlock.isVisible = true },
                onBound = { if (!adClicked) b.obResumeAdBlock.isVisible = true },
                onUnavailable = { b.obResumeAdBlock.isVisible = false },
                onAdEngaged = { onAdClicked() },
            ),
            destroyOnTeardown = flow.viewDestroyOnTeardown,
        ).also { it.start() }
        if (adClicked) hideAd()
    }

    /**
     * Frozen at the click, which may be reported once the landing page has paused the screen: the
     * slot's re-collect on the way back (at STARTED under `screen_slots_bind_started`) would
     * otherwise draw the clicked ad again before onResume hides it.
     */
    private fun onAdClicked() {
        adClicked = true
        slot?.setFrozen(true)
    }

    private fun hideAd() {
        slot?.setFrozen(true)
        binding?.obResumeAdBlock?.isVisible = false
    }

    /**
     * Every request of this screen is POLITE: a held native_resume is refreshed only while no other
     * request is in flight, otherwise it is asked for POLITE; back from its ad's landing page the
     * screen hides the ad and asks for nothing (DashDrama ResumeActivity.java:142-149).
     */
    override fun onResume() {
        super.onResume()
        if (isFinishing || !OnboardingPool.isActive) return
        if (adClicked) {
            hideAd()
            return
        }
        if (!OnboardingPool.refreshPolite(AdPlacement.ResumeNative)) OnboardingPool.preload(AdPlacement.ResumeNative)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(KEY_AD_CLICKED, adClicked)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        OnboardingPool.detachScreen(this)
        slot = null
        binding = null
        super.onDestroy()
    }

    override fun handleBack() = finish()

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
