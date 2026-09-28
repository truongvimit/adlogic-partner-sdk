package io.onboardkit.ads

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import io.onboardkit.core.ObLog

// Exactly one runs, unless removed first: block once RESUMED (now if already) or onHostLost.
internal fun LifecycleOwner.whenResumed(onHostLost: () -> Unit, block: () -> Unit): () -> Unit {
    if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
        block()
        return {}
    }
    if (lifecycle.currentState == Lifecycle.State.DESTROYED) {
        onHostLost()
        return {}
    }
    ObLog.d(ObLog.Section.SHOW, "holding for RESUMED state=${lifecycle.currentState}")
    val observer = object : LifecycleEventObserver {
        override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    source.lifecycle.removeObserver(this)
                    block()
                }

                Lifecycle.Event.ON_DESTROY -> {
                    source.lifecycle.removeObserver(this)
                    onHostLost()
                }

                else -> Unit
            }
        }
    }
    lifecycle.addObserver(observer)
    return { lifecycle.removeObserver(observer) }
}
