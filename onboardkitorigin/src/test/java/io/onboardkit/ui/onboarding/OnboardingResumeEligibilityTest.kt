package io.onboardkit.ui.onboarding

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.os.Bundle
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.ads.module.admob.AppOpenManager
import com.ads.module.consent.ConsentCenter
import io.onboardkit.OnboardingSdk
import io.onboardkit.R
import io.onboardkit.ads.AdSkipReason
import io.onboardkit.ads.OnboardingAdProvider
import io.onboardkit.config.AdsConfig
import io.onboardkit.config.AdFullScreenStepDefinition
import io.onboardkit.config.NativeAdUnit
import io.onboardkit.config.ContentStepDefinition
import io.onboardkit.config.InterstitialAdUnit
import io.onboardkit.config.onboardKitConfig
import io.onboardkit.core.StepId
import io.onboardkit.ui.language.ObLanguageActivity
import io.onboardkit.ui.splash.ObSplashActivity
import io.onboardkit.ui.ob5.ObFullScreenAdActivity
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
// Give this process-singleton fixture its own sandbox; install() intentionally keeps its first provider.
@Config(sdk = [34], application = Application::class, instrumentedPackages = ["io.onboardkit"])
@LooperMode(LooperMode.Mode.PAUSED)
class OnboardingResumeEligibilityTest {
    private var controller: ActivityController<out Activity>? = null
    private val manager get() = AppOpenManager.getInstance()

    @Before fun setup() {
        val provider = Mockito.mock(OnboardingAdProvider::class.java) { call ->
            if (call.method.name == "suppressAppResume") {
                manager.disableAppResumeWithActivity(call.getArgument<Class<out Activity>>(0))
                null
            } else Mockito.RETURNS_DEFAULTS.answer(call)
        }
        OnboardingSdk.install(ApplicationProvider.getApplicationContext()) {
            adProvider = provider
            trackkitAutoTracking(false)
        }
        ConsentCenter.setHostConsent(true, false)
        OnboardingSdk.setCanRequestAds(true)
        OnboardingSdk.configure(onboardKitConfig {
            step(ContentStepDefinition(StepId.OB1, title = "Introduction"))
            ads = AdsConfig(appResume = InterstitialAdUnit("test-resume"))
        }.getOrThrow()).getOrThrow()
    }

    @After fun cleanup() {
        controller?.pause()?.stop()?.destroy()
        ConsentCenter.clearHostConsent()
    }

    @Test fun `first-open language permits a genuine resume`() {
        val activity = launch(ObLanguageActivity::class.java)
        assertFalse(manager.isResumeSuppressedFor(activity))
        assertNull(manager.resumeSkipReasonFor(activity))
        assertNull(OnboardingSdk.appResume().skipReason(activity))
    }

    @Test fun `onboarding content permits a genuine resume before completion`() {
        val activity = launch(ObOnboardingHostActivity::class.java)
        assertFalse(manager.isResumeSuppressedFor(activity))
        assertNull(manager.resumeSkipReasonFor(activity))
        assertNull(OnboardingSdk.appResume().skipReason(activity))
    }

    @Test fun `fullscreen page blocks resume without permanently excluding the pager host`() {
        OnboardingSdk.configure(onboardKitConfig {
            step(AdFullScreenStepDefinition(StepId.OB1, autoNextEnabled = false))
            step(ContentStepDefinition(StepId.OB2, title = "Content"))
            ads = AdsConfig(appResume = InterstitialAdUnit("test-resume"),
                fullScreenStepNative = NativeAdUnit("test-native"))
        }.getOrThrow()).getOrThrow()
        val activity = launch(ObOnboardingHostActivity::class.java)
        assertFalse(manager.isResumeSuppressedFor(activity))
        assertEquals(AdSkipReason.SUPPRESSED_BY_FLOW.key, manager.resumeSkipReasonFor(activity))
        val pager = activity.findViewById<ViewPager2>(R.id.ob_step_pager)
        pager.setCurrentItem(1, false)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(manager.resumeSkipReasonFor(activity))
        pager.setCurrentItem(0, false)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(AdSkipReason.SUPPRESSED_BY_FLOW.key, manager.resumeSkipReasonFor(activity))
    }

    @Test fun `partner exclusion is never removed by a language screen opt-in`() {
        manager.disableAppResumeWithActivity(ObLanguageActivity::class.java)
        assertTrue(manager.isResumeSuppressedFor(launch(ObLanguageActivity::class.java)))
    }

    @Test fun `splash remains excluded`() {
        assertTrue(manager.isResumeSuppressedFor(launch(ResumePolicySplash::class.java)))
    }

    @Test fun `dedicated fullscreen remains excluded`() {
        assertTrue(manager.isResumeSuppressedFor(launch(ObFullScreenAdActivity::class.java)))
    }

    @Test fun `eligible content still respects transient suppression and consent`() {
        val activity = launch(ObOnboardingHostActivity::class.java)
        val guard = OnboardingSdk.appResume()
        guard.suppress()
        guard.suppress()
        guard.release()
        assertEquals(AdSkipReason.SUPPRESSED_BY_FLOW.key, manager.resumeSkipReasonFor(activity))
        guard.release()
        assertNull(manager.resumeSkipReasonFor(activity))
        OnboardingSdk.setCanRequestAds(false)
        assertEquals(AdSkipReason.CONSENT_NOT_GRANTED.key, manager.resumeSkipReasonFor(activity))
    }

    @Test fun `language confirmation blocks resume only while the modal is visible`() {
        val activity = launch(ObLanguageActivity::class.java)
        val root = activity.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2340, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1080, 2340)
        shadowOf(Looper.getMainLooper()).idle()
        val list = activity.findViewById<RecyclerView>(R.id.ob_language_list)
        val row = requireNotNull(list.findViewHolderForAdapterPosition(0)).itemView
        repeat(4) { row.performClick() }
        shadowOf(Looper.getMainLooper()).idle()
        val modal = requireNotNull(ShadowDialog.getLatestDialog())
        assertTrue(modal.isShowing)
        assertEquals(AdSkipReason.SUPPRESSED_BY_FLOW.key, manager.resumeSkipReasonFor(activity))
        modal.findViewById<View>(R.id.ob_confirm_close).performClick()
        assertNull(manager.resumeSkipReasonFor(activity))
    }

    private fun <T : Activity> launch(type: Class<T>): T {
        val launched = Robolectric.buildActivity(type)
        launched.get().setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar)
        launched.setup().visible()
        controller = launched
        shadowOf(Looper.getMainLooper()).idle()
        return launched.get()
    }
}

/** Avoids splash's unrelated loading workflow while exercising its inherited exclusion. */
class ResumePolicySplash : ObSplashActivity() {
    override fun onCreateSafe(savedInstanceState: Bundle?) = Unit
}
