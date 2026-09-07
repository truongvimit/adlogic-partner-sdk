package io.retentionkit.sample

import android.app.Application
import io.retentionkit.core.*

class ProofApplication : Application() {
    internal lateinit var profile: ProofProfile
    internal var installStatus = "Not initialized"
    private val events = ArrayDeque<String>()
    @Synchronized internal fun recentEvents(): List<String> = events.toList()
    @Synchronized private fun recordEvent(event: RetentionEvent) {
        if (events.size >= 20) events.removeFirst()
        events.addLast(event.name + (event.attributes["reason"]?.let { " ($it)" } ?: ""))
    }
    override fun onCreate() {
        super.onCreate()
        profile = ProfileFactory.create()
        installStatus = profile.install(this, RetentionOptions(
            featureProvider = RetentionFeatureProvider { context -> listOf(
                RetentionFeature("uppercase", context.getString(R.string.rk_proof_uppercase), R.drawable.rk_proof_tool),
                RetentionFeature("word_count", context.getString(R.string.rk_proof_words), R.drawable.rk_proof_tool),
            ) },
            router = RetentionSplashRouter(ProofSplashActivity::class.java),
            eventSink = RetentionEventSink(::recordEvent),
            // This proof app has no IAP. Setup remains false until its explicit UI action.
            initialUserState = RetentionUserState(entitlement = RetentionEntitlement.NON_SUBSCRIBER),
        ))
    }
}
