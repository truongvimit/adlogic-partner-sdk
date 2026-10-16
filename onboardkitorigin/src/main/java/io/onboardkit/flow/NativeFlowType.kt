package io.onboardkit.flow

import com.ads.module.config.AdRemoteConfig
import com.ads.module.helper.adnative.pool.PoolParity
import io.onboardkit.ads.NextScreenTiming

/** The post-splash flow `native_flow.type` picks, once per run. */
internal enum class NativeFlowType {
    /** One activity per screen, each native requested for its own placement. */
    PER_SCREEN,

    /** LFO, pager and Privacy/Goal in one activity, every native slot drawing from one shared pool. */
    SHARED_POOL,
}

/** How the pooled flow applies the language picked on its LFO before the next stage. */
internal enum class PoolLocaleApply {
    /** Updates the activity's resources and replaces the stage fragment, as DashDrama does. */
    IN_PLACE,

    /** Recreates the activity so its base context carries the new locale. */
    RECREATE,
}

/**
 * Every `native_flow` value a SHARED_POOL run uses, latched at the run's start so a remote refresh
 * cannot change the flow halfway through. Defaults equal the bundled document: DashDrama 1.0.6's,
 * apart from the deviations [PoolParity] names; the classic keys these mirror keep their own
 * defaults for PER_SCREEN.
 */
internal data class NativeFlowSnapshot(
    val type: NativeFlowType = NativeFlowType.PER_SCREEN,
    val parity: PoolParity = PoolParity(),
    val adoptSplashLanguage1: Boolean = true,
    val localeApply: PoolLocaleApply = PoolLocaleApply.IN_PLACE,
    val exitNextScreenTiming: NextScreenTiming = NextScreenTiming.AFTER_AD,
    val doneHiddenGone: Boolean = true,
    val pagerSmoothScroll: Boolean = true,
    /** Keep the selected page and its immediate neighbour prepared. */
    val pagerOffscreenPages: Int = 1,
    val resumeOnGoals: Boolean = true,
    val stuckLoadingLogMs: Long = 60_000L,
    val viewDestroyOnTeardown: Boolean = true,
    val lfoConfirmRevealDelayMs: Long = 3_000L,
    val selectNextRevealDelayMs: Long = 3_000L,
    val goalsExitReuseSplashInter: Boolean = true,
    val fullscreenSkipDelayMs: Long = 2_000L,
    /** Counted from page selection, not from the ad's bind. */
    val fullscreenAutoNextDelayMs: Long = 10_000L,
    /** How long the app stays in the background before a return opens the pooled resume screen;
     * defaults to the same 2 s delay as `open_resume.app_resume_load_delay_ms`. */
    val resumeMinBackgroundMs: Long = AdRemoteConfig.DEFAULT_APP_RESUME_LOAD_DELAY_MS,
)

/** Anything but an exact type name runs the classic flow. */
internal fun resolveNativeFlowType(raw: String?): NativeFlowType =
    NativeFlowType.entries.firstOrNull { it.name == raw } ?: NativeFlowType.PER_SCREEN
