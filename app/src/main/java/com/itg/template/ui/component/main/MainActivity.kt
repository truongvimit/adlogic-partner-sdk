package com.itg.template.ui.component.main

import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.ads.module.ads.ERainAd
import com.ads.module.admob.AppOpenManager
import com.ads.module.helper.adnative.NativeAdHelper
import com.ads.module.helper.adnative.NativeAdParam
import com.itg.devconfig.dialog.DialogAdminOrganicAds
import com.hjq.permissions.dsl.xxPermissions
import com.hjq.permissions.permission.PermissionLists
import com.itg.template.BuildConfig
import com.itg.template.R
import com.itg.template.ads.AdRemoteConfig
import com.itg.template.ads.AdUnitConfig
import com.itg.template.ads.toNativeStyle
import com.itg.template.ads.AdsManager
import com.itg.template.ads.RemoteConfigUtils
import com.itg.template.ads.banner_home
import com.itg.template.ads.inter_onboarding
import com.itg.template.ads.open_resume
import com.itg.template.ads.native_welcome
import com.itg.template.ads.inter_welcome
import com.itg.template.ads.native_home
import com.itg.template.ads.native_onboarding_1_4
import com.itg.template.ads.native_onboarding_fullscreen_1_3
import com.itg.template.ads.native_onboarding_fullscreen_1_4
import com.itg.template.ads.native_permission
import com.itg.template.data.model.ForceUpdateConfig
import com.itg.template.databinding.ActivityMainBinding
import com.itg.template.ui.bases.BannerConfig
import com.itg.template.ui.bases.BaseActivityWithBanner
import com.itg.template.ui.bases.ConsentHandler
import com.itg.template.ui.bases.ext.click
import com.itg.template.ui.component.main.dialog.ForceUpdateDialog
import com.itg.template.ui.component.main.dialog.NoInternetDialog
import com.itg.template.utils.ConnectionLiveData
import com.itg.template.utils.ITGTrackingHelper
import com.itg.template.utils.Routes
import com.itg.template.app.ResumeAdsEntryRule
import com.itg.template.app.ResumeAdsEntryMode
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber

@AndroidEntryPoint
class MainActivity : BaseActivityWithBanner<ActivityMainBinding>() {

    override val bannerConfig = BannerConfig(AdRemoteConfig.banner_home, true)

    private lateinit var consentHandler: ConsentHandler
    private val delayHandler = Handler(Looper.getMainLooper())
    private var delayRunnable: Runnable? = null
    private lateinit var noInternetDialog: NoInternetDialog
    private lateinit var forceUpdateDialog: ForceUpdateDialog
    private var forceUpdateDialogHandle: Dialog? = null
    private var cachedForceUpdateConfig: ForceUpdateConfig? = null

    // Customization state
    private var currentCtaColor: String = "default"
    private var currentComponents: List<String> = listOf("icon_headline", "body", "media", "cta")

    // Dashboard native previews — one NativeAdHelper per slot, created on first load
    private var nativeSmallHelper: NativeAdHelper? = null
    private var nativeFullHelper: NativeAdHelper? = null
    private var customizationHelper: NativeAdHelper? = null

    override fun getLayoutActivity(): Int = R.layout.activity_main

    override fun initViews() {
        super.initViews()
        noInternetDialog = NoInternetDialog(this)
        forceUpdateDialog = ForceUpdateDialog(this)
        checkInternet()
        initConsentHandler()
        checkConsentStatus()
        maybeShowForceUpdateDialog()
        showSdkVersionInfo()
        buildFlagRows()
        updateResumeModeUI()
    }

    // ─── SDK Version ──────────────────────────────────────────

    private fun showSdkVersionInfo() {
        val erainVersion = BuildConfig.ERAIN_STUDIO_VERSION
        val adsVersion = BuildConfig.PLAY_SERVICES_ADS_VERSION
        mBinding.tvSdkVersion.text = "ERain Studio v$erainVersion • Ads SDK v$adsVersion"
    }

    // ─── Consent ──────────────────────────────────────────────

    private fun initConsentHandler() {
        consentHandler = ConsentHandler(
            activity = this,
            appSharedPref = appSharedPref,
            trackingSuffix = 2,
            onConsentFlowCompleted = { Timber.d("Consent flow completed") },
            onConsentSuccess = { canPersonalized ->
                if (canPersonalized) {
                    Routes.startSplashActivity(this)
                    finish()
                }
            },
            onNotUsingAdConsent = {
                appSharedPref.isUserGlobal = true
            }
        )
    }

    private fun checkConsentStatus() {
        if (appSharedPref.isConfirmConsent.not() && appSharedPref.isUserGlobal.not()) {
            delayShowConsentDialog()
        }
    }

    private fun delayShowConsentDialog() {
        if (!RemoteConfigUtils.getOnShowDialogConsent()) {
            return
        }
        delayRunnable = Runnable {
            consentHandler.requestConsent()
        }
        delayHandler.postDelayed(delayRunnable!!, 5000L)
    }

    // ─── Internet ─────────────────────────────────────────────

    private fun checkInternet() {
        ConnectionLiveData(this).observe(this) { isNetwork ->
            if (isNetwork) {
                Timber.d("network on")
                noInternetDialog.dismiss()
            } else {
                Timber.d("network off")
                noInternetDialog.show()
            }
        }
    }

    // ─── Force Update ─────────────────────────────────────────

    private fun maybeShowForceUpdateDialog() {
        val config = RemoteConfigUtils.getForceUpdateConfig() ?: return
        if (config.storeLink.isBlank()) return
        val needsUpdate = BuildConfig.VERSION_CODE < config.minVersionCode
        if (needsUpdate || config.force) {
            cachedForceUpdateConfig = config
            forceUpdateDialog.show(config)
        }
    }

    // ─── Click Handlers ───────────────────────────────────────

    override fun onClickViews() {
        super.onClickViews()

        // Utilities
        mBinding.buttonHello.click {
            ITGTrackingHelper.logEventClick(this::class.java.simpleName, "buttonHello", null)
        }
        mBinding.buttonRequestPermission.click { requestPermission() }
        mBinding.buttonShowSetting.click { Routes.startSettingActivity(this) }
        mBinding.buttonShowForceUpdate.click {
            val fallback = ForceUpdateConfig(
                title = getString(R.string.app_name),
                description = getString(R.string.force_update_message),
                storeLink = ""
            )
            val config = cachedForceUpdateConfig
                ?: RemoteConfigUtils.getForceUpdateConfig()
                ?: fallback
            forceUpdateDialog.show(config)
            cachedForceUpdateConfig = config
        }

        // ── Interstitial Onboarding ──
        mBinding.btnLoadInterOnboarding.click {
            ensureAdRemoteConfig()
            AdsManager.loadInterOnboarding(this, ignoreLimit = true)
            mBinding.tvInterOnboardingStatus.text = "Loading…"
            Handler(Looper.getMainLooper()).postDelayed({
                mBinding.tvInterOnboardingStatus.text = "Loaded ✓"
            }, 2000)
        }
        mBinding.btnShowInterOnboarding.click {
            AdsManager.showInterOnboarding(this, ignoreLimit = true) {
                Timber.d("Inter Onboarding shown or skipped")
                mBinding.tvInterOnboardingStatus.text = "Shown – reloading…"
                AdsManager.loadInterOnboarding(this, ignoreLimit = true)
                Handler(Looper.getMainLooper()).postDelayed({
                    mBinding.tvInterOnboardingStatus.text = "Loaded ✓"
                }, 2000)
            }
        }

        // ── Interstitial Welcome ──
        mBinding.btnLoadInterWelcome.click {
            ensureAdRemoteConfig()
            AdsManager.loadInterWelcome(this, ignoreLimit = true)
            mBinding.tvInterWelcomeStatus.text = "Loading…"
            Handler(Looper.getMainLooper()).postDelayed({
                mBinding.tvInterWelcomeStatus.text = "Loaded ✓"
            }, 2000)
        }
        mBinding.btnShowInterWelcome.click {
            AdsManager.showInterWelcome(this, ignoreLimit = true) {
                Timber.d("Inter Welcome shown or skipped")
                mBinding.tvInterWelcomeStatus.text = "Shown – reloading…"
                AdsManager.loadInterWelcome(this, ignoreLimit = true)
                Handler(Looper.getMainLooper()).postDelayed({
                    mBinding.tvInterWelcomeStatus.text = "Loaded ✓"
                }, 2000)
            }
        }

        // ── Reward Ads ──
        mBinding.btnLoadShowReward.click {
            // Dev show Dialog Loading
            AdsManager.loadAndShowReward(
                this@MainActivity,
                onSuccess = {
                    // Dev gone Dialog Loading and nextAction
                },
                onFailed = {
                    // Dev gone Dialog Loading and nextAction
                }
            )
        }

        // ── Native Ads ──
        mBinding.btnLoadNativeSmall.click { loadNativeSmallPreview() }
        mBinding.btnLoadNativeFull.click { loadNativeFullPreview() }

        // ── CTA Color Buttons ──
        mBinding.btnCtaColorDefault.click { setCtaColor("default") }
        mBinding.btnCtaColorRed.click { setCtaColor("#E53935") }
        mBinding.btnCtaColorBlue.click { setCtaColor("#1E88E5") }
        mBinding.btnCtaColorOrange.click { setCtaColor("#FB8C00") }
        mBinding.btnCtaColorPurple.click { setCtaColor("#8E24AA") }
        mBinding.btnCtaColorBlack.click { setCtaColor("#212121") }

        // ── Component Order Buttons ──
        mBinding.btnOrderDefault.click {
            setComponentOrder(listOf("icon_headline", "body", "media", "cta"))
            highlightOrderButton(mBinding.btnOrderDefault)
        }
        mBinding.btnOrderMediaFirst.click {
            setComponentOrder(listOf("media", "icon_headline", "body", "cta"))
            highlightOrderButton(mBinding.btnOrderMediaFirst)
        }
        mBinding.btnOrderCtaTop.click {
            setComponentOrder(listOf("cta", "icon_headline", "body", "media"))
            highlightOrderButton(mBinding.btnOrderCtaTop)
        }
        mBinding.btnOrderNoMedia.click {
            setComponentOrder(listOf("icon_headline", "body", "cta"))
            highlightOrderButton(mBinding.btnOrderNoMedia)
        }

        // ── Apply & Preview ──
        mBinding.btnApplyPreview.click { loadCustomizationPreview() }

        // ── Admin Ad Toggle Dialog ──
        mBinding.btnOpenAdminDialog.click { openAdminAdToggleDialog() }

        // ── Resume / Welcome Flow Mode Buttons ──
        mBinding.btnModeOpenResume.click { setResumeMode(ResumeAdsEntryMode.OPEN_RESUME) }
        mBinding.btnModeWelcome.click { setResumeMode(ResumeAdsEntryMode.WELCOME) }
        mBinding.btnModeNone.click { setResumeMode(ResumeAdsEntryMode.NONE) }

        // ── Refresh Flags ──
        mBinding.btnRefreshFlags.click { refreshFlags() }
    }

    // ─── Native Ad Preview ────────────────────────────────────
    // Each slot is a NativeAdHelper with the auto-derived skeleton: requestAds() shows the
    // shimmer, binds the fill, and hides the slot on fail. Style is re-read per click so
    // the customization overrides apply to the next load's ad AND skeleton.

    private fun adConfig(key: String): AdUnitConfig =
        runCatching { AdRemoteConfig.getInstance().ads[key] }.getOrNull()
            ?: AdUnitConfig(id = "", isEnable = false)

    private fun loadNativeSmallPreview() {
        ensureAdRemoteConfig()
        overrideAdConfig()
        val helper = nativeSmallHelper ?: AdsManager.nativeHelper(
            this, this, placement = null, adConfig("native_language_1"),
            R.layout.layout_native_ad_small, bypassUaGate = true,
        ).setNativeContentView(mBinding.flNativeSmall).also { nativeSmallHelper = it }
        helper.setNativeStyle(adConfig("native_language_1").toNativeStyle())
        helper.requestAds(NativeAdParam.Request)
    }

    private fun loadNativeFullPreview() {
        ensureAdRemoteConfig()
        overrideAdConfig()
        val helper = nativeFullHelper ?: AdsManager.nativeHelper(
            this, this, placement = null, adConfig("native_onboarding_fullscreen_1_3"),
            R.layout.layout_native_ad_full, bypassUaGate = true,
        ).setNativeContentView(mBinding.flNativeFull).also { nativeFullHelper = it }
        helper.setNativeStyle(adConfig("native_onboarding_fullscreen_1_3").toNativeStyle())
        helper.requestAds(NativeAdParam.Request)
    }

    private fun loadCustomizationPreview() {
        ensureAdRemoteConfig()
        overrideAdConfig()
        val helper = customizationHelper ?: AdsManager.nativeHelper(
            this, this, placement = null, adConfig("native_language_1"),
            R.layout.layout_native_ad_small, bypassUaGate = true,
        ).setNativeContentView(mBinding.flCustomizationPreview).also { customizationHelper = it }
        helper.setNativeStyle(adConfig("native_language_1").toNativeStyle())
        helper.requestAds(NativeAdParam.Request)
    }

    // ─── CTA Color Customization ──────────────────────────────

    private fun setCtaColor(color: String) {
        currentCtaColor = color
        val displayName = if (color == "default") "default" else color
        mBinding.tvCurrentCtaColor.text = "Current: $displayName"
        overrideAdConfig()
    }

    // ─── Component Order Customization ────────────────────────

    private fun setComponentOrder(components: List<String>) {
        currentComponents = components
        mBinding.tvCurrentOrder.text = "Current: ${components.joinToString(" → ")}"
        overrideAdConfig()
    }

    private fun highlightOrderButton(selected: com.google.android.material.button.MaterialButton) {
        val allButtons = listOf(
            mBinding.btnOrderDefault,
            mBinding.btnOrderMediaFirst,
            mBinding.btnOrderCtaTop,
            mBinding.btnOrderNoMedia
        )
        allButtons.forEach { btn ->
            if (btn == selected) {
                btn.setTextColor(getColor(R.color.color_main))
                btn.strokeColor = android.content.res.ColorStateList.valueOf(getColor(R.color.color_main))
            } else {
                btn.setTextColor(getColor(R.color.textPrimary))
                btn.strokeColor = android.content.res.ColorStateList.valueOf(getColor(R.color.color_divider))
            }
        }
    }

    /**
     * Override the in-memory AdRemoteConfig with the current customization values
     * (colorCTA and components) so that the next native ad load/populate will use them.
     */
    private fun overrideAdConfig() {
        if (!AdRemoteConfig.isInitialized()) return
        try {
            val instance = AdRemoteConfig.getInstance()
            val updatedAds = instance.ads.mapValues { (_, config) ->
                config.copy(
                    colorCTA = currentCtaColor,
                    components = currentComponents
                )
            }
            AdRemoteConfig.updateInstance(instance.copy(ads = updatedAds))
        } catch (e: Exception) {
            Timber.w(e, "Failed to override ad config")
        }
    }

    // ─── shouldDisplay Flags ──────────────────────────────────

    private data class FlagInfo(val name: String, val getter: () -> Boolean)

    // One UA gate for every placement; each row is the same check under that placement's flag
    private val flags = listOf(
        FlagInfo("shouldDisplayNativeOnboardingFull1") { ERainAd.getInstance().shouldDisplayForUa(AdRemoteConfig.native_onboarding_fullscreen_1_3.enableUaCheck) },
        FlagInfo("shouldDisplayNativeOnboardingFull2") { ERainAd.getInstance().shouldDisplayForUa(AdRemoteConfig.native_onboarding_fullscreen_1_4.enableUaCheck) },
        FlagInfo("shouldDisplayNativeOnboardingNormal2") { ERainAd.getInstance().shouldDisplayForUa(AdRemoteConfig.native_onboarding_1_4.enableUaCheck) },
        FlagInfo("shouldDisplayNativeHome") { ERainAd.getInstance().shouldDisplayForUa(AdRemoteConfig.native_home.enableUaCheck) },
        FlagInfo("shouldDisplayNativePermission") { ERainAd.getInstance().shouldDisplayForUa(AdRemoteConfig.native_permission.enableUaCheck) },
        FlagInfo("shouldDisplayInterOnboarding") { ERainAd.getInstance().shouldDisplayForUa(AdRemoteConfig.inter_onboarding.enableUaCheck) },
        FlagInfo("shouldDisplayNativeWelcomeBack") { ERainAd.getInstance().shouldDisplayForUa(AdRemoteConfig.native_welcome.enableUaCheck) },
        FlagInfo("shouldDisplayWidgetUninstall") { ERainAd.getInstance().shouldDisplayForUa(RemoteConfigUtils.getOnEnableUninstallWidget()) },
    )

    private fun buildFlagRows() {
        val container = mBinding.llFlagsContainer
        container.removeAllViews()

        flags.forEach { flag ->
            val value = try {
                flag.getter()
            } catch (_: Exception) {
                false
            }
            container.addView(createFlagRow(flag.name, value))
        }
    }

    private fun refreshFlags() {
        buildFlagRows()
    }

    private fun openAdminAdToggleDialog() {
        DialogAdminOrganicAds.setOnAdminAdToggleListener { isOrganic ->
            // Restart app from Splash to apply the new ad configuration
            Routes.startSplashActivity(this@MainActivity)
            finish()
        }
        DialogAdminOrganicAds.show(this)
    }

    private fun setResumeMode(mode: ResumeAdsEntryMode) {
        ensureAdRemoteConfig()
        if (!AdRemoteConfig.isInitialized()) return
        try {
            val instance = AdRemoteConfig.getInstance()
            val updatedAds = instance.ads.toMutableMap()

            val openResumeConfig = updatedAds["open_resume"]
            val nativeWelcomeConfig = updatedAds["native_welcome"]
            val interWelcomeConfig = updatedAds["inter_welcome"]

            if (openResumeConfig != null) {
                updatedAds["open_resume"] = openResumeConfig.copy(isEnable = (mode == ResumeAdsEntryMode.OPEN_RESUME))
            }
            if (nativeWelcomeConfig != null) {
                updatedAds["native_welcome"] = nativeWelcomeConfig.copy(isEnable = (mode == ResumeAdsEntryMode.WELCOME))
            }
            if (interWelcomeConfig != null) {
                updatedAds["inter_welcome"] = interWelcomeConfig.copy(isEnable = (mode == ResumeAdsEntryMode.WELCOME))
            }

            AdRemoteConfig.updateInstance(instance.copy(ads = updatedAds))

            // Dynamically enable/disable AppOpenManager based on mode
            if (mode == ResumeAdsEntryMode.OPEN_RESUME) {
                val openResumeId = AdRemoteConfig.open_resume.id
                AppOpenManager.getInstance().setAppResumeAdId(openResumeId)
                AppOpenManager.getInstance().enableAppResume()
            } else {
                AppOpenManager.getInstance().disableAppResume()
            }

            updateResumeModeUI()
        } catch (e: Exception) {
            Timber.w(e, "Failed to set resume mode")
        }
    }

    private fun updateResumeModeUI() {
        ensureAdRemoteConfig()
        if (!AdRemoteConfig.isInitialized()) return

        val mode = ResumeAdsEntryRule.currentMode()
        mBinding.tvCurrentResumeMode.text = "Current Mode: $mode"

        val openResumeEnable = AdRemoteConfig.open_resume.isEnable
        val nativeWelcomeEnable = AdRemoteConfig.native_welcome.isEnable
        val interWelcomeEnable = AdRemoteConfig.inter_welcome.isEnable

        mBinding.tvResumeConfigsDetail.text = "open_resume: ${if (openResumeEnable) "ON" else "OFF"} • native_welcome: ${if (nativeWelcomeEnable) "ON" else "OFF"} • inter_welcome: ${if (interWelcomeEnable) "ON" else "OFF"}"

        // Highlight selected button
        val allButtons = listOf(mBinding.btnModeOpenResume, mBinding.btnModeWelcome, mBinding.btnModeNone)
        val selected = when (mode) {
            ResumeAdsEntryMode.OPEN_RESUME -> mBinding.btnModeOpenResume
            ResumeAdsEntryMode.WELCOME -> mBinding.btnModeWelcome
            ResumeAdsEntryMode.NONE -> mBinding.btnModeNone
        }

        allButtons.forEach { btn ->
            if (btn == selected) {
                btn.setTextColor(getColor(R.color.color_main))
                btn.strokeColor = android.content.res.ColorStateList.valueOf(getColor(R.color.color_main))
            } else {
                btn.setTextColor(getColor(R.color.textPrimary))
                btn.strokeColor = android.content.res.ColorStateList.valueOf(getColor(R.color.color_divider))
            }
        }
    }

    private fun createFlagRow(name: String, enabled: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dpToPx(6), 0, dpToPx(6))
        }

        // Status dot
        val dot = View(this).apply {
            val size = dpToPx(10)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = dpToPx(10)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (enabled) Color.parseColor("#2E7D32") else Color.parseColor("#C62828"))
            }
        }

        // Flag name
        val label = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = name.removePrefix("shouldDisplay")
            setTextColor(Color.parseColor("#1F1F1F"))
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

        // Status text
        val status = TextView(this).apply {
            text = if (enabled) "ON" else "OFF"
            setTextColor(if (enabled) Color.parseColor("#2E7D32") else Color.parseColor("#C62828"))
            textSize = 11f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }

        row.addView(dot)
        row.addView(label)
        row.addView(status)
        return row
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    // ─── Helpers ──────────────────────────────────────────────

    private fun ensureAdRemoteConfig() {
        if (!AdRemoteConfig.isInitialized()) {
            AdRemoteConfig.initializeFromAssets(this)
        }
    }

    private fun requestPermission() {
        xxPermissions {
            permissions(PermissionLists.getPostNotificationsPermission())
            onDoNotAskAgain { permissions, userResult ->
                Timber.tag("Permission").d("Do not ask again ")
            }
            onShouldShowRationale { shouldShowRationaleList, onUserResult ->
                Timber.tag("Permission").d("Should show rationale")
            }
            onResult { allGranted, grantedList, deniedList -> }
        }
    }

    // ─── Lifecycle ────────────────────────────────────────────

    override fun onDestroy() {
        super.onDestroy()
        if (::consentHandler.isInitialized) {
            consentHandler.clear()
        }
        delayRunnable?.let {
            delayHandler.removeCallbacks(it)
        }
        noInternetDialog.dismiss()
        forceUpdateDialog.dismiss()
    }
}
