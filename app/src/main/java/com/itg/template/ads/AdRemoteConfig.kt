package com.itg.template.ads

import com.itg.template.BuildConfig
import android.content.Context
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import timber.log.Timber
import java.io.InputStream

data class AdRemoteConfig(
    val ads: Map<String, AdUnitConfig> = emptyMap()
) {
    companion object {
        private const val RELEASE_FILE_NAME = "ad_config.json"
        private const val DEBUG_FILE_NAME = "ad_config_debug.json"

        @Volatile
        private var instance: AdRemoteConfig? = null

        private val moshi: Moshi = Moshi.Builder()
            .add(AdRemoteConfigJsonAdapterFactory())
            .addLast(KotlinJsonAdapterFactory())
            .build()

        private val adapter = moshi.adapter(AdRemoteConfig::class.java)

        fun getInstance(): AdRemoteConfig {
            return instance ?: synchronized(this) {
                instance ?: throw IllegalStateException("AdRemoteConfig not initialized. Call initialize() first.")
            }
        }

        fun initialize(context: Context, json: String? = null) {
            synchronized(this) {
                instance = if (BuildConfig.DEBUG) {
                    fromAssets(context, DEBUG_FILE_NAME)
                } else {
                    fromJsonOrAssets(context, json, RELEASE_FILE_NAME)
                }
            }
        }

        fun initializeFromAssets(context: Context) {
            synchronized(this) {
                val fileName = if (BuildConfig.DEBUG) DEBUG_FILE_NAME else RELEASE_FILE_NAME
                instance = fromAssets(context, fileName)
            }
        }

        fun initializeFromJson(json: String) {
            synchronized(this) {
                instance = fromJson(json)
            }
        }

        fun updateInstance(newConfig: AdRemoteConfig) {
            synchronized(this) {
                instance = newConfig
            }
        }

        fun isInitialized(): Boolean {
            return instance != null
        }

        fun reset() {
            synchronized(this) {
                instance = null
            }
        }

        private fun fromJson(json: String): AdRemoteConfig {
            return adapter.fromJson(json) ?: throw IllegalArgumentException("Invalid JSON")
        }

        private fun fromInputStream(inputStream: InputStream): AdRemoteConfig {
            return inputStream.bufferedReader().use { reader ->
                adapter.fromJson(reader.readText()) ?: throw IllegalArgumentException("Invalid JSON")
            }
        }

        private fun fromAssets(context: Context, fileName: String = RELEASE_FILE_NAME): AdRemoteConfig {
            return context.assets.open(fileName).use { inputStream ->
                fromInputStream(inputStream)
            }
        }

        fun fromRawResource(context: Context, resId: Int): AdRemoteConfig {
            return context.resources.openRawResource(resId).use { inputStream ->
                fromInputStream(inputStream)
            }
        }

        private fun fromJsonOrAssets(context: Context, json: String?, fileName: String = RELEASE_FILE_NAME): AdRemoteConfig {
            if (!json.isNullOrBlank()) {
                try {
                    return fromJson(json)
                } catch (e: Exception) {
                    // Fallback to assets if JSON parsing fails
                }
            }
            return fromAssets(context, fileName)
        }

        fun fromInputStreamOrAssets(context: Context, inputStream: InputStream?, fileName: String = RELEASE_FILE_NAME): AdRemoteConfig {
            if (BuildConfig.DEBUG) {
                return fromAssets(context, DEBUG_FILE_NAME)
            }
            if (inputStream != null) {
                try {
                    return fromInputStream(inputStream)
                } catch (e: Exception) {
                    // Fallback to assets if InputStream parsing fails
                }
            }
            return fromAssets(context, fileName)
        }
    }

    private fun getAdUnit(key: String): AdUnitConfig {
        val adUnitConfig = ads[key]
        if (adUnitConfig == null) {
            Timber.w("Ad unit '%s' not found in configuration.", key)
            return AdUnitConfig(id = "", isEnable = false)
        }
        return adUnitConfig
    }

    val inter_splash: AdUnitConfig
        get() = getAdUnit("inter_splash")

    val banner_splash: AdUnitConfig
        get() = getAdUnit("banner_splash")

    val open_resume: AdUnitConfig
        get() = getAdUnit("open_resume")

    val native_language_1: AdUnitConfig
        get() = getAdUnit("native_language_1")

    val native_language_1_click: AdUnitConfig
        get() = getAdUnit("native_language_1_click")

    val native_language_2: AdUnitConfig
        get() = getAdUnit("native_language_2")

    val native_language_2_click: AdUnitConfig
        get() = getAdUnit("native_language_2_click")

    val native_onboarding_1_1: AdUnitConfig
        get() = getAdUnit("native_onboarding_1_1")

    val native_onboarding_2_1: AdUnitConfig
        get() = getAdUnit("native_onboarding_2_1")

    val native_onboarding_1_4: AdUnitConfig
        get() = getAdUnit("native_onboarding_1_4")

    val native_onboarding_2_4: AdUnitConfig
        get() = getAdUnit("native_onboarding_2_4")

    val native_onboarding_fullscreen_1_3: AdUnitConfig
        get() = getAdUnit("native_onboarding_fullscreen_1_3")

    val native_onboarding_fullscreen_2_3: AdUnitConfig
        get() = getAdUnit("native_onboarding_fullscreen_2_3")

    val native_onboarding_fullscreen_1_4: AdUnitConfig
        get() = getAdUnit("native_onboarding_fullscreen_1_4")

    val native_onboarding_fullscreen_2_4: AdUnitConfig
        get() = getAdUnit("native_onboarding_fullscreen_2_4")

    val native_permission: AdUnitConfig
        get() = getAdUnit("native_permission")

    val native_home: AdUnitConfig
        get() = getAdUnit("native_home")

    val inter_onboarding: AdUnitConfig
        get() = getAdUnit("inter_onboarding")


    val banner_home: AdUnitConfig
        get() = getAdUnit("banner_home")

    val native_survey: AdUnitConfig
        get() = getAdUnit("native_survey")

    val native_confirm_uninstall: AdUnitConfig
        get() = getAdUnit("native_confirm_uninstall")

    val native_welcome: AdUnitConfig
        get() = getAdUnit("native_welcome")

    val inter_welcome: AdUnitConfig
        get() = getAdUnit("inter_welcome")

    val reward_example: AdUnitConfig
        get() = getAdUnit("reward_example")
}
