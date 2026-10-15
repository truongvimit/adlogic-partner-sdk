package io.onboardkit.ui.pool

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import io.onboardkit.OnboardingSdk
import io.onboardkit.R
import io.onboardkit.ads.AdPlacement
import io.onboardkit.config.PrivacyScreenConfig
import io.onboardkit.core.StepId

/**
 * The SDK's Privacy step as a pooled select screen (deviation D-PRIVACY): the consent toggle is
 * the selection, in both directions, and Continue is enabled while consent is given. Continue
 * opens Goal, which claims its own ad: only full pages are warmed a screen ahead.
 */
internal class PoolPrivacyFragment : Fragment(), PoolStage {

    private val select = PoolSelectScreen(
        fragment = this,
        ad1 = AdPlacement.StepNative(StepId.PARTNER_PRIVACY),
        ad2 = AdPlacement.StepNative(StepId.PARTNER_PRIVACY_ALT),
    )
    private var accepted = false
    private var continued = false

    override val blocksAppResume: Boolean get() = false

    private val config: PrivacyScreenConfig
        get() = OnboardingSdk.requireConfig().privacyGoalsScreen.privacy

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        accepted = savedInstanceState?.getBoolean(KEY_ACCEPTED) == true
        select.restore(savedInstanceState)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        PoolPrivacyGoalsViews.inflate(inflater, container, config.layoutRes)

    @Suppress("DEPRECATION")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val cfg = config
        val consent = PoolPrivacyGoalsViews.required(view, cfg.consentViewId, "privacy.consentViewId")
        val ad = PoolPrivacyGoalsViews.frame(
            view, cfg.adContainerId.takeIf { it != 0 } ?: R.id.ob_privacy_goals_ad, "privacy.adContainerId",
        )
        val cta = PoolPrivacyGoalsViews.actionView(view, cfg.continueViewId, setOf(consent, ad), "privacy action")
        select.onViewCreated(ad, cta)
        cta.isEnabled = accepted
        cta.setOnClickListener { if (accepted) continueToGoal() }
        PoolPrivacyGoalsViews.bindConsent(consent, accepted) { checked ->
            accepted = checked
            cta.isEnabled = checked
            select.onSelection()
        }
    }

    private fun continueToGoal() {
        val host = activity as? ObPoolFlowActivity ?: return
        if (continued) return
        continued = true
        host.goTo(ObPoolFlowActivity.Stage.GOAL)
    }

    override fun onResume() {
        super.onResume()
        select.onResume()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_ACCEPTED, accepted)
        select.save(outState)
    }

    override fun onDestroyView() {
        select.onDestroyView()
        super.onDestroyView()
    }

    private companion object {
        const val KEY_ACCEPTED = "ob_pool_privacy_accepted"
    }
}
