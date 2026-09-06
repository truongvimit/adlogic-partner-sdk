package io.retentionkit.sample

import android.app.Activity
import io.retentionkit.core.*
import io.retentionkit.feedback.FeedbackShowResult
import io.retentionkit.feedback.FeedbackOptions
import io.retentionkit.feedback.RetentionFeedbackModule

internal object ProfileFactory {
    fun create(): ProofProfile = object : ProofProfile {
        private val feedback = RetentionFeedbackModule(FeedbackOptions(shortcutEnabled = false))
        override fun modules() = listOf(feedback)
        override fun dispatchPending(runtime: RetentionRuntime, token: String): ProofRoute {
            val entry = runtime.entries.pending(token) ?: return ProofRoute.Blocked("missing_or_consumed")
            if (!feedback.handles(entry)) return super.dispatchPending(runtime, token)
            if (!runtime.userState.setupCompleted) return ProofRoute.Blocked("setup_incomplete")
            val gate = runtime.ui.eligibility()
            if (gate is RetentionEligibility.Blocked) return ProofRoute.Blocked(gate.reason.name.lowercase())
            return when (val result = feedback.handleEntry(entry)) {
                FeedbackShowResult.Scheduled -> ProofRoute.SdkHandled
                is FeedbackShowResult.Unavailable -> ProofRoute.Blocked(result.reason)
            }
        }
        override fun actions(activity: Activity, runtime: RetentionRuntime) = listOf(
            ProofAction("Open exit feedback") { feedback.show().toString() },
        )
    }
}
