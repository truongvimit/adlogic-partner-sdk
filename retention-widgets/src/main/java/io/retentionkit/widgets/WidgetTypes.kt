package io.retentionkit.widgets

import android.app.PendingIntent
import android.content.Context
import android.widget.RemoteViews
import io.retentionkit.core.RetentionFeature
import io.retentionkit.core.RetentionSuppressionReason

/** Feature labels/icons come from the shared localized catalogue. No Activity is retained. */
data class WidgetOptions @JvmOverloads constructor(
    val providerClass: Class<out RetentionWidgetProvider> = RetentionWidgetProvider::class.java,
    val renderer: RetentionWidgetRenderer = StandardWidgetRenderer(),
    val featureIds: List<String> = emptyList(),
    val shortcutsEnabled: Boolean = true,
    val maxShortcuts: Int = 3,
    val reservedShortcutSlots: Int = 1,
) {
    init {
        require(featureIds.size <= 4 && featureIds.distinct().size == featureIds.size)
        require(featureIds.all { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")) })
        require(maxShortcuts in 0..4)
        require(reservedShortcutSlots in 0..4)
    }
}

data class WidgetInstance(
    val appWidgetId: Int,
    val featureIds: List<String>,
    val minWidthDp: Int = 0,
    val minHeightDp: Int = 0,
)

data class WidgetAction(val feature: RetentionFeature, val pendingIntent: PendingIntent)

/** Called on main; return local RemoteViews only. Supplied click intents are direct Activity intents. */
fun interface RetentionWidgetRenderer {
    fun render(context: Context, instance: WidgetInstance, actions: List<WidgetAction>): RemoteViews
}

sealed class WidgetPinResult {
    data class Requested(val token: String) : WidgetPinResult()
    data class Confirmed(val token: String, val appWidgetId: Int) : WidgetPinResult()
    /** Launcher refusal/cancellation has no callback. Timeout/return is not evidence of cancellation. */
    data class Unknown(val token: String, val reason: String) : WidgetPinResult()
    data class Duplicate(val token: String) : WidgetPinResult()
    data class Unavailable(val reason: String) : WidgetPinResult()
    data class Blocked(val reason: RetentionSuppressionReason) : WidgetPinResult()
    data class Failed(val reason: String) : WidgetPinResult()
}

sealed class WidgetInvitationResult {
    data object Shown : WidgetInvitationResult()
    data class Unavailable(val reason: String) : WidgetInvitationResult()
    data class Blocked(val reason: RetentionSuppressionReason) : WidgetInvitationResult()
    data class Failed(val reason: String) : WidgetInvitationResult()
}
