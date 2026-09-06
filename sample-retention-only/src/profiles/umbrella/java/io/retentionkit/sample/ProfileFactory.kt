package io.retentionkit.sample

import android.app.Application
import android.app.Activity
import android.content.Intent
import io.retentionkit.RetentionDispatchResult
import io.retentionkit.core.*
import io.retentionkit.RetentionKit
import io.retentionkit.RetentionKitOptions

internal object ProfileFactory {
    fun create(): ProofProfile = object : ProofProfile {
        override fun install(application: Application, options: RetentionOptions): String {
            // Install and dispatch through the actual umbrella facade, not a manual module list.
            val result = RetentionKit.install(application, RetentionKitOptions(
                featureProvider = options.featureProvider,
                router = options.router,
                localeProvider = options.localeProvider,
                initialUserState = options.initialUserState,
                eventSink = options.eventSink,
            ))
            return result.toString()
        }
        override fun capture(runtime: RetentionRuntime, intent: Intent?) = RetentionKit.get()!!.capture(intent)
        override fun dispatchPending(runtime: RetentionRuntime, token: String): ProofRoute = when (val result = RetentionKit.get()!!.dispatchPending(token)) {
            is RetentionDispatchResult.Navigate -> ProofRoute.Navigate(result.entry)
            RetentionDispatchResult.SdkHandled -> ProofRoute.SdkHandled
            is RetentionDispatchResult.Unavailable -> ProofRoute.Blocked(result.reason)
        }
        override fun actions(activity: Activity, runtime: RetentionRuntime): List<ProofAction> {
            val kit = RetentionKit.get() ?: return emptyList()
            return listOf(
                ProofAction("Refresh quiet notifications") { kit.notifications?.refreshForegroundNotifications().toString() },
                ProofAction("Ask to pin widget") { kit.widgets?.requestPin().toString() },
                ProofAction("Open exit feedback") { kit.feedback?.show().toString() },
                ProofAction("Open Play Store") { kit.review?.openStore().toString() },
            )
        }
    }
}
