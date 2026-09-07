package io.retentionkit.feedback

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.LifecycleOwner
import io.retentionkit.core.RetentionUiPurpose

/** Stable reason IDs may be tracked; labels use the selected app locale. No survey is mandatory. */
data class FeedbackReason(val id: String, val label: String)
data class FeedbackContent(
    val title: String,
    val body: String,
    val reasonsLabel: String,
    val reasons: List<FeedbackReason>,
    val keepLabel: String,
    val continueLabel: String,
    val systemExplanation: String,
    val shortcutLabel: String,
)
fun interface FeedbackContentProvider { fun content(localizedContext: Context): FeedbackContent }
fun interface FeedbackUiFactory { fun create(activity: Activity, controller: FeedbackController, content: FeedbackContent): View }
/** Ads stay in the host adapter. The returned resource is closed when this UI is destroyed. */
fun interface FeedbackNativeContent {
    fun bind(activity: Activity, lifecycleOwner: LifecycleOwner, container: ViewGroup): AutoCloseable
}
enum class FeedbackSystemAction { UNINSTALL_CONFIRMATION, APP_MANAGEMENT }

fun interface FeedbackLauncher {
    fun launch(activity: Activity, intent: Intent): Boolean
    companion object {
        @JvmField val ANDROID = FeedbackLauncher { activity, intent ->
            try { activity.startActivity(intent); true } catch (_: Exception) { false }
        }
    }
}

data class FeedbackOptions @JvmOverloads constructor(
    val enabled: Boolean = true,
    val shortcutEnabled: Boolean = true,
    val showReasons: Boolean = true,
    val featureIds: List<String> = emptyList(),
    val brandColor: Int = 0xff2455c7.toInt(),
    val appIconRes: Int? = null,
    val contentProvider: FeedbackContentProvider = FeedbackContentProvider { context ->
        FeedbackContent(
            context.getString(R.string.rk_feedback_title), context.getString(R.string.rk_feedback_body),
            context.getString(R.string.rk_feedback_reasons_label), listOf(
                FeedbackReason("hard_to_use", context.getString(R.string.rk_feedback_reason_usage)),
                FeedbackReason("missing_feature", context.getString(R.string.rk_feedback_reason_feature)),
                FeedbackReason("other", context.getString(R.string.rk_feedback_reason_other)),
            ), context.getString(R.string.rk_feedback_keep), context.getString(R.string.rk_feedback_continue),
            context.getString(R.string.rk_feedback_system_explanation), context.getString(R.string.rk_feedback_shortcut),
        )
    },
    val uiFactory: FeedbackUiFactory? = null,
    val launcher: FeedbackLauncher = FeedbackLauncher.ANDROID,
    val systemAction: FeedbackSystemAction = FeedbackSystemAction.UNINSTALL_CONFIRMATION,
    val appManagementFallback: Boolean = true,
    val nativeContent: FeedbackNativeContent? = null,
    /** Launcher shortcut branding is independent of the survey header appIconRes. */
    val shortcutIconRes: Int = R.drawable.rk_ic_uninstall,
)

enum class FeedbackPhase { OPEN, KEPT, FEATURE_HANDOFF, SYSTEM_HANDOFF, CANCELLED }
data class FeedbackSession(val token: String, val createdAtMillis: Long, val phase: FeedbackPhase, val selectedReasons: Set<String>, val shown: Boolean, val uiPurpose: RetentionUiPurpose = RetentionUiPurpose.PROMPT)
sealed class FeedbackShowResult {
    data object Scheduled : FeedbackShowResult()
    data class Unavailable(val reason: String) : FeedbackShowResult()
}
sealed class FeedbackActionResult {
    /** Action accepted. Inside a core subscriber, handoff waits for queued observers; events report its outcome. */
    data object Applied : FeedbackActionResult()
    data class Blocked(val reason: String) : FeedbackActionResult()
    data class Failed(val reason: String) : FeedbackActionResult()
}
