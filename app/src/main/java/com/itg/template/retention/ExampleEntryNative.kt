package com.itg.template.retention

import android.app.Activity
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.lifecycleScope
import com.ads.module.ads.wrapper.ApNativeAd
import com.ads.module.config.AdRemoteConfig
import com.ads.module.config.AdUnitConfig
import com.ads.module.funtion.AdCallback
import com.ads.module.helper.adnative.AdNativeState
import com.ads.module.helper.adnative.NativeAdParam
import com.itg.template.R
import com.itg.template.ads.AdsManager
import io.retentionkit.core.RetentionEntrySource
import kotlinx.coroutines.launch

/** Host view adapter only. Existing Ads SDK owns consent, Billing, UA, load/bind and ad callbacks. */
object ExampleEntryNative {
    fun placement(source: RetentionEntrySource?): String = when (source) {
        RetentionEntrySource.WIDGET, RetentionEntrySource.SHORTCUT -> "native_widget"
        RetentionEntrySource.FEEDBACK -> "native_uninstall"
        null -> "native_home"
        else -> "native_noti"
    }
    fun attach(activity: Activity, owner: LifecycleOwner, container: FrameLayout, placement: String): AutoCloseable =
        Binding(activity, owner, container, placement)

    private class Binding(activity: Activity, private val parent: LifecycleOwner, container: FrameLayout, placement: String) : LifecycleOwner, AutoCloseable {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle = registry
        private var closed = false
        private val observer = LifecycleEventObserver { _, event ->
            if (!closed) registry.handleLifecycleEvent(event)
        }
        init {
            parent.lifecycle.addObserver(observer)
            val config = AdRemoteConfig.getInstance().ads[placement] ?: AdUnitConfig(id = "", isEnable = false)
            val helper = AdsManager.nativeHelper(activity, this, placement, config, R.layout.layout_native_ad_medium)
                .setNativeContentView(container)
            helper.registerAdListener(object : AdCallback() {
                override fun onNativeAdLoaded(nativeAd: ApNativeAd) { ExampleQa.nativeEvent(placement, "loaded") }
                override fun onAdImpression() { ExampleQa.nativeEvent(placement, "impression") }
            })
            lifecycleScope.launch {
                helper.nativeAdState.collect { state ->
                    when (state) {
                        AdNativeState.Fail -> ExampleQa.nativeEvent(placement, "failed_or_skipped")
                        AdNativeState.Cancel -> ExampleQa.nativeEvent(placement, "cancelled")
                        else -> Unit
                    }
                }
            }
            // This is a request callsite, never a claim that an ad loaded or was shown.
            ExampleQa.nativeEvent(placement, "request_called")
            helper.requestAds(NativeAdParam.Request)
        }
        override fun close() {
            if (closed) return
            closed = true
            parent.lifecycle.removeObserver(observer)
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }
}
