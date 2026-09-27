package io.onboardkit.ui.splash

import android.os.Bundle
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.showNativeAd
import io.onboardkit.ads.whenResumed
import io.onboardkit.config.FullScreenSkipPosition
import io.onboardkit.config.FullScreenSkipStyle
import io.onboardkit.databinding.ObActivityFullscreenAdBinding
import io.onboardkit.remote.OnboardingSettings
import io.onboardkit.ui.applyFullScreenSkip
import io.onboardkit.ui.base.BaseOnboardActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Optional native_fs between the splash interstitial and LFO; never completes onboarding. */
class ObSplashNativeActivity : BaseOnboardActivity() {
    override val screenName = "splash_native"
    private lateinit var binding: ObActivityFullscreenAdBinding
    private var skipTimerStarted = false

    override fun onCreateSafe(savedInstanceState: Bundle?) {
        if (sdk.guard().skipReason(this, AdPlacement.SplashNative) != null) {
            close()
            return
        }
        binding = ObActivityFullscreenAdBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.obSkipButton.applyFullScreenSkip(
            FullScreenSkipStyle.valueOf(OnboardingSettings.text("splash.native.skip.style")),
            FullScreenSkipPosition.valueOf(OnboardingSettings.text("splash.native.skip.position")),
        )
        binding.obSkipButton.setOnClickListener { close() }
        // A ready fill cannot bind before RESUMED. On recreation the helper may instead own a
        // retained presentation, so a READY-only buffer check would wrongly close this screen.
        whenResumed(onHostLost = {}) {
            showNativeAd(
                placement = AdPlacement.SplashNative,
                unit = sdk.requireConfig().ads.splashNative,
                container = binding.obNativeContainer,
                onBound = ::startSkipTimer,
                onUnavailable = { close() },
                preloadedOnly = true,
            )
        }
    }

    private fun startSkipTimer() {
        if (skipTimerStarted) return
        skipTimerStarted = true
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                delay(OnboardingSettings.number("splash.native.skip.delay_ms"))
                binding.obSkipButton.visibility = View.VISIBLE
            }
        }
    }

    override fun handleBack() = close()

    private fun close() {
        if (isFinishing || isDestroyed) return
        setResult(RESULT_OK)
        finish()
    }

    override fun onDestroy() {
        if (!isChangingConfigurations) sdk.provider()?.releaseNative(AdPlacement.SplashNative)
        super.onDestroy()
    }
}
