package io.onboardkit.ads

import android.app.Activity
import android.content.Context
import androidx.annotation.MainThread
import com.ads.module.admob.ResumeAds
import com.ads.module.helper.adnative.pool.LoadMode
import io.onboardkit.OnboardingSdk
import io.onboardkit.ads.pool.OnboardingPool
import io.onboardkit.ui.pool.ObPoolResumeActivity
import io.onboardkit.ui.resume.ObResumeActivity

/** The screen `:ads` opens for native resume. */
internal object ObNativeResume : ResumeAds.NativeScreen {

    /** The pool's native_resume renewal [prepare] started on the way out, for the screen it opens to skip. */
    private var renewedAhead = false

    override fun prepare(context: Context) {
        if (OnboardingPool.routeResume) {
            preparePooled()
            return
        }
        if (!OnboardingSdk.isReady()) return
        val placement = AdPlacement.ResumeNative
        val unit = OnboardingSdk.configOrNull()?.ads?.nativeUnitFor(placement) ?: return
        if (OnboardingSdk.guard().skipReason(context, placement, unit) != null) return
        OnboardingSdk.provider()?.preloadNativeInBackground(
            context, NativeAdRequest(placement, unit, NativeTemplates.layoutForPlacement(placement)),
        )
    }

    /** The renewal the pooled screen runs on opening, moved to the moment the user leaves. */
    private fun preparePooled() {
        renewedAhead = false
        if (!OnboardingSdk.isReady() || !OnboardingPool.isActive) return
        renewPooled()
        renewedAhead = OnboardingPool.isLoading(AdPlacement.ResumeNative)
    }

    /**
     * Renews native_resume past the pool's gate: a held ad is refreshed and stays drawn until the new
     * one fills, an empty loader loads FORCED, and one already loading is left alone.
     */
    @MainThread
    fun renewPooled() {
        if (!OnboardingPool.refresh(AdPlacement.ResumeNative)) {
            OnboardingPool.preload(AdPlacement.ResumeNative, LoadMode.FORCED)
        }
    }

    override fun show(host: Activity) = show(host, afterAdClick = false)

    override fun show(host: Activity, afterAdClick: Boolean) {
        // Both screens exclude themselves; this also covers a routing switch while one is open.
        if (host is ObResumeActivity || host is ObPoolResumeActivity) return
        // Without config the screen's guard would restart the whole flow from the launcher.
        if (!OnboardingSdk.isReady()) return
        if (OnboardingPool.routeResume) {
            val renewed = renewedAhead
            renewedAhead = false
            ObPoolResumeActivity.start(host, afterAdClick, renewed)
        } else {
            ObResumeActivity.start(host, afterAdClick)
        }
    }
}
