package io.onboardkit.remote

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.google.android.gms.tasks.Task
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Fetches remote config with a hard timeout and publishes an immutable [RemoteFlags] snapshot.
 *
 * Every key is re-resolved on every sync: a key deleted from the console falls back to its
 * default instead of sticking to the last fetched value forever. A changed `ob_config_version`
 * additionally clears the on-disk cache before writing the new snapshot.
 */
class RemoteConfigSyncer internal constructor(
    context: Context,
    private val remoteConfigProvider: () -> FirebaseRemoteConfig? = {
        runCatching { FirebaseRemoteConfig.getInstance() }.getOrNull()
    },
) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _flags = MutableStateFlow(loadCached())
    val flags: StateFlow<RemoteFlags> = _flags.asStateFlow()

    private val fetchLock = Any()
    private var fetchTask: Task<Boolean>? = null
    private val snapshotLock = Any()
    private var snapshotRevision = 0L

    /** Bounds the fetch wait by [timeoutMs]; timeout/error keeps the last known snapshot active. */
    suspend fun fetchAndSync(timeoutMs: Long): Boolean {
        val remote = remoteConfigProvider() ?: return false
        val fetched = try {
            fetchDelegate?.invoke(timeoutMs) ?: fetchLocally(remote, timeoutMs)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Remote fetch failed: ${error.message}")
            false
        }

        // A failed/timed-out fetch must not replace a valid disk assignment with SDK defaults.
        if (!fetched) return false
        val revision = synchronized(snapshotLock) { ++snapshotRevision }
        val reader = FirebaseReader(remote)
        com.ads.module.config.settings.SettingsRegistry.acceptSuccessfulFetch(
            listOf("ad_behavior_config", "onboarding_config").associateWith(reader::string),
        )
        val snapshot = withContext(Dispatchers.Default) {
            RemoteFlags.from(reader)
        }
        withContext(Dispatchers.IO) {
            synchronized(snapshotLock) {
                // Parsing/persistence can suspend; a newer manual assignment must not be lost.
                if (revision == snapshotRevision) {
                    persist(snapshot)
                    _flags.value = snapshot
                }
            }
        }
        return fetched
    }

    private suspend fun fetchLocally(remote: FirebaseRemoteConfig, timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) {
            val task = synchronized(fetchLock) {
                fetchTask?.takeUnless { it.isComplete }
                    ?: remote.fetchAndActivate().also { fetchTask = it }
            }
            // Task.await does not cancel Firebase's task when this caller stops waiting.
            task.await()
            true
        } ?: false

    /** For hosts that manage remote config themselves — inject values without Firebase. */
    fun applySnapshot(snapshot: RemoteFlags) = synchronized(snapshotLock) {
        snapshotRevision++
        persist(snapshot)
        _flags.value = snapshot
    }

    private fun persist(snapshot: RemoteFlags) {
        prefs.edit {
            clear()
            cachedValues(snapshot).forEach { (key, value) -> putString(key, value) }
        }
    }

    private fun loadCached(): RemoteFlags {
        if (prefs.all.isEmpty()) return RemoteFlags()
        val reader = object : RemoteValueReader {
            override fun string(key: String): String? = prefs.getString(key, null)
        }
        return RemoteFlags.from(reader)
    }

    private fun cachedValues(snapshot: RemoteFlags): Map<String, String> = buildMap {
        put(ObRemoteKeys.ENABLE_ALL_ADS.key, snapshot.enableAllAds.toString())
        put(ObRemoteKeys.ENABLE_UI_CONTENT.key, snapshot.enableUiContent.toString())
        put(ObRemoteKeys.ENABLE_STEP_OB1.key, snapshot.enableStepOb1.toString())
        put(ObRemoteKeys.ENABLE_STEP_OB2.key, snapshot.enableStepOb2.toString())
        put(ObRemoteKeys.ENABLE_STEP_OB3.key, snapshot.enableStepOb3.toString())
        put(ObRemoteKeys.ENABLE_STEP_OB4.key, snapshot.enableStepOb4.toString())
        put(ObRemoteKeys.ENABLE_STEP_OB5.key, snapshot.enableStepOb5.toString())
        put(ObRemoteKeys.ENABLE_QUESTION.key, snapshot.enableQuestion.toString())
        put(ObRemoteKeys.ENABLE_QUESTION_OLD_USER.key, snapshot.enableQuestionOldUser.toString())
        put(ObRemoteKeys.ENABLE_LANGUAGE_NATIVE_2.key, snapshot.enableLanguageNative2.toString())
        put(ObRemoteKeys.PASS_LFO_IF_COMPLETED.key, snapshot.passLfoIfCompleted.toString())
        put(ObRemoteKeys.SHOW_LANGUAGE_TAP_HINT.key, snapshot.showLanguageTapHint.toString())
        put(
            ObRemoteKeys.LANGUAGE_TAP_HINT_DELAY_SEC.key,
            snapshot.languageTapHintDelaySec.toString()
        )
        put(
            ObRemoteKeys.SHOW_LANGUAGE_CONFIRM_BEFORE_SELECT.key,
            snapshot.showLanguageConfirmBeforeSelect.toString(),
        )
        put(
            ObRemoteKeys.SHOW_LANGUAGE_CONFIRM_DIALOG.key,
            snapshot.showLanguageConfirmDialog.toString(),
        )
        put(ObRemoteKeys.LANGUAGE_SUPPORTED_CODES.key, snapshot.languageSupportedCodes)
        put(ObRemoteKeys.ADS_AFTER_ONBOARD_INTER.key, snapshot.adsAfterOnboardInter.toString())
        put(ObRemoteKeys.REUSE_SPLASH_INTER.key, snapshot.reuseSplashInter.toString())
        put(ObRemoteKeys.ADS_SPLASH_BANNER.key, snapshot.adsSplashBanner.toString())
        put(ObRemoteKeys.ADS_SPLASH_INTER.key, snapshot.adsSplashInter.toString())
        put(ObRemoteKeys.ADS_LANGUAGE_NATIVE.key, snapshot.adsLanguageNative.toString())
        put(
            ObRemoteKeys.ADS_LANGUAGE_CONFIRM_NATIVE.key,
            snapshot.adsLanguageConfirmNative.toString(),
        )
        put(ObRemoteKeys.ADS_CONTENT_NATIVE.key, snapshot.adsContentNative.toString())
        put(ObRemoteKeys.ADS_FULLSCREEN_NATIVE.key, snapshot.adsFullScreenNative.toString())
        put(ObRemoteKeys.ADS_QUESTION_NATIVE.key, snapshot.adsQuestionNative.toString())
        put(ObRemoteKeys.ADS_QUESTION_INTER.key, snapshot.adsQuestionInter.toString())
        put(ObRemoteKeys.ADS_APP_RESUME.key, snapshot.adsAppResume.toString())
        put(ObRemoteKeys.SPLASH_LFO_PARALLEL_PRELOAD_ENABLED.key, snapshot.splashLfoParallelPreloadEnabled.toString())
        put(ObRemoteKeys.SPLASH_NOTIFICATION_SETTLE_MS.key, snapshot.splashNotificationSettleMs.toString())
        put(ObRemoteKeys.SPLASH_MIN_DISPLAY_MS.key, snapshot.splashMinDisplayMs.toString())
        put(ObRemoteKeys.SPLASH_AD_BUDGET_MS.key, snapshot.splashAdBudgetMs.toString())
        put(ObRemoteKeys.SPLASH_BANNER_WAIT_MS.key, snapshot.splashBannerWaitMs.toString())
        put(ObRemoteKeys.SKIP_BUTTON_DELAY_SEC.key, snapshot.skipButtonDelaySec.toString())
        put(
            ObRemoteKeys.FULLSCREEN_AUTO_DISMISS_SEC.key,
            snapshot.fullScreenAutoDismissSec.toString(),
        )
        put(ObRemoteKeys.SHOW_SKIP_OB3.key, snapshot.showSkipOb3.toString())
        put(ObRemoteKeys.SHOW_SKIP_OB5.key, snapshot.showSkipOb5.toString())
        put(ObRemoteKeys.UI_CONTENT_JSON.key, snapshot.uiContentJson)
        put(ObRemoteKeys.UI_DESIGN_TOKENS_JSON.key, snapshot.uiDesignTokensJson)
        put(ObRemoteKeys.QUESTION_CONFIG_JSON.key, snapshot.questionConfigJson)
        put(ObRemoteKeys.CONFIG_VERSION.key, snapshot.configVersion.toString())
    }

    private class FirebaseReader(private val remote: FirebaseRemoteConfig) : RemoteValueReader {
        override fun string(key: String): String? {
            val value = remote.getValue(key)
            return if (value.source == FirebaseRemoteConfig.VALUE_SOURCE_REMOTE) {
                value.asString()
            } else {
                null
            }
        }
    }

    internal companion object {
        private const val TAG = "OnboardKit.Remote"
        private const val PREFS_NAME = "ob_remote_cache"

        @Volatile
        var fetchDelegate: (suspend (Long) -> Boolean)? = null
    }
}
