# Privacy → Goal स्क्रीन

[English](privacy-goals-screen.md) · [Tiếng Việt](privacy-goals-screen.vi.md) · [हिन्दी](privacy-goals-screen.hi.md)

यह अधूरे onboarding flow का वैकल्पिक अंतिम भाग है: Splash → भाषा → OB pager → exit interstitial → Privacy → Goal → app। App asset या remote `onboarding_config` में इसे चालू करें:

```json
{ "privacy_goals_screen": { "enabled": true } }
```

Default `false` है। Host `PrivacyGoalsScreenConfig` से भी चालू कर सकता है; remote और app asset code से ऊपर हैं। `goal.options` दें। यह list खाली हो तो warning आती है, ये screens और उनके native preloads छोड़ दिए जाते हैं और सामान्य onboarding exit चलता है। Ad fill या premium status इन screens को बंद नहीं करता।

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

दोनों ad files में ये keys रखें; release में production और debug में test IDs दें:

```json
{
  "native_select_high": { "id": "HIGH_NATIVE_UNIT", "isEnable": true },
  "native_select": { "id": "BASE_NATIVE_UNIT", "isEnable": true, "click_action": "reload" },
  "native_select_alt_high": { "id": "ALT_HIGH_NATIVE_UNIT", "isEnable": true },
  "native_select_alt": { "id": "ALT_BASE_NATIVE_UNIT", "isEnable": true, "click_action": "reload" }
}
```

Privacy और Goal अलग SDK placements हैं, जो ये configured keys साझा करते हैं। `native_select` पहला ad है। पहली consent acceptance/goal selection पर ALT replacement शुरू होता है। ALT सफलतापूर्वक bind होने तक पहला ad दिखता रहता है; no-fill पर भी बना रहता है। Preload और show दोनों fixed 4:3 media-left frame (`ob_layout_native_media_left.xml`) इस्तेमाल करते हैं। CTA color/height लागू हैं; `positionCTA` frame नहीं बदलता। `click_action` केवल base key से पढ़ा जाता है; default `reload` है।

Base `isEnable` पूरे waterfall को नियंत्रित करता है, `_high` समेत। किसी base को बंद करना उसका ad बंद करता है, screen नहीं। Consent, premium, UA और global ad gate लागू रहते हैं; अलग `privacyAd.enabled`/`goalAd.enabled` switches नहीं हैं।

Privacy का पहला native वास्तविक अंतिम pager step पर preload होता है, fullscreen होने पर भी। `onboarding.exit_interstitial.next_screen_timing` लागू है: `UNDER_AD` Privacy को ad के नीचे खोलता है; `AFTER_AD` dismissal का इंतज़ार करता है। Notification/widget/uninstall entry इस exit path में dismissal का इंतज़ार करती है।

Privacy में acceptance के बाद Continue मिलता है। Goal पूरा होने पर picks `GoalAnswer` के रूप में दर्ज होते हैं (`OnboardingEvent.GoalsSelected`, `OnboardingOutcome.Completed.goals` और बाद में `OnboardingSdk.selectedGoals()`), और onboarding सीधे complete होता है; सामान्य OB5/paywall exit आगे नहीं खुलता। Goal में Back से Privacy आता है; Privacy में Back task बंद करता है। Goal screen दोबारा बनने पर choices फिर चुननी पड़ती हैं।

Goal पूरा होने पर ही flow completed होता है। उससे पहले app बंद करने पर अगला launch Splash → भाषा से शुरू होता है। Feature बंद हो तो layouts inflate नहीं होते और सामान्य onboarding exit चलता है।
