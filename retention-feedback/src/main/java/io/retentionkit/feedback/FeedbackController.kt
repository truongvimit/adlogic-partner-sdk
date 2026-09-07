package io.retentionkit.feedback

import android.app.Activity
import android.view.ViewGroup
import androidx.lifecycle.LifecycleOwner
import io.retentionkit.core.RetentionFeature
import io.retentionkit.core.RetentionUiLease
import java.lang.ref.WeakReference

/** Custom UI must use these actions; the module remains the owner of state and handoff identity. */
class FeedbackController internal constructor(
    private val module: RetentionFeedbackModule,
    val sessionToken: String,
    activity: Activity,
) {
    private val source = WeakReference(activity)
    internal var lease: RetentionUiLease? = null
    private var detached = false
    private val nativeResources = mutableListOf<AutoCloseable>()
    val lifecycleOwner: LifecycleOwner get() = checkNotNull(source.get() as? LifecycleOwner) { "Feedback UI is detached" }

    /** Use once for each custom slot; the controller closes only its own bindings on destruction. */
    fun bindNative(container: ViewGroup) {
        if (detached) return
        val activity = activity() ?: return
        module.options.nativeContent?.bind(activity, lifecycleOwner, container)?.let { resource ->
            if (detached) resource.close() else nativeResources.add(resource)
        }
    }
    fun state(): FeedbackSession? = module.session(sessionToken)
    fun features(): List<RetentionFeature> = module.features()
    fun selectReason(id: String, selected: Boolean): FeedbackActionResult = module.selectReason(this, id, selected)
    fun keep(): FeedbackActionResult = module.keep(this)
    fun tryFeature(featureId: String): FeedbackActionResult = module.tryFeature(this, featureId)
    fun continueToSystem(): FeedbackActionResult = module.continueToSystem(this)
    /** Explicit legacy App Info choice; default Continue uses continueToSystem(). */
    fun continueToAppManagement(): FeedbackActionResult = module.continueToAppManagement(this)
    internal fun activity(): Activity? = source.get()?.takeUnless { it.isFinishing || it.isDestroyed }
    internal fun activate(): FeedbackActivation = module.activate(this)
    internal fun pause() { lease?.close(); lease = null }
    internal fun detach() {
        detached = true
        pause()
        nativeResources.toList().also { nativeResources.clear() }.forEach {
            try { it.close() } catch (error: Exception) { module.diagnostic("native_close", error) }
        }
        source.clear()
    }
}

/** A live session may be temporarily blocked by focus, host UI or Activity lifecycle. */
internal enum class FeedbackActivation { READY, WAITING, TERMINAL }
