package io.onboardkit.ui.pool

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.annotation.MainThread
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withStarted
import com.ads.module.helper.adnative.pool.LoadMode
import io.onboardkit.OnboardingSdk
import io.onboardkit.R
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.AdSkipReason
import io.onboardkit.ads.NextScreenTiming
import io.onboardkit.ads.pool.OnboardingPool
import io.onboardkit.ads.pool.PoolNativeSlot
import io.onboardkit.ads.pool.PoolPlacementMapper
import io.onboardkit.ads.showInterstitial
import io.onboardkit.ads.trackSkipped
import io.onboardkit.core.FinishReason
import io.onboardkit.core.ObLog
import io.onboardkit.core.StepHost
import io.onboardkit.core.StepId
import io.onboardkit.core.StepType
import io.onboardkit.flow.NativeFlowSnapshot
import io.onboardkit.flow.PoolLocaleApply
import io.onboardkit.paywall.PaywallPlacement
import io.onboardkit.remote.OnboardingSettings
import io.onboardkit.ui.base.BaseOnboardActivity
import io.trackkit.Tracker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.util.Locale

/** What the host needs from the fragment running the current stage. */
internal interface PoolStage {
    /** True while an app-resume ad must not open over this stage. Read live at return time. */
    val blocksAppResume: Boolean
}

/** A screen's last exit, waiting on its interstitial. */
internal sealed interface PoolExit {
    /** The LFO's Done with [code] picked. */
    data class Language(val code: String) : PoolExit

    /** Goal's Next: the run completes without a paywall. */
    data object Goal : PoolExit
}

/** The pager stage; the host's [StepHost] delegates to it so the classic step fragments work unchanged. */
internal interface PoolPagerStage : PoolStage {
    val currentIndex: StateFlow<Int>
    val totalSteps: StateFlow<Int>
    fun next(exitReason: String?)
    fun completeAdStep(stepId: StepId, exitReason: String)
    fun onWindowTouched()

    /** The placement [stepId]'s page was planned with; null for a page without a slot. */
    fun pagePlacement(stepId: StepId): AdPlacement?
}

/**
 * SHARED_POOL host. LFO, the onboarding pager, Privacy and Goal are fragments of this one
 * activity, and every native slot in them renders the one selection of the process-wide pool
 * (DashDrama 1.0.6's single-activity first-open flow).
 *
 * Stage switches always wait for STARTED: a switch requested from an interstitial callback must
 * not commit after onSaveInstanceState. Back is a no-op on every stage, as in DashDrama.
 */
internal class ObPoolFlowActivity : BaseOnboardActivity(), StepHost {

    internal enum class Stage(val screenName: String) {
        LANGUAGE("ob_language"),
        PAGER("ob_onboarding"),
        PRIVACY("ob_privacy_goals_screen"),
        GOAL("ob_privacy_goals_screen"),
    }

    override val excludeFromAppResume: Boolean = false

    internal var stage: Stage = Stage.LANGUAGE
        private set

    /** Latched at the flow's final exit; app-resume stays off from then on. */
    internal var exitInProgress = false
        private set

    /** The exit whose interstitial is up; a recreated host and its restored stage read it to stay latched. */
    internal val pendingExit: PoolExit? get() = pending?.exit

    private var finishing = false

    /** The knobs latched for this run; a remote refresh mid-run cannot change them. */
    internal val snapshot: NativeFlowSnapshot
        get() = OnboardingSdk.session.nativeFlow ?: OnboardingSettings.nativeFlowSnapshot()

    internal override val resumeBlockedByScreen: Boolean
        get() {
            if (super.resumeBlockedByScreen || exitInProgress || pending != null) return true
            return when (stage) {
                Stage.PRIVACY, Stage.GOAL -> stageFragment()?.blocksAppResume == true
                Stage.PAGER -> stageFragment()?.blocksAppResume ?: true
                Stage.LANGUAGE -> stageFragment()?.blocksAppResume == true
            }
        }

    private val noIndex = MutableStateFlow(0)

    override val currentIndex: StateFlow<Int> get() = pagerStage()?.currentIndex ?: noIndex
    override val totalSteps: StateFlow<Int> get() = pagerStage()?.totalSteps ?: noIndex

    override fun onCreateSafe(savedInstanceState: Bundle?) {
        setContentView(R.layout.ob_activity_pool_flow)
        if (savedInstanceState == null) {
            // Started only from startResolved, which activates the pool. A recreation never
            // activates: reset() or a classic run since then turned the pool off for good, and a
            // restart after process death never gets here (BaseOnboardActivity).
            if (!OnboardingPool.isActive) OnboardingPool.activate(applicationContext, snapshot)
            // A new run: an exit an abandoned host left behind is not this run's.
            pending = null
        }
        live = WeakReference(this)
        if (pending?.exit == PoolExit.Goal) exitInProgress = true
        stage = savedInstanceState?.getString(KEY_STAGE)
            ?.let { runCatching { Stage.valueOf(it) }.getOrNull() } ?: Stage.LANGUAGE
        if (supportFragmentManager.findFragmentByTag(STAGE_TAG) == null) {
            commitStage(stage, newStageFragment(stage, restoredLanguage = null))
        }
        ObLog.d(ObLog.Section.SCREEN, "pool | flow stage=${stage.name.lowercase()}")
        // The previous instance may have died under the exit's ad: this one carries the exit on.
        runEndedExit()
    }

    override fun onDestroy() {
        if (live?.get() === this) {
            live = null
            // Gone for good, not recreated: its exit never runs.
            if (isFinishing && !isChangingConfigurations) pending = null
        }
        super.onDestroy()
    }

    // ── Stages ──

    private fun stageFragment(): PoolStage? =
        supportFragmentManager.findFragmentByTag(STAGE_TAG) as? PoolStage

    private fun pagerStage(): PoolPagerStage? = stageFragment() as? PoolPagerStage

    private fun newStageFragment(target: Stage, restoredLanguage: String?): Fragment = when (target) {
        Stage.LANGUAGE -> PoolLanguageFragment.newInstance(restoredLanguage)
        Stage.PAGER -> PoolPagerFragment()
        Stage.PRIVACY -> PoolPrivacyFragment()
        Stage.GOAL -> PoolGoalFragment()
    }

    private fun commitStage(target: Stage, fragment: Fragment) {
        stage = target
        supportFragmentManager.commit {
            setReorderingAllowed(true)
            replace(R.id.ob_pool_stage, fragment, STAGE_TAG)
        }
        Tracker.screen(target.screenName, fragment.javaClass.simpleName)
        ObLog.d(ObLog.Section.NAV, "pool | stage -> ${target.name.lowercase()}")
    }

    /**
     * Moves to [target] once this activity is at least STARTED. The picked language is applied
     * first: in place by default (DashDrama updates the activity resources and keeps going), or
     * by recreating the activity when `native_flow.pool.locale_apply` is RECREATE.
     *
     * @param restoredLanguage for the OB1 back return: the LFO comes back with this pick.
     */
    internal fun goTo(target: Stage, restoredLanguage: String? = null) {
        lifecycleScope.launch { withStarted { switchStage(target, restoredLanguage) } }
    }

    private fun switchStage(target: Stage, restoredLanguage: String? = null) {
        if (isFinishing || isDestroyed) return
        val recreate = snapshot.localeApply == PoolLocaleApply.RECREATE && localeChanged()
        if (!recreate) applyLocaleInPlace()
        commitStage(target, newStageFragment(target, restoredLanguage))
        // The committed stage is what the recreated activity restores; attachBaseContext
        // wraps the new locale.
        if (recreate) recreate()
    }

    private fun pickedLocale(): Locale? {
        val code = OnboardingSdk.selectedLanguageOrNull() ?: return null
        val parts = code.split('-')
        return if (parts.size >= 2) Locale(parts[0], parts[1]) else Locale(parts[0])
    }

    private fun localeChanged(): Boolean {
        val picked = pickedLocale() ?: return false
        return resources.configuration.locales[0] != picked
    }

    /** DD W5/h.java:23-45: Locale.setDefault plus updateConfiguration on this activity's resources. */
    private fun applyLocaleInPlace() {
        val locale = pickedLocale() ?: return
        if (resources.configuration.locales[0] == locale) return
        Locale.setDefault(locale)
        val configuration = Configuration(resources.configuration).apply { setLocale(locale) }
        @Suppress("DEPRECATION")
        resources.updateConfiguration(configuration, resources.displayMetrics)
    }

    /** The OB1 back button: the LFO comes back with [code] picked and its confirm shown. */
    internal fun returnToLanguage(code: String?) {
        if (exitInProgress || isFinishing) return
        ObLog.d(ObLog.Section.NAV, "pool | ob1 back -> language")
        goTo(Stage.LANGUAGE, restoredLanguage = code)
    }

    // ── Exits ──

    /**
     * Offers the splash interstitial that was loaded but never shown, then carries out [exit]:
     * after the ad closes (AFTER_AD, DashDrama's Done) or once it is on screen (UNDER_AD).
     *
     * The ad can outlive this instance (a configuration change, "Don't keep activities"), so its
     * callbacks hold neither this host nor the screen: the exit runs once, on whichever host is
     * alive and STARTED when the ad ends.
     */
    internal fun exitWithInterstitial(reuseSplashInter: Boolean, exit: PoolExit) {
        val ticket = PendingExit(exit).also { pending = it }
        if (!reuseSplashInter) {
            ObLog.d(ObLog.Section.SHOW, "pool | splash_inter reuse off")
            AdPlacement.SplashInterstitial.trackSkipped(AdSkipReason.PLACEMENT_OFF_BY_REMOTE)
            adEnded(ticket)
            return
        }
        val underAd = snapshot.exitNextScreenTiming == NextScreenTiming.UNDER_AD
        showInterstitial(
            AdPlacement.SplashInterstitial,
            onNext = { if (underAd) adEnded(ticket) },
            onFinished = { if (!underAd) adEnded(ticket) },
        )
    }

    /** Runs the exit whose ad has ended, once, as soon as this host is STARTED. */
    private fun runEndedExit() {
        lifecycleScope.launch {
            withStarted {
                val ticket = pending?.takeIf { it.adEnded } ?: return@withStarted
                if (isFinishing) return@withStarted
                pending = null
                when (val exit = ticket.exit) {
                    is PoolExit.Language -> completeLanguage(exit.code)
                    PoolExit.Goal -> finishFlow(withPaywall = false)
                }
            }
        }
    }

    /**
     * DashDrama's Done: save, then move on. Only a full first page is warmed here, without owning
     * it; any other first screen claims its own ad when it opens.
     */
    private fun completeLanguage(code: String) {
        OnboardingSdk.persistLanguage(code)
        // SDK scope: the stage switch destroys the LFO's view right away.
        OnboardingSdk.stateStoreOrNull()?.let { store -> OnboardingSdk.scope().launch { store.markLfoCompleted() } }
        val steps = OnboardingSdk.preload().stepDefinitions(sdk.guard().isPremium(this))
        ObLog.d(ObLog.Section.NAV, "pool | language done enabledSteps=${steps.map { it.id.value }}")
        when {
            steps.isNotEmpty() -> {
                steps.first().takeIf { it.type == StepType.AD_FULL_SCREEN }
                    ?.let(PoolPlacementMapper::pagePlacement)
                    ?.let { OnboardingPool.preload(it, LoadMode.FORCED) }
                switchStage(Stage.PAGER)
            }
            // Privacy/Goal is the optional tail of this flow even when remote disables every page.
            OnboardingSdk.privacyGoalsScreenEnabled() -> switchStage(Stage.PRIVACY)
            else -> finishFlow(withPaywall = true)
        }
    }

    /**
     * Ends the run: the paywall on the paths that classic shows it on, then completion exactly once.
     * Pool ads outlive the flow, for the pooled resume screen, unless the user just bought.
     */
    internal fun finishFlow(withPaywall: Boolean) {
        if (finishing) return
        finishing = true
        exitInProgress = true
        lifecycleScope.launch {
            if (withPaywall) sdk.presentPaywall(this@ObPoolFlowActivity, PaywallPlacement.AFTER_ONBOARDING)
            if (sdk.guard().isPremium(this@ObPoolFlowActivity)) {
                OnboardingPool.releaseAll()
            }
            OnboardingSdk.completeFlow(this@ObPoolFlowActivity)
            finish()
        }
    }

    /** Latches the last exit before its interstitial, so app-resume cannot open under it. */
    internal fun markExitStarted() {
        exitInProgress = true
    }

    // ── StepHost: the pager's classic step fragments call these through `activity as StepHost` ──

    override fun next(exitReason: String?) {
        pagerStage()?.next(exitReason)
    }

    override fun completeAdStep(stepId: StepId, exitReason: String) {
        pagerStage()?.completeAdStep(stepId, exitReason)
    }

    /** Swipe never unlocks in this flow (DashDrama Style5 disables user input). */
    override fun setAdStepSwipeEnabled(stepId: StepId, enabled: Boolean) = Unit

    override fun back(): Boolean = false

    override fun finishFlow(reason: FinishReason) {
        finishFlow(withPaywall = true)
    }

    /** ContentStepFragment's indicator count and its LAST CTA read this through its resolver. */
    internal fun poolTotalSteps(): Int = pagerStage()?.totalSteps?.value ?: 0

    /**
     * ContentStepFragment's resolver: a content page's slot renders the pool's selection instead
     * of requesting its own native. The pager claims the page's placement on selection.
     */
    internal fun bindStepSlot(
        fragment: Fragment,
        placement: AdPlacement,
        container: FrameLayout,
        callbacks: StepSlotCallbacks,
    ): Boolean {
        // The engine, not the routing flag: after reset() mid-run the pages keep the pool they started on.
        if (OnboardingPool.engineOrNull() == null) return false
        // A page planned without a placement draws nothing in DashDrama; a slot would borrow other ads.
        if (placement is AdPlacement.StepNative && pagerStage()?.pagePlacement(placement.stepId) == null) {
            callbacks.onUnavailable(AdSkipReason.NO_AD_UNIT)
            return true
        }
        val minState = if (snapshot.parity.pagerSlotsBindResumed) Lifecycle.State.RESUMED else Lifecycle.State.STARTED
        PoolNativeSlot(
            owner = fragment.viewLifecycleOwner,
            placement = placement,
            kind = PoolNativeSlot.Kind.OWNER,
            minState = minState,
            container = container,
            callbacks = callbacks,
            destroyOnTeardown = snapshot.viewDestroyOnTeardown,
            // Pages are laid out offscreen and bind after their slide: hold the ad's height meanwhile.
            placeholderUntilLive = true,
        ).start()
        return true
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) pagerStage()?.onWindowTouched()
        return super.dispatchTouchEvent(ev)
    }

    /** DD A0/c.java:71-78, 109-112: back does nothing on LFO, the pager and the select screens. */
    override fun handleBack() = Unit

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_STAGE, stage.name)
        super.onSaveInstanceState(outState)
    }

    /** An exit and whether its ad has ended. */
    private class PendingExit(val exit: PoolExit) {
        var adEnded = false
    }

    internal companion object {
        private const val KEY_STAGE = "ob_pool_stage"
        private const val STAGE_TAG = "ob_pool_stage"

        /**
         * Process-wide, main thread: the exit outlives the host instance that offered its ad. A
         * restart after process death never restores this host, so the bundle need not carry it.
         */
        private var pending: PendingExit? = null

        /** The host instance a pending exit runs on. */
        private var live: WeakReference<ObPoolFlowActivity>? = null

        fun start(activity: Activity) {
            activity.startActivity(Intent(activity, ObPoolFlowActivity::class.java))
        }

        /** An ad from an abandoned run ends nothing. */
        @MainThread
        private fun adEnded(ticket: PendingExit) {
            if (pending !== ticket) return
            ticket.adEnded = true
            live?.get()?.runEndedExit()
        }
    }
}
