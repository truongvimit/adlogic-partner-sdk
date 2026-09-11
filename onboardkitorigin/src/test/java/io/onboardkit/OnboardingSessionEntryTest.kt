package io.onboardkit

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import io.onboardkit.config.onboardKitConfig
import io.onboardkit.core.OnboardingListener
import io.onboardkit.core.OnboardingOutcome
import io.onboardkit.core.QuestionAnswer
import io.onboardkit.core.StepId
import io.onboardkit.flow.FlowDestination
import io.onboardkit.flow.StartDecision
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
// Keep this no-provider fixture separate from suites whose install() retains an ad provider.
@Config(sdk = [34], application = Application::class, instrumentedPackages = ["io.onboardkit.core.session"])
class OnboardingSessionEntryTest {
    private lateinit var activity: ActivityController<Activity>
    private val outcomes = mutableListOf<OnboardingOutcome.Completed>()

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        OnboardingSdk.install(app) {
            trackkitAutoTracking(false)
        }
        OnboardingSdk.setListener(OnboardingListener { _, outcome ->
            if (outcome is OnboardingOutcome.Completed) outcomes += outcome
        })
        OnboardingSdk.configure(onboardKitConfig {}.getOrThrow()).getOrThrow()
        runBlocking { OnboardingSdk.reset() }
        activity = Robolectric.buildActivity(Activity::class.java).setup()
    }

    @After
    fun tearDown() {
        activity.pause().stop().destroy()
    }

    @Test
    fun `a later splash survey delivers its own completion once in the same process`() {
        startResolved(FlowDestination.LANGUAGE, "first")
        OnboardingSdk.persistLanguage("vi")
        OnboardingSdk.session.recordStepShown(StepId.OB1)
        OnboardingSdk.session.answers += QuestionAnswer("old", "Old answer")
        OnboardingSdk.completeFlow(activity.get())
        OnboardingSdk.completeFlow(activity.get())
        assertEquals(1, outcomes.size)

        // A returning user's splash calls startResolved directly, without public start().
        startResolved(FlowDestination.QUESTION_OLD_USER, "returning")
        OnboardingSdk.completeFlow(activity.get())
        OnboardingSdk.completeFlow(activity.get())

        assertEquals("Each splash run must deliver exactly one completion", 2, outcomes.size)
        val returning = outcomes.last()
        assertEquals("returning", returning.passthrough?.getString("entry"))
        assertEquals("vi", returning.selectedLanguage)
        assertTrue("Prior onboarding steps cannot leak into a new survey", returning.stepsShown.isEmpty())
        assertTrue("Prior survey answers cannot leak into a new run", returning.answers.isEmpty())
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
}
