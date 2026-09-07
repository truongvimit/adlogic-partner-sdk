package io.retentionkit.widgets

import android.content.Context
import android.view.View
import android.widget.RemoteViews

/** A compact row only when short and wide enough; otherwise a two-column grid for 3–4 features. */
class StandardWidgetRenderer : RetentionWidgetRenderer {
    override fun render(context: Context, instance: WidgetInstance, actions: List<WidgetAction>): RemoteViews {
        // Launcher minimum height can describe the landscape bound, not the current portrait
        // surface. Height alone must not squeeze four columns into a narrow default 3-cell widget.
        val horizontal = instance.minWidthDp >= 300 && instance.minHeightDp in 1..139
        val views = RemoteViews(context.packageName, if (horizontal) R.layout.rk_widget_row else R.layout.rk_widget_grid)
        val cells = intArrayOf(R.id.rk_action_1, R.id.rk_action_2, R.id.rk_action_3, R.id.rk_action_4)
        val labels = intArrayOf(R.id.rk_label_1, R.id.rk_label_2, R.id.rk_label_3, R.id.rk_label_4)
        val icons = intArrayOf(R.id.rk_icon_1, R.id.rk_icon_2, R.id.rk_icon_3, R.id.rk_icon_4)
        views.setTextViewText(R.id.rk_widget_title, context.getString(R.string.rk_widget_title))
        views.setTextViewText(R.id.rk_widget_empty, context.getString(R.string.rk_widget_unavailable))
        views.setViewVisibility(R.id.rk_widget_empty, if (actions.isEmpty()) View.VISIBLE else View.GONE)
        cells.indices.forEach { index ->
            val action = actions.getOrNull(index)
            views.setViewVisibility(cells[index], if (action == null) View.GONE else View.VISIBLE)
            if (action != null) {
                views.setTextViewText(labels[index], action.feature.label)
                views.setImageViewResource(icons[index], action.feature.iconRes)
                views.setContentDescription(cells[index], action.feature.description.ifBlank { action.feature.label })
                views.setOnClickPendingIntent(cells[index], action.pendingIntent)
            }
        }
        return views
    }
}
