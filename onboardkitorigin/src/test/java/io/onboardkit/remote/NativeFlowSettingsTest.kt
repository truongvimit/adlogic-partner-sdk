package io.onboardkit.remote

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.ads.module.config.AdRemoteConfig
import com.ads.module.config.settings.AdBehavior
import com.ads.module.config.settings.SettingsDocument
import com.ads.module.helper.adnative.pool.PoolParity
import io.onboardkit.OnboardingSdk
import io.onboardkit.ads.FakeAdProvider
import io.onboardkit.ads.NextScreenTiming
import io.onboardkit.config.onboardKitConfig
import io.onboardkit.core.session.OnboardingSession
import io.onboardkit.flow.NativeFlowSnapshot
import io.onboardkit.flow.NativeFlowType
import io.onboardkit.flow.PoolLocaleApply
import io.onboardkit.flow.resolveNativeFlowType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NativeFlowSettingsTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val alias = ObRemoteKeys.NATIVE_REUSE_ENABLED.key

    /** Spec section 2 value for value: a change to the bundle must be a change to this list. */
    private val specDefaults: Map<String, Any> = mapOf(
        "native_flow.type" to "PER_SCREEN",
        "native_flow.pool.parity.refresh_overwrites_unshown" to true,
        "native_flow.pool.parity.retry_skipped_polite" to false,
        "native_flow.pool.parity.async_gate" to true,
        "native_flow.pool.parity.async_selector" to true,
        "native_flow.pool.parity.borrow_showing" to true,
        "native_flow.pool.parity.destroy_replaced_ads" to false,
        "native_flow.pool.parity.pager_slots_bind_resumed" to false,
        "native_flow.pool.locale_apply" to "IN_PLACE",
        "native_flow.pool.exit.next_screen_timing" to "AFTER_AD",
        "native_flow.pool.pager.offscreen_pages" to 1L,
        "native_flow.pool.stuck_loading_log_ms" to 60_000L,
        "native_flow.pool.view.destroy_on_teardown" to true,
        "native_flow.pool.lfo.confirm_button.reveal_delay_ms" to 3_000L,
        "native_flow.pool.lfo.loading_dialog.duration_ms" to 0L,
        "native_flow.pool.select.next_reveal_delay_ms" to 3_000L,
        "native_flow.pool.goals.exit_reuse_splash_inter" to true,
        "native_flow.pool.onboarding.fullscreen.skip.delay_ms" to 2_000L,
        "native_flow.pool.onboarding.fullscreen.auto_next.delay_ms" to 10_000L,
        "native_flow.pool.resume.min_background_ms" to 2_000L,
    )

    private val specSnapshot = NativeFlowSnapshot(
        type = NativeFlowType.PER_SCREEN,
        parity = PoolParity(
            refreshOverwritesUnshown = true, retrySkippedPolite = false, asyncGate = true, asyncSelector = true,
            borrowShowing = true, destroyReplacedAds = false, pagerSlotsBindResumed = false,
        ),
        localeApply = PoolLocaleApply.IN_PLACE,
        exitNextScreenTiming = NextScreenTiming.AFTER_AD,
        pagerOffscreenPages = 1,
        stuckLoadingLogMs = 60_000L,
        viewDestroyOnTeardown = true,
        lfoConfirmRevealDelayMs = 3_000L,
        lfoLoadingDialogMs = 0L,
        selectNextRevealDelayMs = 3_000L,
        goalsExitReuseSplashInter = true,
        fullscreenSkipDelayMs = 2_000L,
        fullscreenAutoNextDelayMs = 10_000L,
        resumeMinBackgroundMs = 2_000L,
    )

    /** The checked-in asset keeps the legacy flow unless the host opts into the pool. */
    private val bundledSnapshot = specSnapshot.copy(type = NativeFlowType.PER_SCREEN)

    @Before fun setup() {
        ReflectionHelpers.setField(OnboardingSdk, "application", null)
        OnboardingSdk.install(app) {
            adProvider = FakeAdProvider()
            trackkitAutoTracking(false)
        }
        clearSources()
    }

    @After fun cleanup() {
        clearSources()
        ReflectionHelpers.setField(OnboardingSdk, "application", null)
    }

    private fun clearSources() {
        installAsset(null)
        OnboardingSettings.document.acceptSuccessfulFetch(null)
        OnboardingSettings.acceptLegacy(RemoteFlags(supplied = emptySet()))
        AdBehavior.document.acceptSuccessfulFetch(null)
        AdRemoteConfig.reset()
    }

    /** The app asset tier is read once from the Context; tests swap it through the module's own entry. */
    private fun installAsset(json: String?) {
        val install = SettingsDocument::class.java.declaredMethods.single { it.name.startsWith("install$") }
        install.isAccessible = true
        install.invoke(OnboardingSettings.document, json, null)
    }

    private fun deliverAlias(on: Boolean?) = OnboardingSettings.acceptLegacy(
        if (on == null) RemoteFlags(supplied = emptySet()) else RemoteFlags(nativeReuseEnabled = on, supplied = setOf(alias)))

    @Test fun `snapshot defaults equal the spec and the bundle declares exactly its paths`() {
        val bundled = BundledOnboarding.VALUES.filterKeys { it == "native_flow" || it.startsWith("native_flow.") }
        assertEquals(specDefaults.keys, bundled.keys)
        specDefaults.forEach { (path, value) ->
            val actual = bundled.getValue(path)
            assertEquals(path, value, if (actual is Number) actual.toLong() else actual)
        }
        assertEquals(specSnapshot, NativeFlowSnapshot())
        assertEquals(bundledSnapshot, OnboardingSettings.nativeFlowSnapshot())
        assertEquals(NativeFlowType.PER_SCREEN, OnboardingSettings.nativeFlowTypeOrDefault())
    }

    @Test fun `every knob reads its own path`() {
        assertTrue(OnboardingSettings.document.acceptSuccessfulFetch("""
            {"native_flow":{"type":"SHARED_POOL","pool":{
              "parity":{"refresh_overwrites_unshown":false,"retry_skipped_polite":true,"async_gate":false,
                "async_selector":false,"borrow_showing":false,"destroy_replaced_ads":true,
                "pager_slots_bind_resumed":true},
              "locale_apply":"RECREATE","exit":{"next_screen_timing":"UNDER_AD"},
              "pager":{"offscreen_pages":2},
              "stuck_loading_log_ms":0,"view":{"destroy_on_teardown":false},
              "lfo":{"confirm_button":{"reveal_delay_ms":11},"loading_dialog":{"duration_ms":16}},"select":{"next_reveal_delay_ms":12},
              "goals":{"exit_reuse_splash_inter":false},"resume":{"min_background_ms":15},
              "onboarding":{"fullscreen":{"skip":{"delay_ms":13},"auto_next":{"delay_ms":14}}}}}}
        """.trimIndent()))
        val flipped = PoolParity(
            refreshOverwritesUnshown = false, retrySkippedPolite = true, asyncGate = false, asyncSelector = false,
            borrowShowing = false, destroyReplacedAds = true, pagerSlotsBindResumed = true,
        )
        assertEquals(NativeFlowSnapshot(
            type = NativeFlowType.SHARED_POOL,
            parity = flipped,
            localeApply = PoolLocaleApply.RECREATE,
            exitNextScreenTiming = NextScreenTiming.UNDER_AD,
            pagerOffscreenPages = 2,
            stuckLoadingLogMs = 0L,
            viewDestroyOnTeardown = false,
            lfoConfirmRevealDelayMs = 11L,
            lfoLoadingDialogMs = 16L,
            selectNextRevealDelayMs = 12L,
            goalsExitReuseSplashInter = false,
            fullscreenSkipDelayMs = 13L,
            fullscreenAutoNextDelayMs = 14L,
            resumeMinBackgroundMs = 15L,
        ), OnboardingSettings.nativeFlowSnapshot())
        assertEquals(NativeFlowType.SHARED_POOL, OnboardingSettings.nativeFlowTypeOrDefault())
    }

    @Test fun `a pool knob leaves the classic key of the same meaning alone`() {
        assertTrue(OnboardingSettings.document.acceptSuccessfulFetch(
            """{"native_flow":{"pool":{"onboarding":{"fullscreen":{"skip":{"delay_ms":100},"auto_next":{"delay_ms":200}}}}}}"""))
        assertEquals(5_000L, OnboardingSettings.number("onboarding.fullscreen.skip.delay_ms"))
        assertEquals(15_000L, OnboardingSettings.number("onboarding.fullscreen.auto_next.delay_ms"))
        assertEquals(100L, OnboardingSettings.nativeFlowSnapshot().fullscreenSkipDelayMs)
        assertEquals(200L, OnboardingSettings.nativeFlowSnapshot().fullscreenAutoNextDelayMs)
    }

    @Test fun `an invalid enum falls back to the next source`() {
        val invalid = """{"native_flow":{"type":"shared_pool","pool":{"locale_apply":"SIDEWAYS","exit":{"next_screen_timing":"AUTO"}}}}"""
        assertTrue(OnboardingSettings.document.acceptSuccessfulFetch(invalid))
        assertNull(OnboardingSettings.values.remoteValue("native_flow.type"))
        assertEquals(bundledSnapshot, OnboardingSettings.nativeFlowSnapshot())
        assertEquals(NativeFlowType.PER_SCREEN, OnboardingSettings.nativeFlowTypeOrDefault())

        installAsset("""{"native_flow":{"type":"SHARED_POOL","pool":{"locale_apply":"RECREATE","exit":{"next_screen_timing":"UNDER_AD"}}}}""")
        assertTrue(OnboardingSettings.document.acceptSuccessfulFetch(invalid))
        val snapshot = OnboardingSettings.nativeFlowSnapshot()
        assertEquals(NativeFlowType.SHARED_POOL, snapshot.type)
        assertEquals(PoolLocaleApply.RECREATE, snapshot.localeApply)
        assertEquals(NextScreenTiming.UNDER_AD, snapshot.exitNextScreenTiming)
        assertEquals(NativeFlowType.SHARED_POOL, OnboardingSettings.nativeFlowTypeOrDefault())
    }

    @Test fun `only an exact type name selects a flow`() {
        assertEquals(NativeFlowType.SHARED_POOL, resolveNativeFlowType("SHARED_POOL"))
        assertEquals(NativeFlowType.PER_SCREEN, resolveNativeFlowType("PER_SCREEN"))
        for (raw in listOf(null, "", "shared_pool", " SHARED_POOL", "POOL")) {
            assertEquals(raw.toString(), NativeFlowType.PER_SCREEN, resolveNativeFlowType(raw))
        }
    }

    @Test fun `remote document beats the delivered alias, which beats the asset, which beats the bundle`() {
        fun type() = OnboardingSettings.nativeFlowTypeOrDefault()
        assertEquals(NativeFlowType.PER_SCREEN, type())
        installAsset("""{"native_flow":{"type":"SHARED_POOL","pool":{"stuck_loading_log_ms":5}}}""")
        assertEquals(NativeFlowType.SHARED_POOL, type())
        assertEquals(5L, OnboardingSettings.nativeFlowSnapshot().stuckLoadingLogMs)

        deliverAlias(false)
        assertEquals(NativeFlowType.PER_SCREEN, type())
        assertEquals(NativeFlowType.PER_SCREEN, OnboardingSettings.nativeFlowSnapshot().type)

        assertTrue(OnboardingSettings.document.acceptSuccessfulFetch(
            """{"native_flow":{"type":"SHARED_POOL","pool":{"stuck_loading_log_ms":7}}}"""))
        assertEquals(NativeFlowType.SHARED_POOL, type())
        assertEquals(7L, OnboardingSettings.nativeFlowSnapshot().stuckLoadingLogMs)
        deliverAlias(true)
        assertTrue(OnboardingSettings.document.acceptSuccessfulFetch("""{"native_flow":{"type":"PER_SCREEN"}}"""))
        assertEquals(NativeFlowType.PER_SCREEN, type())

        OnboardingSettings.document.acceptSuccessfulFetch(null)
        assertEquals(NativeFlowType.SHARED_POOL, type())
        assertEquals(5L, OnboardingSettings.nativeFlowSnapshot().stuckLoadingLogMs)
        deliverAlias(false)
        assertEquals(NativeFlowType.PER_SCREEN, type())
        deliverAlias(null)
        assertEquals(NativeFlowType.SHARED_POOL, type())
        installAsset(null)
        assertEquals(NativeFlowType.PER_SCREEN, type())
        assertEquals(bundledSnapshot, OnboardingSettings.nativeFlowSnapshot())
    }

    @Test fun `a native_flow override leaves the resolved config untouched`() {
        val config = onboardKitConfig { defaultSteps() }.getOrThrow()
        OnboardingSdk.configure(config).getOrThrow()
        assertSame(config, OnboardingSettings.resolve(config))
        val before = OnboardingSdk.configOrNull()

        assertTrue(OnboardingSettings.document.acceptSuccessfulFetch(
            """{"native_flow":{"type":"SHARED_POOL","pool":{"pager":{"offscreen_pages":2}}}}"""))
        assertTrue(OnboardingSettings.values.hasOverride("native_flow"))
        // resolveConfig returns the host config untouched unless one of its own scopes, "flow" among
        // them, is overridden; the prefix match must not mistake native_flow for flow.
        assertFalse(OnboardingSettings.values.hasOverride("flow"))
        assertSame(config, OnboardingSettings.resolve(config))
        assertSame(before, OnboardingSdk.configOrNull())

        deliverAlias(true)
        installAsset("""{"native_flow":{"type":"SHARED_POOL"}}""")
        assertFalse(OnboardingSettings.values.hasOverride("flow"))
        assertSame(config, OnboardingSettings.resolve(config))
        assertSame(before, OnboardingSdk.configOrNull())
    }

    @Test fun `a new run and a reset both drop the latched native flow`() {
        val session = OnboardingSession()
        session.nativeFlow = specSnapshot.copy(type = NativeFlowType.SHARED_POOL)
        session.begin(null)
        assertNull(session.nativeFlow)
        session.nativeFlow = specSnapshot
        session.reset()
        assertNull(session.nativeFlow)
    }
}
