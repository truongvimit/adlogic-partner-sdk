package io.onboardkit.ui.pool

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import io.onboardkit.ui.base.BaseOnboardActivity

/** Placeholder; replaced by the pooled resume screen. */
internal class ObPoolResumeActivity : BaseOnboardActivity() {
    override val excludeFromAppResume: Boolean = false
    override val restartFlowAfterProcessDeath: Boolean get() = false

    override fun onCreateSafe(savedInstanceState: Bundle?) {
        finish()
    }

    internal companion object {
        fun start(host: Activity) {
            host.startActivity(Intent(host, ObPoolResumeActivity::class.java))
        }
    }
}
