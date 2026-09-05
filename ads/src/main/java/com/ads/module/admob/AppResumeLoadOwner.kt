package com.ads.module.admob

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.ads.module.consent.ConsentCenter
import com.ads.module.event.ERainLogEventManager
import com.ads.module.funtion.AdType
import com.ads.module.helper.AdGate
import com.ads.module.helper.AdSkipReason
import com.ads.module.helper.Entitlement
import com.ads.module.tracking.AdLoadAttempt
import com.ads.module.tracking.AdLoadContext
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.appopen.AppOpenAd
import io.trackkit.AdFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Owns the resume buffer and its vendor request. All mutations run on the main thread; returning
 * from a vendor request does not authorize it to refill a newer unit or a revoked session.
 * [AppOpenManager] exposes this owner only for foreground resume.
 *
 * Request telemetry marks the logical dispatch boundary. A synchronous telemetry sink may revoke
 * that request before vendor entry; cancellation settles it without adding network backoff.
 * A request event alone therefore does not prove that a physical network request was sent.
 */
internal class AppResumeLoadOwner(private val loader: AppResumeAdLoader) {
    /** Original accepted fill and attribution, retained unchanged through pre-show rejection. */
    class LoadedAd(
        val ad: AppOpenAd,
        val context: AdLoadContext,
        val attemptId: String,
        val generation: Long,
        val loadedAt: Long,
        val personalized: Boolean,
    )

    private class Pending(
        val generation: Long,
        val unitId: String,
        val personalized: Boolean,
        val deadline: Long,
    ) {
        lateinit var timeout: Runnable
        val attempt = AdLoadAttempt(AdLoadContext("app_resume", AdFormat.APP_OPEN))
        var startedAt: Long? = null
        var vendorResultReceived = false
    }

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observing = false
    private var application: Context? = null
    private var unitId = ""
    private var initialized = false
    private var enabled = true
    private var generation = 0L
    private var pending: Pending? = null
    private var failures = 0
    private var retryAfter = 0L

    @Volatile
    private var buffered: LoadedAd? = null

    fun initialize(app: Application, id: String?) {
        onMain {
            application = app.applicationContext
            initialized = true
            updateUnit(id)
            if (!observing) {
                observing = true
                scope.launch {
                    ConsentCenter.requestEligibility.collect { allowed ->
                        if (allowed) request() else invalidate()
                    }
                }
                scope.launch {
                    Entitlement.observe(app.applicationContext).collect { premium ->
                        if (premium) invalidate() else request()
                    }
                }
                scope.launch {
                    ConsentCenter.state.collect {
                        val choice = ConsentCenter.canPersonalize()
                        if (pending?.let { it.personalized != choice } == true ||
                            buffered?.let { it.personalized != choice } == true
                        ) {
                            invalidate()
                        }
                        request()
                    }
                }
            }
            request()
        }
    }

    fun setInitialized(value: Boolean) {
        onMain {
            if (initialized == value) return@onMain
            initialized = value
            if (value) request() else invalidate()
        }
    }

    fun setEnabled(value: Boolean) {
        onMain {
            if (enabled == value) {
                if (value) request()
                return@onMain
            }
            enabled = value
            if (value) request() else invalidate()
        }
    }

    fun setUnitId(id: String?) {
        onMain {
            if (updateUnit(id)) request()
        }
    }

    private fun updateUnit(id: String?): Boolean {
        val next = id?.trim().orEmpty()
        if (unitId == next) return false
        unitId = next
        invalidate()
        return true
    }

    /** Clears only the resume generation. In-flight GMA requests cannot be canceled. */
    fun invalidate() {
        onMain {
            generation++
            val cancelled = pending
            val discarded = buffered
            cancelled?.let { handler.removeCallbacks(it.timeout) }
            pending = null
            buffered = null
            failures = 0
            retryAfter = 0L
            // Settle the detached request without turning invalidation into network backoff.
            cancelled?.let {
                reportTier(it, "load_failed")
                it.attempt.onFailed(null)
            }
            discarded?.let { clearPaidListener(it.ad) }
        }
    }

    fun request() {
        onMain {
            val context = application ?: return@onMain
            val expectedGeneration = generation
            if (!canLoad(context) || generation != expectedGeneration) return@onMain
            val now = SystemClock.elapsedRealtime()
            pending?.let {
                if (now >= it.deadline) fail(it, outcome = "timeout") else return@onMain
            }
            // Expiry telemetry can release this generation or synchronously start its replacement.
            if (!canStartRequest(context, expectedGeneration)) return@onMain
            // Expiry releases an old cache before its replacement is requested.
            val discarded = buffered
            buffered = null
            discarded?.let { clearPaidListener(it.ad) }
            if (!canStartRequest(context, expectedGeneration)) return@onMain

            val request = Pending(generation, unitId, ConsentCenter.canPersonalize(),
                SystemClock.elapsedRealtime() + LOAD_TIMEOUT_MS)
            request.timeout = Runnable { fail(request, outcome = "timeout") }
            // Claim before dispatch: a vendor adapter (and a fake) may answer synchronously.
            pending = request
            handler.postDelayed(request.timeout, LOAD_TIMEOUT_MS)
            try {
                val adRequest = AdRequest.Builder().also(Admob::applyPersonalization).build()
                request.startedAt = SystemClock.elapsedRealtime()
                request.attempt.onRequestStarted(request.unitId)
                if (!isRequestAuthorized(context, request)) {
                    cancel(request)
                    return@onMain
                }
                if (SystemClock.elapsedRealtime() >= request.deadline) {
                    fail(request, outcome = "timeout")
                    return@onMain
                }
                loader.load(context, request.unitId, adRequest, object : AppOpenAd.AppOpenAdLoadCallback() {
                    override fun onAdLoaded(ad: AppOpenAd) {
                        onMain { acceptFill(context, request, ad) }
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        onMain {
                            if (claimVendorResult(request)) fail(request, error.code)
                        }
                    }
                })
            } catch (_: Exception) {
                // A synchronous dispatch exception must not leave the slot loading forever.
                fail(request)
            }
        }
    }

    private fun owns(request: Pending): Boolean =
        pending === request && request.generation == generation && request.unitId == unitId

    private fun canStartRequest(context: Context, expectedGeneration: Long): Boolean =
        generation == expectedGeneration && pending == null && !isAdAvailable() &&
            SystemClock.elapsedRealtime() >= retryAfter && canLoad(context) &&
            generation == expectedGeneration && pending == null && !isAdAvailable() &&
            SystemClock.elapsedRealtime() >= retryAfter

    private fun claimVendorResult(request: Pending): Boolean {
        if (!owns(request) || request.vendorResultReceived) return false
        request.vendorResultReceived = true
        return true
    }

    private fun acceptFill(context: Context, request: Pending, ad: AppOpenAd) {
        // Claim before reporting the tier: a telemetry sink can synchronously deliver a duplicate.
        if (!claimVendorResult(request)) return
        // Handler delays use uptime; elapsed time also includes deep sleep.
        if (SystemClock.elapsedRealtime() >= request.deadline) {
            fail(request, outcome = "timeout")
            return
        }
        reportTier(request, "loaded")
        if (!isRequestAuthorized(context, request)) {
            cancel(request)
            return
        }
        val capturedContext = request.attempt.context
        try {
            ad.setOnPaidEventListener { value ->
                ERainLogEventManager.logPaidAdImpression(
                    context, value, ad.adUnitId,
                    ad.responseInfo?.mediationAdapterClassName.orEmpty(), AdType.APP_OPEN,
                    capturedContext,
                )
            }
        } catch (error: RuntimeException) {
            cancel(request)
            clearPaidListener(ad)
            Log.w(TAG, "App-open paid callback setup failed", error)
            return
        }
        // Vendor setup and telemetry sinks can reenter policy, release or unit updates.
        if (!isRequestAuthorized(context, request)) {
            cancel(request)
            clearPaidListener(ad)
            return
        }
        if (SystemClock.elapsedRealtime() >= request.deadline) {
            fail(request, outcome = "timeout")
            clearPaidListener(ad)
            return
        }
        finish(request)
        failures = 0
        retryAfter = 0L
        buffered = LoadedAd(ad, capturedContext, request.attempt.id, request.generation,
            SystemClock.elapsedRealtime(), request.personalized)
        request.attempt.onLoaded(request.unitId)
    }

    private fun isRequestAuthorized(context: Context, request: Pending): Boolean =
        owns(request) && canLoad(context) && request.personalized == ConsentCenter.canPersonalize() && owns(request)

    /** Policy cancellation or unusable setup does not add network backoff. */
    private fun cancel(request: Pending) {
        if (!owns(request)) return
        finish(request)
        reportTier(request, "load_failed")
        request.attempt.onFailed(null)
    }

    private fun clearPaidListener(ad: AppOpenAd) {
        try {
            ad.setOnPaidEventListener(null)
        } catch (error: RuntimeException) {
            Log.w(TAG, "App-open paid callback cleanup failed", error)
        }
    }

    private fun finish(request: Pending) {
        handler.removeCallbacks(request.timeout)
        pending = null
    }

    private fun fail(request: Pending, errorCode: Int? = null, outcome: String = "load_failed") {
        if (!owns(request)) return
        finish(request)
        failures = (failures + 1).coerceAtMost(5)
        val delay = (INITIAL_BACKOFF_MS shl (failures - 1)).coerceAtMost(MAX_BACKOFF_MS)
        retryAfter = SystemClock.elapsedRealtime() + delay
        // A later lifecycle/explicit request may retry. No timer introduces a new ad opportunity.
        reportTier(request, outcome, errorCode)
        request.attempt.onFailed(errorCode)
    }

    private fun reportTier(request: Pending, outcome: String, errorCode: Int? = null) {
        val startedAt = request.startedAt ?: return
        request.attempt.onTierResult(1, request.unitId, outcome, errorCode,
            (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L))
    }

    private fun canLoad(context: Context): Boolean =
        initialized && enabled && unitId.isNotEmpty() && ConsentCenter.canRequestAds() &&
            !AdGate.isPurchased(context) && AdGate.isNetworkAvailable(context)

    fun canShow(): Boolean = showSkipReason() == null

    /** Policy-only query. It emits nothing and does not consume a buffer or resume opportunity. */
    fun showSkipReason(): AdSkipReason? {
        val context = application ?: return AdSkipReason.DISABLED_CONFIG
        return when {
            !initialized || !enabled || unitId.isEmpty() -> AdSkipReason.DISABLED_CONFIG
            !ConsentCenter.canRequestAds() -> AdSkipReason.CONSENT_NOT_GRANTED
            ConsentCenter.isFormShowing() -> AdSkipReason.CONSENT_FORM_SHOWING
            // initialize already owns the entitlement observer; a query does not install another.
            Entitlement.isPremium(context) -> AdSkipReason.PURCHASED
            else -> null
        }
    }

    fun isAdAvailable(): Boolean {
        val ad = buffered ?: return false
        val age = SystemClock.elapsedRealtime() - ad.loadedAt
        return age >= 0 && age < MAX_AD_AGE_MS && ad.personalized == ConsentCenter.canPersonalize()
    }

    /** Keeps the original buffer while its vendor callback and cosmetic options are prepared. */
    fun peekForShow(): LoadedAd? {
        check(Looper.myLooper() == Looper.getMainLooper())
        val candidate = buffered ?: return null
        return candidate.takeIf { rejectionFor(it) == null }
    }

    /** Checks the captured fill's original lifetime, policy and identity without consuming it. */
    fun rejectionFor(expected: LoadedAd): AdSkipReason? {
        showSkipReason()?.let { return it }
        val age = SystemClock.elapsedRealtime() - expected.loadedAt
        return when {
            age < 0 || age >= MAX_AD_AGE_MS -> AdSkipReason.EXPIRED
            expected.personalized != ConsentCenter.canPersonalize() -> AdSkipReason.NOT_READY
            buffered !== expected -> AdSkipReason.NOT_READY
            else -> null
        }
    }

    /**
     * Claims only the expected, still-authorized buffer after [commit] accepts presentation.
     * A rejected commitment may synchronously notify the host; it must leave the original buffer
     * and timestamp, or a replacement installed by that notification, untouched.
     */
    fun takeForShow(expected: LoadedAd, commit: () -> Boolean): LoadedAd? {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (rejectionFor(expected) != null) return null
        if (!commit() || buffered !== expected) return null
        buffered = null
        return expected
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else handler.post { action() }
    }

    private companion object {
        const val TAG = "AppResumeLoadOwner"
        const val LOAD_TIMEOUT_MS = 30_000L
        const val INITIAL_BACKOFF_MS = 5_000L
        const val MAX_BACKOFF_MS = 60_000L
        const val MAX_AD_AGE_MS = 4 * 60 * 60 * 1_000L
    }
}
