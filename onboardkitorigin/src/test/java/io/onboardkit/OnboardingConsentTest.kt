package io.onboardkit

import com.ads.module.consent.ConsentCenter
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OnboardingConsentTest {
    @After
    fun restoreHostPolicy() {
        OnboardingSdk.setCanRequestAds(true)
        ConsentCenter.setHostConsent(canRequestAds = false, personalized = false)
    }

    @Test
    fun `host enabling onboarding ads cannot manufacture consent authorization`() {
        ConsentCenter.setHostConsent(canRequestAds = false, personalized = false)

        OnboardingSdk.setCanRequestAds(true)

        assertFalse(OnboardingSdk.canRequestAds())
    }

    @Test
    fun `revocation and later permission are read without waiting for an observer`() {
        OnboardingSdk.setCanRequestAds(true)
        ConsentCenter.setHostConsent(canRequestAds = true, personalized = false)
        assertTrue(OnboardingSdk.canRequestAds())

        ConsentCenter.setHostConsent(canRequestAds = false, personalized = false)
        assertFalse(OnboardingSdk.canRequestAds())

        ConsentCenter.setHostConsent(canRequestAds = true, personalized = false)
        assertTrue(OnboardingSdk.canRequestAds())
    }

    @Test
    fun `consent updates cannot undo the host turning onboarding ads off`() {
        OnboardingSdk.setCanRequestAds(false)
        ConsentCenter.setHostConsent(canRequestAds = true, personalized = true)
        assertFalse(OnboardingSdk.canRequestAds())

        ConsentCenter.setHostConsent(canRequestAds = false, personalized = false)
        ConsentCenter.setHostConsent(canRequestAds = true, personalized = false)
        assertFalse(OnboardingSdk.canRequestAds())

        OnboardingSdk.setCanRequestAds(true)
        assertTrue(OnboardingSdk.canRequestAds())
    }
}
