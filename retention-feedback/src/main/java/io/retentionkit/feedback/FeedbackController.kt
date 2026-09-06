package io.retentionkit.feedback

import android.app.Activity
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
    fun state(): FeedbackSession? = module.session(sessionToken)
    fun features(): List<RetentionFeature> = module.features()
    fun selectReason(id: String, selected: Boolean): FeedbackActionResult = module.selectReason(this, id, selected)
    fun keep(): FeedbackActionResult = module.keep(this)
    fun tryFeature(featureId: String): FeedbackActionResult = module.tryFeature(this, featureId)
    fun continueToAppManagement(): FeedbackActionResult = module.continueToAppManagement(this)
    internal fun activity(): Activity? = source.get()?.takeUnless { it.isFinishing || it.isDestroyed }
    internal fun activate(): Boolean = module.activate(this)
    internal fun pause() { lease?.close(); lease = null }
    internal fun detach() { pause(); source.clear() }
}
