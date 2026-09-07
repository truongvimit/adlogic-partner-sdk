package io.retentionkit.integration

import android.content.Intent
import android.os.Bundle
import io.onboardkit.ui.splash.ObSplashActivity
import io.retentionkit.core.RetentionEntryCodec
import io.retentionkit.core.RetentionEntryDecodeResult

/** Standard Onboard splash with exact retention capture/recreation/new-delivery semantics. */
abstract class RetentionSplashActivity : ObSplashActivity() {
    override fun onCreateSafe(savedInstanceState: Bundle?) {
        val restored = RetentionEntryCodec.decode(savedInstanceState?.getString(STATE_ENTRY))
        if (restored is RetentionEntryDecodeResult.Valid) RetentionEntryCodec.write(intent, restored.entry)
        RetentionSuite.get()?.captureSplash(intent)
        super.onCreateSafe(savedInstanceState)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        val entry = (RetentionEntryCodec.read(intent) as? RetentionEntryDecodeResult.Valid)?.entry
        entry?.let { outState.putString(STATE_ENTRY, RetentionEntryCodec.encode(it)) }
        super.onSaveInstanceState(outState)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // An in-flight splash has already selected its ad policy. A newer tap owns a new splash.
        startActivity(Intent(intent).setClass(this, javaClass).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
    private companion object { const val STATE_ENTRY = "retention.splash.entry" }
}
