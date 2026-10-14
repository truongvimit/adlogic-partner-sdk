package io.onboardkit.ads.pool

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.ads.module.config.AdRemoteConfig
import com.ads.module.config.settings.AdBehavior
import com.ads.module.consent.ConsentCenter
import com.ads.module.helper.AdGate
import com.ads.module.helper.adnative.pool.PoolRequest
import io.onboardkit.R
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.AdSkipReason
import io.onboardkit.ads.AdsGuard
import io.onboardkit.config.AdsConfig
import io.onboardkit.config.GoalOption
import io.onboardkit.config.GoalsScreenConfig
import io.onboardkit.config.NativeAdUnit
import io.onboardkit.config.OnboardKitConfig
import io.onboardkit.config.PrivacyGoalsScreenConfig
import io.onboardkit.config.onboardKitConfig
import io.onboardkit.core.StepId
import io.onboardkit.remote.RemoteFlags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, instrumentedPackages = ["io.onboardkit.ads.pool"])
@LooperMode(LooperMode.Mode.PAUSED)
class OnboardingPoolPolicyTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val goal = AdPlacement.StepNative(StepId.PARTNER_GOAL)

    private fun config(units: Boolean, goals: Boolean): OnboardKitConfig = onboardKitConfig {
        defaultSteps()
        ads = if (!units) AdsConfig() else AdsConfig(
            languageNative = LFO1,
            stepNatives = mapOf(StepId.PARTNER_GOAL to NativeAdUnit("goal-unit")),
        )
        privacyGoalsScreen = PrivacyGoalsScreenConfig(
            enabled = goals,
            goal = GoalsScreenConfig(options = listOf(GoalOption("edit", title = "Edit"))),
        )
    }.getOrThrow()

    private val fullConfig by lazy { config(units = true, goals = true) }

    private fun policy(
        placement: AdPlacement = AdPlacement.Language1,
        provider: Boolean = true,
        cfg: OnboardKitConfig? = fullConfig,
        flags: RemoteFlags = RemoteFlags(),
        formShowing: Boolean = false,
    ) = OnboardingPoolPolicy(
        app,
        placementOf = { placement.takeIf { p -> p.key == it } },
        providerInstalled = { provider },
        config = { cfg },
        flags = { flags },
        consentFormShowing = { formShowing },
    )

    private fun request(placement: AdPlacement, cfg: OnboardKitConfig?, adConfigKey: String? = null) = PoolRequest(
        key = placement.key,
        ids = cfg?.ads?.nativeUnitFor(placement)?.tiers.orEmpty(),
        layoutRes = R.layout.ob_layout_native_lfo,
        adConfigKey = adConfigKey,
    )

    private val lfo1 get() = request(AdPlacement.Language1, fullConfig)

    @Before fun setUp() {
        PoolTestSdk.install(app)
    }

    @After fun tearDown() {
        PoolTestSdk.tearDown()
    }

    @Test fun `a fully allowed placement is refused by neither side`() {
        val policy = policy()
        assertNull(policy.configRefusal(lfo1))
        assertNull(policy.transientRefusal(lfo1))
    }

    @Test fun `run-long causes are config refusals`() {
        setPremium(true)
        assertConfig(AdSkipReason.PREMIUM, policy())
        setPremium(false)

        assertConfig(AdSkipReason.NO_PROVIDER, policy(provider = false))
        assertConfig(AdSkipReason.ADS_OFF_IN_CONFIG, policy(cfg = null))
        assertConfig(AdSkipReason.PLACEMENT_OFF_BY_REMOTE, policy(flags = RemoteFlags(adsLanguageNative = false)))
        assertEquals(AdSkipReason.NO_AD_UNIT.key, policy().configRefusal(lfo1.copy(ids = listOf(" ", ""))))

        AdBehavior.document.acceptSuccessfulFetch("""{"global":{"ads_enabled":false}}""")
        assertConfig(AdSkipReason.ADS_OFF_BY_REMOTE, policy())
        AdBehavior.document.acceptSuccessfulFetch(null)

        val noGoals = config(units = true, goals = false)
        assertEquals(AdSkipReason.ADS_OFF_IN_CONFIG.key,
            policy(goal, cfg = noGoals).configRefusal(request(goal, noGoals)))

        // Not organic-proof yet: a placement that demands UA attribution is refused.
        AdRemoteConfig.initializeFromJson("""{"native_lang":{"ids":[{"id":"ua-unit"}],"isEnable":true,"enable_ua_check":true}}""")
        assertEquals(AdSkipReason.UA_GATE.key, policy().configRefusal(lfo1.copy(adConfigKey = "native_lang")))
    }

    @Test fun `passing causes are transient refusals that leave the config side open`() {
        AdGate.holdRequests().use { assertTransient(AdSkipReason.REQUESTS_HELD, policy()) }

        setOnline(app, false)
        assertTransient(AdSkipReason.OFFLINE, policy())
        setOnline(app, true)

        ConsentCenter.setHostConsent(false, false)
        assertTransient(AdSkipReason.CONSENT_NOT_GRANTED, policy())
        ConsentCenter.setHostConsent(true, false)

        assertTransient(AdSkipReason.CONSENT_NOT_GRANTED, policy(formShowing = true))
    }

    @Test fun `config refusal plus transient refusal is exactly the guard and gate refusal`() {
        val placements = listOf(AdPlacement.Language1, goal)
        val cases = mutableListOf<String>()
        val allowed = mutableListOf<String>()
        for (globalOff in listOf(false, true)) {
            AdBehavior.document.acceptSuccessfulFetch(if (globalOff) """{"global":{"ads_enabled":false}}""" else null)
            for (bits in 0 until (1 shl 8)) {
                fun on(bit: Int) = bits and (1 shl bit) != 0
                val premium = on(0)
                val provider = !on(1)
                val configMissing = on(2)
                val units = !on(3)
                val goals = !on(4)
                val flags = if (on(5)) RemoteFlags(adsLanguageNative = false, adsContentNative = false) else RemoteFlags()
                val offline = on(6)
                val noConsent = on(7)
                val cfg = if (configMissing) null else config(units, goals)
                setPremium(premium)
                setOnline(app, !offline)
                ConsentCenter.setHostConsent(!noConsent, false)
                for (held in listOf(false, true)) {
                    val hold = if (held) AdGate.holdRequests() else null
                    try {
                        placements.forEach { placement ->
                            val request = request(placement, cfg)
                            val policy = policy(placement, provider, cfg, flags)
                            val pool = (policy.configRefusal(request) ?: policy.transientRefusal(request)) != null
                            val guard = AdsGuard(provider, { cfg }, { flags }, ConsentCenter::canRequestAds)
                                .skipReason(app, placement)
                            val gate = AdGate.skipReason(app, true, AdGate.placementPassesUaGate(request.key))
                            val sdk = guard != null || gate != null
                            if (!pool) allowed += "${placement.key} bits=$bits held=$held global=$globalOff"
                            if (pool != sdk) cases += "${placement.key} bits=$bits held=$held global=$globalOff " +
                                "pool=$pool guard=$guard gate=$gate"
                        }
                    } finally {
                        hold?.close()
                    }
                }
            }
        }
        assertTrue(cases.joinToString("\n"), cases.isEmpty())
        assertEquals("Only the all-clear state allows a placement; the goals switch binds only its own",
            listOf("language1 bits=0 held=false global=false", "step_partner_goal bits=0 held=false global=false",
                "language1 bits=16 held=false global=false"), allowed)
    }

    @Test fun `every reason maps back to its analytics value`() {
        val keys = listOf(
            AdSkipReason.PREMIUM, AdSkipReason.NO_PROVIDER, AdSkipReason.ADS_OFF_BY_REMOTE,
            AdSkipReason.ADS_OFF_IN_CONFIG, AdSkipReason.PLACEMENT_OFF_BY_REMOTE, AdSkipReason.NO_AD_UNIT,
            AdSkipReason.UA_GATE, AdSkipReason.REQUESTS_HELD, AdSkipReason.OFFLINE, AdSkipReason.CONSENT_NOT_GRANTED,
        )
        keys.forEach { assertEquals(it, skipReasonOf(it.key)) }
        assertNull(skipReasonOf("consent_form_showing"))
    }

    private fun assertConfig(reason: AdSkipReason, policy: OnboardingPoolPolicy) {
        assertEquals(reason.key, policy.configRefusal(lfo1))
        assertNull(policy.transientRefusal(lfo1))
    }

    private fun assertTransient(reason: AdSkipReason, policy: OnboardingPoolPolicy) {
        assertNull(policy.configRefusal(lfo1))
        assertEquals(reason.key, policy.transientRefusal(lfo1))
    }
}
