package com.ads.module.event

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.ads.module.ads.ERainAd
import com.ads.module.config.AdjustConfig
import com.ads.module.config.ERainAdConfig
import com.ads.module.funtion.AdType
import com.ads.module.helper.interstitial.Int02Application
import com.ads.module.helper.interstitial.Int02MobileAdsShadow
import com.facebook.FacebookSdk
import com.google.android.gms.ads.AdValue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Int02Application::class, shadows = [Int02MobileAdsShadow::class])
class PaidImpressionAdjustTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val revenues = mutableListOf<Pair<String, Double>>()
    private val relay = object : io.trackkit.mmp.MmpTracking.Relay {
        override fun onRevenue(token: String, value: Double, currency: String?) {
            revenues += token to value
        }
    }

    @Before fun setUp() {
        mockStatic(FacebookSdk::class.java).use {
            ERainAd.getInstance().init(app, ERainAdConfig(app).apply {
                adjustConfig = AdjustConfig(false).apply { eventAdImpression = "imp123" }
            })
        }
        io.trackkit.mmp.MmpTracking.addRelay(relay)
    }

    @After fun tearDown() {
        io.trackkit.mmp.MmpTracking.removeRelay(relay)
    }

    @Test fun `every paid impression also sends the Adjust impression token event`() {
        val value = mock(AdValue::class.java)
        `when`(value.valueMicros).thenReturn(1_500_000L)
        `when`(value.currencyCode).thenReturn("USD")
        ERainLogEventManager.logPaidAdImpression(app, value, "resume-unit", "adapter", AdType.APP_OPEN)
        assertEquals(listOf("imp123" to 1.5), revenues)
    }
}
