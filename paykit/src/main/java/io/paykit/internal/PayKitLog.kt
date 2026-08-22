package io.paykit.internal

import android.util.Log
import io.paykit.PayKitLogLevel

/** One tag for the whole paywall, gated by `PayKitConfig.logLevel`. */
internal object PayKitLog {

    const val TAG = "PayKit"

    @Volatile
    var level: PayKitLogLevel = PayKitLogLevel.WARN

    fun d(message: String) {
        if (allows(PayKitLogLevel.DEBUG)) Log.d(TAG, message)
    }

    fun i(message: String) {
        if (allows(PayKitLogLevel.INFO)) Log.i(TAG, message)
    }

    fun w(message: String) {
        if (allows(PayKitLogLevel.WARN)) Log.w(TAG, message)
    }

    fun e(message: String, error: Throwable? = null) {
        if (allows(PayKitLogLevel.ERROR)) Log.e(TAG, message, error)
    }

    // NONE..DEBUG are declared least-to-most verbose, so ordinal comparison is the whole gate.
    private fun allows(required: PayKitLogLevel): Boolean = level.ordinal >= required.ordinal
}
