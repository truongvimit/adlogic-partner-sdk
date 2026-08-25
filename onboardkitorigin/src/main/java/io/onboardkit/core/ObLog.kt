package io.onboardkit.core

import android.util.Log

/**
 * One tag for the whole first-open flow, so `adb logcat -s OB_FLOW` is the entire story:
 * which phase started, what the gate decided and why, which tier filled, when the ad became
 * visible, and which screen it handed off to.
 *
 * Every line is `section | key=value …` on purpose — greppable by placement
 * (`logcat -s OB_FLOW | grep splash_inter`) and by outcome (`… | grep BLOCKED`).
 */
object ObLog {

    const val TAG = "OB_FLOW"

    /** Off in release by default; flip with [io.onboardkit.OnboardingSdk.setFlowLogging]. */
    @Volatile
    var enabled: Boolean = true

    /** Wall clock of the first logged line, so every entry carries a flow-relative timestamp. */
    @Volatile
    private var originMs: Long = 0L

    enum class Section(private val label: String) {
        SPLASH("SPLASH"),
        REMOTE("REMOTE"),
        GATE("GATE  "),
        LOAD("LOAD  "),
        SHOW("SHOW  "),
        NAV("NAV   "),
        RESUME("RESUME"),
        SCREEN("SCREEN"),
        PRELOAD("PRELD "),
        ;

        override fun toString(): String = label
    }

    /** Marks t=0. Called when the splash starts so timings read as "since app launch". */
    fun startFlow() {
        originMs = System.currentTimeMillis()
        d(Section.SPLASH, "flow_start")
    }

    fun d(section: Section, message: String) {
        if (!enabled) return
        Log.d(TAG, "${elapsed()} $section | $message")
    }

    fun w(section: Section, message: String) {
        if (!enabled) return
        Log.w(TAG, "${elapsed()} $section | $message")
    }

    /**
     * An integration mistake, not flow tracing — so this one ignores [enabled].
     *
     * `setFlowLogging(false)` is the recommended release setting, and a partner who has taken the
     * trace off is exactly the one who most needs to be told their config does not work.
     */
    fun e(section: Section, message: String) {
        Log.e(TAG, "${elapsed()} $section | $message")
    }

    private fun elapsed(): String {
        if (originMs == 0L) return "[    ----]"
        val ms = System.currentTimeMillis() - originMs
        return "[%+8d]".format(ms)
    }
}
