package com.ads.module.event

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.adjust.sdk.Adjust
import com.adjust.sdk.AdjustAdRevenue
import com.ads.module.admob.Admob
import com.ads.module.ads.ERainAd
import com.ads.module.config.AdjustConfig
import com.ads.module.config.ERainAdConfig
import com.ads.module.funtion.AdType
import com.ads.module.funtion.AdmobHelper
import com.ads.module.tracking.AdFormatRegistry
import com.ads.module.tracking.AdLoadContext
import com.facebook.FacebookSdk
import com.google.android.gms.ads.AdValue
import com.google.android.gms.ads.MobileAds
import io.trackkit.AdFormat
import io.trackkit.AdImpression
import io.trackkit.PlacementRegistry
import io.trackkit.TrackSink
import io.trackkit.Tracker
import io.trackkit.TrackerConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Real bridge/config/cap/Tracker; only GMA, Facebook and Adjust SDK boundaries are replaced. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class ERainCapturedEventContextTest {
    private lateinit var app: Application
    private lateinit var adjust: MockedStatic<Adjust>
    private lateinit var adjustConfig: AdjustConfig
    private val revenues = mutableListOf<AdImpression>()
    private val adjustRevenues = mutableListOf<AdjustAdRevenue>()
    private val events = mutableListOf<Pair<String, Map<String, Any?>>>()

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        // AdjustConfig validates checkCallingOrSelfPermission; Robolectric keeps that grant
        // separately from the merged manifest's declared normal INTERNET permission.
        shadowOf(app).grantPermissions(Manifest.permission.INTERNET)
        assertEquals(PackageManager.PERMISSION_GRANTED,
            app.checkCallingOrSelfPermission(Manifest.permission.INTERNET))
        adjust = Mockito.mockStatic(Adjust::class.java) { invocation ->
            if (invocation.method.name == "trackAdRevenue") {
                adjustRevenues += invocation.getArgument<AdjustAdRevenue>(0)
            }
            null
        }
        adjustConfig = AdjustConfig(true, "abcdefghijkl")
        Mockito.mockStatic(MobileAds::class.java).use {
            Mockito.mockStatic(FacebookSdk::class.java).use {
                ERainAd.getInstance().init(app, ERainAdConfig(app).apply {
                    setFacebookClientToken("test-client-token")
                    setAdjustConfig(this@ERainCapturedEventContextTest.adjustConfig)
                })
            }
        }
        assertTrue("Real public initialization must enable the Adjust relay", ERainAdjust.isEnabled())
        Admob.getInstance().setMaxClickAdsPerDay(10)
        PlacementRegistry.clear()
        AdFormatRegistry.clear()
        Tracker.resetForTesting()
        Tracker.addSink(object : TrackSink {
            override val id = "captured-event-context"
            override fun onEvent(name: String, params: Map<String, Any?>) { events += name to params }
            override fun onAdRevenue(impression: AdImpression) { revenues += impression }
        })
        Tracker.install(app, TrackerConfig(strictValidation = true, logLevel = 0))
    }

    @After
    fun tearDown() {
        Admob.getInstance().setMaxClickAdsPerDay(0)
        if (::adjustConfig.isInitialized) adjustConfig.setEnableAdjust(false)
        if (::adjust.isInitialized) adjust.close()
        PlacementRegistry.clear()
        AdFormatRegistry.clear()
        Tracker.resetForTesting()
    }

    @Test
    fun `paid callbacks preserve captured placement and vendor values through both revenue channels`() {
        val captured = AdLoadContext("app_resume", AdFormat.APP_OPEN, false)
        PlacementRegistry.register(UNIT, "replacement-screen")
        AdFormatRegistry.register(UNIT, AdFormat.NATIVE)
        val first = value(1_250_000L, "EUR", 2)
        val second = value(2_750_000L, "JPY", 3)

        ERainLogEventManager.logPaidAdImpression(app, first, UNIT, "network-a", AdType.APP_OPEN, captured)
        ERainLogEventManager.logPaidAdImpression(app, second, UNIT, "network-b", AdType.APP_OPEN, captured)

        assertEquals(2, revenues.size)
        assertEquals(listOf("app_resume", "app_resume"), revenues.map { it.placement })
        assertEquals(listOf(AdFormat.APP_OPEN, AdFormat.APP_OPEN), revenues.map { it.format })
        assertEquals(listOf(UNIT, UNIT), revenues.map { it.adUnitId })
        assertEquals(listOf(1_250_000L, 2_750_000L), revenues.map { it.valueMicros })
        assertEquals(listOf("EUR", "JPY"), revenues.map { it.currency })
        assertEquals(listOf(2, 3), revenues.map { it.precision })
        assertEquals(listOf("network-a", "network-b"), revenues.map { it.network })
        assertEquals(2, adjustRevenues.size)
        assertEquals(listOf("app_resume", "app_resume"), adjustRevenues.map { it.adRevenuePlacement })
        assertEquals(listOf(UNIT, UNIT), adjustRevenues.map { it.adRevenueUnit })
        assertEquals(listOf(1.25, 2.75), adjustRevenues.map { it.revenue })
        assertEquals(listOf("EUR", "JPY"), adjustRevenues.map { it.currency })
        assertEquals(listOf("network-a", "network-b"), adjustRevenues.map { it.adRevenueNetwork })
    }

    @Test
    fun `captured click attribution survives remapping and each real click increments the cap once`() {
        val captured = AdLoadContext("app_resume", AdFormat.APP_OPEN, false)
        PlacementRegistry.register(UNIT, "replacement-screen")
        AdFormatRegistry.register(UNIT, AdFormat.NATIVE)
        val before = AdmobHelper.getNumClickAdsPerDay(app, UNIT)

        ERainLogEventManager.logClickAdsEvent(app, UNIT, captured)
        ERainLogEventManager.logClickAdsEvent(app, UNIT, captured)
        assertEquals(before + 2, AdmobHelper.getNumClickAdsPerDay(app, UNIT))
        val capturedClicks = events.filter { it.first == "ad_click" }.map { it.second }
        assertEquals(2, capturedClicks.size)
        capturedClicks.forEach {
            assertEquals("app_resume", it["placement"])
            assertEquals("app_open", it["ad_format"])
            assertEquals(UNIT, it["ad_unit_id"])
        }

        ERainLogEventManager.logClickAdsEvent(app, UNIT)
        assertEquals(before + 3, AdmobHelper.getNumClickAdsPerDay(app, UNIT))
        val clicks = events.filter { it.first == "ad_click" }.map { it.second }
        assertEquals(3, clicks.size)
        assertEquals("replacement-screen", clicks.last()["placement"])
        assertEquals("native", clicks.last()["ad_format"])
        assertTrue(adjustRevenues.isEmpty())
    }

    @Test
    fun `legacy paid method keeps registry and AdType resolution while null values remain silent`() {
        PlacementRegistry.register(UNIT, "current-screen")
        AdFormatRegistry.register(UNIT, AdFormat.NATIVE)
        ERainLogEventManager.logPaidAdImpression(app, value(17_000L, "USD", 1), UNIT,
            "legacy-network", AdType.INTERSTITIAL)
        ERainLogEventManager.logPaidAdImpression(app, null, UNIT, "ignored", AdType.APP_OPEN,
            AdLoadContext("app_resume", AdFormat.APP_OPEN))

        assertEquals(1, revenues.size)
        assertEquals("current-screen", revenues.single().placement)
        assertEquals(AdFormat.INTERSTITIAL, revenues.single().format)
        assertEquals(17_000L, revenues.single().valueMicros)
        assertEquals(1, adjustRevenues.size)
        assertEquals("current-screen", adjustRevenues.single().adRevenuePlacement)
        assertEquals(0.017, requireNotNull(adjustRevenues.single().revenue), 0.000001)
    }

    private fun value(micros: Long, currency: String, precision: Int): AdValue =
        Mockito.mock(AdValue::class.java).also {
            Mockito.`when`(it.valueMicros).thenReturn(micros)
            Mockito.`when`(it.currencyCode).thenReturn(currency)
            Mockito.`when`(it.precisionType).thenReturn(precision)
        }

    private companion object { const val UNIT = "captured-context-unit" }
}
