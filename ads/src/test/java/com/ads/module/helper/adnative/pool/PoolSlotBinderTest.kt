package com.ads.module.helper.adnative.pool

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.ads.module.R
import com.ads.module.ads.wrapper.ApNativeAd
import com.ads.module.config.settings.AdBehavior
import com.ads.module.helper.adnative.NativeAdStyle
import com.ads.module.helper.adnative.NativeComponent
import com.ads.module.helper.adnative.pool.PoolSlotBinder.Outcome
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [PoolNativeAdViewShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class PoolSlotBinderTest {
    private lateinit var controller: ActivityController<Activity>
    private lateinit var root: FrameLayout
    private lateinit var container: FrameLayout
    private val slotLayout = R.layout.custom_native_admob_medium

    @Before fun setUp() {
        AdBehavior.document.install(null, null)
        setPremium(false)
        controller = Robolectric.buildActivity(Activity::class.java).setup()
        val themed = ContextThemeWrapper(controller.get(), R.style.AppTheme)
        root = FrameLayout(themed)
        container = newContainer()
        root.addView(container)
        controller.get().setContentView(root)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @After fun tearDown() {
        AdBehavior.document.install(null, null)
        setPremium(false)
        controller.pause().stop().destroy()
    }

    private fun newContainer() = FrameLayout(root.context)

    private fun binder(
        destroyOnTeardown: Boolean = true,
        style: NativeAdStyle? = null,
        into: FrameLayout = container,
    ) = PoolSlotBinder(into, slotLayout, { style }, destroyOnTeardown)

    private fun ad(body: String? = null) =
        ApNativeAd(R.layout.custom_native_admob_free_size, PoolVendorAd(body))

    private fun shown(ad: ApNativeAd, lender: String = "language1") = PoolSelection(lender, ad, true)
    private val shimmer = PoolSelection.shimmer<ApNativeAd>()
    private val hidden = PoolSelection.hidden<ApNativeAd>()

    private fun adView(of: FrameLayout = container) = of.getChildAt(0) as NativeAdView
    private fun skeleton() = container.getChildAt(0) as ShimmerFrameLayout
    private val NativeAdView.gma get() = Shadow.extract<PoolNativeAdViewShadow>(this)

    @Test fun `every new value binds into a fresh view and the old view is not destroyed`() {
        val binder = binder()
        val first = ad()
        val second = ad()
        assertNull(binder.boundAd())

        assertEquals(Outcome.BOUND_NEW, binder.render(shown(first)))
        val firstView = adView()
        assertSame(first.admobNativeAd, firstView.gma.registered)
        assertSame(first, binder.boundAd())

        assertEquals(Outcome.BOUND_NEW, binder.render(shown(second, lender = "language2")))
        val secondView = adView()
        assertNotSame(firstView, secondView)
        assertEquals(1, container.childCount)
        assertEquals(View.VISIBLE, container.visibility)
        assertSame(second, binder.boundAd())
        assertNull(firstView.parent)
        assertEquals("DashDrama swaps without destroy", 0, firstView.gma.destroyCount)
        assertEquals(0, (first.admobNativeAd as PoolVendorAd).destroyed)
    }

    @Test fun `a duplicate value of the bound ad is skipped until a fresh render is forced`() {
        val binder = binder()
        val ad = ad()
        binder.render(shown(ad))
        val view = adView()
        assertEquals(Outcome.BOUND_SAME, binder.render(shown(ad)))
        assertSame(view, adView())

        binder.forceFreshOnNextRender()
        assertEquals("A re-collected replay inflates again", Outcome.BOUND_NEW, binder.render(shown(ad)))
        val replayed = adView()
        assertNotSame(view, replayed)
        assertSame(ad.admobNativeAd, replayed.gma.registered)
        assertEquals(0, view.gma.destroyCount)

        assertEquals("The force applies once", Outcome.BOUND_SAME, binder.render(shown(ad)))
        assertSame(replayed, adView())
    }

    @Test fun `the slot layout is inflated, not the ad's own layout`() {
        val ad = ad()
        binder().render(shown(ad))
        val view = adView()
        assertNotNull(view.findViewById(R.id.ad_container))
        assertNull("custom_native_admob_free_size carries ad_media", view.findViewById(R.id.ad_media))
        assertEquals(R.layout.custom_native_admob_free_size, ad.layoutCustomNative)
        assertEquals("Pool test ad", view.findViewById<TextView>(R.id.ad_headline).text.toString())
    }

    @Test fun `the style lands after populate and the Ad badge stays visible`() {
        binder().render(shown(ad(body = "Body copy")))
        assertEquals("Populate shows a present body", View.VISIBLE, adView().findViewById<View>(R.id.ad_body).visibility)

        val style = NativeAdStyle(
            components = listOf(NativeComponent.ICON_HEADLINE, NativeComponent.CTA),
            ctaBackgroundColor = Color.BLUE,
        )
        val styled = newContainer().also { root.addView(it) }
        binder(style = style, into = styled).render(shown(ad(body = "Body copy")))
        val view = adView(styled)
        // The styler detaches an excluded block, so read it through the registered asset.
        val body = checkNotNull(view.bodyView)
        assertEquals("An excluded block stays hidden although populate showed it", View.GONE, body.visibility)
        assertNull(view.findViewById(R.id.ad_body))
        val cta = view.findViewById<View>(R.id.ad_call_to_action)
        assertEquals(View.VISIBLE, cta.visibility)
        assertEquals(Color.BLUE, (cta.background as GradientDrawable).color?.defaultColor)
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.ad_icon).visibility)
    }

    @Test fun `the skeleton is built lazily, started explicitly and reused while it is the container's child`() {
        val binder = binder()
        assertEquals("Nothing is built before a shimmer value", 0, container.childCount)

        assertEquals(Outcome.SHIMMER, binder.render(shimmer))
        val first = skeleton()
        assertTrue(first.isShimmerStarted)
        assertEquals(View.VISIBLE, container.visibility)
        assertEquals(View.VISIBLE, first.findViewById<View>(R.id.ad_icon).visibility)
        assertNull(binder.boundAd())

        assertEquals(Outcome.SHIMMER, binder.render(shimmer))
        assertSame(first, skeleton())

        container.removeAllViews()
        binder.render(shimmer)
        val rebuilt = skeleton()
        assertNotSame("A skeleton removed by someone else is not reused", first, rebuilt)

        binder.render(shown(ad()))
        assertFalse(rebuilt.isShimmerStarted)
        binder.render(shimmer)
        assertNotSame("A bind detaches the skeleton for good", rebuilt, skeleton())
        assertNull(binder.boundAd())
    }

    @Test fun `the skeleton never starts on its own`() {
        val binder = binder()
        binder.render(shimmer)
        val skeleton = skeleton()
        binder.render(hidden)
        assertFalse(skeleton.isShimmerStarted)
        root.removeView(container)
        root.addView(container)
        container.visibility = View.VISIBLE
        assertFalse("autoStart stays off, so a re-attach does not animate", skeleton.isShimmerStarted)
    }

    @Test fun `no skeleton is built while ads cannot show`() {
        setPremium(true)
        val binder = binder()
        assertEquals(Outcome.HIDDEN, binder.render(shimmer))
        assertEquals(0, container.childCount)
        assertEquals(View.GONE, container.visibility)

        setPremium(false)
        AdBehavior.document.install("""{"global":{"ads_enabled":false}}""", null)
        assertEquals(Outcome.HIDDEN, binder.render(shimmer))
        assertEquals(0, container.childCount)

        AdBehavior.document.install(null, null)
        assertEquals(Outcome.SHIMMER, binder.render(shimmer))
    }

    @Test fun `a hidden value stops the shimmer and leaves the views in place`() {
        val binder = binder()
        binder.render(shimmer)
        val skeleton = skeleton()
        assertEquals(Outcome.HIDDEN, binder.render(hidden))
        assertFalse(skeleton.isShimmerStarted)
        assertEquals(View.GONE, container.visibility)
        assertSame(skeleton, container.getChildAt(0))

        val ad = ad()
        binder.render(shown(ad))
        val view = adView()
        binder.render(hidden)
        assertEquals(View.GONE, container.visibility)
        assertSame(view, adView())
        assertSame(ad, binder.boundAd())
        assertEquals(Outcome.BOUND_SAME, binder.render(shown(ad)))
        assertEquals(View.VISIBLE, container.visibility)
    }

    @Test fun `a slot whose ad another view registered since has lost its registration until it binds again`() {
        val ad = ad()
        val other = newContainer().also { root.addView(it) }
        val first = binder()
        val second = binder(into = other)
        assertFalse("Nothing bound", first.lostRegistration())
        first.render(shown(ad))
        assertFalse(first.lostRegistration())

        second.render(shown(ad, lender = "step_ob1"))
        assertTrue(first.lostRegistration())
        assertFalse(second.lostRegistration())
        assertEquals("The same ad is no rebind", Outcome.BOUND_SAME, first.render(shown(ad)))
        assertTrue(first.lostRegistration())

        first.forceFreshOnNextRender()
        assertEquals(Outcome.BOUND_NEW, first.render(shown(ad)))
        assertFalse(first.lostRegistration())
        assertTrue(second.lostRegistration())
        second.render(hidden)
        assertFalse("A hidden slot shows nothing to report from", second.lostRegistration())
    }

    @Test fun `only the view an ad was last registered to holds its registration`() {
        val ad = ad()
        val other = newContainer().also { root.addView(it) }
        val first = binder()
        val second = binder(into = other)
        assertFalse("Nothing bound", first.holdsRegistration())
        first.render(shown(ad))
        assertTrue(first.holdsRegistration())

        second.render(shown(ad, lender = "step_ob1"))
        assertFalse(first.holdsRegistration())
        assertTrue(second.holdsRegistration())
        second.render(hidden)
        assertTrue("Hiding keeps the view and its registration", second.holdsRegistration())
        assertEquals(Outcome.SHIMMER, second.render(shimmer))
        assertFalse("A skeleton replaced the view", second.holdsRegistration())
    }

    @Test fun `teardown destroys this slot's view only when it is the ad's last registrant`() {
        val ad = ad()
        val other = newContainer().also { root.addView(it) }
        val first = binder()
        val second = binder(into = other)
        first.render(shown(ad))
        val firstView = adView()
        second.render(shown(ad, lender = "step_ob1"))
        val secondView = adView(other)

        first.teardown()
        assertEquals("Another slot registered the ad since", 0, firstView.gma.destroyCount)
        second.teardown()
        assertEquals(1, secondView.gma.destroyCount)
        assertNotNull("The ad itself is never destroyed", ad.admobNativeAd)
        assertEquals(0, (ad.admobNativeAd as PoolVendorAd).destroyed)
    }

    @Test fun `teardown also destroys a swapped-out view that is still its ad's last registrant`() {
        val binder = binder()
        val first = ad()
        val second = ad()
        val third = ad()
        binder.render(shown(first))
        val firstView = adView()
        binder.render(shown(second, lender = "language2"))
        val secondView = adView()
        binder.render(shown(third, lender = "step_ob1"))
        val thirdView = adView()
        val otherContainer = newContainer().also { root.addView(it) }
        val other = binder(into = otherContainer)
        other.render(shown(second, lender = "language2"))
        val borrowedView = adView(otherContainer)

        binder.teardown()
        assertEquals("Detached by a later bind, still the first ad's registrant", 1, firstView.gma.destroyCount)
        assertEquals("Another slot registered the second ad since", 0, secondView.gma.destroyCount)
        assertEquals(1, thirdView.gma.destroyCount)
        assertEquals(0, borrowedView.gma.destroyCount)
        listOf(first, second, third).forEach { assertEquals(0, (it.admobNativeAd as PoolVendorAd).destroyed) }

        binder.teardown()
        assertEquals("Destroyed once", 1, firstView.gma.destroyCount)
        other.teardown()
        assertEquals(1, borrowedView.gma.destroyCount)
    }

    @Test fun `teardown leaves the view alone when the toggle is off and stops the shimmer`() {
        val kept = binder(destroyOnTeardown = false)
        kept.render(shown(ad()))
        val view = adView()
        kept.teardown()
        assertEquals(0, view.gma.destroyCount)

        val other = newContainer().also { root.addView(it) }
        val shimmering = binder(into = other)
        shimmering.render(shimmer)
        shimmering.teardown()
        assertFalse((other.getChildAt(0) as ShimmerFrameLayout).isShimmerStarted)
    }

    @Test fun `an ad without a vendor ad is ignored`() {
        val binder = binder()
        val ad = ad()
        binder.render(shown(ad))
        val view = adView()

        val empty = ApNativeAd(slotLayout, null as NativeAd?)
        assertEquals(Outcome.IGNORED, binder.render(shown(empty)))
        val released = ad().also { it.destroy() }
        assertEquals(Outcome.IGNORED, binder.render(shown(released)))
        assertSame(view, adView())
        assertSame(ad, binder.boundAd())
        assertEquals(View.VISIBLE, container.visibility)
    }

    @Test fun `a slot layout with no NativeAdView is bound through one`() {
        val plain = PoolSlotBinder(container, R.layout.layout_native_medium, { null }, true)
        val ad = ad()
        assertEquals(Outcome.BOUND_NEW, plain.render(shown(ad)))
        val view = adView()
        assertSame(ad.admobNativeAd, view.gma.registered)
        assertNotNull(view.findViewById(R.id.fl_adplaceholder))
    }
}
