package com.ads.module.admob

import android.content.Context
import android.view.View
import android.widget.ImageView
import android.widget.RatingBar
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.ads.module.R
import com.ads.module.engine.NativeEngine
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdView
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.ByteArrayOutputStream
import java.io.PrintStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NativeAssetBindingTest {
    private lateinit var context: Context
    private lateinit var nativeAd: NativeAd

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        nativeAd = mock(NativeAd::class.java)
        doReturn("Headline").`when`(nativeAd).headline
        doReturn("Body").`when`(nativeAd).body
        doReturn("Open").`when`(nativeAd).callToAction
        doReturn("Advertiser").`when`(nativeAd).advertiser
    }

    @Test
    fun `partial partner layout binds available assets and registers ad without exception logging`() {
        val headline = TextView(context)
        val body = TextView(context)
        val callToAction = TextView(context)
        val view = adView(mapOf(
            R.id.ad_headline to headline,
            R.id.ad_body to body,
            R.id.ad_call_to_action to callToAction,
        ))

        assertNoExceptionLogging { NativeEngine.populate(nativeAd, view) }

        assertEquals("Headline", headline.text.toString())
        assertEquals("Body", body.text.toString())
        assertEquals("Open", callToAction.text.toString())
        assertEquals(View.VISIBLE, body.visibility)
        verify(view).setNativeAd(nativeAd)
    }

    @Test
    fun `mistyped optional assets do not prevent remaining assets or ad registration`() {
        val headline = TextView(context)
        val advertiser = TextView(context)
        val view = adView(mapOf(
            R.id.ad_headline to headline,
            R.id.ad_body to ImageView(context),
            R.id.ad_call_to_action to View(context),
            R.id.ad_price to ImageView(context),
            R.id.ad_stars to TextView(context),
            R.id.ad_advertiser to advertiser,
        ))
        doReturn("$1").`when`(nativeAd).price
        doReturn(4.5).`when`(nativeAd).starRating

        assertNoExceptionLogging { NativeEngine.populate(nativeAd, view) }

        assertEquals("Headline", headline.text.toString())
        assertEquals("Advertiser", advertiser.text.toString())
        verify(view).setNativeAd(nativeAd)
    }

    @Test
    fun `absent creative assets preserve reserved text space and remove icon space`() {
        val body = TextView(context)
        val price = TextView(context)
        val stars = RatingBar(context)
        val icon = ImageView(context)
        val view = adView(mapOf(
            R.id.ad_headline to TextView(context),
            R.id.ad_body to body,
            R.id.ad_price to price,
            R.id.ad_stars to stars,
            R.id.ad_app_icon to icon,
        ))
        doReturn(null).`when`(nativeAd).body
        doReturn(null).`when`(nativeAd).starRating

        assertNoExceptionLogging { NativeEngine.populate(nativeAd, view) }

        assertEquals(View.INVISIBLE, body.visibility)
        assertEquals(View.INVISIBLE, price.visibility)
        assertEquals(View.INVISIBLE, stars.visibility)
        assertEquals(View.GONE, icon.visibility)
        verify(view).setNativeAd(nativeAd)
    }

    @Test
    fun `generic asset views retain GMA registration and absent asset visibility`() {
        val callToAction = View(context).apply { visibility = View.INVISIBLE }
        val body = View(context)
        val icon = View(context)
        val stars = View(context)
        val view = adView(mapOf(
            R.id.ad_headline to TextView(context),
            R.id.ad_call_to_action to callToAction,
            R.id.ad_body to body,
            R.id.ad_app_icon to icon,
            R.id.ad_stars to stars,
        ))
        doReturn(null).`when`(nativeAd).body
        doReturn(null).`when`(nativeAd).starRating

        assertNoExceptionLogging { NativeEngine.populate(nativeAd, view) }

        verify(view).setCallToActionView(callToAction)
        verify(view).setBodyView(body)
        verify(view).setIconView(icon)
        verify(view).setStarRatingView(stars)
        assertEquals(View.VISIBLE, callToAction.visibility)
        assertEquals(View.INVISIBLE, body.visibility)
        assertEquals(View.INVISIBLE, stars.visibility)
        assertEquals(View.GONE, icon.visibility)
        verify(view).setNativeAd(nativeAd)
    }

    @Test
    fun `exceptional vendor asset does not prevent later assets or ad registration`() {
        val headline = TextView(context)
        val callToAction = TextView(context)
        val advertiser = TextView(context)
        val view = adView(mapOf(
            R.id.ad_headline to headline,
            R.id.ad_body to TextView(context),
            R.id.ad_call_to_action to callToAction,
            R.id.ad_advertiser to advertiser,
        ))
        doThrow(IllegalStateException("Vendor body unavailable")).`when`(nativeAd).body

        NativeEngine.populate(nativeAd, view)

        assertEquals("Headline", headline.text.toString())
        assertEquals("Open", callToAction.text.toString())
        assertEquals("Advertiser", advertiser.text.toString())
        verify(view).setNativeAd(nativeAd)
    }

    // Replace only GMA's asset storage; real Android views exercise text and visibility binding.
    private fun adView(assets: Map<Int, View>): NativeAdView =
        mock(NativeAdView::class.java).also { view ->
            doAnswer { assets[it.getArgument<Int>(0)] }.`when`(view).findViewById<View>(anyInt())
            doReturn(assets[R.id.ad_headline]).`when`(view).headlineView
            doReturn(assets[R.id.ad_body]).`when`(view).bodyView
            doReturn(assets[R.id.ad_call_to_action]).`when`(view).callToActionView
            doReturn(assets[R.id.ad_app_icon]).`when`(view).iconView
            doReturn(assets[R.id.ad_price]).`when`(view).priceView
            doReturn(assets[R.id.ad_stars]).`when`(view).starRatingView
            doReturn(assets[R.id.ad_advertiser]).`when`(view).advertiserView
        }

    private fun assertNoExceptionLogging(bind: () -> Unit) {
        val previous = System.err
        val output = ByteArrayOutputStream()
        val logCount = ShadowLog.getLogs().size
        try {
            System.setErr(PrintStream(output))
            bind()
        } finally {
            System.setErr(previous)
        }
        assertEquals("Optional assets must not construct and print exceptions", "", output.toString())
        val failures = ShadowLog.getLogs().drop(logCount).filter {
            it.throwable != null || it.msg == "Native asset binding failed"
        }
        assertEquals("Optional assets must not log binding failures", emptyList<Any>(), failures)
    }
}
