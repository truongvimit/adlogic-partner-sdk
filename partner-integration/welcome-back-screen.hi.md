# Welcome Back स्क्रीन

[English](welcome-back-screen.md) · [Tiếng Việt](welcome-back-screen.vi.md) · [हिन्दी](welcome-back-screen.hi.md)

Welcome Back वह स्क्रीन है जो लौटने वाला user हर बार launcher से app खोलने पर splash के बाद देखता है: Splash (`inter_splash_o`) → Welcome Back → app। User चार goals में से एक चुनता है, फिर Continue app को सौंप देता है। यह first-open user के लिए या notification, widget, uninstall entry के लिए कभी नहीं खुलती; उनका route वही रहता है।

यह default रूप से चालू है। App asset या remote `onboarding_config` में बंद करें:

```json
{ "welcome_back": { "enabled": false } }
```

बंद होने पर लौटने वाले user का launcher launch सीधे app में जाता है, जो splash ad के नीचे खुलता है, `OnboardingOutcome.Skipped(ALREADY_COMPLETED)` के साथ।

SDK चार PDF goals देता है (Edit PDF, Add text, Sign & fill, Split & merge)। अपने goals दें:

```kotlin
welcomeBackScreen = WelcomeBackScreenConfig(
    options = listOf(
        GoalOption("scan", titleRes = R.string.goal_scan, imageRes = R.drawable.ic_goal_scan),
        GoalOption("read", titleRes = R.string.goal_read, imageRes = R.drawable.ic_goal_read),
    ),
)
```

खाली list स्क्रीन बंद कर देती है। Selection हमेशा single है।

## Layout contract

Restyle करने के लिए app में ये resource names override करें:

- `res/layout/ob_welcome_back_screen.xml`
- `res/layout/ob_welcome_back_option.xml`

`ob_welcome_back_options` को `RecyclerView`, `ob_welcome_back_continue` को action और `ob_welcome_back_ad` को अपने parent block के अंदर `FrameLayout` रखें। Option root को clicks और `isSelected` मिलते हैं; पहला `TextView` title और पहला `ImageView` image पाता है।

## Ads और completion

इन्हें `ad_config.json` (production IDs) में, बाकी native placements जैसे shape में declare करें; `ad_config_debug.json` में सिर्फ `native_welcome1` और `native_welcome2`, हर एक की एक test `id` चाहिए:

```json
{
  "native_welcome1_high": { "id": "HIGH_NATIVE_UNIT", "isEnable": true },
  "native_welcome1": { "id": "BASE_NATIVE_UNIT", "isEnable": true, "click_action": "reload" },
  "native_welcome2_high": { "id": "ALT_HIGH_NATIVE_UNIT", "isEnable": true },
  "native_welcome2": { "id": "ALT_BASE_NATIVE_UNIT", "isEnable": true, "click_action": "reload" }
}
```

`native_welcome1` को splash, LFO1 वाले समय और mode (`splash.load.lfo1_preload_mode`) पर preload करता है। `native_welcome2` Welcome Back खुलने पर preload होता है और पहले tap पर पहले ad की जगह लेता है; दूसरे का fill न हो तो पहला ad रहता है। दोनों अपने base key के `positionCTA` से render होते हैं। Base `isEnable` पूरा waterfall बंद करता है; स्क्रीन फिर भी दिखती है।

Default timing में स्क्रीन splash interstitial बंद होने के बाद खुलती है। Continue चुनाव को `GoalAnswer` के रूप में दर्ज करता है (`OnboardingEvent.GoalsSelected` से भेजा जाता है, बाद में `OnboardingSdk.selectedGoals()` से पढ़ा जा सकता है) और launch passthrough के साथ `OnboardingOutcome.Skipped(ALREADY_COMPLETED)` देता है। कुछ भी complete mark नहीं होता और `FlowCompleted` / `fo_flow_complete` नहीं भेजा जाता, इसलिए अगले launcher launch पर स्क्रीन फिर दिखती है। Back app बंद करता है।
