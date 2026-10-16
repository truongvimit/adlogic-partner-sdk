package io.onboardkit.ui.pool

import androidx.annotation.MainThread
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

/**
 * [owner]'s lifecycle, never above [cap]'s, and DESTROYED for good once either is. A dialog is
 * RESUMED from show to dismiss, also while its activity is paused under an ad's landing page or
 * stopped in the background; a slot in it follows this instead, capped by the activity.
 */
@MainThread
internal class CappedLifecycleOwner(owner: LifecycleOwner, cap: LifecycleOwner) : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    private val ownerLifecycle = owner.lifecycle
    private val capLifecycle = cap.lifecycle

    // Taken from the events, not re-read: a ComponentDialog swaps in a fresh registry once destroyed.
    private var ownerState = ownerLifecycle.currentState
    private var capState = capLifecycle.currentState
    private val ownerObserver = LifecycleEventObserver { _, event ->
        ownerState = event.targetState
        sync()
    }
    private val capObserver = LifecycleEventObserver { _, event ->
        capState = event.targetState
        sync()
    }

    init {
        ownerLifecycle.addObserver(ownerObserver)
        capLifecycle.addObserver(capObserver)
        sync()
    }

    private fun sync() {
        val current = registry.currentState
        val target = minOf(ownerState, capState)
        when {
            current == Lifecycle.State.DESTROYED || target == current -> return
            target == Lifecycle.State.DESTROYED -> {
                ownerLifecycle.removeObserver(ownerObserver)
                capLifecycle.removeObserver(capObserver)
                // LifecycleRegistry refuses INITIALIZED -> DESTROYED.
                if (current == Lifecycle.State.INITIALIZED) registry.currentState = Lifecycle.State.CREATED
            }
            // Once created a lifecycle only goes back down to DESTROYED.
            target == Lifecycle.State.INITIALIZED -> return
        }
        registry.currentState = target
    }
}
