package io.onboardkit.ads

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ads.module.admob.AppOpenManager
import com.ads.module.consent.ConsentCenter
import com.ads.module.helper.Entitlement
import com.ads.module.helper.EntitlementSource
import io.onboardkit.OnboardingSdk
import io.onboardkit.ads.erain.ERainAdProvider
import io.onboardkit.config.AdsConfig
import io.onboardkit.config.InterstitialAdUnit
import io.onboardkit.config.onboardKitConfig
import io.onboardkit.remote.RemoteConfigSyncer
import io.onboardkit.remote.RemoteFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real SDK install/provider -> shared AppOpen policy. One selected phase per fresh process.
 * No GMA request/show is made: this proves wiring/eligibility, not an impression or OS click return.
 * Requires the real AppOpenResumeDeviceActivity manifest fixture from the RES01/02 device patch.
 */
@RunWith(AndroidJUnit4::class)
class OnboardResumePolicyDeviceTest {
    @Test
    fun installedPolicySeparatesSharedEligibilityFromOpenSlotEligibility() {
        val phase = InstrumentationRegistry.getArguments().getString("resumePolicyPhase") ?: "depth"
        require(phase in setOf("depth", "click", "open_off", "missing_open_unit", "master_off", "config_off", "host_off", "consent_off", "premium"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = ApplicationProvider.getApplicationContext<Application>()
        val manager = AppOpenManager.getInstance()
        val source = object : EntitlementSource {
            override fun isPremium(context: Context) = phase == "premium"
        }
        instrumentation.runOnMainSync {
            assertFalse("Fresh process required", OnboardingSdk.isReady())
            manager.disableAppResume()
            manager.init(app, "")
            manager.setDisableAdResumeByClickAction(false)
            manager.disableAppResumeWithActivity(AppOpenResumeDeviceActivity::class.java)
            ConsentCenter.setHostConsent(phase != "consent_off", false)
            Entitlement.install(source) // Public host billing signal, not a replacement SDK manager.
            // Public snapshot/cache API seeds the installed SDK before it reads its immutable flags.
            RemoteConfigSyncer(app) { null }.applySnapshot(RemoteFlags(
                enableAllAds = phase != "master_off",
                adsAppResume = phase != "open_off",
            ))
            OnboardingSdk.install(app) {
                adProvider = ERainAdProvider()
                trackkitAutoTracking(false)
            }
            OnboardingSdk.configure(onboardKitConfig {
                ads = AdsConfig(
                    enabled = phase != "config_off",
                    appResume = if (phase == "missing_open_unit") null else InterstitialAdUnit(TEST_UNIT),
                )
            }.getOrThrow()).getOrThrow()
            OnboardingSdk.setCanRequestAds(phase != "host_off")
        }
        try {
            ActivityScenario.launch<AppOpenResumeDeviceActivity>(
                Intent(app, AppOpenResumeDeviceActivity::class.java),
            ).use { scenario ->
                scenario.onActivity { activity ->
                    val policy = OnboardingSdk.appResume()
                    when (phase) {
                        "depth" -> {
                            assertNull(manager.resumeSkipReasonFor(activity))
                            policy.suppress()
                            policy.suppress()
                            try {
                                repeat(2) { assertEquals("suppressed_by_flow", manager.resumeSkipReasonFor(activity)) }
                                policy.release()
                                assertEquals("suppressed_by_flow", manager.resumeSkipReasonFor(activity))
                            } finally { policy.release() }
                            assertNull(manager.resumeSkipReasonFor(activity))
                        }
                        "click" -> {
                            policy.onAdClicked()
                            repeat(2) {
                                assertEquals("returning_from_ad_click", manager.resumeSkipReasonFor(activity))
                                assertEquals(AdSkipReason.RETURNING_FROM_AD_CLICK, policy.skipReason(activity))
                            }
                        }
                        "open_off", "missing_open_unit" -> {
                            assertNull("WELCOME eligibility must not require an OPEN slot", manager.resumeSkipReasonFor(activity))
                            val expected = if (phase == "open_off") AdSkipReason.PLACEMENT_OFF_BY_REMOTE else AdSkipReason.NO_AD_UNIT
                            assertEquals(expected, policy.skipReason(activity))
                        }
                        else -> {
                            val expected = when (phase) {
                                "master_off" -> "ads_off_remote"
                                "config_off" -> "ads_off_config"
                                "premium" -> "purchased" // The ads module's existing Entitlement key.
                                else -> "consent_not_granted"
                            }
                            repeat(2) { assertEquals(expected, manager.resumeSkipReasonFor(activity)) }
                        }
                    }
                }
            }
        } finally {
            instrumentation.runOnMainSync {
                manager.disableAppResume()
                manager.setResumeSkipPolicy(null)
                manager.setDisableAdResumeByClickAction(false)
                manager.enableAppResumeWithActivity(AppOpenResumeDeviceActivity::class.java)
                ConsentCenter.clearHostConsent()
            }
        }
    }

    private companion object { const val TEST_UNIT = "ca-app-pub-3940256099942544/9257395921" }
}
