package io.onboardkit.ui.resume

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import io.onboardkit.OnboardingSdk
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.showNativeAd
import io.onboardkit.databinding.ObResumeScreenBinding
import io.onboardkit.remote.OnboardingSettings
import io.onboardkit.ui.base.BaseOnboardActivity

/**
 * Native resume, opened when the app returns from the background. Start or back closes it onto the
 * screen the user left. Its shown ad outlives it, so the next return shows that ad at once and binds
 * a newer one over it when that loads.
 */
class ObResumeActivity : BaseOnboardActivity() {

    override val screenName: String = "ob_resume"

    // An ordinary screen to every resume type, so its own return renews its ad.
    override val excludeFromAppResume: Boolean = false

    // Restored over the app's own restored screen, not a restart of the whole flow.
    override val restartFlowAfterProcessDeath: Boolean get() = false

    private var renewOnResume = false

    override fun onCreateSafe(savedInstanceState: Bundle?) {
        val b = ObResumeScreenBinding.inflate(layoutInflater)
        setContentView(b.root)
        val cfg = sdk.requireConfig().resumeScreen
        b.obResumeImage.setImageResource(cfg.imageRes)
        b.obResumeTitle.text = cfg.title ?: getString(cfg.titleRes)
        b.obResumeSubtitle.text = cfg.subtitle ?: getString(cfg.subtitleRes)
        OnboardingSettings.resumeStartColor()?.let(b.obResumeStart::setTextColor)
        b.obResumeStart.setOnClickListener { finish() }

        val placement = AdPlacement.ResumeNative
        var bound = false
        showNativeAd(
            placement = placement,
            unit = sdk.requireConfig().ads.nativeUnitFor(placement),
            container = b.obResumeAd,
            onLoading = { b.obResumeAdBlock.isVisible = true },
            onBound = {
                bound = true
                b.obResumeAdBlock.isVisible = true
            },
            onUnavailable = { if (!bound) b.obResumeAdBlock.isVisible = false },
        )
    }

    override fun onResume() {
        super.onResume()
        if (renewOnResume) {
            renewOnResume = false
            renewAd()
        }
    }

    /** A return to this screen itself: bind what its background request brought. */
    internal fun renew() {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) renewAd() else renewOnResume = true
    }

    private fun renewAd() {
        OnboardingSdk.provider()?.renewNative(AdPlacement.ResumeNative)
    }

    override fun handleBack() = finish()

    internal companion object {
        fun start(host: Activity) {
            host.startActivity(Intent(host, ObResumeActivity::class.java))
        }
    }
}
