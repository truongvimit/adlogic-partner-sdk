package io.onboardkit.remote

import com.ads.module.config.settings.ScopeChain
import com.ads.module.config.settings.SettingsSnapshot

/**
 * Every remote-settable field of a full-screen page, with the scopes it reads, most specific first.
 *
 * The chain is data here, not an expression spelled out at each read. A new field is one entry, and
 * widening or narrowing a field's reach is one edit to its scope list — which is also what keeps two
 * sibling fields from drifting into different precedence. They had drifted: [SkipStyle] used to put
 * the host's own value ahead of `flow.fullscreen_skip_style`, so a shared scope reached OB5 and any
 * page that left the field alone, but never a page whose style the app had set in code.
 */
internal enum class FullScreenSetting(private val leaf: String, private vararg val shared: String) {
    SkipEnabled("skip.enabled", "onboarding.fullscreen.skip.enabled"),
    SkipDelayMs("skip.delay_ms", "onboarding.fullscreen.skip.delay_ms"),
    SkipStyle("skip.style", "onboarding.fullscreen.skip.style", "flow.fullscreen_skip_style"),

    /** Owned per page: no shared scope is declared, so nothing above one page can move its side. */
    SkipPosition("skip.position"),

    AutoNextEnabled("auto_next.enabled", "onboarding.fullscreen.auto_next.enabled"),
    AutoNextDelayMs("auto_next.delay_ms", "onboarding.fullscreen.auto_next.delay_ms");

    /** The host's own value is the fallback the returned chain takes, never a scope within it. */
    fun on(page: String, values: SettingsSnapshot = OnboardingSettings.values): ScopeChain =
        values.scoped("onboarding.steps.$page.fullscreen.$leaf", *shared)
}
