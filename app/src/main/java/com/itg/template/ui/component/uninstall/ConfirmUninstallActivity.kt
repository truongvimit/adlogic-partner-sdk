package com.itg.template.ui.component.uninstall

import android.app.Activity
import android.os.Bundle
import io.retentionkit.RetentionKit
import io.retentionkit.core.RetentionEntry
import io.retentionkit.core.RetentionEntrySource

/** Compatibility target for old app links. Standard optional feedback owns the current flow. */
class ConfirmUninstallActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val runtime = RetentionKit.get()?.runtime
        if (runtime != null) {
            val entry = RetentionEntry(RetentionEntrySource.FEEDBACK, "retention.feedback", "legacy_feedback",
                createdAtMillis = runtime.clock.wallTimeMillis())
            runtime.createEntryIntent(entry)?.let(::startActivity)
        }
        finish()
    }
}
