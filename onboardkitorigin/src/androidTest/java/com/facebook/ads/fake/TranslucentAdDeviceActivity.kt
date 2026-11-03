package com.facebook.ads.fake

import android.app.Activity
import android.os.Bundle

/** A translucent ad Activity in Meta's namespace: the host under it only pauses. */
class TranslucentAdDeviceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = this
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var current: TranslucentAdDeviceActivity? = null
    }
}
