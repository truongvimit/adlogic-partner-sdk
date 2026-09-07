package io.retentionkit.review

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.android.play.core.review.ReviewInfo
import com.google.android.play.core.review.ReviewManagerFactory

interface ReviewToken
sealed class ReviewInfoResult {
    data class Ready(val token: ReviewToken) : ReviewInfoResult()
    data class Failed(val reason: String) : ReviewInfoResult()
}
sealed class ReviewFlowResult {
    data object FinishedOutcomeUnknown : ReviewFlowResult()
    data class Failed(val reason: String) : ReviewFlowResult()
}
/** Callbacks may be late, duplicate or delivered on any thread; the module validates each one. */
interface ReviewTransport {
    fun request(callback: (ReviewInfoResult) -> Unit)
    fun launch(activity: Activity, token: ReviewToken, callback: (ReviewFlowResult) -> Unit)
    fun openStore(activity: Activity): Boolean
}
fun interface ReviewTransportFactory {
    fun create(context: Context): ReviewTransport
    companion object { @JvmField val PLAY = ReviewTransportFactory { PlayReviewTransport(it) } }
}

class PlayReviewTransport(context: Context) : ReviewTransport {
    private val manager = ReviewManagerFactory.create(context.applicationContext)
    private data class PlayToken(val info: ReviewInfo) : ReviewToken
    override fun request(callback: (ReviewInfoResult) -> Unit) {
        manager.requestReviewFlow().addOnCompleteListener { task ->
            callback(if (task.isSuccessful) ReviewInfoResult.Ready(PlayToken(task.result)) else ReviewInfoResult.Failed("play_request_failed"))
        }
    }
    override fun launch(activity: Activity, token: ReviewToken, callback: (ReviewFlowResult) -> Unit) {
        require(token is PlayToken) { "Token did not originate from Play review transport" }
        manager.launchReviewFlow(activity, token.info).addOnCompleteListener { task ->
            callback(if (task.isSuccessful) ReviewFlowResult.FinishedOutcomeUnknown else ReviewFlowResult.Failed("play_launch_failed"))
        }
    }
    override fun openStore(activity: Activity): Boolean {
        return try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${activity.packageName}")))
            true
        } catch (_: android.content.ActivityNotFoundException) {
            try {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${activity.packageName}")))
                true
            } catch (_: Exception) { false }
        }
    }
}
