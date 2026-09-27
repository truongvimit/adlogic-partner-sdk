package com.itg.template.ui.component.splash

import androidx.lifecycle.lifecycleScope
import com.ads.module.billing.Billing
import io.onboardkit.ui.splash.ObSplashActivity
import io.paykit.PayKit
import io.suite.firebase.FirebaseUpdateConfig
import kotlinx.coroutines.launch

/** SDK owns splash sequencing; process-owned remote integrations live in AdsAppManager. */
class SplashActivity : ObSplashActivity() {
    override fun readForceUpdateConfig() = FirebaseUpdateConfig.activated()

    /** Refresh entitlement alongside consent; every ad gate reads the current published value. */
    override suspend fun onInitBilling() {
        lifecycleScope.launch { PayKit.sync(timeoutMs = 3_000) }
        Billing.awaitReady(timeoutMs = 5_000)
    }
}
