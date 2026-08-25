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
import com.ads.module.helper.AdGate
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentForm
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.FormError
import com.google.android.ump.UserMessagingPlatform
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
 * The UMP consent flow, and the single place ad consent is resolved.
 *
 * Every terminal path funnels through [resolve], the only caller of `Tracker.setConsent`.
 *
 * The answer decides **how** ads are requested, not **whether**. A user who refuses personalization
 * still gets ads — AdMob downgrades them to non-personalized or limited from the TC string UMP
 * wrote, and [canPersonalize] carries the same verdict into the request extras. The one thing that
 * does hold requests back is an unanswered form still on screen, because an ad underneath it is a
 * policy problem rather than a revenue one.
 */
object ConsentCenter {

    private const val TAG = "ConsentCenter"

    /** Google Advertising Products, the vendor whose consent an AdMob request depends on. */
    private const val GOOGLE_VENDOR_ID = 755

    /** Purposes that require explicit consent; legitimate interest is not enough. */
    private val PURPOSES_REQUIRING_CONSENT = listOf(1, 3, 4)

    /** Purposes satisfied by either consent or legitimate interest. */
    private val PURPOSES_ALLOWING_LEGITIMATE_INTEREST = listOf(2, 7, 9, 10)

    private const val PREF_FILE_SUFFIX = "_preferences"

    private const val PREF_CONSENT = "ads_consent"

    /** The user accepted the form; do not ask again. */
    private const val KEY_CONSENT_ACCEPTED = "consent_accepted"

    /** UMP reported no form is needed here; do not ask again. */
    private const val KEY_CONSENT_NOT_REQUIRED = "consent_not_required"

    private const val KEY_PURPOSE_CONSENTS = "IABTCF_PurposeConsents"
    private const val KEY_VENDOR_CONSENTS = "IABTCF_VendorConsents"
    private const val KEY_VENDOR_LI = "IABTCF_VendorLegitimateInterests"
    private const val KEY_PURPOSE_LI = "IABTCF_PurposeLegitimateInterests"

    // Values of the `status` param on consent_result.
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

    /** Replays to late subscribers, so a screen that starts after the answer still sees it. */
    val state: StateFlow<ConsentState> = _state.asStateFlow()

    /** Process-wide: the UMP form belongs to the session, not to whichever screen asks. */
    private val requested = AtomicBoolean(false)

    @Volatile
    private var consentInformation: ConsentInformation? = null

    @Volatile
    private var options: ConsentOptions = ConsentOptions()

    private val timeoutHandler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null

    /**
     * The screen whose flow is in flight, weakly — the timeout Runnable captures its callback, and
     * through it the Activity. Weak so that a screen destroyed mid-flow is collectable even before
     * [detach] runs.
     */
    @Volatile
    private var pendingScreen: WeakReference<Activity>? = null

    @Volatile
    private var callbackHandled = false

    /** Set once at init so the flow can run without the host passing options every time. */
    @JvmStatic
    fun configure(newOptions: ConsentOptions) {
        options = newOptions
    }

    @JvmStatic
    fun options(): ConsentOptions = options

    /**
     * Runs the UMP flow at most once per process, then reports that it is safe to request ads.
     *
     * **[onCompleted] answers "is the consent step finished", not "did the user agree".** A refusal
     * finishes the step: AdMob still serves that user, downgraded to non-personalized or limited
     * ads from the TC string UMP wrote. Refusing to request at all would forfeit that inventory for
     * no compliance benefit. What the user chose is carried by [canPersonalize] instead, and is
     * what reaches Consent Mode and the request extras.
     *
     * `false` therefore means only "do not request yet" — the form is still on screen unanswered,
     * or there was no network to consult UMP with.
     *
     * A second caller resolves immediately with the first one's outcome: splash and main both ask,
     * and without this the user saw the form twice in one session.
     *
     * @param screen tags the telemetry so a funnel can tell splash from main.
     * @param onFormAnswered fires **only** when this call put a form on screen and the user
     *   answered it — the second-chance prompt's cue to restart a session that spent itself with
     *   the ad gate shut. Do not restart from [onCompleted]: it also answers `true` for a call
     *   that resolved from an earlier one, which is how Splash and Main came to bounce off each
     *   other for the life of the process.
     * @param onCompleted always invoked, exactly once, on the main thread.
     */
    @JvmStatic
    @JvmOverloads
    fun request(
        activity: Activity,
        screen: String = "splash",
        onFormAnswered: ((personalized: Boolean) -> Unit)? = null,
        onCompleted: (mayRequestAds: Boolean) -> Unit,
    ) {
        // Answered in an earlier session, or a region UMP does not ask in: done, and ads may run.
        // This early return is load-bearing. Without it every launch waits on a UMP round trip,
        // and any launch where that round trip is slow loses its ads for the whole session.
        if (isAlreadyResolved(activity)) {
            grantWithoutAsking()
            onCompleted(true)
            return
        }
        // No network means UMP cannot be consulted and no form can appear. The flow runs, but
        // without ads: a request sent before any answer exists is the one thing consent forbids,
        // and an offline session had no fill to lose anyway. Nothing is persisted, so the next
        // launch asks properly, and the second-chance prompt reopens the gate within this one.
        if (!AdGate.isNetworkAvailable(activity)) {
            Log.d(TAG, "consent skipped: no network")
            onCompleted(false)
            return
        }
        if (!requested.compareAndSet(false, true)) {
            onCompleted(_state.value != ConsentState.UNKNOWN)
            return
        }
        callbackHandled = false
        pendingScreen = WeakReference(activity)
        Tracker.track(TrackkitEvents.ConsentEvents.Requested())
        armTimeout(screen, onCompleted)
        loadAndShowConsent(activity, screen, onFormAnswered, onCompleted)
    }

    /**
     * Drops the pending timeout when the screen that started the flow goes away.
     *
     * The Runnable holds the completion callback for the whole timeout window, and through it the
     * Activity — the module this replaced cleared exactly this from `onDestroy`. A no-op unless
     * [activity] is the screen that actually started the flow, so an unrelated screen's teardown
     * cannot disarm someone else's.
     *
     * Nothing else is reset: the UMP callbacks still resolve if they arrive, and a screen that
     * asks afterwards gets the session's answer through the once-per-process guard.
     */
    @JvmStatic
    fun detach(activity: Activity) {
        if (pendingScreen?.get() !== activity) return
        pendingScreen = null
        cancelTimeout()
    }

    /** True once the user accepted, or once UMP said this region needs no form. */
    @JvmStatic
    fun isAlreadyResolved(context: Context): Boolean {
        val prefs = sdkPreferences(context)
        return prefs.getBoolean(KEY_CONSENT_ACCEPTED, false) ||
            prefs.getBoolean(KEY_CONSENT_NOT_REQUIRED, false)
    }

    private fun grantWithoutAsking() {
        if (_state.value == ConsentState.GRANTED) return
        _state.value = ConsentState.GRANTED
        Tracker.setConsent(analytics = true, ads = true)
        MmpTracking.setConsent(true, true)
    }

    private fun remember(context: Context, key: String) {
        sdkPreferences(context).edit().putBoolean(key, true).apply()
    }

    private fun sdkPreferences(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_CONSENT, Context.MODE_PRIVATE)

    /**
     * Whether to hand UMP the debug settings that force the EEA form.
     *
     * Unset follows the host's own debuggable flag: the module this replaced read the app's
     * `BuildConfig.DEBUG` through its callback, and leaving the switch off by default meant a
     * debug build outside the EEA never saw the form at all.
     */
    private fun isDebugFlow(context: Context): Boolean =
        options.debug
            ?: ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)

    private fun armTimeout(screen: String, onCompleted: (Boolean) -> Unit) {
        cancelTimeout()
        val runnable = Runnable {
            if (callbackHandled) return@Runnable
            callbackHandled = true
            releaseScreen()
            // UMP never answered. Report it as an error, not as a refusal, and let the flow run:
            // holding ads back here turned a slow network into a session with no ads at all.
            // Cancelled once a form is actually on screen, so this only covers the round trip.
            Log.w(TAG, "consent timed out after ${options.timeoutMs}ms — continuing")
            resolve(personalized = true, status = STATUS_ERROR, screen = screen)
            onCompleted(true)
        }
        timeoutRunnable = runnable
        timeoutHandler.postDelayed(runnable, options.timeoutMs)
    }

    private fun cancelTimeout() {
        timeoutRunnable?.let(timeoutHandler::removeCallbacks)
        timeoutRunnable = null
    }

    /** Terminal for this flow: nothing more will fire, so stop pointing at the screen. */
    private fun releaseScreen() {
        pendingScreen = null
    }

    private fun loadAndShowConsent(
        activity: Activity,
        screen: String,
        onFormAnswered: ((Boolean) -> Unit)?,
        onCompleted: (Boolean) -> Unit,
    ) {
        val information = UserMessagingPlatform.getConsentInformation(activity)
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

        information.requestConsentInfoUpdate(
            activity,
            params,
            {
                Log.v(TAG, "requestConsentInfoUpdate success")
                if (information.isConsentFormAvailable) {
                    loadForm(activity, information, screen, onFormAnswered, onCompleted)
                } else {
                    onNotRequired(activity, screen, onCompleted)
                }
            },
            { formError -> onError(formError, screen, onCompleted) },
        )
    }

    private fun loadForm(
        activity: Activity,
        information: ConsentInformation,
        screen: String,
        onFormAnswered: ((Boolean) -> Unit)?,
        onCompleted: (Boolean) -> Unit,
    ) {
        UserMessagingPlatform.loadConsentForm(
            activity,
            { form: ConsentForm ->
                when (information.consentStatus) {
                    ConsentInformation.ConsentStatus.REQUIRED -> {
                        Tracker.track(TrackkitEvents.ConsentEvents.Shown())
                        // The timeout guards the network round-trip, not the human. Once the form
                        // is on screen the user may take as long as they like: firing at 15s
                        // resolved DENIED and then discarded the Accept they were about to tap.
                        cancelTimeout()
                        // The load is asynchronous, so the screen that asked may already be gone.
                        // UMP shows the form on this Activity's window; handing it a destroyed one
                        // is a crash on some devices and a leak on the rest.
                        if (activity.isFinishing || activity.isDestroyed) {
                            callbackHandled = true
                            releaseScreen()
                            Log.w(TAG, "consent form ready but the screen is gone — skipping")
                            onCompleted(false)
                            return@loadConsentForm
                        }
                        form.show(activity) {
                            if (callbackHandled) return@show
                            callbackHandled = true
                            releaseScreen()
                            val personalized = canShowPersonalizedAds(activity)
                            if (personalized) {
                                remember(activity, KEY_CONSENT_ACCEPTED)
                            } else {
                                // Only an acceptance is remembered. Recording a refusal here would
                                // make the next launch skip UMP and resolve "granted" from the
                                // flag — serving personalised ads to someone who refused them.
                                // Resetting brings the form back so they can change their mind.
                                information.reset()
                            }
                            resolve(
                                personalized = personalized,
                                status = if (personalized) STATUS_GRANTED else STATUS_DENIED,
                                screen = screen,
                            )
                            // Answered is answered — requests may go out now, personalized or not.
                            onCompleted(true)
                            // Only this path is a real answer to a form this call put on screen.
                            // The second-chance prompt restarts the app from here, and firing it
                            // from any other terminal path restarted a session that had already
                            // resolved — Splash and Main then bounced off each other forever.
                            onFormAnswered?.invoke(personalized)
                        }
                    }

                    ConsentInformation.ConsentStatus.NOT_REQUIRED ->
                        onNotRequired(activity, screen, onCompleted)

                    // OBTAINED means the user already answered — and in TCF a refusal is OBTAINED
                    // too. Read what they actually chose instead of assuming a grant.
                    ConsentInformation.ConsentStatus.OBTAINED ->
                        onAlreadyAnswered(activity, screen, onCompleted)

                    // UNKNOWN: no answer to read and no form to show. Resolve closed for this
                    // launch and leave nothing persisted, so the next launch asks again.
                    else -> {
                        if (callbackHandled) return@loadConsentForm
                        callbackHandled = true
                        cancelTimeout()
                        releaseScreen()
                        Log.w(TAG, "consent status unknown — continuing without a form")
                        resolve(personalized = true, status = STATUS_ERROR, screen = screen)
                        onCompleted(true)
                    }
                }
            },
            { formError -> onError(formError, screen, onCompleted) },
        )
    }

    private fun onNotRequired(activity: Activity, screen: String, onCompleted: (Boolean) -> Unit) {
        if (callbackHandled) return
        callbackHandled = true
        cancelTimeout()
        releaseScreen()
        // Outside the consent regions UMP has nothing to ask, and personalised ads are allowed.
        remember(activity, KEY_CONSENT_NOT_REQUIRED)
        resolve(personalized = true, status = STATUS_NOT_REQUIRED, screen = screen)
        onCompleted(true)
    }

    /**
     * The user answered in an earlier session. Read the answer out of the TCF strings rather than
     * assuming it was a grant: a refusal is also [ConsentInformation.ConsentStatus.OBTAINED], and
     * treating the two alike turned a refusal into a permanent grant.
     */
    private fun onAlreadyAnswered(activity: Activity, screen: String, onCompleted: (Boolean) -> Unit) {
        if (callbackHandled) return
        callbackHandled = true
        cancelTimeout()
        releaseScreen()
        val personalized = canShowPersonalizedAds(activity)
        // Same rule as the form path: only an acceptance is remembered, so a refusal is re-read
        // from the TCF strings next launch rather than short-circuiting to "granted".
        if (personalized) remember(activity, KEY_CONSENT_ACCEPTED)
        resolve(
            personalized = personalized,
            status = if (personalized) STATUS_GRANTED else STATUS_DENIED,
            screen = screen,
        )
        onCompleted(true)
    }

    private fun onError(
        formError: FormError,
        screen: String,
        onCompleted: (Boolean) -> Unit,
    ) {
        if (callbackHandled) return
        callbackHandled = true
        cancelTimeout()
        releaseScreen()
        Log.e(TAG, "consent error ${formError.errorCode}: ${formError.message}")
        // UMP could not be consulted, so there is no answer to read. Reading the TCF strings here
        // resolved DENIED for everyone who has none — every user outside a consent region on a
        // launch where the round trip failed — and that stamped npa=1 and Consent Mode "denied"
        // on the whole session. An error is not a refusal: report it and carry on personalized,
        // which is what the module this replaced did.
        resolve(
            personalized = true,
            status = STATUS_ERROR,
            screen = screen,
            errorCode = formError.errorCode,
        )
        onCompleted(true)
    }

    /**
     * Records what the user chose. Do not call `Tracker.setConsent` anywhere else.
     *
     * [personalized] drives Consent Mode and the request extras — it does **not** decide whether a
     * request happens at all. UMP asks about **ads**, so only the ads axis follows the answer:
     * first-party analytics stays granted, because refusing personalised ads must not also erase
     * `first_open`, retention and the onboarding funnel. Sinks translate the pair into their own
     * vendor switch.
     */
    private fun resolve(personalized: Boolean, status: String, screen: String, errorCode: Int? = null) {
        _state.value = if (personalized) ConsentState.GRANTED else ConsentState.DENIED
        Tracker.track(TrackkitEvents.ConsentEvents.Result(status, errorCode, screen))
        Tracker.setConsent(analytics = true, ads = personalized)
        // Adjust is not a Trackkit sink — it lives in :ads — so the same gate relays to it here.
        MmpTracking.setConsent(true, personalized)
    }

    /**
     * Whether ad requests may be personalised.
     *
     * `false` does not mean "no ads" — it means non-personalized ones. Read by the request builders
     * so the `npa` extra matches what the user actually chose.
     */
    @JvmStatic
    fun canPersonalize(): Boolean = _state.value != ConsentState.DENIED

    /**
     * Clears the stored answer so the form shows again — the "withdraw consent" entry a privacy
     * settings screen needs, and the reset a debug build wants.
     *
     * Clears the module's own flags as well as UMP's state: leaving them set meant the next
     * request short-circuited to "already resolved" and silently re-granted, which is the one
     * thing a withdrawal must never do.
     */
    @JvmStatic
    fun reset(context: Context) {
        consentInformation?.reset()
        sdkPreferences(context).edit()
            .remove(KEY_CONSENT_ACCEPTED)
            .remove(KEY_CONSENT_NOT_REQUIRED)
            .apply()
        requested.set(false)
        callbackHandled = false
        cancelTimeout()
        releaseScreen()
        _state.value = ConsentState.UNKNOWN
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
