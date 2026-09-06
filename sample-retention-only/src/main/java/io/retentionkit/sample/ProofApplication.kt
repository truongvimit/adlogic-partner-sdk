package io.retentionkit.sample

import android.app.Application
import android.content.Intent
import io.retentionkit.core.*

class ProofApplication : Application() {
    internal lateinit var profile: ProofProfile
    internal var installStatus = "Not initialized"
    override fun onCreate() {
        super.onCreate()
        profile = ProfileFactory.create()
        installStatus = profile.install(this, RetentionOptions(
            featureProvider = RetentionFeatureProvider { context -> listOf(
                RetentionFeature("uppercase", context.getString(R.string.rk_proof_uppercase), R.drawable.rk_proof_tool),
                RetentionFeature("word_count", context.getString(R.string.rk_proof_words), R.drawable.rk_proof_tool),
            ) },
            router = RetentionRouter { context, _ -> Intent(context, ProofActivity::class.java) },
            // This proof app has no IAP. Setup remains false until its explicit UI action.
            initialUserState = RetentionUserState(entitlement = RetentionEntitlement.NON_SUBSCRIBER),
        ))
    }
}
