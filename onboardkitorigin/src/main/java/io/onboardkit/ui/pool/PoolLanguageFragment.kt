package io.onboardkit.ui.pool

import android.content.res.ColorStateList
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import android.view.ViewTreeObserver
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.ads.module.config.AdRemoteConfig
import com.ads.module.config.toNativeStyle
import com.ads.module.helper.adnative.NativeAdShimmer
import com.ads.module.helper.adnative.pool.LoadMode
import com.ads.module.helper.adnative.pool.LoadResult
import com.ads.module.helper.adnative.pool.PoolCancellable
import com.ads.module.helper.adnative.pool.PoolEvent
import com.ads.module.helper.adnative.pool.PoolSelection
import com.ads.module.helper.adnative.pool.PoolState
import com.ads.module.helper.adnative.pool.reportsSuccess
import com.bumptech.glide.Glide
import io.onboardkit.OnboardingSdk
import io.onboardkit.R
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.NativeTemplates
import io.onboardkit.ads.pool.OnboardingPool
import io.onboardkit.ads.pool.PoolNativeSlot
import io.onboardkit.ads.pool.PoolSplashAdoption
import io.onboardkit.ads.pool.SCREEN_SLOT_MIN_STATE
import io.onboardkit.config.LanguageConfirmButtonStyle
import io.onboardkit.config.ObLanguage
import io.onboardkit.core.analytics.AnalyticsEvent
import io.onboardkit.core.events.OnboardingEvent
import io.onboardkit.databinding.ObActivityLanguageBinding
import io.onboardkit.flow.NativeFlowSnapshot
import io.onboardkit.remote.OnboardingSettings
import io.onboardkit.ui.language.LanguageAdapter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The pooled LFO (DashDrama LanguageFirstFragment). One owner slot renders the pool's selection
 * under the Language1 template until a tap's Language2 claim succeeds, then under Language2; the
 * confirm control appears a fixed delay after the first tap, never before.
 *
 * Analytics timing (deviation D-ANALYTICS-ORDER): there is no second slot to swap in, so
 * `LanguageCompleted(1)` and `LanguageViewed(2)` fire when the first tap makes Language2 the owner,
 * whether or not its ad has filled yet. The pick is persisted only after the exit interstitial,
 * right before the next stage applies its locale.
 *
 * With `ad_config.<LFO key>.carousel` on, the ad block is a two-page [PoolLanguageCarousel]: page 0
 * is the owner slot above, page 1 a second slot under the LFO's carousel key (`language1_carousel`
 * / `language2_carousel`, the LFO's own ad units). Page 1 shows its own ad, else keeps the ad it
 * shows, else borrows the pool's newest unshown ad that page 0 does not show; with nothing to
 * borrow it loads POLITE. Every load the carousel starts waits for an idle pool, so two never run.
 */
internal class PoolLanguageFragment : Fragment(), PoolStage {

    private var binding: ObActivityLanguageBinding? = null
    private var adapter: LanguageAdapter? = null
    private var slot: PoolNativeSlot? = null
    private var carousel: PoolLanguageCarousel? = null
    private var secondSlot: PoolNativeSlot? = null

    /** Page 1's placement while the carousel is up. */
    private var carouselKey: AdPlacement? = null
    private var carouselEvents: PoolCancellable? = null

    /** The rows run under the carousel, VideoDownloader's layout; null while they end above it. */
    private var listUnderAds: ViewTreeObserver.OnGlobalLayoutListener? = null

    /** The page under the loading skeleton while its own load runs. */
    private var coveredPage: Int? = null
    private val uncoverAfterFill = Runnable { uncover() }
    private var dialog: PoolConfirmLanguageDialog? = null
    private var revealJob: Job? = null
    private var loadingJob: Job? = null

    /** What is left of LFO1's loading card; it counts down only while the screen is resumed. */
    private var loadingLeftMs = 0L

    private var selectedCode: String? = null
    private var tapCount = 0

    /** DashDrama's screen index: Language1 until a tap's Language2 claim reports success. */
    private var currentKey: AdPlacement = AdPlacement.Language1

    /** What onDestroyView clears: the LFO key this screen last made the owner. */
    private var ownedKey: AdPlacement? = null

    /** The splash's LFO1 handoff, spent by the first adoption so a restore cannot adopt twice. */
    private var handoff = false
    private var confirmRevealed = false
    private var resumedOnce = false
    private var exitLatched = false
    private var tearingDown = false

    override val blocksAppResume: Boolean get() = exitLatched || dialog?.isShowing == true

    private val host: ObPoolFlowActivity? get() = activity as? ObPoolFlowActivity

    private val snapshot: NativeFlowSnapshot
        get() = host?.snapshot ?: OnboardingSdk.session.nativeFlow ?: OnboardingSettings.nativeFlowSnapshot()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ObActivityLanguageBinding.inflate(inflater, container, false).also { binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val b = requireNotNull(binding)
        tearingDown = false
        val languages = OnboardingSdk.requireConfig().language.languages
        fun offered(code: String?): String? = code?.takeIf { c -> languages.any { it.code == c } }
        val restored = arguments?.getString(ARG_RESTORED_LANGUAGE)
        when {
            // A recreation keeps what the user did after any OB1-back restore.
            savedInstanceState != null -> {
                selectedCode = offered(savedInstanceState.getString(KEY_SELECTED))
                tapCount = savedInstanceState.getInt(KEY_TAPS)
                if (savedInstanceState.getBoolean(KEY_ON_LANGUAGE2)) currentKey = AdPlacement.Language2
                confirmRevealed = savedInstanceState.getBoolean(KEY_CONFIRM_REVEALED)
                handoff = savedInstanceState.getBoolean(KEY_HANDOFF)
                loadingLeftMs = savedInstanceState.getLong(KEY_LOADING_LEFT)
            }
            // OB1 back (D-OB1BACK): the pick and LFO2 come back, the splash handoff stays spent.
            restored != null -> {
                selectedCode = offered(restored)
                tapCount = 1
                currentKey = AdPlacement.Language2
                confirmRevealed = selectedCode != null
            }
            else -> {
                // A fresh pooled LFO starts with no selected row. A language saved by a previous
                // run must not become an implicit pick; OB1 back and recreation restore their
                // explicit in-progress pick through the branches above.
                selectedCode = null
                handoff = OnboardingSdk.preload().takeLanguage1Preload()
                loadingLeftMs = snapshot.lfoLoadingDialogMs
            }
        }

        // Recreated under its own exit's ad: Done was tapped and the exit runs on this host.
        exitLatched = exitLatched || host?.pendingExit is PoolExit.Language

        adapter = LanguageAdapter(::onLanguageTapped, OnboardingSettings.onboardingPrimaryColor()).also {
            it.selectedCode = selectedCode
            b.obLanguageList.layoutManager = LinearLayoutManager(requireContext())
            b.obLanguageList.adapter = it
            it.submitList(languages)
        }
        bindConfirmChrome(b)
        bindConfirm()
        b.obLanguageConfirm.setOnClickListener { onDone() }
        b.obAdBlock2.visibility = View.GONE

        val flow = snapshot
        syncCarousel()
        if (slot == null) slot = newOwnerSlot()
        if (exitLatched) {
            // Requests nothing (see onResume) and is no new view of the screen.
            OnboardingPool.setOwner(currentKey)
            ownedKey = currentKey
            return
        }
        // Before the claim, so it finds LFO1 held or in flight instead of starting a second chain.
        if (restored == null || savedInstanceState != null) {
            PoolSplashAdoption.run(handoff, flow)
            handoff = false
        }
        claimCurrent()
        if (loadingLeftMs > 0L) runLoading(b)
        if (tapCount > 0 && selectedCode != null && !confirmRevealed) scheduleReveal()
        OnboardingSdk.track(AnalyticsEvent.LanguageViewed(screenIndex(currentKey), variant = variant(currentKey)))
    }

    /** The confirm control's look, as on the classic first-open LFO; its tap hint is not ported. */
    private fun bindConfirmChrome(b: ObActivityLanguageBinding) {
        val confirmStyle = OnboardingSdk.requireConfig().language.confirmButtonStyle
        b.obLanguageConfirm.contentDescription = getString(
            if (confirmStyle == LanguageConfirmButtonStyle.TEXT) R.string.ob_language_done else R.string.ob_next,
        )
        b.obLanguageConfirmIcon.isVisible = confirmStyle == LanguageConfirmButtonStyle.CHECK_ICON
        b.obLanguageConfirmText.isVisible = confirmStyle == LanguageConfirmButtonStyle.TEXT
        OnboardingSettings.text("lfo.confirm_button.image_url").takeIf {
            it.isNotBlank() && confirmStyle == LanguageConfirmButtonStyle.CHECK_ICON
        }?.let {
            Glide.with(this).load(it).error(R.drawable.ob_ic_check).into(b.obLanguageConfirmIcon)
        }
        OnboardingSettings.onboardingPrimaryColor()?.let { tint ->
            b.obLanguageConfirmIcon.imageTintList = ColorStateList.valueOf(tint)
            b.obLanguageConfirmText.setTextColor(tint)
        }
    }

    /** GONE until revealed. */
    private fun bindConfirm() {
        val b = binding ?: return
        b.obLanguageConfirm.alpha = 1f
        b.obLanguageConfirm.visibility = if (confirmRevealed) View.VISIBLE else View.GONE
    }

    private fun scheduleReveal() {
        if (revealJob != null) return
        val delayMs = snapshot.lfoConfirmRevealDelayMs
        revealJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(delayMs)
            confirmRevealed = true
            bindConfirm()
        }
    }

    /**
     * LFO1's "loading languages" card: it blocks the header and rows, never the ad below, while
     * its progress runs to 100%. Paused with the screen, so time away never shortens the wait.
     */
    private fun runLoading(b: ObActivityLanguageBinding) {
        val card = b.obLanguageLoading
        val totalMs = maxOf(snapshot.lfoLoadingDialogMs, loadingLeftMs)
        val languages = OnboardingSdk.requireConfig().language.languages
        OnboardingSettings.onboardingPrimaryColor()?.let { tint ->
            card.obLanguageLoadingRing.indeterminateTintList = ColorStateList.valueOf(tint)
            card.obLanguageLoadingProgress.progressTintList = ColorStateList.valueOf(tint)
        }
        card.root.isVisible = true
        var shownIndex = -1
        fun render() {
            val doneMs = totalMs - loadingLeftMs
            val percent = (doneMs * 100 / totalMs).toInt()
            card.obLanguageLoadingProgress.progress = percent
            card.obLanguageLoadingPercent.text = getString(R.string.ob_splash_percent, percent)
            if (languages.isEmpty()) return
            val index = (doneMs / LOADING_FLAG_STEP_MS % languages.size).toInt()
            if (index == shownIndex) return
            shownIndex = index
            card.obLanguageLoadingFlag.setImageResource(languages[index].flagRes)
            card.obLanguageLoadingName.text = languages[index].displayName
        }
        render()
        loadingJob = viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                var lastMs = SystemClock.uptimeMillis()
                while (loadingLeftMs > 0L) {
                    delay(LOADING_TICK_MS)
                    val nowMs = SystemClock.uptimeMillis()
                    loadingLeftMs = (loadingLeftMs - (nowMs - lastMs)).coerceAtLeast(0L)
                    lastMs = nowMs
                    render()
                }
                card.root.animate().alpha(0f).setDuration(LOADING_FADE_MS).withEndAction {
                    card.root.isVisible = false
                    card.root.alpha = 1f
                }
                loadingJob?.cancel()
            }
        }
    }

    /**
     * DashDrama's LanguageFirstFragment.h: the slot of a placement refused for this run collapses.
     * A transient refusal keeps it: the pool may still lend another screen's ad.
     */
    private fun claimCurrent() {
        val key = currentKey
        val result = OnboardingPool.claim(key, LoadMode.POLITE)
        if (result.reportsSuccess) ownedKey = key
        binding?.obAdBlock?.isVisible = result !is LoadResult.SkippedByConfig
    }

    private fun onLanguageTapped(language: ObLanguage) {
        if (exitLatched || loadingLeftMs > 0L) return
        // The DashDrama preselection is not a pick: only a row the user already tapped is a re-tap.
        val reselected = tapCount > 0 && selectedCode == language.code
        if (tapCount < Int.MAX_VALUE) tapCount++
        selectedCode = language.code
        adapter?.selectedCode = language.code
        OnboardingSdk.emitEvent(OnboardingEvent.LanguageSelected(language.code))
        OnboardingSdk.track(AnalyticsEvent.LanguageSelected(screenIndex(currentKey), language.code))
        claimLanguage2(language.code)
        if (!confirmRevealed) scheduleReveal()
        if (shouldShowConfirmDialog(reselected)) showConfirmDialog(language)
    }

    /**
     * DashDrama requests LFO2 FORCED on every tap; the pool turns a repeat into a no-op. Like its
     * h(2, true), a successful claim opens the ad frame a refused Language1 collapsed.
     */
    private fun claimLanguage2(code: String) {
        val result = OnboardingPool.claim(AdPlacement.Language2, LoadMode.FORCED)
        if (!result.reportsSuccess) return
        ownedKey = AdPlacement.Language2
        binding?.obAdBlock?.isVisible = true
        if (currentKey == AdPlacement.Language2) return
        currentKey = AdPlacement.Language2
        slot?.setPlacement(AdPlacement.Language2)
        syncCarousel()
        OnboardingSdk.track(AnalyticsEvent.LanguageCompleted(1, code))
        OnboardingSdk.track(AnalyticsEvent.LanguageViewed(2, variant = variant(AdPlacement.Language2)))
    }

    /** The classic LFO's rule and keys: from the Nth tap, or a tap on the picked row. */
    private fun shouldShowConfirmDialog(reselected: Boolean): Boolean =
        (reselected || tapCount >= OnboardingSettings.number("lfo.confirm_dialog.show_from_tap")) &&
            OnboardingSdk.requireConfig().language.confirmDialogOnReselectEnabled

    /**
     * The dialog is an owner screen of its own: the LFO's slot freezes behind it so the two never
     * fight over one ad's registration, and the owner returns to the LFO key on any close.
     */
    private fun showConfirmDialog(language: ObLanguage) {
        val activity = activity ?: return
        dialog?.dismiss()
        slot?.setFrozen(true)
        secondSlot?.setFrozen(true)
        OnboardingPool.claim(AdPlacement.LanguageConfirm, LoadMode.POLITE)
        dialog = PoolConfirmLanguageDialog(
            activity = activity,
            language = language,
            destroyOnTeardown = snapshot.viewDestroyOnTeardown,
            onConfirmed = ::onDone,
            onClosed = ::onConfirmDialogClosed,
        ).also { it.show() }
    }

    private fun onConfirmDialogClosed() {
        dialog = null
        // The view is going: handing the owner back could override the next stage's claim.
        if (tearingDown) return
        OnboardingPool.setOwner(currentKey)
        ownedKey = currentKey
        slot?.setFrozen(false)
        secondSlot?.setFrozen(false)
    }

    /**
     * DashDrama LanguageFirstFragment.onResume: refresh the current key without touching the owner,
     * then claim it POLITE. A Language1 the splash already filled is not refreshed on the first
     * resume (DashDrama's LFO1 is still loading there). An open dialog keeps the owner. Once the
     * exit is latched nothing is requested: a refresh would close the gate on the next stage's preload.
     */
    override fun onResume() {
        super.onResume()
        val first = !resumedOnce
        resumedOnce = true
        if (exitLatched) return
        if (dialog?.isShowing == true) {
            OnboardingPool.refresh(AdPlacement.LanguageConfirm)
            OnboardingPool.claim(AdPlacement.LanguageConfirm, LoadMode.POLITE)
            return
        }
        val adoptedUnshown = currentKey == AdPlacement.Language1 &&
            OnboardingPool.engineOrNull()?.isAdoptedUnshown(AdPlacement.Language1.key) == true
        val secondShown = carouselKey?.takeIf { carousel?.currentIndex == 1 }
        when {
            // Page 1 is the screen the user returns to: it renews like the LFO key would.
            secondShown != null -> if (!OnboardingPool.refresh(secondShown)) {
                OnboardingPool.loadCarouselPage(secondShown, refreshHeld = false)
            }
            !(first && adoptedUnshown) -> OnboardingPool.refresh(currentKey)
        }
        claimCurrent()
    }

    private fun onDone() {
        if (exitLatched) return
        val code = selectedCode ?: return
        val host = host ?: return
        exitLatched = true
        revealJob?.cancel()
        carousel?.stop()
        OnboardingSdk.track(AnalyticsEvent.LanguageCompleted(screenIndex(currentKey), code))
        OnboardingSdk.track(AnalyticsEvent.LanguageFlowCompleted(code))
        // After the ad the host saves the pick, warms the next stage's first slot and moves on.
        host.exitWithInterstitial(OnboardingSdk.flags().reuseSplashInter, PoolExit.Language(code))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SELECTED, selectedCode)
        outState.putInt(KEY_TAPS, tapCount)
        outState.putBoolean(KEY_ON_LANGUAGE2, currentKey == AdPlacement.Language2)
        outState.putBoolean(KEY_CONFIRM_REVEALED, confirmRevealed)
        outState.putBoolean(KEY_HANDOFF, handoff)
        outState.putLong(KEY_LOADING_LEFT, loadingLeftMs)
    }

    override fun onDestroyView() {
        tearingDown = true
        revealJob?.cancel()
        revealJob = null
        loadingJob?.cancel()
        loadingJob = null
        // DashDrama clears only its own key: the next stage may already own the pool.
        dialog?.let {
            it.dismiss()
            OnboardingPool.clearOwnerIf(AdPlacement.LanguageConfirm)
        }
        dialog = null
        ownedKey?.let(OnboardingPool::clearOwnerIf)
        carouselEvents?.cancel()
        carouselEvents = null
        binding?.root?.removeCallbacks(uncoverAfterFill)
        listUnderAds?.let { binding?.root?.viewTreeObserver?.removeOnGlobalLayoutListener(it) }
        listUnderAds = null
        carousel?.destroy()
        carousel = null
        secondSlot = null
        carouselKey = null
        coveredPage = null
        slot = null
        adapter = null
        binding = null
        super.onDestroyView()
    }

    /**
     * Puts up, retargets or takes down the carousel for [currentKey]'s `carousel` switch. Page 1
     * keeps its slot across the LFO1 to LFO2 swap: what it shows stays until LFO2's key fills.
     */
    private fun syncCarousel() {
        val b = binding ?: return
        if (!OnboardingSettings.nativeCarouselEnabled(currentKey)) {
            if (carousel != null) {
                takeDownCarousel()
                replaceOwnerSlot()
            }
            return
        }
        val second = if (currentKey == AdPlacement.Language2) AdPlacement.Language2Carousel
        else AdPlacement.Language1Carousel
        if (carouselKey == second) return
        carouselKey = second
        val existing = secondSlot
        if (existing != null) {
            existing.setPlacement(second)
        } else {
            val view = PoolLanguageCarousel(
                context = requireContext(),
                owner = viewLifecycleOwner,
                hasWindowFocus = { activity?.hasWindowFocus() == true },
                block = b.obAdBlock,
                primaryContainer = b.obNativeContainer,
                listener = carouselListener,
            )
            carousel = view
            secondSlot = PoolNativeSlot(
                owner = viewLifecycleOwner,
                placement = second,
                kind = PoolNativeSlot.Kind.OWNER,
                minState = SCREEN_SLOT_MIN_STATE,
                container = view.secondContainer,
                destroyOnTeardown = snapshot.viewDestroyOnTeardown,
                callbacks = StepSlotCallbacks(onBound = { onPageBound(1) }),
                selection = {
                    OnboardingPool.carouselSelection(carouselPages).map { it?.second ?: PoolSelection.hidden() }
                },
                currentSelection = { OnboardingPool.currentCarouselSelection(carouselPages)?.second },
            ).also { it.start() }
            carouselEvents = OnboardingPool.addEventListener(::onCarouselPoolEvent)
            // The owner slot now renders page 0 of the carousel's joint selection.
            replaceOwnerSlot()
            runListUnderAds(true)
        }
        if (exitLatched) return
        // Posted: the screen's own claim of the LFO key goes first and page 1 waits behind it.
        b.root.post { fillSecondPage() }
        carousel?.start()
    }

    private val carouselPages = OnboardingPool.CarouselPages(
        firstKey = { currentKey.key },
        secondKey = { carouselKey?.key.orEmpty() },
        firstShown = { slot?.shownAd() },
        secondShown = { secondSlot?.shownAd() },
    )

    /**
     * Page 0, or the LFO's only slot: the pool's owner selection, or with the carousel up page 0 of
     * the joint selection, which keeps page 1's ad off it.
     */
    private fun newOwnerSlot(): PoolNativeSlot {
        val b = requireNotNull(binding)
        val paged = carouselKey != null
        return PoolNativeSlot(
            owner = viewLifecycleOwner,
            placement = currentKey,
            kind = PoolNativeSlot.Kind.OWNER,
            minState = SCREEN_SLOT_MIN_STATE,
            container = b.obNativeContainer,
            destroyOnTeardown = snapshot.viewDestroyOnTeardown,
            callbacks = StepSlotCallbacks(onBound = { onPageBound(0) }),
            selection = {
                if (!paged) OnboardingPool.selection
                else OnboardingPool.carouselSelection(carouselPages).map { it?.first ?: PoolSelection.hidden() }
            },
            currentSelection = {
                if (!paged) OnboardingPool.engineOrNull()?.currentSelection()
                else OnboardingPool.currentCarouselSelection(carouselPages)?.first
            },
        ).also { it.start() }
    }

    /** A carousel switched on or off mid-screen: page 0 changes selection, so its slot is rebuilt. */
    private fun replaceOwnerSlot() {
        val old = slot ?: return
        old.stop()
        slot = newOwnerSlot()
    }

    /**
     * With the carousel up the language rows run to the bottom of the screen under the ad block,
     * padded by its height so the last row still scrolls clear of it: whatever the scroll, rows
     * show through the margins and the peeking page's edge, never sometimes a row and sometimes
     * the screen behind it. Without it the rows end above the block as in the layout.
     */
    private fun runListUnderAds(under: Boolean) {
        val b = binding ?: return
        val root = b.root as ConstraintLayout
        val list = b.obLanguageList
        if (under == (listUnderAds != null)) return
        // Cloned now, not kept from earlier: applyTo also re-applies every child's visibility.
        val bottom = ConstraintSet().apply { clone(root) }
        if (under) {
            bottom.connect(list.id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
            bottom.setMargin(list.id, ConstraintSet.BOTTOM, 0)
            bottom.applyTo(root)
            listUnderAds = ViewTreeObserver.OnGlobalLayoutListener {
                val block = binding?.obAdBlock ?: return@OnGlobalLayoutListener
                val pad = if (block.isVisible) block.height else 0
                if (list.paddingBottom != pad) list.updatePadding(bottom = pad)
            }.also(root.viewTreeObserver::addOnGlobalLayoutListener)
        } else {
            listUnderAds?.let(root.viewTreeObserver::removeOnGlobalLayoutListener)
            listUnderAds = null
            // ob_activity_language's own constraint: above the Save button, 8dp off it unless gone.
            bottom.connect(list.id, ConstraintSet.BOTTOM, R.id.ob_language_save, ConstraintSet.TOP)
            bottom.setMargin(list.id, ConstraintSet.BOTTOM, resources.getDimensionPixelSize(R.dimen.ob_language_list_bottom_gap))
            bottom.applyTo(root)
            list.updatePadding(bottom = 0)
        }
    }

    private fun takeDownCarousel() {
        runListUnderAds(false)
        carouselEvents?.cancel()
        carouselEvents = null
        uncover()
        secondSlot?.stop()
        secondSlot = null
        carousel?.dismantle()
        carousel = null
        carouselKey = null
    }

    /**
     * Page 1 with nothing to show and nothing to borrow loads its own key POLITE. A key whose last
     * chain found no fill waits for a swipe or a resume rather than retrying on every pool event.
     */
    private fun fillSecondPage() {
        val key = carouselKey ?: return
        if (exitLatched) return
        if (OnboardingPool.currentCarouselSelection(carouselPages)?.second?.ad != null) return
        if (OnboardingPool.loaderState(key) == PoolState.LoadError) return
        OnboardingPool.loadCarouselPage(key, refreshHeld = false)
    }

    private fun pagePlacement(index: Int): AdPlacement? = if (index == 0) currentKey else carouselKey

    private fun pageSlot(index: Int): PoolNativeSlot? = if (index == 0) slot else secondSlot

    private val carouselListener = object : PoolLanguageCarousel.Listener {
        override fun hasAd(index: Int): Boolean = pageSlot(index)?.showsAd() == true

        /** A new ad for the page, under the loading skeleton while it loads over an old one. */
        override fun onSwipedTo(index: Int) {
            if (exitLatched) return
            val placement = pagePlacement(index) ?: return
            val loading = OnboardingPool.isLoading(placement) ||
                OnboardingPool.loadCarouselPage(placement, refreshHeld = true)
            if (loading && pageSlot(index)?.showsAd() == true) cover(index, placement)
        }

        /** The page's own key loads POLITE if it holds nothing; a held ad stays as it is. */
        override fun onAutoScrolledTo(index: Int) {
            if (exitLatched) return
            pagePlacement(index)?.let { OnboardingPool.loadCarouselPage(it, refreshHeld = false) }
        }
    }

    private fun cover(index: Int, placement: AdPlacement) {
        coveredPage = index
        carousel?.setLoading(index) {
            NativeAdShimmer.from(
                requireContext(),
                NativeTemplates.layoutForPlacement(placement),
                OnboardingSdk.configuredPlacementKey(placement)?.let { AdRemoteConfig.getInstance().unit(it).toNativeStyle() },
            )
        }
    }

    private fun uncover() {
        binding?.root?.removeCallbacks(uncoverAfterFill)
        coveredPage = null
        carousel?.clearLoading()
    }

    private fun onPageBound(index: Int) {
        if (coveredPage == index) uncover()
    }

    /**
     * A covered page's load ended: a fill uncovers when the page binds it (or shortly after, should
     * another page show it), a no-fill at once with the old ad still there. Any end may have
     * idled the pool, so an empty page 1 gets its turn.
     */
    private fun onCarouselPoolEvent(event: PoolEvent<*>) {
        val ended = event is PoolEvent.Filled<*> || event is PoolEvent.Exhausted<*>
        if (!ended) return
        val covered = coveredPage?.let(::pagePlacement)
        if (covered?.key == event.key) {
            if (event is PoolEvent.Filled<*>) binding?.root?.postDelayed(uncoverAfterFill, UNCOVER_FALLBACK_MS)
            else uncover()
        }
        binding?.root?.post { fillSecondPage() }
    }

    private fun screenIndex(placement: AdPlacement): Int = if (placement == AdPlacement.Language2) 2 else 1

    /** The template this screen's slot renders, so a funnel can slice by it. */
    private fun variant(placement: AdPlacement): String = NativeTemplates.variantFor(placement)

    companion object {
        private const val ARG_RESTORED_LANGUAGE = "ob_pool_restored_language"
        private const val KEY_SELECTED = "ob_pool_selected_language"
        private const val KEY_TAPS = "ob_pool_language_tap_count"
        private const val KEY_ON_LANGUAGE2 = "ob_pool_language2"
        private const val KEY_CONFIRM_REVEALED = "ob_pool_confirm_revealed"
        private const val KEY_HANDOFF = "ob_pool_lfo1_preload_handoff"
        private const val KEY_LOADING_LEFT = "ob_pool_lfo1_loading_left_ms"
        private const val LOADING_TICK_MS = 50L
        private const val LOADING_FLAG_STEP_MS = 400L
        private const val LOADING_FADE_MS = 200L
        private const val UNCOVER_FALLBACK_MS = 1_000L

        fun newInstance(restoredLanguage: String?): PoolLanguageFragment =
            PoolLanguageFragment().apply { arguments = bundleOf(ARG_RESTORED_LANGUAGE to restoredLanguage) }
    }
}
