package io.paykit

/**
 * Checkpoints a host may gate a paywall on.
 *
 * [key] is the wire value in the remote `placements` array, so renaming a constant never changes
 * what the remote-config console has to send.
 */
enum class PaywallPlacement(val key: String) {
    SPLASH("splash"),
    AFTER_ONBOARDING("after_onboarding"),
    HOME("home"),
    SETTING("setting"),
    FEATURE_LOCK("feature_lock"),
    OTHER("other"),
    ;

    companion object {

        /** Null for an unknown key: a typo in remote config must not resolve to some placement. */
        @JvmStatic
        fun fromKey(key: String): PaywallPlacement? = entries.firstOrNull { it.key == key }
    }
}
