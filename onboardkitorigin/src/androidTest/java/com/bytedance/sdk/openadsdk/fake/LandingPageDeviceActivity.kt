package com.bytedance.sdk.openadsdk.fake

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/** An opaque in-process landing page in Pangle's namespace, as TTLandingPageActivity is. */
class LandingPageDeviceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "Fake Pangle landing page" })
        current = this
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var current: LandingPageDeviceActivity? = null
    }
}
