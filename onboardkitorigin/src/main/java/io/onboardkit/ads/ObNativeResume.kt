package io.onboardkit.ads

import android.app.Activity
import android.content.Context
import com.ads.module.admob.ResumeAds
import io.onboardkit.OnboardingSdk
import io.onboardkit.ui.resume.ObResumeActivity

/** The screen `:ads` opens for native resume. */
internal object ObNativeResume : ResumeAds.NativeScreen {

    override fun prepare(context: Context) {
        if (!OnboardingSdk.isReady()) return
        val placement = AdPlacement.ResumeNative
        val unit = OnboardingSdk.configOrNull()?.ads?.nativeUnitFor(placement) ?: return
        if (OnboardingSdk.guard().skipReason(context, placement, unit) != null) return
        OnboardingSdk.provider()?.preloadNativeInBackground(
            context, NativeAdRequest(placement, unit, NativeTemplates.layoutForPlacement(placement)),
        )
    }

    override fun show(host: Activity) {
        // Without config the screen's guard would restart the whole flow from the launcher.
        if (!OnboardingSdk.isReady()) return
        if (host is ObResumeActivity) host.renew() else ObResumeActivity.start(host)
    }
}
