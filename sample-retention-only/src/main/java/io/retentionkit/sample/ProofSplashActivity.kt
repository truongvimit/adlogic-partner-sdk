package io.retentionkit.sample

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.TextView
import io.retentionkit.core.RetentionEntryAcceptance
import io.retentionkit.core.RetentionRuntime

/**
 * Vendor-free consumer front door. This proof has no ad dependency; the full example demonstrates
 * OnboardKit's ad completion policy. All external entries still visit a real Splash Activity.
 */
class ProofSplashActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var selectedExtras: Bundle? = null
    private var resumed = false
    private var forwarded = false
    private val continueEntry = Runnable {
        if (!resumed || forwarded || isFinishing || isDestroyed) return@Runnable
        startActivity(Intent(this, ProofActivity::class.java).apply { selectedExtras?.let(::putExtras) })
        forwarded = true
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            setText(R.string.rk_proof_name)
            textSize = 24f
            gravity = Gravity.CENTER
        })
        if (savedInstanceState?.containsKey(STATE_SELECTION) == true) {
            selectedExtras = savedInstanceState.getBundle(STATE_SELECTION)?.let(::Bundle)
            forwarded = savedInstanceState.getBoolean(STATE_FORWARDED)
        } else capture(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        forwarded = false
        capture(intent)
        scheduleContinuation()
    }

    private fun capture(intent: Intent?) {
        selectedExtras = null
        val runtime = RetentionRuntime.get() ?: return
        val accepted = (application as ProofApplication).profile.capture(runtime, intent)
        if (accepted is RetentionEntryAcceptance.Accepted) {
            // Reusable PendingIntents are materialized by capture. Keep those rewritten extras,
            // never re-read an older pending item or consume before the actual destination.
            selectedExtras = intent?.extras?.let(::Bundle)
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        scheduleContinuation()
    }

    private fun scheduleContinuation() {
        main.removeCallbacks(continueEntry)
        // Small visible Splash for this offline proof only, not an ad timeout or delivery claim.
        if (resumed && !forwarded) main.postDelayed(continueEntry, 300)
    }

    override fun onPause() {
        resumed = false
        main.removeCallbacks(continueEntry)
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBundle(STATE_SELECTION, selectedExtras?.let(::Bundle))
        outState.putBoolean(STATE_FORWARDED, forwarded)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val STATE_SELECTION = "proof.splash.selection"
        private const val STATE_FORWARDED = "proof.splash.forwarded"
    }
}
