package io.onboardkit.ui.splash

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ads.module.helper.adnative.NativeClickAction
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.AdSkipReason
import io.onboardkit.ads.pool.OnboardingPool
import io.onboardkit.ads.pool.PoolNativeSlot
import io.onboardkit.ads.showNativeAd
import io.onboardkit.ads.whenResumed
import io.onboardkit.config.FullScreenSkipPosition
import io.onboardkit.config.FullScreenSkipStyle
import io.onboardkit.databinding.ObActivityFullscreenAdBinding
import io.onboardkit.remote.OnboardingSettings
import io.onboardkit.ui.AdClickReturnTracker
import io.onboardkit.ui.applyFullScreenSkip
import io.onboardkit.ui.base.BaseOnboardActivity
import io.onboardkit.ui.pool.StepSlotCallbacks
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Optional native_fs between the splash interstitial and LFO; never completes onboarding. */
class ObSplashNativeActivity : BaseOnboardActivity() {
    override val screenName = "splash_native"
    private lateinit var binding: ObActivityFullscreenAdBinding
    private var skipTimerJob: Job? = null
    private var skipUnlocked = false
    private val adClickReturn = AdClickReturnTracker()

    override fun onCreateSafe(savedInstanceState: Bundle?) {
        skipUnlocked = savedInstanceState?.getBoolean(KEY_SKIP_UNLOCKED) == true
        if (sdk.guard().skipReason(this, AdPlacement.SplashNative) != null) {
            close()
            return
        }
        binding = ObActivityFullscreenAdBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.obSkipButton.applyFullScreenSkip(
            OnboardingSettings.skipStyle("splash.native.skip.style", FullScreenSkipStyle.CLOSE_ICON),
            OnboardingSettings.skipPosition("splash.native.skip.position", FullScreenSkipPosition.RIGHT),
        )
        binding.obSkipButton.setOnClickListener { close() }
        if (skipUnlocked) unlockSkip()
        if (pooled) {
            bindPoolSlot()
            return
        }
        // A ready fill cannot bind before RESUMED. On recreation the helper may instead own a
        // retained presentation, so a READY-only buffer check would wrongly close this screen.
        whenResumed(onHostLost = {}) {
            showNativeAd(
                placement = AdPlacement.SplashNative,
                unit = sdk.requireConfig().ads.splashNative,
                container = binding.obNativeContainer,
                onBound = ::startSkipTimer,
                // A failed waterfall has no useful screen to wait on. Returning the result lets
                // the splash continue exactly as if the optional native slot had been disabled.
                onUnavailable = { _: AdSkipReason -> close() },
                preloadedOnly = true,
                onAdEngaged = ::onAdEngaged,
            )
        }
    }

    /**
     * SHARED_POOL: the screen is the pool's first slot. It owns native_fs and requests nothing; the
     * ad, shown or still loading, stays in the pool for the LFO after it.
     */
    private fun bindPoolSlot() {
        OnboardingPool.setOwner(AdPlacement.SplashNative)
        PoolNativeSlot(
            owner = this,
            placement = AdPlacement.SplashNative,
            kind = PoolNativeSlot.Kind.OWNER,
            minState = Lifecycle.State.RESUMED,
            container = binding.obNativeContainer,
            callbacks = StepSlotCallbacks(
                onBound = ::startSkipTimer,
                onUnavailable = { close() },
                onAdEngaged = ::onAdEngaged,
            ),
            destroyOnTeardown = OnboardingSettings.nativeFlowSnapshot().viewDestroyOnTeardown,
        ).start()
    }

    override fun onPause() {
        // Match the onboarding pager: only a native click arms AUTO_NEXT. Home/background merely
        // unlocks X, so returning from Home does not accidentally advance the flow.
        adClickReturn.onPause()
        if (!isFinishing && !isDestroyed) {
            unlockSkip()
        }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        // The destination owns the screen while the ad is open. Queue the close after resume so
        // it cannot race the Activity's resume transaction, just like onboarding page navigation.
        if (!adClickReturn.onResume()) return
        binding.root.post {
            if (!isFinishing && !isDestroyed) close()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(KEY_SKIP_UNLOCKED, skipUnlocked)
        super.onSaveInstanceState(outState)
    }

    private fun startSkipTimer() {
        if (skipUnlocked || skipTimerJob != null) return
        skipTimerJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                delay(OnboardingSettings.number("splash.native.skip.delay_ms"))
                unlockSkip()
            }
        }
    }

    private fun onAdEngaged(action: NativeClickAction) {
        unlockSkip()
        adClickReturn.onEngaged(action)
    }

    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        // If a vendor reports a click but no destination actually opens, a later touch proves the
        // user is still on this screen. Do not consume a future Home/resume trip as AUTO_NEXT.
        if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) adClickReturn.onTouch()
        return super.dispatchTouchEvent(event)
    }

    private fun unlockSkip() {
        skipUnlocked = true
        skipTimerJob?.cancel()
        skipTimerJob = null
        if (::binding.isInitialized) binding.obSkipButton.visibility = View.VISIBLE
    }

    override fun handleBack() = close()

    private fun close() {
        if (isFinishing || isDestroyed) return
        setResult(RESULT_OK)
        finish()
    }

    override fun onDestroy() {
        if (!isChangingConfigurations) {
            if (pooled) OnboardingPool.clearOwnerIf(AdPlacement.SplashNative)
            else sdk.provider()?.releaseNative(AdPlacement.SplashNative)
        }
        super.onDestroy()
    }

    private val pooled: Boolean get() = intent.getBooleanExtra(EXTRA_POOLED, false)

    internal companion object {
        private const val KEY_SKIP_UNLOCKED = "ob_splash_native_skip_unlocked"
        private const val EXTRA_POOLED = "ob_splash_native_pooled"

        fun intent(context: Context, pooled: Boolean): Intent =
            Intent(context, ObSplashNativeActivity::class.java).putExtra(EXTRA_POOLED, pooled)
    }
}
