package com.ads.module.consent

/**
 * Knobs for the UMP flow. Defaults suit a normal release build, so a host that wants the standard
 * behaviour configures nothing.
 *
 * @param timeoutMs how long to wait for UMP to answer before carrying on. It bounds the network
 *   round trip only — it is cancelled once a form is actually on screen, so a user reading the
 *   form is never rushed. Expiring lets the flow continue rather than blocking ads: a slow
 *   network is not a refusal.
 * @param debug forces the EEA form to appear on a test device regardless of real geography. `null`
 *   follows the host's own debuggable flag, which is what the module this replaced did with
 *   `BuildConfig.DEBUG`; pass `true`/`false` to override.
 * @param testDeviceHashedId the id UMP logs on first run; required for [debug] to take effect.
 * @param underAgeOfConsent tags the request under the age of consent, as GDPR requires for apps
 *   directed at children.
 */
data class ConsentOptions(
    val timeoutMs: Long = 20_000,
    val debug: Boolean? = null,
    val testDeviceHashedId: String? = null,
    val underAgeOfConsent: Boolean = false,
)
