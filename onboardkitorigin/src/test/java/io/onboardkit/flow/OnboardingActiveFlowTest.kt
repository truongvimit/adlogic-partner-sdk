package io.onboardkit.flow

import android.app.Activity
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.onboardkit.OnboardingSdk
import io.onboardkit.StartOptions
import io.onboardkit.core.OnboardingListener
import io.onboardkit.core.OnboardingOutcome
import io.onboardkit.core.SkipReason
import io.onboardkit.core.StepId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class OnboardingActiveFlowTest {
    private lateinit var controller: ActivityController<Activity>
    private val callbackStates = mutableListOf<Boolean>()
    private val scope = CoroutineScope(Dispatchers.Main)

    @Before
    fun setup() {
        OnboardingSdk.install(ApplicationProvider.getApplicationContext()) { trackkitAutoTracking(false) }
        runBlocking { OnboardingSdk.reset() }
        OnboardingSdk.setListener(OnboardingListener { _, _ -> callbackStates += OnboardingSdk.isFlowActive.value })
        controller = Robolectric.buildActivity(Activity::class.java).setup()
    }

    @After
    fun cleanup() {
        scope.cancel()
        runBlocking { OnboardingSdk.reset() }
        controller.pause().stop().destroy()
    }

    @Test
    fun `skipped launch never exposes an active UI flow to a late collector`() {
        val observed = mutableListOf<Boolean>()
        scope.launch { OnboardingSdk.isFlowActive.collect { observed += it } }
        OnboardingSdk.startResolved(controller.get(), StartDecision.Skip(SkipReason.ALREADY_COMPLETED))
        assertEquals(listOf(false), callbackStates)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(false), observed)
    }

    @Test
    fun `actual start publishes active and abort clears it before terminal callback`() {
        OnboardingSdk.startResolved(controller.get(), StartDecision.Start(FlowDestination.LANGUAGE, 0))
        assertTrue(OnboardingSdk.isFlowActive.value)
        OnboardingSdk.deliverOutcome(controller.get(), OnboardingOutcome.Aborted(StepId.OB1))
        assertFalse(OnboardingSdk.isFlowActive.value)
        assertEquals(listOf(false), callbackStates)
    }

    @Test
    fun `completion cannot be followed by stale active state when collection starts late`() {
        val observed = mutableListOf<Boolean>()
        scope.launch { OnboardingSdk.isFlowActive.collect { observed += it } }
        OnboardingSdk.startResolved(controller.get(), StartDecision.Start(FlowDestination.LANGUAGE, 0), StartOptions())
        OnboardingSdk.completeFlow(controller.get())
        assertEquals(listOf(false), callbackStates)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(false), observed)
    }

    @Test
    fun `failed Activity launch clears active state without swallowing the failure`() {
        val broken = Robolectric.buildActivity(FailedFlowLaunchActivity::class.java).setup()
        try {
            assertThrows(ActivityNotFoundException::class.java) {
                OnboardingSdk.startResolved(broken.get(), StartDecision.Start(FlowDestination.LANGUAGE, 0))
            }
            assertFalse(OnboardingSdk.isFlowActive.value)
        } finally {
            broken.pause().stop().destroy()
        }
    }
}

class FailedFlowLaunchActivity : Activity() {
    override fun startActivity(intent: Intent?) {
        throw ActivityNotFoundException("test host cannot launch setup")
    }
}
