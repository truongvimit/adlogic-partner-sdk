package io.onboardkit

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.ads.module.config.settings.SettingsDocument
import com.ads.module.config.settings.SettingsSnapshot
import com.ads.module.helper.adnative.pool.SharedNativePool
import io.onboardkit.ads.FakeAdProvider
import io.onboardkit.ads.pool.ApFakeLoader
import io.onboardkit.ads.pool.OnboardingPool
import io.onboardkit.ads.pool.installTestEngine
import io.onboardkit.config.onboardKitConfig
import io.onboardkit.core.OnboardingListener
import io.onboardkit.core.OnboardingOutcome
import io.onboardkit.core.GoalAnswer
import io.onboardkit.core.StepId
import io.onboardkit.flow.FlowDestination
import io.onboardkit.flow.NativeFlowType
import io.onboardkit.flow.StartDecision
import io.onboardkit.remote.BundledOnboarding
import io.onboardkit.remote.ObRemoteKeys
import io.onboardkit.remote.OnboardingSettings
import io.onboardkit.remote.RemoteFlags
import io.trackkit.TrackSink
import io.trackkit.Tracker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
// Keep this no-provider fixture separate from suites whose install() retains an ad provider.
@Config(sdk = [34], application = Application::class, instrumentedPackages = ["io.onboardkit.core.session"])
class OnboardingSessionEntryTest {
    private lateinit var activity: ActivityController<Activity>
    private val outcomes = mutableListOf<OnboardingOutcome.Completed>()
    private val skips = mutableListOf<OnboardingOutcome.Skipped>()
    private val userProperties = mutableListOf<Pair<String, String?>>()

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        OnboardingSdk.install(app) {
            trackkitAutoTracking(false)
        }
        OnboardingSdk.setListener(OnboardingListener { _, outcome ->
            if (outcome is OnboardingOutcome.Completed) outcomes += outcome
            if (outcome is OnboardingOutcome.Skipped) skips += outcome
        })
        OnboardingSdk.configure(onboardKitConfig {}.getOrThrow()).getOrThrow()
        runBlocking { OnboardingSdk.reset() }
        activity = Robolectric.buildActivity(Activity::class.java).setup()
        userProperties.clear()
        Tracker.resetForTesting()
        Tracker.addSink(object : TrackSink {
            override val id: String = "routing"
            override fun onEvent(name: String, params: Map<String, Any?>) = Unit
            override fun onUserProperty(key: String, value: String?) {
                userProperties += key to value
            }
        })
        Tracker.install(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        activity.pause().stop().destroy()
        installAsset(null)
        OnboardingSettings.document.acceptSuccessfulFetch(null)
        OnboardingSettings.acceptLegacy(RemoteFlags(supplied = emptySet()))
        ReflectionHelpers.setField(OnboardingSdk, "adProvider", null)
        OnboardingPool.resetForTest()
        SharedNativePool.resetForTest()
        shadowOf(Looper.getMainLooper()).idle()
        Tracker.resetForTesting()
    }

    @Test
    fun `a later splash run delivers its own completion once in the same process`() {
        startResolved(FlowDestination.LANGUAGE, "first")
        OnboardingSdk.persistLanguage("vi")
        OnboardingSdk.session.recordStepShown(StepId.OB1)
        OnboardingSdk.session.goals += GoalAnswer("old", "Old goal")
        OnboardingSdk.completeFlow(activity.get())
        OnboardingSdk.completeFlow(activity.get())
        assertEquals(1, outcomes.size)

        // A returning user's splash calls startResolved directly, without public start().
        startResolved(FlowDestination.WELCOME_BACK, "returning")
        OnboardingSdk.completeFlow(activity.get())
        OnboardingSdk.completeFlow(activity.get())

        assertEquals("Each splash run must deliver exactly one completion", 2, outcomes.size)
        val returning = outcomes.last()
        assertEquals("returning", returning.passthrough?.getString("entry"))
        assertEquals("vi", returning.selectedLanguage)
        assertTrue("Prior onboarding steps cannot leak into a new run", returning.stepsShown.isEmpty())
        assertTrue("Prior goals cannot leak into a new run", returning.goals.isEmpty())
    }

    @Test
    fun `welcome back hands over without marking or completing anything`() {
        val completedAt = runBlocking {
            val store = requireNotNull(OnboardingSdk.stateStoreOrNull())
            store.markFlowCompleted(nowMs = 42L)
            store.current().flowCompletedAtMs
        }
        repeat(2) { launch ->
            startResolved(FlowDestination.WELCOME_BACK, "launcher$launch")
            OnboardingSdk.recordGoals(listOf(GoalAnswer("edit", "Edit PDF")))
            OnboardingSdk.finishWelcomeBack(activity.get())
            OnboardingSdk.finishWelcomeBack(activity.get())
        }

        assertTrue("Welcome Back never reports a completed flow", outcomes.isEmpty())
        assertEquals("One hand-over per launch, every launch", listOf("launcher0", "launcher1"),
            skips.map { it.passthrough?.getString("entry") })
        assertTrue(skips.all { it.reason == io.onboardkit.core.SkipReason.ALREADY_COMPLETED })
        assertEquals(completedAt, runBlocking { OnboardingSdk.stateStoreOrNull()!!.current().flowCompletedAtMs })
    }

    @Test
    fun `splash handoff starts the flow duration clock`() {
        startResolved(FlowDestination.LANGUAGE, "first")
        ShadowSystemClock.advanceBy(Duration.ofMillis(1_200))

        assertEquals(
            "Splash must start the clock used by completion analytics",
            1_200L,
            OnboardingSdk.session.elapsedMs,
        )
    }

    @Test
    fun `public force restart keeps the selected language and clears persisted progress`() {
        runBlocking {
            val store = requireNotNull(OnboardingSdk.stateStoreOrNull())
            store.setLanguage("es")
            store.markFlowCompleted()
        }
        OnboardingSdk.session.recordStepShown(StepId.OB1)
        OnboardingSdk.session.finished.set(true)

        runBlocking {
            OnboardingSdk.start(
                activity.get(),
                StartOptions(forceRestart = true, passthrough = Bundle().apply { putString("entry", "replay") }),
            )
        }

        assertEquals(
            "io.onboardkit.ui.language.ObLanguageActivity",
            shadowOf(activity.get()).nextStartedActivity.component?.className,
        )
        assertFalse(runBlocking { OnboardingSdk.isCompleted() })
        OnboardingSdk.completeFlow(activity.get())
        OnboardingSdk.completeFlow(activity.get())
        assertEquals(1, outcomes.size)
        assertEquals("es", outcomes.single().selectedLanguage)
        assertEquals("replay", outcomes.single().passthrough?.getString("entry"))
        assertTrue(outcomes.single().stepsShown.isEmpty())
    }

    private fun startResolved(destination: FlowDestination, entry: String) {
        OnboardingSdk.startResolved(
            activity.get(),
            StartDecision.Start(destination, 0),
            StartOptions(passthrough = Bundle().apply { putString("entry", entry) }),
        )
    }

    @Test
    fun `only a valid SHARED_POOL opens the pooled flow, whatever source says it`() {
        // A pool that can run, so only the type decides below.
        installTestEngine(ApFakeLoader())
        assertRoutes(CLASSIC_LFO, "missing")

        OnboardingSettings.document.acceptSuccessfulFetch("""{"native_flow":{"type":"shared_pool"}}""")
        assertRoutes(CLASSIC_LFO, "invalid remote value")
        OnboardingSettings.document.acceptSuccessfulFetch(null)

        installAsset("""{"native_flow":{"type":"POOL"}}""")
        assertRoutes(CLASSIC_LFO, "invalid asset value")
        installAsset(null)

        deliverAlias(null)
        assertRoutes(CLASSIC_LFO, "alias absent")
        deliverAlias(false)
        assertRoutes(CLASSIC_LFO, "alias false")
        deliverAlias(true)
        OnboardingSettings.document.acceptSuccessfulFetch("""{"native_flow":{"type":"PER_SCREEN"}}""")
        assertRoutes(CLASSIC_LFO, "alias delivered under a PER_SCREEN document")
        OnboardingSettings.document.acceptSuccessfulFetch(null)
        assertRoutes(POOLED_FLOW, "alias delivered alone")
    }

    @Test
    fun `SHARED_POOL without a pool the provider can run is the classic LFO`() {
        OnboardingSettings.document.acceptSuccessfulFetch(SHARED_POOL)
        assertRoutes(CLASSIC_LFO, "no provider")
        ReflectionHelpers.setField(OnboardingSdk, "adProvider", FakeAdProvider())
        assertRoutes(CLASSIC_LFO, "a provider other than ERain, no test engine")
    }

    @Test
    fun `a throwing knob read runs the classic LFO and leaves no pool run behind`() {
        installTestEngine(ApFakeLoader())
        withThrowingPoolKnobs { assertRoutes(CLASSIC_LFO, "snapshot throws") }
    }

    @Test
    fun `SHARED_POOL opens the pooled flow and latches its knobs for the run`() {
        installTestEngine(ApFakeLoader())
        OnboardingSettings.document.acceptSuccessfulFetch(
            """{"native_flow":{"type":"SHARED_POOL","pool":{"done_hidden_gone":false}}}""")
        assertRoutes(POOLED_FLOW, "shared pool")
        val latched = requireNotNull(OnboardingSdk.session.nativeFlow)
        assertFalse(latched.doneHiddenGone)

        OnboardingSettings.document.acceptSuccessfulFetch("""{"native_flow":{"type":"PER_SCREEN"}}""")
        assertSame("A fetch mid-run changes nothing", latched, OnboardingSdk.session.nativeFlow)
        assertTrue(OnboardingPool.routeResume)
        assertRoutes(CLASSIC_LFO, "the next run reads the new document")

        OnboardingSettings.document.acceptSuccessfulFetch(SHARED_POOL)
        assertRoutes(POOLED_FLOW, "shared pool again")
        runBlocking { OnboardingSdk.reset() }
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("reset() ends the pool's routing", OnboardingPool.routeResume)
        assertNull(OnboardingSdk.session.nativeFlow)
    }

    @Test
    fun `ONBOARDING and WELCOME_BACK stay classic under SHARED_POOL`() {
        installTestEngine(ApFakeLoader())
        OnboardingSettings.document.acceptSuccessfulFetch(SHARED_POOL)
        startResolved(FlowDestination.ONBOARDING, "resume")
        assertEquals("io.onboardkit.ui.onboarding.ObOnboardingHostActivity", nextStarted())
        startResolved(FlowDestination.WELCOME_BACK, "returning")
        assertEquals("io.onboardkit.ui.welcomeback.ObWelcomeBackActivity", nextStarted())
        assertNull(OnboardingSdk.session.nativeFlow)
        assertFalse(OnboardingPool.routeResume)
        assertTrue("Only the language arm reads the type", userProperties.isEmpty())
    }

    private fun nextStarted(): String? = shadowOf(activity.get()).nextStartedActivity?.component?.className

    /** Runs the LANGUAGE arm through the splash handoff and through public start(forceRestart). */
    private fun assertRoutes(expected: String, case: String) {
        for (forceRestart in listOf(false, true)) {
            val label = "$case via ${if (forceRestart) "start(forceRestart)" else "startResolved"}"
            shadowOf(activity.get()).clearNextStartedActivities()
            if (forceRestart) {
                runBlocking { OnboardingSdk.start(activity.get(), StartOptions(forceRestart = true)) }
            } else {
                startResolved(FlowDestination.LANGUAGE, "routing")
            }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(label, expected, nextStarted())
            val pooled = expected == POOLED_FLOW
            assertEquals(label, pooled, OnboardingPool.routeResume)
            assertEquals(label, if (pooled) NativeFlowType.SHARED_POOL else null, OnboardingSdk.session.nativeFlow?.type)
            assertNotNull(label, userProperties.lastOrNull())
            assertEquals(label, "ob_native_flow" to if (pooled) "shared_pool" else "per_screen", userProperties.last())
        }
    }

    /** The app asset tier is read once from the Context; the module's own entry swaps it. */
    private fun installAsset(json: String?) {
        val install = SettingsDocument::class.java.declaredMethods.single { it.name.startsWith("install$") }
        install.isAccessible = true
        install.invoke(OnboardingSettings.document, json, null)
    }

    private fun deliverAlias(on: Boolean?) = OnboardingSettings.acceptLegacy(
        if (on == null) RemoteFlags(supplied = emptySet())
        else RemoteFlags(nativeReuseEnabled = on, supplied = setOf(ObRemoteKeys.NATIVE_REUSE_ENABLED.key)))

    /** SHARED_POOL is delivered, but every pool knob read throws, as a corrupt document might. */
    private fun withThrowingPoolKnobs(block: () -> Unit) {
        val state = ReflectionHelpers.getField<MutableStateFlow<SettingsSnapshot>>(OnboardingSettings.document, "state")
        val original = state.value
        val defaults = object : HashMap<String, Any>(BundledOnboarding.VALUES) {
            override fun get(key: String): Any? =
                if (key.startsWith("native_flow.pool.")) throw IllegalStateException("broken $key") else super.get(key)
        }
        val constructor = SettingsSnapshot::class.java.declaredConstructors.single { it.parameterCount == 4 }
        constructor.isAccessible = true
        state.value = constructor.newInstance(
            defaults, emptyMap<String, Any>(), mapOf("native_flow.type" to "SHARED_POOL"), emptyMap<String, Any>(),
        ) as SettingsSnapshot
        try {
            block()
        } finally {
            state.value = original
        }
    }

    private companion object {
        const val CLASSIC_LFO = "io.onboardkit.ui.language.ObLanguageActivity"
        const val POOLED_FLOW = "io.onboardkit.ui.pool.ObPoolFlowActivity"
        const val SHARED_POOL = """{"native_flow":{"type":"SHARED_POOL"}}"""
    }
}
