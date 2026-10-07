package io.onboardkit.remote

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.ads.module.config.AdRemoteConfig
import com.ads.module.config.AdUnitConfig
import com.ads.module.helper.adnative.NativeClickAction
import io.onboardkit.OnboardingSdk
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.FakeAdProvider
import io.onboardkit.config.AdsConfig
import io.onboardkit.config.onboardKitConfig
import io.onboardkit.core.StepId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** `ad_config.<baseKey>.click_action` is the only click source; each placement kind has a code default. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NativeClickActionSettingsTest {
    private val auto = NativeClickAction.AUTO_NEXT
    private val reload = NativeClickAction.RELOAD
    private val waterfall = NativeClickAction.RELOAD_WATERFALL
    private val none = NativeClickAction.NONE

    private val pagerPages = listOf(
        AdPlacement.StepNative(StepId.OB1), AdPlacement.StepNative(StepId.OB2),
        AdPlacement.StepNative(StepId.OB3), AdPlacement.StepNative(StepId.OB4),
        AdPlacement.StepNative(StepId("custom_page")),
        AdPlacement.StepFullScreen(StepId.FULL1), AdPlacement.StepFullScreen(StepId.FULL2),
    )
    private val reloadingNatives = listOf(
        AdPlacement.Language1, AdPlacement.Language2, AdPlacement.LanguageConfirm,
        AdPlacement.StepNative(StepId.PARTNER_PRIVACY), AdPlacement.StepNative(StepId.PARTNER_PRIVACY_ALT),
        AdPlacement.StepNative(StepId.PARTNER_GOAL), AdPlacement.StepNative(StepId.PARTNER_GOAL_ALT),
        AdPlacement.WelcomeBack1, AdPlacement.WelcomeBack2, AdPlacement.Ob5, AdPlacement.SplashInlineNative,
    )

    @Before fun setup() {
        ReflectionHelpers.setField(OnboardingSdk, "application", null)
        OnboardingSdk.install(ApplicationProvider.getApplicationContext()) {
            adProvider = FakeAdProvider()
            trackkitAutoTracking(false)
        }
        OnboardingSettings.document.acceptSuccessfulFetch(null)
        AdRemoteConfig.reset()
        OnboardingSdk.configure(onboardKitConfig { defaultSteps(); ads = AdsConfig.fromAdConfig() }.getOrThrow())
    }

    @After fun cleanup() {
        OnboardingSettings.document.acceptSuccessfulFetch(null)
        AdRemoteConfig.reset()
        ReflectionHelpers.setField(OnboardingSdk, "application", null)
    }

    private fun action(p: AdPlacement) = OnboardingSettings.nativeClickAction(p)

    private fun adConfig(vararg units: Pair<String, NativeClickAction?>) = AdRemoteConfig.update(AdRemoteConfig(
        units.associate { (key, action) -> key to AdUnitConfig("unit-$key", true, clickAction = action) }))

    @Test fun `without click_action pager pages auto advance and every other native reloads`() {
        adConfig("native_ob1" to null, "native_full1" to null, "native_lang" to null, "native_select" to null)
        pagerPages.forEach { assertEquals(it.key, auto, action(it)) }
        reloadingNatives.forEach { assertEquals(it.key, reload, action(it)) }
        assertEquals(auto, action(AdPlacement.SplashNative))
    }

    @Test fun `the base key click_action overrides the default of every placement kind`() {
        adConfig(
            "native_ob1" to none, "native_full2" to reload, "native_lang" to auto,
            "native_popup_lang" to none, "native_select" to auto, "native_welcome1" to none,
            "native_welcome2" to waterfall,
            "native_onboarding_fullscreen_1_4" to auto, "native_fs" to none, "native_splash" to auto,
        )
        assertEquals(none, action(AdPlacement.StepNative(StepId.OB1)))
        assertEquals(auto, action(AdPlacement.StepNative(StepId.OB2)))
        assertEquals(reload, action(AdPlacement.StepFullScreen(StepId.FULL2)))
        assertEquals(auto, action(AdPlacement.Language1))
        assertEquals(none, action(AdPlacement.LanguageConfirm))
        assertEquals(auto, action(AdPlacement.StepNative(StepId.PARTNER_PRIVACY)))
        assertEquals(auto, action(AdPlacement.StepNative(StepId.PARTNER_GOAL)))
        assertEquals(reload, action(AdPlacement.StepNative(StepId.PARTNER_PRIVACY_ALT)))
        assertEquals(none, action(AdPlacement.WelcomeBack1))
        assertEquals(waterfall, action(AdPlacement.WelcomeBack2))
        assertEquals(auto, action(AdPlacement.Ob5))
        assertEquals(none, action(AdPlacement.SplashNative))
        assertEquals(auto, action(AdPlacement.SplashInlineNative))
    }

    @Test fun `a click_action on a floor key is ignored`() {
        adConfig(
            "native_ob1_high" to none, "native_ob1" to null,
            "native_lang_high1" to auto, "native_lang" to null,
        )
        assertEquals(auto, action(AdPlacement.StepNative(StepId.OB1)))
        assertEquals(reload, action(AdPlacement.Language1))
    }

    @Test fun `LFO2 without its own unit follows the LFO1 key and with one reads its own`() {
        adConfig("native_lang" to auto)
        assertEquals(auto, action(AdPlacement.Language2))
        adConfig("native_lang" to auto, "native_lang_alt" to none)
        assertEquals(none, action(AdPlacement.Language2))
        assertEquals(auto, action(AdPlacement.Language1))
    }

    @Test fun `a remote patch changes the click action and a silent remote keeps the app's`() {
        adConfig("native_ob1" to none, "native_lang" to auto)
        AdRemoteConfig.initializeFromJson("""{"native_ob1":{"click_action":"reload"},"native_lang":{"isEnable":true}}""")
        assertEquals(reload, action(AdPlacement.StepNative(StepId.OB1)))
        assertEquals(auto, action(AdPlacement.Language1))
        AdRemoteConfig.initializeFromJson("{}")
        assertEquals(none, action(AdPlacement.StepNative(StepId.OB1)))
    }

    @Test fun `onboarding_config behavior scopes carry no click action`() {
        OnboardingSettings.document.acceptSuccessfulFetch("""
            {
              "splash": {"native": {"behavior": {"click": {"action": "none"}}}},
              "lfo": {"native1": {"behavior": {"click": {"action": "auto_next"}}},
                      "confirm_dialog": {"native_behavior": {"click": {"action": "auto_next"}}}},
              "onboarding": {
                "ads": {"content_native_behavior": {"click": {"action": "reload"}},
                        "fullscreen_native_behavior": {"click": {"action": "none"}}},
                "steps": {"ob1": {"behavior": {"click": {"action": "none"}}}},
                "navigation": {"ad_click_return_completes_step": false}
              }
            }
        """.trimIndent())
        pagerPages.forEach { assertEquals(it.key, auto, action(it)) }
        reloadingNatives.forEach { assertEquals(it.key, reload, action(it)) }
    }
}
