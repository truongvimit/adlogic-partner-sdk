package io.retentionkit

import android.view.View
import android.view.ViewTreeObserver
import java.lang.ref.WeakReference

/** Owns only listener registration; each handoff retains its own focus/readiness behavior. */
internal class WindowFocusRegistration(
    view: View,
    listener: ViewTreeObserver.OnWindowFocusChangeListener,
) : AutoCloseable {
    private val weakView = WeakReference(view)
    private val originalObserver = WeakReference(view.viewTreeObserver)
    private var listener: ViewTreeObserver.OnWindowFocusChangeListener? = listener

    init { originalObserver.get()?.addOnWindowFocusChangeListener(listener) }

    override fun close() {
        val owned = listener ?: return
        listener = null
        // A registration made before attachment may be merged into the live window observer.
        val live = weakView.get()?.viewTreeObserver
        live?.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(owned)
        originalObserver.get()?.takeIf { it !== live && it.isAlive }
            ?.removeOnWindowFocusChangeListener(owned)
        weakView.clear()
        originalObserver.clear()
    }
}
