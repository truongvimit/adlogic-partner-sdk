package io.retentionkit.sample

import android.app.Activity
import io.retentionkit.core.RetentionRuntime
import io.retentionkit.feedback.FeedbackOptions
import io.retentionkit.feedback.RetentionFeedbackModule

internal object ProfileFactory {
    fun create(): ProofProfile = object : ProofProfile {
        private val feedback = RetentionFeedbackModule(FeedbackOptions(shortcutEnabled = false))
        override fun modules() = listOf(feedback)
        override fun actions(activity: Activity, runtime: RetentionRuntime) = listOf(
            ProofAction("Open exit feedback") { feedback.show().toString() },
        )
    }
}
