package com.ads.module.config

/**
 * Everything the Adjust integration needs, in one object.
 *
 * Adjust identifies events by six-character _tokens_ minted on its dashboard, never by
 * name. Every token field below defaults to empty and an empty token is skipped with a warning
 * rather than sent: `AdjustEvent("")` is accepted client-side, dropped server-side, and the
 * revenue disappears without a trace.
 */
open class AdjustConfig private constructor(
    enableAdjust: Boolean,
    adjustToken: String?,
    @Suppress("UNUSED_PARAMETER") primary: Unit?,
) {

    companion object {
        /**
         * Adjust's own source keys for the dedicated ad-revenue API.
         */
        const val AD_REVENUE_ADMOB = "admob_sdk"
        const val AD_REVENUE_APPLOVIN_MAX = "applovin_max_sdk"
    }

    constructor(enableAdjust: Boolean) : this(enableAdjust, "", null)

    constructor(enableAdjust: Boolean, adjustToken: String?) : this(enableAdjust, adjustToken, null)

    open var isEnableAdjust: Boolean = enableAdjust

    /** App token from the Adjust dashboard. Without it the SDK is never initialised. */
    open var adjustToken: String? = adjustToken

    /** Event token fired when a purchase completes. */
    open var eventNamePurchase: String? = ""

    /**
     * Event token fired on every paid ad impression, in addition to `Adjust.trackAdRevenue`.
     * Networks that cannot consume Adjust's ad-revenue API (TikTok, Meta) read this one instead.
     * Leave it empty unless a partner network asked for it — configuring it means the same money
     * reaches Adjust twice, once as ad revenue and once as event revenue.
     */
    open var eventAdImpression: String? = ""

    /**
     * Meta app id. Adjust needs it to forward install and event data to Meta; without it the
     * Meta-attributed campaigns in the Adjust dashboard stay empty. Same value as the
     * `facebook_app_id` string resource.
     */
    open var fbAppId: String? = ""
}
