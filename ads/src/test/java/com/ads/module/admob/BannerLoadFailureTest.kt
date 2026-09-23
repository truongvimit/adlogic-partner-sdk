package com.ads.module.admob

import android.app.Activity
import android.app.Application
import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.ads.module.config.settings.AdBehavior
import com.ads.module.engine.BannerEngine
import com.ads.module.funtion.AdCallback
import com.ads.module.helper.Entitlement
import com.ads.module.helper.EntitlementSource
import com.ads.module.helper.banner.BannerType
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.BaseAdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.OnPaidEventListener
import com.google.android.gms.ads.ResponseInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.RealObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [ThrowingBannerVendorShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class BannerLoadFailureTest {
    private lateinit var controller: ActivityController<Activity>
    private lateinit var container: FrameLayout
    private lateinit var shimmer: ShimmerFrameLayout
    private var failures = 0

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        AdBehavior.initialize(app)
        AdBehavior.document.acceptSuccessfulFetch("{}")
        Entitlement.install(object : EntitlementSource {
            override fun isPremium(context: Context) = false
        })
        controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        container = FrameLayout(activity)
        shimmer = ShimmerFrameLayout(activity).apply {
            layoutParams = FrameLayout.LayoutParams(320, 50)
        }
        activity.setContentView(FrameLayout(activity).apply {
            addView(container)
            addView(shimmer)
        })
        ThrowingBannerVendorShadow.dispatch = { throw IllegalStateException("Vendor load failed") }
        ThrowingBannerVendorShadow.destroyedViews.clear()
    }

    @After
    fun tearDown() {
        ThrowingBannerVendorShadow.dispatch = {}
        ThrowingBannerVendorShadow.destroyedViews.clear()
        controller.pause().stop().destroy()
        AdBehavior.document.acceptSuccessfulFetch("{}")
    }

    @Test
    fun `plain synchronous load exception ends shimmer and reports one failure`() {
        load(BannerType.Normal, failureCallback())
        assertFailedSlot()
    }

    @Test
    fun `collapsible synchronous load exception ends shimmer and reports one failure`() {
        load(BannerType.Collapsible("bottom"), failureCallback())
        assertFailedSlot()
    }

    @Test
    fun `synchronous load exception retires only its view before notifying the caller`() {
        val survivor = View(controller.get())
        container.addView(survivor)
        load(BannerType.Normal, object : AdCallback() {
            override fun onAdFailedToLoad(adError: LoadAdError?) {
                failures++
                assertEquals(1, container.childCount)
                assertSame(survivor, container.getChildAt(0))
                assertEquals(1, ThrowingBannerVendorShadow.destroyedViews.size)
            }
        })
        assertFailedSlot()
    }

    @Test
    fun `late vendor callbacks from an aborted request cannot change its terminal result`() {
        var lateListener: AdListener? = null
        var loaded = 0
        ThrowingBannerVendorShadow.dispatch = { view ->
            lateListener = view.adListener
            throw IllegalStateException("Vendor load failed")
        }
        load(BannerType.Normal, object : AdCallback() {
            override fun onAdLoaded() { loaded++ }
            override fun onAdFailedToLoad(adError: LoadAdError?) { failures++ }
        })
        assertFailedSlot()
        checkNotNull(lateListener).onAdLoaded()
        checkNotNull(lateListener).onAdFailedToLoad(LoadAdError(3, "Late", "test", null, null))
        assertFailedSlot()
        assertEquals(0, loaded)
    }

    @Test
    fun `exception from synthetic failure callback is contained without a second notification`() {
        val error = IllegalArgumentException("Partner callback failed")
        load(BannerType.Normal, failureCallback(error))
        assertFailedSlot()
    }

    @Test
    fun `exception from vendor failure callback is not turned into another failure`() {
        val error = IllegalArgumentException("Partner callback failed")
        ThrowingBannerVendorShadow.dispatch = { view ->
            checkNotNull(view.adListener).onAdFailedToLoad(
                LoadAdError(3, "No fill", "test", null, null),
            )
        }
        load(BannerType.Normal, failureCallback(error))
        assertFailedSlot()
    }

    @Test
    fun `collapsible synchronous vendor failure reaches the installed listener once`() {
        ThrowingBannerVendorShadow.dispatch = { view ->
            checkNotNull(view.adListener).onAdFailedToLoad(
                LoadAdError(3, "No fill", "test", null, null),
            )
        }
        load(BannerType.Collapsible("bottom"), failureCallback())
        assertFailedSlot()
    }

    @Test
    fun `exception from loaded callback is not reclassified as load failure`() {
        val error = IllegalArgumentException("Partner callback failed")
        var loaded = 0
        ThrowingBannerVendorShadow.dispatch = { view -> checkNotNull(view.adListener).onAdLoaded() }
        load(BannerType.Normal, object : AdCallback() {
            override fun onAdLoaded() { loaded++; throw error }
            override fun onAdFailedToLoad(adError: LoadAdError?) { failures++ }
        })
        assertEquals(1, loaded)
        assertEquals(0, failures)
        assertEquals(View.VISIBLE, container.visibility)
        assertFalse(shimmer.isShimmerStarted)
    }

    private fun load(type: BannerType, callback: AdCallback) = BannerEngine.load(
        controller.get(), "test-banner", container, shimmer, type, callback,
    )

    private fun failureCallback(error: RuntimeException? = null) = object : AdCallback() {
        override fun onAdFailedToLoad(adError: LoadAdError?) {
            failures++
            if (error != null) throw error
        }
    }

    private fun assertFailedSlot() {
        assertEquals(1, failures)
        assertEquals(View.GONE, container.visibility)
        assertEquals(View.GONE, shimmer.visibility)
        assertFalse(shimmer.isShimmerStarted)
    }
}

@Implements(value = BaseAdView::class, isInAndroidSdk = false)
class ThrowingBannerVendorShadow : org.robolectric.shadows.ShadowViewGroup() {
    @RealObject private lateinit var actual: BaseAdView
    private var listener: AdListener? = null
    private var size: AdSize? = null
    private var unit: String? = null

    @Implementation fun setAdListener(value: AdListener?) { listener = value }
    @Implementation fun getAdListener(): AdListener? = listener
    @Implementation fun setAdSize(value: AdSize) { size = value }
    @Implementation fun getAdSize(): AdSize? = size
    @Implementation fun setAdUnitId(value: String) { unit = value }
    @Implementation fun getAdUnitId(): String? = unit
    @Implementation fun loadAd(request: AdRequest) { dispatch(actual) }
    @Implementation fun setOnPaidEventListener(value: OnPaidEventListener?) {}
    @Implementation fun getResponseInfo(): ResponseInfo = ResponseInfo.zzc(null)
    @Implementation fun destroy() { destroyedViews += actual }

    companion object {
        var dispatch: (BaseAdView) -> Unit = {}
        val destroyedViews = mutableListOf<BaseAdView>()
    }
}
