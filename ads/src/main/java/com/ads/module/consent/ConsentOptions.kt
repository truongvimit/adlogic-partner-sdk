package com.ads.module.consent

/**
 * Knobs for the SDK's UMP flow and request fallback.
 *
 * @param timeoutMs how long to wait for UMP to answer before carrying on. It bounds the network
 *   round trip only — it is cancelled before a form is shown, so a user reading the
 *   form is never rushed. Expiring opens the SDK's ad-request fallback even without previous
 *   consent; it does not change UMP consent or enable personalization. Each new attempt waits
 *   again unless UMP itself already authorizes requests.
 * @param debug treats every device as an EEA test device; `null` follows the app's debuggable flag.
 * @param underAgeOfConsent tags the request under the age of consent, as GDPR requires for apps
 *   directed at children.
 */
data class ConsentOptions(
    val timeoutMs: Long = 20_000,
    val debug: Boolean? = null,
    val underAgeOfConsent: Boolean = false,
)
