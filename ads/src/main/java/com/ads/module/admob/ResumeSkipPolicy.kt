package com.ads.module.admob

import android.app.Activity

/**
 * Optional host eligibility at a resume opportunity. Both queries must be side-effect free: return
 * null to permit, or a stable skip reason; never consume one-shot state or emit events. The shared
 * hook also gates a host's alternate welcome flow. App-open mode, its own unit and placement flag
 * belong only in the second hook, so disabling OPEN does not disable WELCOME.
 */
fun interface ResumeSkipPolicy {
    fun skipReasonFor(activity: Activity): String?

    /** Extra OPEN-only eligibility. Ordinary lambda policies need no second implementation. */
    fun appOpenSkipReasonFor(activity: Activity): String? = null
}
