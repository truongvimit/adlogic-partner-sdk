package com.ads.module.helper.adnative.pool

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Bundle
import android.os.Looper
import com.ads.module.ads.wrapper.ApNativeAd
import com.ads.module.consent.ConsentCenter
import com.ads.module.helper.Entitlement
import com.ads.module.helper.EntitlementSource
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MediaContent
import com.google.android.gms.ads.MuteThisAdListener
import com.google.android.gms.ads.MuteThisAdReason
import com.google.android.gms.ads.OnPaidEventListener
import com.google.android.gms.ads.ResponseInfo
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdView
import org.mockito.MockedConstruction
import org.mockito.Mockito.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.shadows.ShadowViewGroup

/** A vendor native whose body is optional, so populate's force-show of `ad_body` is observable. */
class PoolVendorAd(private val body: String? = null) : NativeAd() {
    var destroyed = 0
    override fun destroy() { destroyed++ }
    override fun getHeadline() = "Pool test ad"
    override fun getImages() = emptyList<Image>()
    override fun getBody(): String? = body
    override fun getIcon(): Image? = null
    override fun getCallToAction() = "Open"
    override fun getAdvertiser(): String? = null
    override fun getStarRating(): Double? = null
    override fun getStore(): String? = null
    override fun getPrice(): String? = null
    override fun getAdChoicesInfo(): AdChoicesInfo? = null
    override fun isCustomMuteThisAdEnabled() = false
    override fun getMuteThisAdReasons() = emptyList<MuteThisAdReason>()
    override fun muteThisAd(reason: MuteThisAdReason) {}
    override fun setMuteThisAdListener(listener: MuteThisAdListener) {}
    override fun getExtras() = Bundle()
    override fun setUnconfirmedClickListener(listener: UnconfirmedClickListener) {}
    override fun cancelUnconfirmedClick() {}
    override fun enableCustomClickGesture() {}
    override fun isCustomClickGestureEnabled() = false
    override fun recordCustomClickGesture() {}
    override fun performClick(bundle: Bundle) {}
    override fun recordImpression(bundle: Bundle) = true
    override fun reportTouchEvent(bundle: Bundle) {}
    override fun getMediaContent(): MediaContent? = null
    override fun getResponseInfo(): ResponseInfo? = null
    override fun setOnPaidEventListener(listener: OnPaidEventListener?) {}
    override fun recordEvent(bundle: Bundle) {}
    override fun getPlacementId() = 0L
    override fun setPlacementId(id: Long) {}
    override fun zza(): Any? = null
}

/** Replaces only Google's registration; inflation, populate and styling stay real. */
@Implements(value = NativeAdView::class, isInAndroidSdk = false)
class PoolNativeAdViewShadow : ShadowViewGroup() {
    private val assets = mutableMapOf<String, android.view.View?>()
    var registered: NativeAd? = null
    var destroyCount = 0

    @Implementation fun setNativeAd(ad: NativeAd) { registered = ad }
    @Implementation fun destroy() { destroyCount++ }
    @Implementation fun setHeadlineView(view: android.view.View?) { assets["Headline"] = view }
    @Implementation fun getHeadlineView(): android.view.View? = assets["Headline"]
    @Implementation fun setBodyView(view: android.view.View?) { assets["Body"] = view }
    @Implementation fun getBodyView(): android.view.View? = assets["Body"]
    @Implementation fun setCallToActionView(view: android.view.View?) { assets["CallToAction"] = view }
    @Implementation fun getCallToActionView(): android.view.View? = assets["CallToAction"]
    @Implementation fun setIconView(view: android.view.View?) { assets["Icon"] = view }
    @Implementation fun getIconView(): android.view.View? = assets["Icon"]
    @Implementation fun setPriceView(view: android.view.View?) { assets["Price"] = view }
    @Implementation fun getPriceView(): android.view.View? = assets["Price"]
    @Implementation fun setStarRatingView(view: android.view.View?) { assets["StarRating"] = view }
    @Implementation fun getStarRatingView(): android.view.View? = assets["StarRating"]
    @Implementation fun setAdvertiserView(view: android.view.View?) { assets["Advertiser"] = view }
    @Implementation fun getAdvertiserView(): android.view.View? = assets["Advertiser"]
    @Implementation fun setMediaView(view: com.google.android.gms.ads.nativead.MediaView?) {}
}

/** One AdLoader request: the unit it asked for and GMA's two listeners. */
class GmaRequest(val unit: String, val loaded: NativeAd.OnNativeAdLoadedListener, val listener: AdListener) {
    fun fill(ad: NativeAd = PoolVendorAd()): NativeAd = ad.also { loaded.onNativeAdLoaded(it) }
    fun fail() = listener.onAdFailedToLoad(LoadAdError(3, "No fill", "test", null, null))
}

/** Captures every `AdLoader.Builder(...).build().loadAd()`; close it in @After. */
class GmaRequests : AutoCloseable {
    val all = mutableListOf<GmaRequest>()
    var throwOnLoad: RuntimeException? = null

    private val builders: MockedConstruction<AdLoader.Builder> =
        mockConstruction(AdLoader.Builder::class.java) { builder, construction ->
            var loaded: NativeAd.OnNativeAdLoadedListener? = null
            var listener: AdListener? = null
            doAnswer { loaded = it.getArgument(0); builder }.`when`(builder).forNativeAd(any())
            doAnswer { listener = it.getArgument(0); builder }.`when`(builder).withAdListener(any())
            doReturn(builder).`when`(builder).withNativeAdOptions(any())
            val adLoader = mock(AdLoader::class.java)
            doReturn(adLoader).`when`(builder).build()
            doAnswer {
                throwOnLoad?.let { throw it }
                all += GmaRequest(construction.arguments()[1] as String, checkNotNull(loaded), checkNotNull(listener))
                null
            }.`when`(adLoader).loadAd(any(AdRequest::class.java))
        }

    fun last(): GmaRequest = all.last()

    override fun close() = builders.close()
}

/** Network up, consent granted, not premium: every AdGate check passes. */
fun allowAds(context: Context) {
    ConsentCenter.setHostConsent(true, false)
    setPremium(false)
    setOnline(context, true)
}

fun setPremium(premium: Boolean) {
    Entitlement.install(object : EntitlementSource {
        override fun isPremium(context: Context) = premium
    })
}

fun setOnline(context: Context, online: Boolean) {
    val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    if (!online) {
        shadowOf(connectivity).setActiveNetworkInfo(null)
        shadowOf(connectivity).setDefaultNetworkActive(false)
        return
    }
    shadowOf(connectivity).setDefaultNetworkActive(true)
    shadowOf(connectivity).setActiveNetworkInfo(ShadowNetworkInfo.newInstance(
        NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true))
    shadowOf(connectivity).setNetworkCapabilities(connectivity.activeNetwork,
        NetworkCapabilities().also { shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_WIFI) })
}

/** Every sink call, with the thread it arrived on. */
class RecordingSink : PoolLoadSink<ApNativeAd> {
    data class Call(val name: String, val ad: ApNativeAd?, val onMain: Boolean)

    val calls = mutableListOf<Call>()
    val names: List<String> get() = calls.map { it.name }

    override fun onLoaded(ad: ApNativeAd) = record("loaded", ad)
    override fun onFailed() = record("failed", null)
    override fun onImpression(ad: ApNativeAd) = record("impression", ad)
    override fun onClicked(ad: ApNativeAd) = record("clicked", ad)

    private fun record(name: String, ad: ApNativeAd?) {
        calls += Call(name, ad, Looper.myLooper() === Looper.getMainLooper())
    }
}
