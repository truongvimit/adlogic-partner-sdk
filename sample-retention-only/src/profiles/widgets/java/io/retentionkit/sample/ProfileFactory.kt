package io.retentionkit.sample

import android.app.Activity
import io.retentionkit.core.RetentionRuntime
import io.retentionkit.widgets.RetentionWidgets
import io.retentionkit.widgets.WidgetOptions

internal object ProfileFactory {
    fun create(): ProofProfile = object : ProofProfile {
        private val widgets = RetentionWidgets(WidgetOptions(shortcutsEnabled = false))
        override fun modules() = listOf(widgets)
        override fun actions(activity: Activity, runtime: RetentionRuntime) = listOf(
            ProofAction("Check widget capability") { widgets.pinCapability().toString() },
            ProofAction("Ask to pin widget") { widgets.requestPin().toString() },
            ProofAction("Refresh installed widgets") { widgets.refresh(); "Installed widget refresh requested." },
        )
    }
}
