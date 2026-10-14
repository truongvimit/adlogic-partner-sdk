package io.onboardkit.ui.pool

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import io.onboardkit.OnboardingSdk
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.pool.OnboardingPool
import io.onboardkit.ads.pool.PoolNativeSlot
import io.onboardkit.databinding.ObResumeScreenBinding
import io.onboardkit.flow.NativeFlowSnapshot
import io.onboardkit.remote.OnboardingSettings
import io.onboardkit.ui.base.BaseOnboardActivity

/**
 * Native resume while a SHARED_POOL run routes it (DashDrama ResumeActivity). Its slot never owns:
 * it shows the owner of the screen underneath, or after the flow the newest leftover, and asks for
 * its own native only POLITE. A config refusal of native_resume hides it, as in DashDrama and the
 * classic screen. It never suppresses app-resume either; an ad click is the only mark.
 */
internal class ObPoolResumeActivity : BaseOnboardActivity() {

    override val screenName: String = "ob_resume"

    // An ordinary screen to every resume type, so its own return renews its ad.
    override val excludeFromAppResume: Boolean = false

    // Restored over the app's own restored screen, not a restart of the whole flow.
    override val restartFlowAfterProcessDeath: Boolean get() = false

    override fun onCreateSafe(savedInstanceState: Bundle?) {
        // Restored after the run went classic, or in a process whose pool is gone: nothing to borrow.
        if (!OnboardingPool.isActive) {
            finish()
            return
        }
        val b = ObResumeScreenBinding.inflate(layoutInflater)
        setContentView(b.root)
        val cfg = sdk.requireConfig().resumeScreen
        b.obResumeImage.setImageResource(cfg.imageRes)
        b.obResumeTitle.text = cfg.title ?: getString(cfg.titleRes)
        b.obResumeSubtitle.text = cfg.subtitle ?: getString(cfg.subtitleRes)
        OnboardingSettings.resumeStartColor()?.let(b.obResumeStart::setTextColor)
        b.obResumeStart.setOnClickListener { finish() }

        val flow = runSnapshot()
        PoolNativeSlot(
            owner = this,
            placement = AdPlacement.ResumeNative,
            // OWNER only for visibility: like DashDrama's p(k()), the frame collapses when native_resume
            // itself is refused instead of lending another screen's ad. The screen still never claims.
            kind = PoolNativeSlot.Kind.OWNER,
            minState = if (flow.parity.screenSlotsBindStarted) Lifecycle.State.STARTED else Lifecycle.State.RESUMED,
            container = b.obResumeAd,
            callbacks = StepSlotCallbacks(
                onLoading = { b.obResumeAdBlock.isVisible = true },
                onBound = { b.obResumeAdBlock.isVisible = true },
                onUnavailable = { b.obResumeAdBlock.isVisible = false },
            ),
            destroyOnTeardown = flow.viewDestroyOnTeardown,
        ).start()
        OnboardingPool.preload(AdPlacement.ResumeNative)
    }

    /** DashDrama ResumeActivity.onResume: refresh its own native, then ask for it POLITE. */
    override fun onResume() {
        super.onResume()
        if (isFinishing || !OnboardingPool.isActive) return
        OnboardingPool.refresh(AdPlacement.ResumeNative)
        OnboardingPool.preload(AdPlacement.ResumeNative)
    }

    override fun handleBack() = finish()

    internal companion object {
        fun start(host: Activity) {
            host.startActivity(Intent(host, ObPoolResumeActivity::class.java))
        }

        /** The run's latched knobs; a later classic or Welcome Back start clears the latch, not the routing. */
        fun runSnapshot(): NativeFlowSnapshot =
            OnboardingSdk.session.nativeFlow ?: OnboardingSettings.nativeFlowSnapshot()
    }
}
