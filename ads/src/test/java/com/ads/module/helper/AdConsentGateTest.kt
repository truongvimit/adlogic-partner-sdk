package com.ads.module.helper

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ads.module.consent.ConsentCenter
import com.ads.module.helper.interstitial.InterstitialAdManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AdConsentGateTest {
    @After
    fun clearHostConsent() {
        ConsentCenter.setHostConsent(canRequestAds = false, personalized = false)
    }

    @Test
    fun `configured online placement still needs consent authorization`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ConsentCenter.setHostConsent(canRequestAds = false, personalized = false)

        val reason = AdGate.skipReason(context, enabled = true, checkNetwork = false)

        assertEquals("consent_not_granted", reason?.key)
    }

    @Test
    fun `interstitial presentation rechecks revoked authorization`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ConsentCenter.setHostConsent(canRequestAds = true, personalized = false)
        ConsentCenter.setHostConsent(canRequestAds = false, personalized = false)

        val reason = InterstitialAdManager.showSkipReason(context, "revoked-consent")

        assertEquals("consent_not_granted", reason?.key)
    }
}
