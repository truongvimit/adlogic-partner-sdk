package io.retentionkit.sample

import android.app.Application
import io.retentionkit.RetentionKit
import io.retentionkit.RetentionKitOptions
import io.retentionkit.core.RetentionOptions

internal object ProfileFactory {
    fun create(): ProofProfile = object : ProofProfile {
        override fun install(application: Application, options: RetentionOptions): String {
            // Real facade API specified by Ticket05; validate against its final compiling commit.
            // This profile must not become a manual list of modules that avoids linking the facade.
            val result = RetentionKit.install(application, RetentionKitOptions(
                featureProvider = options.featureProvider,
                router = options.router,
                localeProvider = options.localeProvider,
                initialUserState = options.initialUserState,
            ))
            return result.toString()
        }
    }
}
