package com.ads.module.event

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adjust.sdk.Adjust
import com.adjust.sdk.AdjustAdRevenue
import com.adjust.sdk.AdjustEvent
import com.ads.module.ads.ERainAd
import com.ads.module.config.AdjustConfig
import com.ads.module.config.ERainAdConfig
import com.ads.module.funtion.AdType
import com.ads.module.helper.interstitial.Int02Application
import com.ads.module.helper.interstitial.Int02MobileAdsShadow
import com.facebook.FacebookSdk
import com.google.android.gms.ads.AdValue
import io.trackkit.PlacementRegistry
import io.trackkit.Tracker
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockedStatic
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Int02Application::class, shadows = [Int02MobileAdsShadow::class])
class PaidImpressionAdjustTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val adRevenues = mutableListOf<AdjustAdRevenue>()
    private val events = mutableListOf<AdjustEvent>()
    private lateinit var adjust: MockedStatic<Adjust>
    private lateinit var config: AdjustConfig
    private var wasInitialized = false
    private val initializedField = ERainAdjust::class.java.getDeclaredField("initialized").apply {
        isAccessible = true
    }

    @Before fun setUp() {
        wasInitialized = initializedField.getBoolean(null)
        Tracker.resetForTesting()
        PlacementRegistry.clear()
        adjust = mockStatic(Adjust::class.java) { invocation ->
            when (invocation.method.name) {
                "trackAdRevenue" -> adRevenues += invocation.getArgument<AdjustAdRevenue>(0)
                "trackEvent" -> events += invocation.getArgument<AdjustEvent>(0)
            }
            null
        }
        config = AdjustConfig(false).apply {
            eventAdImpression = "imp123"
            eventNamePurchase = "iap123"
        }
        mockStatic(FacebookSdk::class.java).use {
            ERainAd.getInstance().init(app, ERainAdConfig(app).apply { adjustConfig = config })
        }
        // Exercise the production relay with Adjust enabled; only the SDK transport is mocked.
        config.setEnableAdjust(true)
        ERainAdjust.markInitialized()
        PlacementRegistry.register("paid-test-unit", "paid-test-placement")
    }

    @After fun tearDown() {
        config.setEnableAdjust(false)
        initializedField.setBoolean(null, wasInitialized)
        Tracker.resetForTesting()
        PlacementRegistry.clear()
        adjust.close()
    }

    @Test fun `each format sends ad revenue and the impression event with its value`() {
        for (format in AdType.values()) {
            adRevenues.clear()
            events.clear()
            paidImpression(format)
            val revenue = adRevenues.single()
            assertEquals(1.5, revenue.revenue!!, 0.0)
            assertEquals("EUR", revenue.currency)
            assertEquals(AdjustConfig.AD_REVENUE_ADMOB, revenue.source)
            assertEquals("paid-test-unit", revenue.adRevenueUnit)
            assertEquals("adapter", revenue.adRevenueNetwork)
            assertEquals("paid-test-placement", revenue.adRevenuePlacement)
            val event = events.single()
            assertEquals("imp123", event.eventToken)
            assertEquals("$format event revenue", 1.5, event.revenue!!, 0.0)
            assertEquals("EUR", event.currency)
        }
    }

    @Test fun `blank impression token keeps ad revenue without an extra event`() {
        config.eventAdImpression = ""
        paidImpression()
        assertEquals(1, adRevenues.size)
        assertTrue(events.isEmpty())
    }

    @Test fun `disabled Adjust sends neither ad revenue nor impression event`() {
        config.setEnableAdjust(false)
        paidImpression()
        assertTrue(adRevenues.isEmpty())
        assertTrue(events.isEmpty())
    }

    @Test fun `null paid value sends nothing`() {
        ERainLogEventManager.logPaidAdImpression(app, null, "paid-test-unit", "adapter", AdType.APP_OPEN)
        ERainLogEventManager.logPaidAdjustWithToken(null, "paid-test-unit")
        assertTrue(adRevenues.isEmpty())
        assertTrue(events.isEmpty())
    }

    @Test fun `legacy token helper sends the impression value`() {
        ERainLogEventManager.logPaidAdjustWithToken(paidValue(), "paid-test-unit")
        assertTrue(adRevenues.isEmpty())
        assertEquals("imp123", events.single().eventToken)
        assertEquals(1.5, events.single().revenue!!, 0.0)
        assertEquals("EUR", events.single().currency)
    }

    @Test fun `purchase and explicit revenue events retain their money`() {
        io.trackkit.mmp.MmpTracking.trackPurchaseRevenue(2_500_000.0, "USD")
        MmpTracking.trackRevenue("rev123", 3.5, "EUR")
        assertEquals(listOf("iap123", "rev123"), events.map { it.eventToken })
        assertEquals(listOf(2.5, 3.5), events.map { it.revenue })
        assertEquals(listOf("USD", "EUR"), events.map { it.currency })
        assertTrue(adRevenues.isEmpty())
    }

    private fun paidImpression(format: AdType = AdType.APP_OPEN) {
        ERainLogEventManager.logPaidAdImpression(app, paidValue(), "paid-test-unit", "adapter", format)
    }

    private fun paidValue(): AdValue = mock(AdValue::class.java).also {
        `when`(it.valueMicros).thenReturn(1_500_000L)
        `when`(it.currencyCode).thenReturn("EUR")
    }
}
