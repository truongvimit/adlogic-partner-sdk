package io.onboardkit.ads

import android.app.Activity
import android.app.Application
import android.content.Context
import com.ads.module.helper.Entitlement
import com.ads.module.helper.EntitlementSource
import androidx.test.core.app.ApplicationProvider
import io.onboardkit.OnboardingSdk
import io.onboardkit.config.*
import io.onboardkit.core.StepId
import io.onboardkit.flow.FlowDestination
import io.onboardkit.remote.OnboardingSettings
import io.onboardkit.remote.RemoteFlags
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PreloadChainTest {
    private val requests = mutableListOf<AdPlacement>()
    private val interstitials = mutableListOf<AdPlacement>()
    private lateinit var chain: PreloadChain
    private lateinit var activity: Activity
    private lateinit var cfg: OnboardKitConfig
    private var flags = RemoteFlags()
    private var premium = false
    private var allowed = true
    private val steps = listOf(StepId.OB1, StepId.FULL1, StepId.OB2, StepId.FULL2, StepId.OB3, StepId.OB4)
    private val expected = steps.map { if (it in listOf(StepId.FULL1, StepId.FULL2)) AdPlacement.StepFullScreen(it) else AdPlacement.StepNative(it) }

    @Before fun setup() {
        OnboardingSdk.install(ApplicationProvider.getApplicationContext()) { trackkitAutoTracking(false) }
        cfg = onboardKitConfig {
            defaultSteps()
            ads = AdsConfig(languageNative = NativeAdUnit("language"),
                welcomeBackNative = NativeAdUnit("welcome1"), welcomeBackDupNative = NativeAdUnit("welcome2"),
                contentStepNative = NativeAdUnit("content"), fullScreenStepNative = NativeAdUnit("fullscreen"),
                afterOnboardingInterstitial = InterstitialAdUnit("exit"))
        }.getOrThrow()
        OnboardingSdk.configure(cfg).getOrThrow()
        val provider = object : FakeAdProvider() {
            override fun preloadNative(activity: Activity, request: NativeAdRequest) { requests += request.placement }
            override fun loadInterstitial(
                activity: Activity,
                placement: AdPlacement,
                unit: InterstitialAdUnit,
                adConfigKey: String?,
                listener: AdEventListener?,
            ) { interstitials += placement }
        }
        Entitlement.install(object : EntitlementSource { override fun isPremium(context: Context) = premium })
        chain = PreloadChain(provider, AdsGuard(true, { cfg }, { flags }, { allowed }), { cfg }, { flags })
        activity = Robolectric.buildActivity(Activity::class.java).get()
    }

    @After fun cleanup() {
        OnboardingSettings.document.acceptSuccessfulFetch(null)
        Entitlement.install(object : EntitlementSource { override fun isPremium(context: Context) = false })
    }

    @Test fun `splash and LFO entry do not warm onboarding natives`() {
        chain.onSplashRemoteReady(activity, FlowDestination.LANGUAGE, 0)
        assertEquals(listOf(AdPlacement.Language1), requests)
        requests.clear()
        chain.onLanguageShown(activity)
        assertEquals(listOf(AdPlacement.Language2), requests)
    }

    @Test fun `welcome back warms slot 1 at splash with a handoff and slot 2 on entry`() {
        OnboardingSettings.document.acceptSuccessfulFetch("""{"welcome_back":{"enabled":true}}""")
        chain.onSplashRemoteReady(activity, FlowDestination.WELCOME_BACK, 0)
        assertEquals(listOf(AdPlacement.WelcomeBack1), requests)
        assertTrue(chain.takeWelcome1Preload())
        assertFalse("The handoff is consumed once", chain.takeWelcome1Preload())
        assertFalse(chain.takeLanguage1Preload())
        requests.clear()
        chain.onSplashRemoteReady(activity, FlowDestination.WELCOME_BACK, 0, firstNativeAlreadyScheduled = true)
        assertTrue(requests.isEmpty())
        chain.preloadWelcome2(activity)
        assertEquals(listOf(AdPlacement.WelcomeBack2), requests)
    }

    @Test fun `disabled welcome back never preloads or leaves a handoff`() {
        chain.onSplashRemoteReady(activity, FlowDestination.WELCOME_BACK, 0)
        chain.preloadWelcome2(activity)
        assertTrue(requests.isEmpty())
        assertFalse(chain.takeWelcome1Preload())
    }

    @Test fun `disabling welcome clears an earlier handoff and stops further requests`() {
        OnboardingSettings.document.acceptSuccessfulFetch("""{"welcome_back":{"enabled":true}}""")
        chain.preloadWelcome1(activity)
        assertEquals(listOf(AdPlacement.WelcomeBack1), requests)
        OnboardingSettings.document.acceptSuccessfulFetch("""{"welcome_back":{"enabled":false}}""")
        chain.preloadWelcome1(activity)
        chain.preloadWelcome2(activity)
        assertEquals(listOf(AdPlacement.WelcomeBack1), requests)
        assertFalse(chain.takeWelcome1Preload())
    }

    @Test fun `default privacy group with configured goals and units never preloads any of its four slots`() {
        cfg = onboardKitConfig {
            defaultSteps()
            privacyGoalsScreen = PrivacyGoalsScreenConfig(
                goal = GoalsScreenConfig(options = listOf(GoalOption("edit", title = "Edit"))),
            )
            ads = AdsConfig(contentStepNative = NativeAdUnit("content"), stepNatives = listOf(
                StepId.PARTNER_PRIVACY, StepId.PARTNER_PRIVACY_ALT, StepId.PARTNER_GOAL, StepId.PARTNER_GOAL_ALT,
            ).associateWith { NativeAdUnit(it.value) })
        }.getOrThrow()
        chain.onStepSelected(activity, steps, steps.lastIndex)
        chain.preloadPrivacy1(activity)
        chain.preloadPrivacy2(activity)
        chain.preloadGoal1(activity)
        chain.preloadGoal2(activity)
        assertTrue(requests.isEmpty())
        chain.onLanguageSelected(activity)
        assertEquals("Ordinary content still preloads", steps.filterNot { it == StepId.FULL1 || it == StepId.FULL2 }
            .map { AdPlacement.StepNative(it) }, requests)
    }

    @Test fun `language selection warms all six slots once and exit inter only on pager entry`() {
        chain.onLanguageSelected(activity)
        assertEquals(expected, requests)
        assertTrue(interstitials.isEmpty())
        chain.onLanguageSelected(activity)
        steps.indices.forEach { chain.onStepSelected(activity, steps, it) }
        assertEquals(expected, requests)
        chain.onOnboardingShown(activity)
        assertEquals(listOf(AdPlacement.AfterOnboardingInterstitial), interstitials)
    }

    @Test fun `remote order omits slots and brings back a page the app disabled`() {
        cfg = onboardKitConfig {
            steps(ContentStepDefinition(StepId.OB1), ContentStepDefinition(StepId.OB2, enabled = false),
                AdFullScreenStepDefinition(StepId.FULL2), ContentStepDefinition(StepId.OB4))
            ads = AdsConfig(contentStepNative = NativeAdUnit("content"), fullScreenStepNative = NativeAdUnit("full"))
        }.getOrThrow()
        OnboardingSettings.document.acceptSuccessfulFetch("""{"onboarding":{"order":["full2","ob2","ob1"]}}""")
        cfg = OnboardingSettings.resolve(cfg)
        chain.onLanguageSelected(activity)
        assertEquals(listOf(AdPlacement.StepFullScreen(StepId.FULL2), AdPlacement.StepNative(StepId.OB2),
            AdPlacement.StepNative(StepId.OB1)), requests)
    }

    @Test fun `missing units and remote disabled steps never preload`() {
        cfg = onboardKitConfig {
            defaultSteps()
            ads = AdsConfig(stepNatives = mapOf(StepId.OB1 to NativeAdUnit("one"), StepId.OB2 to NativeAdUnit("two")))
        }.getOrThrow()
        flags = flags.copy(enableStepOb2 = false)
        chain.onLanguageSelected(activity)
        assertEquals(listOf(AdPlacement.StepNative(StepId.OB1)), requests)
    }

    @Test fun `privacy goals disabled never preload`() {
        cfg = onboardKitConfig {
            defaultSteps()
            privacyGoalsScreen = PrivacyGoalsScreenConfig(
                enabled = true,
                goal = GoalsScreenConfig(options = listOf(GoalOption("edit", title = "Edit"))),
            )
            ads = AdsConfig(
                contentStepNative = NativeAdUnit("content"),
                stepNatives = mapOf(
                    StepId.PARTNER_PRIVACY to NativeAdUnit("privacy"),
                    StepId.PARTNER_PRIVACY_ALT to NativeAdUnit("privacy-alt"),
                    StepId.PARTNER_GOAL to NativeAdUnit("goal"),
                    StepId.PARTNER_GOAL_ALT to NativeAdUnit("goal-alt"),
                ),
            )
        }.getOrThrow()
        chain = PreloadChain(provider = object : FakeAdProvider() {
            override fun preloadNative(activity: Activity, request: NativeAdRequest) { requests += request.placement }
        }, guard = AdsGuard(true, { cfg }, { flags }, { allowed }), config = { cfg }, flags = { flags })
        flags = flags.copy(adsContentNative = false)
        chain.preloadPrivacy1(activity)
        chain.preloadPrivacy2(activity)
        chain.preloadGoal1(activity)
        chain.preloadGoal2(activity)
        assertTrue(requests.isEmpty())

        flags = RemoteFlags()
        cfg = onboardKitConfig {
            defaultSteps()
            privacyGoalsScreen = PrivacyGoalsScreenConfig(enabled = false)
            ads = AdsConfig(stepNatives = mapOf(StepId.PARTNER_PRIVACY to NativeAdUnit("privacy")))
        }.getOrThrow()
        chain = PreloadChain(object : FakeAdProvider() {
            override fun preloadNative(activity: Activity, request: NativeAdRequest) { requests += request.placement }
        }, AdsGuard(true, { cfg }, { flags }, { allowed }), { cfg }, { flags })
        chain.preloadPrivacy1(activity)
        assertTrue(requests.isEmpty())
    }

    @Test fun `a privacy switch with nothing to choose never warms the privacy native`() {
        fun chainFor(privacy: PrivacyGoalsScreenConfig): PreloadChain {
            cfg = onboardKitConfig {
                defaultSteps()
                privacyGoalsScreen = privacy
                ads = AdsConfig(stepNatives = mapOf(StepId.PARTNER_PRIVACY to NativeAdUnit("privacy")))
            }.getOrThrow()
            return PreloadChain(object : FakeAdProvider() {
                override fun preloadNative(activity: Activity, request: NativeAdRequest) { requests += request.placement }
            }, AdsGuard(true, { cfg }, { flags }, { allowed }), { cfg }, { flags })
        }
        chainFor(PrivacyGoalsScreenConfig(enabled = true)).onStepSelected(activity, steps, steps.lastIndex)
        assertTrue(requests.isEmpty())

        chainFor(PrivacyGoalsScreenConfig(
            enabled = true,
            goal = GoalsScreenConfig(options = listOf(GoalOption("edit", title = "Edit"))),
        )).onStepSelected(activity, steps, steps.lastIndex)
        assertEquals(listOf(AdPlacement.StepNative(StepId.PARTNER_PRIVACY)), requests)
    }

    @Test fun `premium consent and force update hold prevent every native request`() {
        premium = true
        chain.onLanguageSelected(activity)
        premium = false
        allowed = false
        chain.onLanguageSelected(activity)
        allowed = true
        com.ads.module.helper.AdGate.holdRequests().use { chain.onLanguageSelected(activity) }
        assertTrue(requests.isEmpty())
        chain.onLanguageSelected(activity)
        assertEquals(expected, requests)
    }

    @Test fun `failed or consumed slot is not requested again until a new attempt`() {
        chain.beginSplashAttempt("one")
        chain.onLanguageSelected(activity)
        val request = NativeAdRequest(AdPlacement.StepNative(StepId.OB1), NativeAdUnit("content"), 0)
        assertFalse(chain.requestNativeOnce(activity, request))
        chain.beginSplashAttempt("one") // Activity recreation
        chain.onLanguageSelected(activity)
        assertEquals(expected, requests)
        chain.beginSplashAttempt("two")
        chain.onLanguageSelected(activity)
        assertEquals(expected + expected, requests)
    }
}
