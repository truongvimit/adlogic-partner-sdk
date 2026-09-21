package com.ads.module.helper

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ads.module.admob.AppOpenManager
import com.ads.module.consent.ConsentCenter
import com.ads.module.engine.InterstitialEngine
import com.ads.module.engine.NativeEngine
import com.ads.module.engine.RewardEngine
import com.ads.module.funtion.AdCallback
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.appopen.AppOpenAd
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAd
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AdRequestHoldTest {
    @Test fun `nested holds close independently and do not change consent`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ConsentCenter.setHostConsent(true, false)
        val first = AdGate.holdRequests()
        val second = AdGate.holdRequests()
        try {
            assertEquals(AdSkipReason.REQUESTS_HELD, AdGate.skipReason(context, true, checkNetwork = false))
            first.close()
            first.close()
            assertTrue(AdGate.areRequestsHeld())
            assertTrue(ConsentCenter.canRequestAds())
            second.close()
            assertFalse(AdGate.areRequestsHeld())
        } finally {
            first.close(); second.close()
            ConsentCenter.clearHostConsent()
        }
    }

    @Test fun `legacy loaders cannot reach Google SDK while startup requests are held`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val callback = mock(AdCallback::class.java)
        AdGate.holdRequests().use {
            mockStatic(InterstitialAd::class.java).use { inter ->
                mockStatic(RewardedAd::class.java).use { reward ->
                    mockStatic(RewardedInterstitialAd::class.java).use { rewardInter ->
                        mockStatic(AppOpenAd::class.java).use { appOpen ->
                            mockConstruction(AdLoader.Builder::class.java).use { native ->
                                InterstitialEngine.load(context, "inter", callback)
                                RewardEngine.load(context, "reward", callback)
                                NativeEngine.load(context, "native", 0, callback)
                                AppOpenManager.getInstance().fetchAd()
                                inter.verifyNoInteractions()
                                reward.verifyNoInteractions()
                                appOpen.verifyNoInteractions()
                                assertTrue(native.constructed().isEmpty())
                            }
                        }
                    }
                }
            }
        }
        assertFalse(AdGate.areRequestsHeld())
    }
}
