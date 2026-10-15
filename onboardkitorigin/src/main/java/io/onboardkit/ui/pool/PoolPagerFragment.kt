package io.onboardkit.ui.pool

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import androidx.lifecycle.withStarted
import androidx.viewpager2.widget.ViewPager2
import com.ads.module.helper.adnative.pool.LoadMode
import com.ads.module.helper.adnative.pool.reportsSuccess
import io.onboardkit.OnboardingSdk
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.NativeTemplates
import io.onboardkit.ads.pool.OnboardingPool
import io.onboardkit.ads.pool.PoolPlacementMapper
import io.onboardkit.config.StepDefinition
import io.onboardkit.core.ObLog
import io.onboardkit.core.StepId
import io.onboardkit.core.StepType
import io.onboardkit.core.analytics.AnalyticsEvent
import io.onboardkit.core.analytics.StepExit
import io.onboardkit.core.events.OnboardingEvent
import io.onboardkit.databinding.ObFragmentPoolPagerBinding
import io.onboardkit.flow.NativeFlowSnapshot
import io.onboardkit.remote.OnboardingSettings
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The SHARED_POOL onboarding pager (DashDrama Style5 over its E6/a page base). Each selection
 * claims the page's placement politely and preloads the next page's; the pool decides what every
 * slot shows. Swipe stays locked, a full page moves on by itself a fixed time after it was
 * selected, and the last page hands over to Privacy or ends the flow, with no interstitial.
 */
internal class PoolPagerFragment : Fragment(), PoolPagerStage {

    private var binding: ObFragmentPoolPagerBinding? = null
    private var adapter: PoolPagerAdapter? = null
    private var pages: List<StepDefinition> = emptyList()

    /** Decided with the plan: the slot a page binds and the claim its selection makes must agree. */
    private var placements: List<AdPlacement?> = emptyList()
    private var selectedAtMs = LongArray(0)
    private val _currentIndex = MutableStateFlow(0)
    private val _totalSteps = MutableStateFlow(0)
    private val handler = Handler(Looper.getMainLooper())
    private var autoNext: Runnable? = null
    private var autoNextJob: Job? = null
    private var lastSelected = -1
    private var visit = 0L
    private var exitLatched = false
    private var ob1BackEnabled = false
    private var returningToLanguage = false

    /** The placement whose load last reported success, given the slot back on every resume (DD f1379c). */
    private var ownerKey: AdPlacement? = null

    /** The last page placement tried; a page without a slot leaves it, and the owner, alone (DD f1380d). */
    private var currentAttempt: AdPlacement? = null

    override val currentIndex: StateFlow<Int> get() = _currentIndex
    override val totalSteps: StateFlow<Int> get() = _totalSteps

    override val blocksAppResume: Boolean
        get() {
            val pager = binding?.obPoolPager ?: return true
            if (exitLatched || returningToLanguage || pager.scrollState != ViewPager2.SCROLL_STATE_IDLE) return true
            return pages.getOrNull(pager.currentItem)?.type != StepType.CONTENT
        }

    private val host: ObPoolFlowActivity? get() = activity as? ObPoolFlowActivity

    private val snapshot: NativeFlowSnapshot
        get() = host?.snapshot ?: OnboardingSettings.nativeFlowSnapshot()

    private val pageCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) = select(position)

        override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) =
            showOb1Back(position, positionOffset)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pages = restoredPages(savedInstanceState)
            ?: OnboardingSdk.preload().stepDefinitions(OnboardingSdk.guard().isPremium(requireContext()))
        placements = pages.map(PoolPlacementMapper::pagePlacement)
        selectedAtMs = LongArray(pages.size)
        // Before the adapter exists: content pages read it for their indicator and their LAST CTA.
        _totalSteps.value = pages.size
        ObLog.d(ObLog.Section.SCREEN, "pool | pager steps=${pages.map { it.id.value }}")
    }

    /** A recreated stage keeps the plan its restored pages were built from. */
    private fun restoredPages(state: Bundle?): List<StepDefinition>? {
        val ids = state?.getStringArrayList(KEY_PAGES) ?: return null
        val known = OnboardingSdk.preload().stepDefinitions() + OnboardingSdk.configOrNull()?.steps.orEmpty()
        return ids.mapNotNull { id -> known.firstOrNull { it.id.value == id } }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ObFragmentPoolPagerBinding.inflate(inflater, container, false).also { binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val b = binding ?: return
        if (pages.isEmpty()) {
            // The LFO never opens an empty pager; a plan emptied since (a purchase) still has to move on.
            exitLatched = true
            view.post { if (binding != null) leave() }
            return
        }
        val pager = b.obPoolPager
        // DD Style5.java:158-163: adapter, offscreen limit, input lock, callback, initial dispatch.
        pager.adapter = PoolPagerAdapter(this, pages).also { adapter = it }
        pager.offscreenPageLimit = snapshot.pagerOffscreenPages.takeIf { it > 0 } ?: pages.size
        pager.isUserInputEnabled = false
        pager.registerOnPageChangeCallback(pageCallback)
        pager.post { if (binding?.obPoolPager === pager) select(pager.currentItem) }

        ob1BackEnabled = pages.first().id == StepId.OB1 &&
            OnboardingSettings.bool("onboarding.steps.ob1.back.enabled")
        b.obPoolStepBack.setOnClickListener { returnToLanguage() }
        showOb1Back(pager.currentItem, 0f)
    }

    /** DD Style5.k with E6/a.i: a repeat of the current page is ignored, whichever path reported it. */
    private fun select(position: Int) {
        val pager = binding?.obPoolPager ?: return
        val pagerAdapter = adapter ?: return
        if (position == lastSelected) return
        val step = pages.getOrNull(position) ?: return
        val thisVisit = ++visit
        if (lastSelected >= 0) pagerAdapter.fragmentAt(lastSelected)?.dispatchUnselected()
        lastSelected = position
        _currentIndex.value = position

        OnboardingSdk.session.recordStepShown(step.id)
        OnboardingSdk.emitEvent(OnboardingEvent.StepViewed(step.id, position))
        OnboardingSdk.track(AnalyticsEvent.StepViewed(step.id, position, variantOf(step)))
        selectedAtMs[position] = System.currentTimeMillis()

        placements.getOrNull(position)?.let(::attempt)
        preloadIfFullPage(position + 1)
        ObLog.d(ObLog.Section.NAV, "pool | page=$position step=${step.id.value} owner=${ownerKey?.key}")

        cancelAutoNext()
        if (step.type == StepType.AD_FULL_SCREEN) scheduleAutoNext(step.id)

        // The page may not be attached yet on the first layout.
        pager.post {
            if (binding?.obPoolPager === pager && visit == thisVisit && pager.currentItem == position) {
                pagerAdapter.fragmentAt(position)?.dispatchSelected()
            }
        }
    }

    /**
     * Only a full page is warmed one screen ahead: it has nothing to show but the ad. FORCED,
     * because it is asked in the same moment as the current page's own request, which would close
     * a POLITE gate on it every time; a full page already holding or loading an ad asks for nothing.
     * A content page claims its own placement when it is selected and borrows the newest ad meanwhile.
     */
    private fun preloadIfFullPage(position: Int) {
        if (pages.getOrNull(position)?.type != StepType.AD_FULL_SCREEN) return
        placements.getOrNull(position)?.let { OnboardingPool.preload(it, LoadMode.FORCED) }
    }

    private fun attempt(placement: AdPlacement) {
        currentAttempt = placement
        if (OnboardingPool.claim(placement, LoadMode.POLITE).reportsSuccess) ownerKey = placement
    }

    /**
     * DD Style5.java:105-109: counted from selection and kept running in the background. Like DD's
     * STARTED collector (J6/m), the move itself waits until the user is back.
     */
    private fun scheduleAutoNext(stepId: StepId) {
        val task = Runnable {
            autoNext = null
            val owner = viewLifecycleOwnerLiveData.value ?: return@Runnable
            autoNextJob = owner.lifecycleScope.launch {
                owner.withStarted { completeAdStep(stepId, StepExit.AUTO_NEXT) }
            }
        }
        autoNext = task
        handler.postDelayed(task, snapshot.fullscreenAutoNextDelayMs)
    }

    private fun cancelAutoNext() {
        autoNext?.let(handler::removeCallbacks)
        autoNext = null
        autoNextJob?.cancel()
        autoNextJob = null
    }

    override fun next(exitReason: String?) {
        val b = binding ?: return
        val pager = b.obPoolPager
        // Mid-transition next() is always a duplicate: a CTA double-tap or a late page callback.
        if (returningToLanguage || pager.scrollState != ViewPager2.SCROLL_STATE_IDLE) return
        val position = pager.currentItem
        val last = position >= pages.size - 1
        if (last) {
            // Leaving never scrolls the pager, so the scroll guard cannot catch a duplicate here.
            if (exitLatched) return
            exitLatched = true
            b.obPoolStepBack.visibility = View.GONE
        }
        pages.getOrNull(position)?.let { step ->
            val dwellMs = dwellAt(position)
            // SDK scope, not lifecycleScope: the last step may end this activity right away.
            val store = OnboardingSdk.stateStoreOrNull()
            if (store != null) OnboardingSdk.scope().launch { store.markStepCompleted(step.id) }
            OnboardingSdk.emitEvent(OnboardingEvent.StepCompleted(step.id, dwellMs))
            OnboardingSdk.track(AnalyticsEvent.StepCompleted(step.id, position, dwellMs, exitReason ?: StepExit.CTA))
        }
        if (last) {
            leave()
            return
        }
        val smooth = snapshot.pagerSmoothScroll
        pager.setCurrentItem(position + 1, smooth)
        // A jump onto a page that is already laid out may report no scroll at all.
        if (!smooth) showOb1Back(position + 1, 0f)
    }

    /** The page's own clock once it was told it is selected, else the stage's from the selection. */
    private fun dwellAt(position: Int): Long {
        adapter?.fragmentAt(position)?.dwellMs()?.takeIf { it > 0L }?.let { return it }
        val selectedAt = selectedAtMs.getOrElse(position) { 0L }
        return if (selectedAt == 0L) 0L else System.currentTimeMillis() - selectedAt
    }

    /** DD Style5 -> Favorite: no interstitial and no preload; the next screen claims its own ad on open. */
    private fun leave() {
        cancelAutoNext()
        val host = host ?: return
        if (OnboardingSdk.privacyGoalsScreenEnabled()) {
            host.goTo(ObPoolFlowActivity.Stage.PRIVACY)
            return
        }
        // As classic: the paywall and the app's next screen open only once this task is in front.
        host.lifecycleScope.launch { host.withResumed { host.finishFlow(withPaywall = true) } }
    }

    override fun pagePlacement(stepId: StepId): AdPlacement? =
        placements.getOrNull(pages.indexOfFirst { it.id == stepId })

    /**
     * An ad page has nothing to show, was skipped, or timed out. The answer can arrive while the
     * pager is settling or resuming, so it leaves the callback stack, waits for idle, and is bound
     * to this visit so it cannot end a later page.
     */
    override fun completeAdStep(stepId: StepId, exitReason: String) {
        val pager = binding?.obPoolPager ?: return
        val position = pages.indexOfFirst { it.id == stepId }
        if (position < 0 || pager.currentItem != position) return
        val thisVisit = visit
        pager.post { completeStepWhenIdle(position, thisVisit, exitReason) }
    }

    private fun completeStepWhenIdle(position: Int, thisVisit: Long, exitReason: String) {
        val pager = binding?.obPoolPager ?: return
        if (visit != thisVisit || pager.currentItem != position || activity?.isFinishing != false) return
        if (pager.scrollState == ViewPager2.SCROLL_STATE_IDLE) {
            next(exitReason)
            return
        }
        pager.registerOnPageChangeCallback(
            object : ViewPager2.OnPageChangeCallback() {
                private var posted = false

                override fun onPageScrollStateChanged(state: Int) {
                    if (state != ViewPager2.SCROLL_STATE_IDLE || posted) return
                    posted = true
                    pager.post {
                        // ViewPager2 forbids changing its callback list during dispatch.
                        pager.unregisterOnPageChangeCallback(this)
                        completeStepWhenIdle(position, thisVisit, exitReason)
                    }
                }
            },
        )
    }

    override fun onWindowTouched() {
        val pager = binding?.obPoolPager ?: return
        adapter?.fragmentAt(pager.currentItem)?.onWindowTouched()
    }

    /** DD E6/a.java:248-264: back in front, the stage takes its owner back, refreshes it and retries its page. */
    override fun onResume() {
        super.onResume()
        ownerKey?.let { owner ->
            OnboardingPool.setOwner(owner)
            OnboardingPool.refresh(owner)
        }
        currentAttempt?.let(::attempt)
    }

    // Follows the scroll, not currentItem: a programmatic Next moves currentItem before the slide.
    private fun showOb1Back(position: Int, offset: Float) {
        val back = binding?.obPoolStepBack ?: return
        val shown = ob1BackEnabled && !exitLatched && !returningToLanguage && position == 0
        val alpha = if (shown) 1f - offset else 0f
        back.alpha = alpha
        back.visibility = if (alpha > 0f) View.VISIBLE else View.GONE
    }

    /** Page 0's back reopens the LFO stage with the pick restored; system back stays a no-op. */
    private fun returnToLanguage() {
        val b = binding ?: return
        val host = host ?: return
        if (returningToLanguage || !ob1BackEnabled || exitLatched || b.obPoolPager.currentItem != 0) return
        returningToLanguage = true
        b.obPoolStepBack.isEnabled = false
        cancelAutoNext()
        host.returnToLanguage(OnboardingSdk.selectedLanguageOrNull())
    }

    private fun variantOf(step: StepDefinition): String = when (step.type) {
        StepType.CONTENT -> NativeTemplates.variantFor(AdPlacement.StepNative(step.id))
        StepType.AD_FULL_SCREEN -> "fullscreen"
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(KEY_PAGES, ArrayList(pages.map { it.id.value }))
    }

    override fun onDestroyView() {
        ownerKey?.let(OnboardingPool::clearOwnerIf)
        cancelAutoNext()
        visit++
        lastSelected = -1
        adapter = null
        binding = null
        super.onDestroyView()
    }

    private companion object {
        const val KEY_PAGES = "ob_pool_pager_pages"
    }
}
