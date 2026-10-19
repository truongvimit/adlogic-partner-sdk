package io.onboardkit.ui.pool

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import com.ads.module.helper.adnative.pool.LoadMode
import com.ads.module.helper.adnative.pool.reportsSuccess
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.pool.OnboardingPool
import io.onboardkit.ads.pool.PoolNativeSlot
import io.onboardkit.ads.pool.SCREEN_SLOT_MIN_STATE
import io.onboardkit.flow.NativeFlowSnapshot
import io.onboardkit.remote.OnboardingSettings

/**
 * DashDrama's Favorite ad pattern, shared by every two-ad select screen: [ad1] is claimed
 * politely when the screen opens, [ad2] forcibly on every selection, and the forward button stays
 * hidden until a fixed time after the first selection. Back in front, the screen gives the slot
 * back to its owner, refreshes it and retries its last claim politely (FavoriteFragment.java:110-124).
 *
 * The one slot keeps [ad1]'s template whichever ad the pool selects. Like Favorite's collector
 * (C6/h), it hides only when ads are refused for the whole flow, never on [ad1]'s own refusal:
 * an [ad2] the screen owns, or a leftover, still shows.
 */
internal class PoolSelectScreen(
    private val fragment: Fragment,
    private val ad1: AdPlacement,
    private val ad2: AdPlacement,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var forward: View? = null
    private val reveal = Runnable { forward?.visibility = View.VISIBLE }

    /** The placement whose claim last reported success: the owner this screen restores. */
    private var ownerKey: AdPlacement? = null

    /** The placement claimed last, retried on every resume. */
    private var currentAttempt: AdPlacement? = null

    /** The first selection happened; a recreated screen shows its forward button at once. */
    var selectedOnce = false
        private set

    private val snapshot: NativeFlowSnapshot
        get() = (fragment.activity as? ObPoolFlowActivity)?.snapshot ?: OnboardingSettings.nativeFlowSnapshot()

    fun restore(state: Bundle?) {
        selectedOnce = state?.getBoolean(KEY_SELECTED_ONCE) == true
    }

    fun save(out: Bundle) {
        out.putBoolean(KEY_SELECTED_ONCE, selectedOnce)
    }

    /** Claims [ad1] and binds the slot; [forward] stays hidden until the reveal. */
    fun onViewCreated(container: FrameLayout, forward: View) {
        val snapshot = snapshot
        this.forward = forward
        forward.visibility = if (selectedOnce) View.VISIBLE else View.GONE
        attempt(ad1, LoadMode.POLITE)
        PoolNativeSlot(
            owner = fragment.viewLifecycleOwner,
            placement = ad1,
            kind = PoolNativeSlot.Kind.NON_OWNER,
            minState = SCREEN_SLOT_MIN_STATE,
            container = container,
            destroyOnTeardown = snapshot.viewDestroyOnTeardown,
        ).start()
    }

    /** DD C6/b: every selection claims [ad2] forcibly; the engine requests it only while it holds nothing. */
    fun onSelection() {
        attempt(ad2, LoadMode.FORCED)
        if (selectedOnce) return
        selectedOnce = true
        handler.postDelayed(reveal, snapshot.selectNextRevealDelayMs)
    }

    fun onResume() {
        ownerKey?.let { owner ->
            OnboardingPool.setOwner(owner)
            OnboardingPool.refresh(owner)
        }
        currentAttempt?.let { attempt(it, LoadMode.POLITE) }
    }

    fun onDestroyView() {
        handler.removeCallbacks(reveal)
        ownerKey?.let(OnboardingPool::clearOwnerIf)
        forward = null
    }

    private fun attempt(placement: AdPlacement, mode: LoadMode) {
        currentAttempt = placement
        if (OnboardingPool.claim(placement, mode).reportsSuccess) ownerKey = placement
    }

    private companion object {
        const val KEY_SELECTED_ONCE = "ob_pool_select_selected_once"
    }
}
