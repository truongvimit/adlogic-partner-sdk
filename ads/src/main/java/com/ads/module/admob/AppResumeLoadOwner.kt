package com.ads.module.admob

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.ads.module.consent.ConsentCenter
import com.ads.module.event.ERainLogEventManager
import com.ads.module.funtion.AdType
import com.ads.module.helper.AdGate
import com.ads.module.helper.Entitlement
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.appopen.AppOpenAd
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Owns the resume buffer and its vendor request. All mutations run on the main thread; returning
 * from a vendor request does not authorize it to refill a newer unit or a revoked session.
 * [AppOpenManager] exposes this owner only for foreground resume.
 */
internal class AppResumeLoadOwner(private val loader: AppResumeAdLoader) {
    private data class BufferedAd(val ad: AppOpenAd, val loadedAt: Long, val personalized: Boolean)

    private class Pending(
        val generation: Long,
        val unitId: String,
        val personalized: Boolean,
        val deadline: Long,
    ) {
        lateinit var timeout: Runnable
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
    private var buffered: BufferedAd? = null

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
            pending?.let { handler.removeCallbacks(it.timeout) }
            pending = null
            buffered?.ad?.setOnPaidEventListener(null)
            buffered = null
            failures = 0
            retryAfter = 0L
        }
    }

    fun request() {
        onMain {
            val context = application ?: return@onMain
            if (!canLoad(context)) return@onMain
            val now = SystemClock.elapsedRealtime()
            pending?.let {
                if (now >= it.deadline) fail(it) else return@onMain
            }
            if (isAdAvailable()) return@onMain
            if (now < retryAfter) return@onMain
            // Expiry releases an old cache before its replacement is requested.
            buffered?.ad?.setOnPaidEventListener(null)
            buffered = null

            val request = Pending(generation, unitId, ConsentCenter.canPersonalize(), now + LOAD_TIMEOUT_MS)
            request.timeout = Runnable { fail(request) }
            // Claim before dispatch: a vendor adapter (and a fake) may answer synchronously.
            pending = request
            handler.postDelayed(request.timeout, LOAD_TIMEOUT_MS)
            try {
                val adRequest = AdRequest.Builder().also(Admob::applyPersonalization).build()
                loader.load(context, request.unitId, adRequest, object : AppOpenAd.AppOpenAdLoadCallback() {
                    override fun onAdLoaded(ad: AppOpenAd) {
                        onMain {
                            if (!owns(request)) return@onMain
                            // Handler delays use uptime; elapsed time also includes deep sleep.
                            if (SystemClock.elapsedRealtime() >= request.deadline) {
                                fail(request)
                                return@onMain
                            }
                            finish(request)
                            if (!canLoad(context) || request.personalized != ConsentCenter.canPersonalize()) return@onMain
                            failures = 0
                            retryAfter = 0L
                            ad.setOnPaidEventListener { value ->
                                ERainLogEventManager.logPaidAdImpression(
                                    context, value, ad.adUnitId,
                                    ad.responseInfo?.mediationAdapterClassName.orEmpty(), AdType.APP_OPEN,
                                )
                            }
                            buffered = BufferedAd(ad, SystemClock.elapsedRealtime(), request.personalized)
                        }
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        onMain { fail(request) }
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

    private fun finish(request: Pending) {
        handler.removeCallbacks(request.timeout)
        pending = null
    }

    private fun fail(request: Pending) {
        if (!owns(request)) return
        finish(request)
        failures = (failures + 1).coerceAtMost(5)
        val delay = (INITIAL_BACKOFF_MS shl (failures - 1)).coerceAtMost(MAX_BACKOFF_MS)
        retryAfter = SystemClock.elapsedRealtime() + delay
        // A later lifecycle/explicit request may retry. No timer introduces a new ad opportunity.
    }

    private fun canLoad(context: Context): Boolean =
        initialized && enabled && unitId.isNotEmpty() && ConsentCenter.canRequestAds() &&
            !AdGate.isPurchased(context) && AdGate.isNetworkAvailable(context)

    fun canShow(): Boolean {
        val context = application ?: return false
        return initialized && enabled && unitId.isNotEmpty() && ConsentCenter.canRequestAds() &&
            !ConsentCenter.isFormShowing() && !AdGate.isPurchased(context)
    }

    fun isAdAvailable(): Boolean {
        val ad = buffered ?: return false
        val age = SystemClock.elapsedRealtime() - ad.loadedAt
        return age >= 0 && age < MAX_AD_AGE_MS && ad.personalized == ConsentCenter.canPersonalize()
    }

    /** Keeps the original buffer while its vendor callback and cosmetic options are prepared. */
    fun peekForShow(): AppOpenAd? {
        check(Looper.myLooper() == Looper.getMainLooper())
        val candidate = buffered ?: return null
        if (!canShow() || !isAdAvailable()) return null
        return candidate.ad.takeIf { buffered === candidate }
    }

    /**
     * Claims only the expected, still-authorized buffer after [commit] accepts presentation.
     * A rejected commitment may synchronously notify the host; it must leave the original buffer
     * and timestamp, or a replacement installed by that notification, untouched.
     */
    fun takeForShow(expected: AppOpenAd, commit: () -> Boolean): AppOpenAd? {
        check(Looper.myLooper() == Looper.getMainLooper())
        val candidate = buffered ?: return null
        if (candidate.ad !== expected || !canShow() || !isAdAvailable() || buffered !== candidate) return null
        if (!commit() || buffered !== candidate) return null
        buffered = null
        return candidate.ad
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else handler.post { action() }
    }

    private companion object {
        const val LOAD_TIMEOUT_MS = 30_000L
        const val INITIAL_BACKOFF_MS = 5_000L
        const val MAX_BACKOFF_MS = 60_000L
        const val MAX_AD_AGE_MS = 4 * 60 * 60 * 1_000L
    }
}
