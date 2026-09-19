package io.onboardkit.ui.splash

import android.os.Bundle
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.showNativeAd
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

    override fun onCreateSafe(savedInstanceState: Bundle?) {
        if (sdk.guard().skipReason(this, AdPlacement.SplashNative) != null ||
            sdk.provider()?.isNativeReady(AdPlacement.SplashNative) != true) {
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
        showNativeAd(
            placement = AdPlacement.SplashNative,
            unit = sdk.requireConfig().ads.splashNative,
            container = binding.obNativeContainer,
            onUnavailable = { close() },
            bufferedOnly = true,
        )
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                launch {
                    delay(OnboardingSettings.number("splash.native.skip.delay_ms"))
                    binding.obSkipButton.visibility = View.VISIBLE
                }
                launch {
                    delay(OnboardingSettings.number("splash.native.auto_dismiss_ms"))
                    close()
                }
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
