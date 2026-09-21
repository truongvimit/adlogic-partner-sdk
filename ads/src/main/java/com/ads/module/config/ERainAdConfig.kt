package com.ads.module.config

import android.app.Application
import com.ads.module.config.settings.AdBehavior

open class ERainAdConfig(application: Application?) {

    companion object {
        const val ENVIRONMENT_DEVELOP = "develop"
        const val ENVIRONMENT_PRODUCTION = "production"

        const val DEFAULT_TOKEN_FACEBOOK_SDK = "client_token"
    }

    private var variantDev = false

    private var enableAdResume = false

    constructor(application: Application?, environment: String?) : this(application) {
        this.variantDev = environment!! == ENVIRONMENT_DEVELOP
    }

    open fun setEnvironment(environment: String?) {
        this.variantDev = environment!! == ENVIRONMENT_DEVELOP
    }

    private var adjustConfigField: AdjustConfig? = null

    /**
     * adjustConfig enable adjust and setup adjust token
     */
    open var adjustConfig: AdjustConfig?
        get() = adjustConfigField
        set(value) {
            adjustConfigField = value
        }

    open val application: Application? = application

    open val isVariantDev: Boolean?
        get() = variantDev

    /**
     * Sets the app-resume ad unit and switches app-resume on.
     *
     * A blank id switches it back off: enabling it anyway made AppOpenManager request an ad with
     * an empty unit, which GMA rejects with "Cannot determine request type" on every cold start.
     */
    open var idAdResume: String? = null
        set(value) {
            field = value
            enableAdResume = value != null && value.trim { it <= ' ' }.isNotEmpty()
        }

    open var listDeviceTest: List<String>? = ArrayList()

    open val isEnableAdResume: Boolean?
        get() = enableAdResume

    open val isEnableAdjust: Boolean?
        get() = adjustConfigField?.isEnableAdjust ?: false

    /**
     * intervalInterstitialAd: time between two interstitial ad impressions
     * unit: seconds
     */
    open var intervalInterstitialAd: Int =
        (AdBehavior.defaultNumber("interstitial.frequency.interval_ms") / 1000).toInt()

    open var facebookClientToken: String? = DEFAULT_TOKEN_FACEBOOK_SDK
}
