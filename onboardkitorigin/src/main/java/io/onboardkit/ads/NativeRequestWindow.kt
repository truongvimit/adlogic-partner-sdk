package io.onboardkit.ads

import android.app.Activity
import android.view.ViewTreeObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

internal fun isRequestWindowOpen(
    alive: Boolean,
    state: Lifecycle.State?,
    focused: Boolean,
    allowWhileVisible: Boolean,
): Boolean = alive && (
    (state == null || state.isAtLeast(Lifecycle.State.RESUMED)) && focused ||
        allowWhileVisible && state?.isAtLeast(Lifecycle.State.STARTED) == true
    )

internal fun Activity.canStartNativeRequest(allowWhileVisible: Boolean): Boolean = isRequestWindowOpen(
    alive = !isFinishing && !isDestroyed,
    state = (this as? LifecycleOwner)?.lifecycle?.currentState,
    focused = hasWindowFocus(),
    allowWhileVisible = allowWhileVisible,
)

// A queued preload owns no network request yet: losing its Activity cancels only this wait.
internal suspend fun Activity.awaitNativeRequestWindow(allowWhileVisible: Boolean): Boolean {
    val owner = this as? LifecycleOwner ?: return canStartNativeRequest(allowWhileVisible)
    return suspendCancellableCoroutine { continuation ->
        val tree = window.decorView.viewTreeObserver
        lateinit var lifecycleObserver: LifecycleEventObserver
        lateinit var focusObserver: ViewTreeObserver.OnWindowFocusChangeListener
        fun detach() {
            owner.lifecycle.removeObserver(lifecycleObserver)
            if (tree.isAlive) tree.removeOnWindowFocusChangeListener(focusObserver)
        }
        fun check() {
            if (!continuation.isActive) return
            if (isFinishing || owner.lifecycle.currentState == Lifecycle.State.DESTROYED) {
                detach()
                continuation.resume(false)
            } else if (canStartNativeRequest(allowWhileVisible)) {
                detach()
                continuation.resume(true)
            }
        }
        lifecycleObserver = LifecycleEventObserver { _, _ -> check() }
        focusObserver = ViewTreeObserver.OnWindowFocusChangeListener { check() }
        tree.addOnWindowFocusChangeListener(focusObserver)
        owner.lifecycle.addObserver(lifecycleObserver)
        continuation.invokeOnCancellation { detach() }
        check()
    }
}
