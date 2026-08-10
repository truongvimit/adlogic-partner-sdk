# OnboardKit (`onboardkitorigin`)

Onboarding SDK viết lại theo bộ report `ONBOARD_SDK_REPORT_01..05` (kiến trúc Apero Onboard
Template v4.4.2) — giữ lại các cơ chế tốt (lazy pager, hot-swap, preload n+1, remote-driven UI,
shimmer gương), sửa toàn bộ danh sách bẫy §10. UI bám Figma **Onboard Template 6.4.0**
(accent `#FF375E`, 3 native ad layout, flag Component 30).

- Namespace: `io.onboardkit` · resource prefix bắt buộc: `ob_` · entry point: `OnboardingSdk`
- Ads: interface `OnboardingAdProvider`; mặc định `ERainAdProvider` bridge sang module `:ads`
  (ERainAd/AdMob). App có thể inject provider khác hoặc `null` (không quảng cáo).

## Tích hợp nhanh

```kotlin
// Application.onCreate()
OnboardingSdk.install(this) {
    adProvider = ERainAdProvider()          // hoặc null
    paywallGate = MyPaywallGate()           // optional
    listener = OnboardingListener { ctx, outcome ->
        // outcome: Completed(selectedLanguage, answers, passthrough, stepsShown) | Skipped | Aborted
        ctx.startActivity(Intent(ctx, MainActivity::class.java))
    }
    analyticsPlugin { event -> Firebase.analytics.logEvent(event.name, event.params.toBundle()) }
}

OnboardingSdk.configure(
    onboardKitConfig {
        defaultSteps()                       // OB1, OB2, OB3(fullscreen ad), OB4
        question = QuestionConfig(options = listOf(QuestionOption("romance", "Romance", imageRes = R.drawable.opt1)))
        ads = AdsConfig(
            splashInterstitial = InterstitialAdUnit(allPriceId = "ca-app-pub-…"),
            languageNative = NativeAdUnit(allPriceId = "…", highFloorId = "…"),
            contentStepNative = NativeAdUnit(allPriceId = "…"),
            fullScreenStepNative = NativeAdUnit(allPriceId = "…"),
            ob5Native = NativeAdUnit(allPriceId = "…"),
            questionNative = NativeAdUnit(allPriceId = "…"),
        )
    }.getOrThrow(),
)
```

Splash: cho launcher activity kế thừa `ObSplashActivity`, override hook khi cần
(`onConsentRequired`, `onInitBilling`, `onRemoteFetched`). Đổi ngôn ngữ trong Settings:
`OnboardingSdk.openLanguagePicker(activity, LanguageScreenMode.SETTINGS)`.

## Remote config keys (prefix `ob_`)

Bật/tắt step `ob_enable_step_ob1..ob5`, `ob_enable_question`, `ob_enable_question_old_user`,
`ob_enable_language_native_2` (native thứ 2 hiện tại chỗ trên LFO sau khi chọn ngôn ngữ);
kill-switch `ob_enable_all_ads`, `ob_enable_ui_content`; timing
`ob_splash_min_display_ms`, `ob_skip_button_delay_sec`, `ob_fullscreen_auto_dismiss_sec`;
template native `ob_native_template_{content,language,question}` = `cta_top|cta_bottom|compact`;
server-driven UI `ob_ui_content` (mảng steps có id/order/enabled), `ob_ui_design_tokens`,
`ob_question_config`; version stamp `ob_config_version` (đổi giá trị → clear cache local).

## ID contract (khi app tự cấp layout)

`ob_native_container` (FrameLayout) · `ob_native_shimmer` (include shimmer gương) ·
`ob_primary_cta` (ObPrimaryButton) · `ob_ad_block`. Riêng layout LFO cần thêm slot thứ hai:
`ob_ad_block_2` · `ob_native_container_2` · `ob_native_shimmer_2`. Native template dùng bộ id AdMob chuẩn
(`ad_headline`, `ad_media`, `ad_call_to_action`, …) để `Admob.populateUnifiedNativeAdView` bind.

## Khác biệt chủ đích so với SDK gốc

- Checkpoint `lastCompletedStep`: kill app giữa chừng resume đúng bước, không quay lại màn ngôn ngữ.
- LFO2 (nhân đôi impression) và refresh-ad-khi-chạm-đáp-án **tắt mặc định** — bật qua config + remote.
- Premium ẩn ad ở mọi màn (kể cả OB5) và có thể bỏ hẳn step chỉ-có-ad.
- Màn fullscreen luôn có đường thoát: Skip ép hiện khi không có auto-next + auto-dismiss theo remote.
- Question lưu đáp án vào DataStore + phát analytics/event (SDK gốc vứt bỏ cả hai).
- Đáp án/step remote hỏng → drop từng phần tử, không hủy cả màn.
