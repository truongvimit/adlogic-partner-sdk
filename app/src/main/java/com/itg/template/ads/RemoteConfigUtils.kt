package com.itg.template.ads

import android.content.Context
import com.google.firebase.Firebase

import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.remoteConfig
import com.google.firebase.remoteconfig.remoteConfigSettings
import com.itg.template.BuildConfig
import com.itg.template.app.AppConstants
import com.itg.template.app.AppConstants.DEFAULT_CTA_HEIGHT
import com.itg.template.app.GlobalApp
import com.itg.template.data.model.ForceUpdateConfig
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlin.collections.set
import kotlin.math.max
import kotlin.math.min

object RemoteConfigUtils {

    /** Volatile: written on the fetch callback thread, read from the main thread by every getter. */
    @Volatile
    var completed = false
        private set

    private const val ON_SHOW_DIALOG_CONSENT = "on_show_dialog_consent"
    private const val AD_REMOTE_CONFIG = "ad_remote_config"
    private const val FORCE_UPDATE_CONFIG = "force_update_config"

    private const val ON_ENABLE_UNINSTALL_WIDGET = "on_enable_uninstall_widget"

    /**
     * Interstitial clicks allowed per ad unit per 24h. `0` disables the cap — UA's on/off switch,
     * and the default, so the feature stays dormant until someone deliberately turns it on.
     */
    private const val MAX_CLICK_ADS_PER_DAY = "max_click_ads_per_day"
    private const val INTERSTITIAL_INTERVAL_SEC = "interstitial_interval_sec"

    private val mapConditionForAd: HashMap<String, Any> = hashMapOf(
        ON_SHOW_DIALOG_CONSENT to true,
        ON_ENABLE_UNINSTALL_WIDGET to false,
        MAX_CLICK_ADS_PER_DAY to 0L,
        INTERSTITIAL_INTERVAL_SEC to 0L,
    )
    
    private const val AD_REMOTE_CONFIG_FILE_DEBUG = "ad_config_debug.json"
    private const val AD_REMOTE_CONFIG_FILE_RELEASE = "ad_config.json"
    private const val FORCE_UPDATE_CONFIG_FILE = "force_update_config.json"

    fun getOnShowDialogConsent(): Boolean = getBoolean(ON_SHOW_DIALOG_CONSENT)
    fun getOnEnableUninstallWidget(): Boolean = getBoolean(ON_ENABLE_UNINSTALL_WIDGET, false)

    /** `0` = cap off. Coerced so a negative remote value cannot mean anything other than off. */
    fun getMaxClickAdsPerDay(): Int =
        getLong(MAX_CLICK_ADS_PER_DAY, 0).coerceAtLeast(0).toInt()

    /** Seconds between two interstitials. `0` = rule off. Enforced by :ads, which owns the clock. */
    fun getInterstitialIntervalSec(): Int =
        getLong(INTERSTITIAL_INTERVAL_SEC, 0).coerceAtLeast(0).toInt()

    interface Listener {
        fun loadSuccess()
    }

    /**
     * Private and nullable, not a public `lateinit`.
     *
     * [Listener] is implemented by the launcher Activity, and this is a process-wide `object`: a
     * strong field here kept that Activity — its whole view tree and any ad it held — alive for
     * the life of the process. The reference is dropped the moment it fires, and [detach] lets a
     * screen that dies before the fetch lands release it early.
     */
    private var listener: Listener? = null

    /** Lazy, not `lateinit`: a getter reached before [init] would otherwise throw. */
    private val remoteConfig: FirebaseRemoteConfig by lazy { Firebase.remoteConfig }

    private val moshi: Moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val forceUpdateAdapter = moshi.adapter(ForceUpdateConfig::class.java)

    fun init(context: Context, mListener: Listener) {
        listener = mListener
        startFetch(context)
    }

    /** Call from `onDestroy` so a screen torn down mid-fetch is not held until the fetch returns. */
    fun detach(mListener: Listener) {
        if (listener === mListener) listener = null
    }

    private fun startFetch(context: Context) {
        val configSettings = remoteConfigSettings {
            minimumFetchIntervalInSeconds = if (BuildConfig.DEBUG) {
                0
            } else {
                60 * 60
            }
        }
        // Only the keys something actually reads. Seeding one default per ad unit used to run on
        // every launch and nothing ever read them back — the ad units arrive as one
        // `ad_remote_config` document, and the shipped asset is already the fallback.
        val defaults = mutableMapOf<String, Any>()
        remoteConfig.apply {
            setConfigSettingsAsync(configSettings)
            mapConditionForAd.forEach { (key, value) ->
                defaults[key] = value
            }
            setDefaultsAsync(defaults)
            fetchAndActivate().addOnCompleteListener {
                // completed FIRST: every getter here returns its hardcoded default while this is
                // false, so a listener reading remote values saw defaults, never the fetched ones.
                completed = true
                // One-shot: take and clear before invoking, so nothing is retained afterwards
                val pending = listener
                listener = null
                pending?.loadSuccess()
            }
        }
    }

    private fun getBoolean(key: String, defaultValue: Boolean = false): Boolean {
        return try {
            if (!completed) defaultValue
            else remoteConfig.getBoolean(key)
        } catch (ex: Exception) {
            ex.printStackTrace()
            defaultValue
        }
    }


    private fun getLong(key: String, defaultValue: Long = 0): Long {
        return try {
            if (!completed) defaultValue
            else remoteConfig.getLong(key)
        } catch (ex: Exception) {
            ex.printStackTrace()
            defaultValue
        }
    }


    fun getAdRemoteConfig(): String {
        val defaultValue = loadDefaultAdRemoteConfig()
        if (!completed) {
            return defaultValue
        } else {
            val configValue = remoteConfig.getString(AD_REMOTE_CONFIG)
            return configValue.ifBlank { defaultValue }
        }
    }


    fun getForceUpdateConfig(): ForceUpdateConfig? {
        val defaultJson = loadDefaultForceUpdateConfig()
        val json = if (!completed) {
            defaultJson
        } else {
            val configValue = remoteConfig.getString(FORCE_UPDATE_CONFIG)
            configValue.ifBlank { defaultJson }
        }

        return try {
            forceUpdateAdapter.fromJson(json)
        } catch (ex: Exception) {
            ex.printStackTrace()
            null
        }
    }

    private fun loadDefaultForceUpdateConfig(): String {
        return try {
            GlobalApp.instance.assets.open(FORCE_UPDATE_CONFIG_FILE).bufferedReader().use { reader ->
                reader.readText()
            }
        } catch (ex: Exception) {
            "{}"
        }
    }

    private fun loadDefaultAdRemoteConfig(): String {
        val fileName = if (BuildConfig.DEBUG) {
            AD_REMOTE_CONFIG_FILE_DEBUG
        } else {
            AD_REMOTE_CONFIG_FILE_RELEASE
        }
        return try {
            GlobalApp.instance.assets.open(fileName).bufferedReader().use { reader ->
                reader.readText()
            }
        } catch (ex: Exception) {
            "{}"
        }
    }

}