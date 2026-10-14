package com.ads.module.helper.adnative.pool

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.LayoutRes
import androidx.annotation.MainThread
import androidx.annotation.RestrictTo
import com.ads.module.admob.Admob
import com.ads.module.ads.wrapper.ApNativeAd
import com.ads.module.config.settings.AdBehavior
import com.ads.module.helper.AdGate
import com.ads.module.helper.adnative.NativeAdShimmer
import com.ads.module.helper.adnative.NativeAdStyle
import com.ads.module.helper.adnative.NativeAdStyler
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdView
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * Binds each delivered ad into a fresh view from the SLOT's layout and never destroys the one it
 * replaces: other slots may still show that ad. The skeleton keeps `NativeAdHelper`'s rules.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
@MainThread
class PoolSlotBinder(
    private val container: FrameLayout,
    @LayoutRes private val slotLayout: Int,
    private val style: () -> NativeAdStyle?,
    private val destroyOnTeardown: Boolean,
) {
    enum class Outcome {
        /** A fresh view now shows the ad. */
        BOUND_NEW,

        /** The same ad's view is still in the container; nothing was rebound. */
        BOUND_SAME,
        SHIMMER,

        /** The container is GONE; its views stay in place. */
        HIDDEN,

        /** No vendor ad left (destroyed) or the bind threw; the slot is unchanged. */
        IGNORED,
    }

    private var bound: ApNativeAd? = null
    private var boundView: NativeAdView? = null
    private var registered: NativeAd? = null
    private var skeleton: ShimmerFrameLayout? = null
    private var forceFresh = false

    /** Each view bound here that is still its ad's last registrant, held weakly, swapped out or not. */
    private val registrations = mutableListOf<Pair<WeakReference<NativeAd>, WeakReference<NativeAdView>>>()

    /** The ad whose view the container holds, shown or hidden; null under a skeleton. */
    fun boundAd(): ApNativeAd? = bound?.takeIf { boundView?.parent === container }

    /** The next delivered ad is inflated anew even when it is the one already bound (a replay). */
    fun forceFreshOnNextRender() {
        forceFresh = true
    }

    fun render(selection: PoolSelection<ApNativeAd>): Outcome {
        val ad = selection.ad
        return when {
            !selection.show -> hide()
            ad != null -> bind(ad)
            canShowAds() -> showSkeleton()
            // A premium or ads-off user must not see an ad-shaped placeholder either.
            else -> hide()
        }
    }

    /**
     * Never destroys the ad. Destroys every view this slot bound that its ad was last registered
     * to, including one a later bind swapped out: nothing else would release that registration.
     */
    fun teardown() {
        skeleton?.stopShimmer()
        if (destroyOnTeardown) {
            registrations.forEach { (adRef, viewRef) ->
                val nativeAd = adRef.get() ?: return@forEach
                val view = viewRef.get() ?: return@forEach
                if (isLastRegistrant(nativeAd, view)) {
                    lastRegistrant.remove(nativeAd)
                    view.destroy()
                }
            }
        }
        registrations.clear()
        bound = null
        boundView = null
        registered = null
    }

    private fun bind(ad: ApNativeAd): Outcome {
        val nativeAd = ad.admobNativeAd ?: return Outcome.IGNORED
        val current = boundView
        if (!forceFresh && ad === bound && nativeAd === registered && current?.parent === container) {
            container.visibility = View.VISIBLE
            return Outcome.BOUND_SAME
        }
        val view = try {
            inflateAdView().also { view ->
                Admob.getInstance().populateUnifiedNativeAdView(nativeAd, view)
                // After populate: it force-shows body and CTA, which would undo the style.
                style()?.let { s ->
                    NativeAdStyler.applyLayout(view, s)
                    NativeAdStyler.applyAppearance(view, s)
                }
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Cannot bind into slot layout $slotLayout", e)
            return Outcome.IGNORED
        }
        skeleton?.stopShimmer()
        skeleton = null
        container.removeAllViews()
        container.addView(view)
        container.visibility = View.VISIBLE
        bound = ad
        boundView = view
        registered = nativeAd
        forceFresh = false
        lastRegistrant[nativeAd] = WeakReference(view)
        registrations.removeAll { (adRef, viewRef) -> !isLastRegistrant(adRef.get(), viewRef.get()) }
        registrations += WeakReference(nativeAd) to WeakReference(view)
        return Outcome.BOUND_NEW
    }

    private fun isLastRegistrant(nativeAd: NativeAd?, view: NativeAdView?): Boolean =
        nativeAd != null && view != null && lastRegistrant[nativeAd]?.get() === view

    private fun showSkeleton(): Outcome {
        val reusable = skeleton?.takeIf { it.parent === container }
        val shimmer = reusable ?: NativeAdShimmer.from(container.context, slotLayout, style()).also {
            container.removeAllViews()
            container.addView(it)
            skeleton = it
        }
        container.visibility = View.VISIBLE
        shimmer.startShimmer()
        return Outcome.SHIMMER
    }

    private fun hide(): Outcome {
        skeleton?.stopShimmer()
        container.visibility = View.GONE
        return Outcome.HIDDEN
    }

    private fun inflateAdView(): NativeAdView {
        val root = LayoutInflater.from(container.context).inflate(slotLayout, container, false)
        if (root is NativeAdView) return root
        firstAdView(root)?.let { found ->
            (found.parent as? ViewGroup)?.removeView(found)
            return found
        }
        return NativeAdView(container.context).apply {
            layoutParams = root.layoutParams
            addView(root, FrameLayout.LayoutParams(root.layoutParams))
        }
    }

    private fun firstAdView(view: View): NativeAdView? {
        if (view is NativeAdView) return view
        val group = view as? ViewGroup ?: return null
        for (i in 0 until group.childCount) firstAdView(group.getChildAt(i))?.let { return it }
        return null
    }

    private fun canShowAds(): Boolean =
        !AdGate.isPurchased(container.context) && AdBehavior.bool("global.ads_enabled")

    private companion object {
        const val TAG = "PoolSlotBinder"

        val lastRegistrant = WeakHashMap<NativeAd, WeakReference<NativeAdView>>()
    }
}
