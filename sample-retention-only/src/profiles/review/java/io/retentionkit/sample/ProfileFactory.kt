package io.retentionkit.sample

import android.app.Activity
import io.retentionkit.core.RetentionRuntime
import io.retentionkit.review.RetentionReviewModule
import io.retentionkit.review.ReviewOptions

internal object ProfileFactory {
    fun create(): ProofProfile = object : ProofProfile {
        private val review = RetentionReviewModule(ReviewOptions())
        override fun modules() = listOf(review)
        override fun actions(activity: Activity, runtime: RetentionRuntime) = listOf(
            ProofAction("Check eligible review request") { review.requestIfEligible().toString() },
            ProofAction("Open Play Store") { review.openStore().toString() },
        )
    }
}
