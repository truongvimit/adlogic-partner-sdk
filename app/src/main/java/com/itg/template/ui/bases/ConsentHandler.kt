package com.itg.template.ui.bases

import android.app.Activity
import android.os.Handler
import android.os.Looper
import com.google.android.ump.ConsentInformation
import com.google.android.ump.FormError
import com.itg.iaumodule.IAdConsentCallBack
import com.itg.iaumodule.ITGAdConsent
import com.itg.template.BuildConfig
import com.itg.template.app.AppConstants
import com.itg.template.data.pref.AppSharedPref
import com.itg.template.utils.ITGTrackingHelper
import com.itg.template.utils.ITGTrackingHelper.logEvent

class ConsentHandler(
    private val activity: Activity,
    private val appSharedPref: AppSharedPref,
    private val trackingSuffix: Int,
    private val onConsentFlowCompleted: (canPersonalized: Boolean) -> Unit,
    private val onConsentSuccess: ((canPersonalized: Boolean) -> Unit)? = null,
    private val onNotUsingAdConsent: (() -> Unit)? = null
) : IAdConsentCallBack {
    private var canPersonalized = true
    private var consentCallbackHandled = false
    private val consentTimeoutHandler = Handler(Looper.getMainLooper())
    private val consentTimeoutRunnable = Runnable {
        if (consentCallbackHandled) {
            return@Runnable
        }
        consentCallbackHandled = true
        onConsentFlowCompleted(true)
    }

    fun requestConsent() {
        logEvent(getLoadConsentEvent(), null)
        consentCallbackHandled = false
        consentTimeoutHandler.postDelayed(
            consentTimeoutRunnable,
            AppConstants.DEFAULT_TIME_OUT_GDPR
        )
        ITGAdConsent.loadAndShowConsent(true, this)
    }

    fun clear() {
        consentTimeoutHandler.removeCallbacks(consentTimeoutRunnable)
    }

    override fun getCurrentActivity(): Activity = activity

    override fun isDebug(): Boolean = BuildConfig.DEBUG

    override fun isUnderAgeAd(): Boolean = false

    override fun onConsentError(formError: FormError) {
        if (consentCallbackHandled) {
            return
        }
        consentCallbackHandled = true
        clear()
        canPersonalized = true
        logEvent(getConsentErrorEvent(), null)
        onConsentFlowCompleted(canPersonalized)
    }

    override fun onConsentStatus(consentStatus: Int) {
        canPersonalized = consentStatus != ConsentInformation.ConsentStatus.REQUIRED
    }

    override fun onConsentSuccess(consentAccepted: Boolean) {
        if (consentCallbackHandled) {
            return
        }
        consentCallbackHandled = true
        clear()
        canPersonalized = consentAccepted
        handleConsentSelection()
    }

    override fun onNotUsingAdConsent() {
        if (consentCallbackHandled) {
            return
        }
        consentCallbackHandled = true
        clear()
        logEvent(getNotUsingDisplayConsentEvent(), null)
        canPersonalized = true
        onNotUsingAdConsent?.invoke()
        onConsentFlowCompleted(canPersonalized)
    }

    override fun onRequestShowDialog() {
        logEvent(getDisplayConsentEvent(), null)
    }

    override fun testDeviceID(): String = "ED3576D8FCF2F8C52AD8E98B4CFA4005"

    private fun handleConsentSelection() {
        if (canPersonalized) {
            logEvent(getAgreeConsentEvent(), null)
            appSharedPref.isConfirmConsent = true
        } else {
            ITGAdConsent.resetConsentDialog()
            logEvent(getRefuseConsentEvent(), null)
        }
        onConsentSuccess?.invoke(canPersonalized) ?: onConsentFlowCompleted(canPersonalized)
    }

    private fun getLoadConsentEvent(): String = when (trackingSuffix) {
        1 -> ITGTrackingHelper.LOAD_CONSENT_1
        2 -> ITGTrackingHelper.LOAD_CONSENT_2
        else -> ITGTrackingHelper.LOAD_CONSENT_1
    }

    private fun getDisplayConsentEvent(): String = when (trackingSuffix) {
        1 -> ITGTrackingHelper.DISPLAY_CONSENT_1
        2 -> ITGTrackingHelper.DISPLAY_CONSENT_2
        else -> ITGTrackingHelper.DISPLAY_CONSENT_1
    }

    private fun getNotUsingDisplayConsentEvent(): String = when (trackingSuffix) {
        1 -> ITGTrackingHelper.NOT_USING_DISPLAY_CONSENT_1
        2 -> ITGTrackingHelper.NOT_USING_DISPLAY_CONSENT_2
        else -> ITGTrackingHelper.NOT_USING_DISPLAY_CONSENT_1
    }

    private fun getAgreeConsentEvent(): String = when (trackingSuffix) {
        1 -> ITGTrackingHelper.AGREE_CONSENT_1
        2 -> ITGTrackingHelper.AGREE_CONSENT_2
        else -> ITGTrackingHelper.AGREE_CONSENT_1
    }

    private fun getRefuseConsentEvent(): String = when (trackingSuffix) {
        1 -> ITGTrackingHelper.REFUSE_CONSENT_1
        2 -> ITGTrackingHelper.REFUSE_CONSENT_2
        else -> ITGTrackingHelper.REFUSE_CONSENT_1
    }

    private fun getConsentErrorEvent(): String = when (trackingSuffix) {
        1 -> ITGTrackingHelper.CONSENT_ERROR_1
        2 -> ITGTrackingHelper.CONSENT_ERROR_2
        else -> ITGTrackingHelper.CONSENT_ERROR_1
    }
}

