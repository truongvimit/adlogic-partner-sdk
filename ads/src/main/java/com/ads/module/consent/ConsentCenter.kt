package com.ads.module.consent

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.util.Log
import com.ads.module.event.MmpTracking
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentForm
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.FormError
import io.trackkit.ConsentState
import io.trackkit.Tracker
import io.trackkit.TrackkitEvents
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Coordinates UMP consent for this process. UMP's [canRequestAds] authority is separate from
 * [canPersonalize]: an answered form can allow an ad request without allowing personalization.
 * Hosts using their own consent provider must publish its decision with [setHostConsent].
 */
object ConsentCenter {
    internal var umpClient: UmpClient = PlatformUmpClient

    private const val TAG = "ConsentCenter"
    private const val GOOGLE_VENDOR_ID = 755
    private val PURPOSES_REQUIRING_CONSENT = listOf(1, 3, 4)
    private val PURPOSES_ALLOWING_LEGITIMATE_INTEREST = listOf(2, 7, 9, 10)
    private const val PREF_FILE_SUFFIX = "_preferences"
    private const val PREF_CONSENT = "ads_consent"
    private const val KEY_CONSENT_ACCEPTED = "consent_accepted"
    private const val KEY_CONSENT_NOT_REQUIRED = "consent_not_required"
    private const val KEY_PURPOSE_CONSENTS = "IABTCF_PurposeConsents"
    private const val KEY_VENDOR_CONSENTS = "IABTCF_VendorConsents"
    private const val KEY_VENDOR_LI = "IABTCF_VendorLegitimateInterests"
    private const val KEY_PURPOSE_LI = "IABTCF_PurposeLegitimateInterests"
    private const val STATUS_GRANTED = "granted"
    private const val STATUS_DENIED = "denied"
    private const val STATUS_NOT_REQUIRED = "not_required"
    private const val STATUS_ERROR = "error"
    private val EEA_COUNTRIES = listOf(
        "AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "GR", "HU", "IE",
        "IT", "LV", "LT", "LU", "MT", "NL", "PL", "PT", "RO", "SK", "SI", "ES", "SE",
    )
    private val UK_COUNTRIES = listOf("GB", "GG", "IM", "JE")

    private val _state = MutableStateFlow(ConsentState.UNKNOWN)
    val state: StateFlow<ConsentState> = _state.asStateFlow()
    private val _requestEligibility = MutableStateFlow(false)

    /** Current request authority, including previous-session UMP consent after this launch's update. */
    val requestEligibility: StateFlow<Boolean> = _requestEligibility.asStateFlow()

    private val requested = AtomicBoolean(false)
    @Volatile private var consentInformation: ConsentInformation? = null
    @Volatile private var umpUpdateRequested = false
    @Volatile private var applicationContext: Context? = null
    @Volatile private var options = ConsentOptions()
    @Volatile private var hostConsent: HostConsent? = null
    @Volatile private var generation = 0L
    @Volatile private var pendingFlow: PendingFlow? = null
    @Volatile private var visibleForm: VisibleForm? = null
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null

    private data class HostConsent(val allowed: Boolean, val personalized: Boolean)

    private class PendingFlow(
        val generation: Long,
        activity: Activity,
        val screen: String,
        onFormAnswered: ((Boolean) -> Unit)?,
        onCompleted: (Boolean) -> Unit,
    ) {
        val activity = WeakReference(activity)
        var onFormAnswered: ((Boolean) -> Unit)? = onFormAnswered
        var onCompleted: ((Boolean) -> Unit)? = onCompleted

        fun clearCallbacks() {
            onFormAnswered = null
            onCompleted = null
        }
    }

    /** A host handoff can settle the request, but cannot dismiss UMP's visible form. */
    private class VisibleForm(activity: Activity) {
        val activity = WeakReference(activity)
    }

    @JvmStatic
    fun configure(newOptions: ConsentOptions) {
        options = newOptions
    }

    @JvmStatic
    fun options(): ConsentOptions = options

    /**
     * Reads UMP's request authorization, or an explicit host-managed decision. A timeout, network
     * error, remembered SDK preference, and personalization choice cannot grant this permission.
     */
    @JvmStatic
    fun canRequestAds(): Boolean = hostConsent?.allowed
        ?: (umpUpdateRequested && consentInformation?.canRequestAds() == true)

    /**
     * Selects host-managed consent. Call on the main thread after the host consent provider has
     * resolved. A pending UMP request completes with this decision; its late callbacks are ignored.
     */
    @JvmStatic
    fun setHostConsent(canRequestAds: Boolean, personalized: Boolean) {
        val onCompleted = pendingFlow?.onCompleted
        invalidateFlow()
        hostConsent = HostConsent(canRequestAds, canRequestAds && personalized)
        publishAuthority()
        onCompleted?.invoke(canRequestAds)
    }

    /** Returns to UMP authority; the next [request] refreshes it without clearing UMP's stored consent. */
    @JvmStatic
    fun clearHostConsent() {
        if (hostConsent == null) return
        hostConsent = null
        requested.set(false)
        publishAuthority()
    }

    /**
     * Refreshes UMP once per successful process flow. Errors and unanswered attempts remain
     * retryable. UMP is consulted on every new process, even when old SDK preferences exist.
     *
     * [onCompleted] reports current request eligibility, not personalization. Previous UMP consent
     * can permit requests while the update is pending; [requestEligibility] publishes that as soon
     * as UMP makes it available. The network deadline does not time out a form the user is reading.
     *
     * Call on the main thread. Completion runs at most once, except that [detach] abandons a dead
     * screen's callback. [onFormAnswered] runs only after a form shown by this call was answered.
     */
    @JvmStatic
    @JvmOverloads
    fun request(
        activity: Activity,
        screen: String = "splash",
        onFormAnswered: ((personalized: Boolean) -> Unit)? = null,
        onCompleted: (mayRequestAds: Boolean) -> Unit,
    ) {
        applicationContext = activity.applicationContext
        if (hostConsent != null) {
            publishAuthority()
            onCompleted(canRequestAds())
            return
        }
        val existing = pendingFlow
        if (existing != null && !isResolving()) invalidateFlow()
        if (!requested.compareAndSet(false, true)) {
            publishAuthority()
            onCompleted(canRequestAds())
            return
        }
        val flow = PendingFlow(++generation, activity, screen, onFormAnswered, onCompleted)
        pendingFlow = flow
        Tracker.track(TrackkitEvents.ConsentEvents.Requested())
        armTimeout(flow)
        loadAndShowConsent(activity, flow)
    }

    /** Releases only this screen's pending flow. A recreated screen can ask again immediately. */
    @JvmStatic
    fun detach(activity: Activity) {
        if (visibleForm?.activity?.get() === activity) visibleForm = null
        if (pendingFlow?.activity?.get() === activity) invalidateFlow()
    }

    private fun ownsFlow(flow: PendingFlow): Boolean =
        pendingFlow === flow && generation == flow.generation

    private fun invalidateFlow() {
        generation++
        cancelTimeout()
        pendingFlow?.clearCallbacks()
        pendingFlow = null
        requested.set(false)
    }

    /** Whether a live screen is waiting for the UMP update or the user's answer. */
    @JvmStatic
    fun isResolving(): Boolean {
        val owner = pendingFlow?.activity?.get() ?: return false
        return !owner.isFinishing && !owner.isDestroyed
    }

    /** Whether UMP has a form on the live screen, separate from a background update. */
    @JvmStatic
    fun isFormShowing(): Boolean {
        val owner = visibleForm?.activity?.get() ?: return false
        return !owner.isFinishing && !owner.isDestroyed
    }

    /** Whether personalization has a known answer; this is not request authorization. */
    @JvmStatic
    fun hasAnswered(): Boolean = _state.value != ConsentState.UNKNOWN

    /** Current UMP or explicit host authorization. Old SDK preference flags are intentionally ignored. */
    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun isAlreadyResolved(context: Context): Boolean = canRequestAds()

    private fun isDebugFlow(context: Context): Boolean = options.debug
        ?: ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)

    private fun armTimeout(flow: PendingFlow) {
        cancelTimeout()
        val runnable = Runnable {
            if (!ownsFlow(flow)) return@Runnable
            Log.w(TAG, "consent update timed out after ${options.timeoutMs}ms")
            finish(flow, retryable = true, error = true)
        }
        timeoutRunnable = runnable
        timeoutHandler.postDelayed(runnable, options.timeoutMs)
    }

    private fun cancelTimeout() {
        timeoutRunnable?.let(timeoutHandler::removeCallbacks)
        timeoutRunnable = null
    }

    private fun loadAndShowConsent(activity: Activity, flow: PendingFlow) {
        val information = umpClient.consentInformation(activity)
        consentInformation = information
        val params = ConsentRequestParameters.Builder()
            .setTagForUnderAgeOfConsent(options.underAgeOfConsent)
            .apply {
                if (isDebugFlow(activity)) {
                    val debugSettings = ConsentDebugSettings.Builder(activity)
                        .setDebugGeography(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA)
                        .apply { options.testDeviceHashedId?.let(::addTestDeviceHashedId) }
                        .setForceTesting(true)
                        .build()
                    setConsentDebugSettings(debugSettings)
                }
            }
            .build()
        umpUpdateRequested = true
        information.requestConsentInfoUpdate(
            activity,
            params,
            {
                if (!ownsFlow(flow)) return@requestConsentInfoUpdate
                publishAuthority()
                when (information.consentStatus) {
                    ConsentInformation.ConsentStatus.NOT_REQUIRED,
                    ConsentInformation.ConsentStatus.OBTAINED -> finish(flow)
                    ConsentInformation.ConsentStatus.REQUIRED -> {
                        val owner = flow.activity.get()
                        if (information.isConsentFormAvailable && owner != null) loadForm(owner, information, flow)
                        else finish(flow, retryable = true, error = true)
                    }
                    else -> finish(flow, retryable = true, error = true)
                }
            },
            { error ->
                if (ownsFlow(flow)) onError(flow, error)
            },
        )
        // UMP restores previous-session status synchronously when update is called. This must run
        // even without network, before the asynchronous result, so eligible sessions retain ads.
        publishAuthority()
    }

    private fun loadForm(activity: Activity, information: ConsentInformation, flow: PendingFlow) {
        umpClient.loadForm(
            activity,
            { form: ConsentForm ->
                if (!ownsFlow(flow)) return@loadForm
                when (information.consentStatus) {
                    ConsentInformation.ConsentStatus.NOT_REQUIRED,
                    ConsentInformation.ConsentStatus.OBTAINED -> finish(flow)
                    ConsentInformation.ConsentStatus.REQUIRED -> {
                        val owner = flow.activity.get()
                        if (owner == null || owner.isFinishing || owner.isDestroyed) {
                            finish(flow, retryable = true, error = true)
                            return@loadForm
                        }
                        cancelTimeout()
                        val displayed = VisibleForm(owner)
                        visibleForm = displayed
                        Tracker.track(TrackkitEvents.ConsentEvents.Shown())
                        form.show(owner) { error ->
                            if (visibleForm === displayed) visibleForm = null
                            if (!ownsFlow(flow)) return@show
                            if (error != null) onError(flow, error)
                            else finish(flow, formAnswered = true)
                        }
                    }
                    else -> finish(flow, retryable = true, error = true)
                }
            },
            { error -> if (ownsFlow(flow)) onError(flow, error) },
        )
    }

    private fun onError(flow: PendingFlow, error: FormError) {
        Log.w(TAG, "consent error ${error.errorCode}: ${error.message}")
        finish(flow, retryable = true, error = true, errorCode = error.errorCode)
    }

    private fun finish(
        flow: PendingFlow,
        retryable: Boolean = false,
        error: Boolean = false,
        errorCode: Int? = null,
        formAnswered: Boolean = false,
    ) {
        if (!ownsFlow(flow)) return
        cancelTimeout()
        val onCompleted = flow.onCompleted
        val onFormAnswered = flow.onFormAnswered
        flow.clearCallbacks()
        pendingFlow = null
        publishAuthority()
        val allowed = canRequestAds()
        val personalized = canPersonalize()
        val answered = hasAnswered()
        if (retryable || !allowed) requested.set(false)
        val status = when {
            error || !answered -> STATUS_ERROR
            consentInformation?.consentStatus == ConsentInformation.ConsentStatus.NOT_REQUIRED -> STATUS_NOT_REQUIRED
            personalized -> STATUS_GRANTED
            else -> STATUS_DENIED
        }
        Tracker.track(TrackkitEvents.ConsentEvents.Result(status, errorCode, flow.screen))
        onCompleted?.invoke(allowed)
        if (formAnswered && answered) onFormAnswered?.invoke(personalized)
    }

    /** Publishes choices before eligibility so request observers see the matching personalization. */
    private fun publishAuthority() {
        val host = hostConsent
        val allowed = canRequestAds()
        val choice = when {
            host != null -> if (host.personalized) ConsentState.GRANTED else ConsentState.DENIED
            !allowed -> ConsentState.UNKNOWN
            consentInformation?.consentStatus == ConsentInformation.ConsentStatus.NOT_REQUIRED -> ConsentState.GRANTED
            consentInformation?.consentStatus == ConsentInformation.ConsentStatus.OBTAINED -> {
                if (applicationContext?.let(::canShowPersonalizedAds) == true) ConsentState.GRANTED
                else ConsentState.DENIED
            }
            else -> ConsentState.UNKNOWN
        }
        _state.value = choice
        Tracker.setConsent(analytics = true, ads = choice == ConsentState.GRANTED)
        MmpTracking.setConsent(true, choice == ConsentState.GRANTED)
        _requestEligibility.value = allowed
    }

    /** Unknown consent is conservative; use [canRequestAds] separately before sending requests. */
    @JvmStatic
    fun canPersonalize(): Boolean = _state.value == ConsentState.GRANTED

    /**
     * Clears UMP consent for debugging/testing only. This is not a production withdrawal API;
     * production privacy choices must use the consent provider's privacy-options flow. Resetting
     * storage does not dismiss a UMP form already on screen.
     */
    @JvmStatic
    fun reset(context: Context) {
        invalidateFlow()
        (consentInformation ?: umpClient.consentInformation(context.applicationContext)).reset()
        consentInformation = null
        umpUpdateRequested = false
        hostConsent = null
        applicationContext = null
        context.applicationContext.getSharedPreferences(PREF_CONSENT, Context.MODE_PRIVATE).edit()
            .remove(KEY_CONSENT_ACCEPTED)
            .remove(KEY_CONSENT_NOT_REQUIRED)
            .apply()
        publishAuthority()
    }

    // -----------------------------------------------------------------------
    // TCF string reading — what the IAB framework stores after the form closes
    // -----------------------------------------------------------------------

    /**
     * Reads the IAB TCF v2 strings UMP wrote and decides whether an AdMob request may be
     * personalised: every purpose in [PURPOSES_REQUIRING_CONSENT] needs consent, every purpose in
     * [PURPOSES_ALLOWING_LEGITIMATE_INTEREST] needs consent or legitimate interest, and Google
     * itself must be an allowed vendor.
     */
    @JvmStatic
    fun canShowPersonalizedAds(context: Context): Boolean {
        val prefs = defaultPreferences(context)
        val purposeConsent = prefs.getString(KEY_PURPOSE_CONSENTS, "").orEmpty()
        val vendorConsent = prefs.getString(KEY_VENDOR_CONSENTS, "").orEmpty()
        val vendorLI = prefs.getString(KEY_VENDOR_LI, "").orEmpty()
        val purposeLI = prefs.getString(KEY_PURPOSE_LI, "").orEmpty()

        val hasGoogleVendorConsent = hasAttribute(vendorConsent, GOOGLE_VENDOR_ID)
        val hasGoogleVendorLI = hasAttribute(vendorLI, GOOGLE_VENDOR_ID)

        return hasConsentFor(PURPOSES_REQUIRING_CONSENT, purposeConsent, hasGoogleVendorConsent) &&
            hasConsentOrLegitimateInterestFor(
                PURPOSES_ALLOWING_LEGITIMATE_INTEREST,
                purposeConsent,
                purposeLI,
                hasGoogleVendorConsent,
                hasGoogleVendorLI,
            )
    }

    // UMP writes the TCF strings into the default preference file. Resolved by name rather than
    // through the deprecated android.preference.PreferenceManager.
    private fun defaultPreferences(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(
            context.packageName + PREF_FILE_SUFFIX,
            Context.MODE_PRIVATE,
        )

    private fun hasConsentFor(
        purposes: List<Int>,
        purposeConsent: String,
        hasVendorConsent: Boolean,
    ): Boolean {
        purposes.forEach { purpose ->
            if (!hasAttribute(purposeConsent, purpose)) {
                Log.d(TAG, "hasConsentFor: denied for purpose #$purpose")
                return false
            }
        }
        return hasVendorConsent
    }

    private fun hasConsentOrLegitimateInterestFor(
        purposes: List<Int>,
        purposeConsent: String,
        purposeLI: String,
        hasVendorConsent: Boolean,
        hasVendorLI: Boolean,
    ): Boolean {
        purposes.forEach { purpose ->
            val byLegitimateInterest = hasAttribute(purposeLI, purpose) && hasVendorLI
            val byConsent = hasAttribute(purposeConsent, purpose) && hasVendorConsent
            if (!byLegitimateInterest && !byConsent) {
                Log.d(TAG, "hasConsentOrLegitimateInterestFor: denied for #$purpose")
                return false
            }
        }
        return true
    }

    /** TCF strings are 1-indexed bit strings: position N-1 carries purpose/vendor N. */
    private fun hasAttribute(input: String, index: Int): Boolean =
        input.length >= index && input[index - 1] == '1'

    // -----------------------------------------------------------------------
    // Region helpers — kept from the flow this replaced; UMP itself decides whether to ask.
    // -----------------------------------------------------------------------

    @JvmStatic
    fun countryCode(context: Context): String {
        val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        return telephony?.networkCountryIso?.uppercase(Locale.ROOT).orEmpty()
    }

    @JvmStatic
    fun isAdConsentCountry(context: Context): Boolean =
        countryCode(context) in (EEA_COUNTRIES + UK_COUNTRIES)
}
