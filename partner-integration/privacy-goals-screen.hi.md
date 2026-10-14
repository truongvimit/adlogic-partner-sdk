# Privacy → Goal स्क्रीन

[English](privacy-goals-screen.md) · [Tiếng Việt](privacy-goals-screen.vi.md) · [हिन्दी](privacy-goals-screen.hi.md)

यह अधूरे onboarding flow का वैकल्पिक अंतिम भाग है: Splash → भाषा → OB pager → exit interstitial (चालू होने पर; default रूप से बंद) → Privacy → Goal → app। App asset या remote `onboarding_config` में इसे चालू करें:

```json
{ "privacy_goals_screen": { "enabled": true } }
```

Default `false` है। Host `PrivacyGoalsScreenConfig` से भी चालू कर सकता है; remote और app asset code से ऊपर हैं। `goal.options` दें। यह list खाली हो तो warning आती है, ये screens और उनके native preloads छोड़ दिए जाते हैं और सामान्य onboarding exit चलता है। Ad fill या premium status इन screens को बंद नहीं करता।

इस page का बाकी हिस्सा default `PER_SCREEN` flow बताता है; [SHARED_POOL में](#shared_pool-में) बताता है कि `SHARED_POOL` flow में क्या बदलता है।

```kotlin
privacyGoalsScreen = PrivacyGoalsScreenConfig(
    enabled = true,
    goal = GoalsScreenConfig(
        options = listOf(
            GoalOption("work", title = "Work", imageRes = R.drawable.partner_work),
            GoalOption("study", title = "Study", imageRes = R.drawable.partner_study),
        ),
        selectionMode = SelectionMode.MULTIPLE,
        minSelection = 1,
    ),
)
```

Types `io.onboardkit.config` में हैं; अपने drawables दें। `SINGLE` भी समर्थित है; उसके लिए `minSelection = 1` रखें। MULTIPLE में minimum उपलब्ध options से अधिक न हो। Content/resources app में रहते हैं; remote flag सिर्फ screens चालू करता है।

## Layout contract

SDK layouts देता है। Customize करने के लिए app में इन्हीं नामों को override करें:

- `res/layout/ob_privacy_screen.xml`
- `res/layout/ob_goal_screen.xml`
- `res/layout/ob_goal_option.xml`

Privacy consent control का ID `ob_privacy_consent_checkbox`, Goal `RecyclerView` का `ob_goal_options`, और दोनों screens के ad `FrameLayout` का `ob_privacy_goals_ad` रखें। Ad frame का आकार और स्थान समान रखें। इन conventions के साथ अलग view-ID config नहीं चाहिए।

SDK clickable `Button` या `TextView` को action मानता है। कई clickable controls हों तो सही action को optional `ob_privacy_goals_continue` ID दें। Option item root पर click और `isSelected`/`Checkable` state मिलती है। SDK हर bind पर पहले `TextView` में option title सेट करता है, recycled ViewHolder में भी। पहला `ImageView` option image प्राप्त करता है। Preview के लिए `tools:text` और selected styling के लिए selector के साथ `duplicateParentState="true"` इस्तेमाल करें।

## Ads और completion

ये keys production IDs के साथ `ad_config.json` में रखें; `ad_config_debug.json` में सिर्फ `native_select` और `native_select_alt`, हर एक के `ids` में एक test floor चाहिए:

```json
{
  "native_select": {
    "ids": [{ "id": "HIGH_NATIVE_UNIT", "isEnable": true }, { "id": "BASE_NATIVE_UNIT", "isEnable": true }],
    "isEnable": true,
    "click_action": "reload"
  },
  "native_select_alt": {
    "ids": [{ "id": "ALT_HIGH_NATIVE_UNIT", "isEnable": true }, { "id": "ALT_BASE_NATIVE_UNIT", "isEnable": true }],
    "isEnable": true,
    "click_action": "reload"
  }
}
```

Privacy और Goal अलग SDK placements हैं, जो ये configured keys साझा करते हैं। `native_select` पहला ad है। पहली consent acceptance/goal selection पर ALT replacement शुरू होता है। ALT सफलतापूर्वक bind होने तक पहला ad दिखता रहता है; no-fill पर भी बना रहता है। Preload और show दोनों 4:3 media-left frame (`ob_layout_native_media_left.xml`, template `2`) इस्तेमाल करते हैं, जब तक key का `templateId` कोई और template न चुने। CTA color/height लागू हैं; `components` यह frame नहीं बदलता। `click_action` key के `ids` के हर floor पर लागू होता है; default `reload` है।

हर key का `isEnable` उसके पूरे `ids` waterfall को नियंत्रित करता है; floor का अपना `isEnable` सिर्फ उसी floor को रोकता है। किसी key को बंद करना उसका ad बंद करता है, screen नहीं। Consent, premium, UA और global ad gate लागू रहते हैं; अलग `privacyAd.enabled`/`goalAd.enabled` switches नहीं हैं।

Privacy का पहला native वास्तविक अंतिम pager step पर preload होता है, fullscreen होने पर भी। Exit interstitial चालू हो तो `onboarding.exit_interstitial.next_screen_timing` लागू है: `UNDER_AD` Privacy को ad के नीचे खोलता है; `AFTER_AD` dismissal का इंतज़ार करता है। Notification/widget/uninstall entry इस exit path में dismissal का इंतज़ार करती है।

Privacy में acceptance के बाद Continue मिलता है। Goal पूरा होने पर picks `GoalAnswer` के रूप में दर्ज होते हैं (`OnboardingEvent.GoalsSelected`, `OnboardingOutcome.Completed.goals` और बाद में `OnboardingSdk.selectedGoals()`), और onboarding सीधे complete होता है; सामान्य OB5/paywall exit आगे नहीं खुलता। Goal में Back से Privacy आता है; Privacy में Back task बंद करता है। Goal screen दोबारा बनने पर choices फिर चुननी पड़ती हैं।

Goal पूरा होने पर ही flow completed होता है। उससे पहले app बंद करने पर अगला launch Splash → भाषा से शुरू होता है। Feature बंद हो तो layouts inflate नहीं होते और सामान्य onboarding exit चलता है।

## SHARED_POOL में

[`native_flow.type`](remote-settings.hi.md#native-flow-type) `SHARED_POOL` होने पर Privacy और Goal pooled onboarding screen के आखिरी stages हैं ([SHARED_POOL native flow](ads-onboarding-integration.hi.md#shared_pool-native-flow))। `privacy_goals_screen.enabled` (default रूप से बंद), `goal.options`, ऊपर का layout contract और `native_select` / `native_select_alt` keys वही रहते हैं। दोनों screens एक ही select-screen pattern पर चलती हैं:

| | Privacy | Goal |
| --- | --- | --- |
| खुलने पर | `native_select` को POLITE load के रूप में request करती है (दूसरी pool request चलते समय छोड़ा जाता है) और pool की owner बनती है | Privacy जैसा |
| Selection | हर consent बदलाव, check या uncheck | हर option tap |
| हर selection पर | `native_select_alt` को FORCED load के रूप में request करती है (दूसरी pool request चलते समय भी नहीं छोड़ा जाता; `native_select_alt` के पास ad होने या उसके load होते रहने पर बाद के selections कुछ request नहीं करते) और उसे owner बनाती है | Privacy जैसा |
| Forward button | Continue पहले consent बदलाव के `native_flow.pool.select.next_reveal_delay_ms` (3000 ms) बाद तक छिपा रहता है, फिर केवल consent checked रहने पर काम करता है | Next पहले tap के बाद उसी delay तक छिपा रहता है, फिर कम से कम `minSelection` options चुने रहने पर काम करता है |
| Forward action | Goal का पहला ad POLITE load के रूप में preload करता है और Goal खोलता है | ऊपर की तरह goals दर्ज करता है, `native_flow.pool.goals.exit_reuse_splash_inter` `true` हो तो splash interstitial का बचा हुआ ad दिखाता है, फिर बिना paywall के onboarding पूरा करता है |
| Back | कुछ नहीं करता | कुछ नहीं करता |

हर बार screen फिर सामने आने पर pool की ownership वापस लेती है, अपने owned ad को refresh करती है जबकि वह ad दिखता रहता है, और आखिरी माँगी गई placement के लिए POLITE request दोहराती है। उसका अपना ad fill होने तक slot pool का चुनाव दिखाता है: पहले सबसे नया ad जो किसी screen ने अभी तक नहीं दिखाया, नहीं तो सबसे हाल में दिखाया गया ad, और request चलते समय shimmer। Native click न reload करता है न navigate (`click_action` ignore होता है)। Return ads दोनों screens के ऊपर खुल सकते हैं (`native_flow.pool.resume_on_goals`, default `true`)। Privacy से पहले कोई exit interstitial नहीं चलता, और Privacy का पहला ad pager के आखिरी page पर preload नहीं होता: Privacy खुलने पर उसे request करती है। Privacy और Goal अलग-अलग request करते हैं पर `native_select` / `native_select_alt` की IDs साझा करते हैं, इसलिए उन IDs का revenue उस screen के नाम report होता है जिसने सबसे बाद में request किया।
