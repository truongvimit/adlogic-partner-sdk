package io.onboardkit.ui.pool

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class CappedLifecycleOwnerTest {
    private val main get() = shadowOf(Looper.getMainLooper())

    private class TestOwner(state: Lifecycle.State) : LifecycleOwner {
        val registry = LifecycleRegistry(this).also { it.currentState = state }
        override val lifecycle: Lifecycle get() = registry
    }

    @Test fun `a shown dialog is capped by its activity and destroyed with its dismissal`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val dialog = ComponentDialog(activity.get())
        val capped = CappedLifecycleOwner(dialog, activity.get())
        assertEquals("Not shown yet", Lifecycle.State.INITIALIZED, capped.lifecycle.currentState)

        dialog.show()
        main.idle()
        assertEquals(Lifecycle.State.RESUMED, capped.lifecycle.currentState)
        activity.pause()
        assertEquals(Lifecycle.State.RESUMED, dialog.lifecycle.currentState)
        assertEquals(Lifecycle.State.STARTED, capped.lifecycle.currentState)
        activity.stop()
        assertEquals(Lifecycle.State.CREATED, capped.lifecycle.currentState)
        activity.restart().start().resume()
        assertEquals(Lifecycle.State.RESUMED, capped.lifecycle.currentState)

        dialog.dismiss()
        main.idle()
        assertEquals(Lifecycle.State.DESTROYED, capped.lifecycle.currentState)
        activity.pause().resume()
        assertEquals("For good, though the dismissed dialog has a fresh registry", Lifecycle.State.DESTROYED,
            capped.lifecycle.currentState)
        activity.pause().stop().destroy()
    }

    @Test fun `an owner never created is destroyed with its cap`() {
        val owner = TestOwner(Lifecycle.State.INITIALIZED)
        val cap = TestOwner(Lifecycle.State.RESUMED)
        val capped = CappedLifecycleOwner(owner, cap)
        val events = mutableListOf<Lifecycle.Event>()
        capped.lifecycle.addObserver(LifecycleEventObserver { _, event -> events += event })

        cap.registry.currentState = Lifecycle.State.DESTROYED
        assertEquals(Lifecycle.State.DESTROYED, capped.lifecycle.currentState)
        assertEquals(listOf(Lifecycle.Event.ON_CREATE, Lifecycle.Event.ON_DESTROY), events)
        owner.registry.currentState = Lifecycle.State.RESUMED
        assertEquals(Lifecycle.State.DESTROYED, capped.lifecycle.currentState)
    }

    @Test fun `capping an already destroyed owner starts destroyed`() {
        val capped = CappedLifecycleOwner(TestOwner(Lifecycle.State.CREATED).apply {
            registry.currentState = Lifecycle.State.DESTROYED
        }, TestOwner(Lifecycle.State.RESUMED))
        assertEquals(Lifecycle.State.DESTROYED, capped.lifecycle.currentState)
    }
}
