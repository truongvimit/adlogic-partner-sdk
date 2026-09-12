# Partner integration गाइड

अपनी app को जो feature चाहिए, उसकी गाइड चुनें। बुनियादी integration क्रम से पूरा करें; default व्यवहार बदलना हो तभी वैकल्पिक tables खोलें।

| App को क्या चाहिए | कौन सी गाइड पढ़ें | Module |
| --- | --- | --- |
| Splash → भाषा → ads के साथ onboarding → मुख्य स्क्रीन | **[Ads + OnboardKit integration](ads-onboarding-integration.hi.md)** | `ads` + `onboardkitorigin` |
| App की अपनी screens में ads | Ads + OnboardKit गाइड पूरी कर ली है: अपनी app की [AppAdPlacement.kt](examples/ads-onboarding/AppAdPlacement.kt) के साथ [native](ads-onboarding-integration.hi.md#app-की-अपनी-screens-में-native-placement-constant-के-साथ), [inter](ads-onboarding-integration.hi.md#app-की-अपनी-screens-में-interstitial-placement-constant-के-साथ), [app-open](ads-onboarding-integration.hi.md#app-open-on-return) के नमूने इस्तेमाल करें। OnboardKit इस्तेमाल नहीं कर रहे: [Ads](../ads/README.md) को init और placements से लेकर consent तक पूरा करें। | `ads` |
| अपने UI के साथ purchases | [BillingKit integration](billing-integration.hi.md) | `billingkit` |
| दिया हुआ paywall | [PayKit integration](paywall-integration.hi.md) | `paykit`, इसके APIs सीधे बुलाने पर `billingkit` जोड़ें |
| Remote JSON / Firebase Analytics | [Firebase integration](firebase-integration.hi.md) | `suite-firebase` और जिस module से जोड़ रहे हैं वह |
| App के events / analytics कहाँ जाएँ यह चुनना | [Trackkit integration](trackkit-integration.hi.md) | `trackkit` kits पहले से देते हैं |
| Ad debugging dashboard | [AdTracer integration](adtracer-integration.hi.md) | `adtracer`, सिर्फ debug में |

## App में जोड़ने का क्रम

1. **मुख्य feature चुनें:** Ads/OB, अपने UI के साथ BillingKit, या दिए हुए UI के साथ PayKit। App में ads और purchases दोनों हो सकते हैं; PayKit खुद billing initialize करता है, इसलिए `AppPurchase.initBilling` से दूसरा catalog register न करें।
2. **Analytics इकट्ठा करते हैं:** kits के events भेजने से पहले Application में Tracker और sink install करें। Firebase और AdTracer वैकल्पिक destinations हैं; Adjust को [ads/Adjust गाइड](ads-onboarding-integration.hi.md#adjust-token-और-verification) से जोड़ें।
3. **Kits और local data initialize करें:** अपनी मौजूदा Application इस्तेमाल करें। OB के साथ paywall हो तो OnboardKit से पहले PayKit install करें; ads से पहले splash hook में billing का इंतज़ार करें।
4. **Remote इस्तेमाल करते हैं:** local setup के बाद Firebase sources जोड़ें। OB splash ads पहले ही refresh करता है; paywall को remote config चाहिए उससे पहले अपना `PayKit.sync()` चाहिए।
5. **परीक्षण:** चुनी हुई गाइड के अंत वाली checklist चलाएँ; dashboard पर ad flow देखना हो तो AdTracer जोड़ें।

हर गाइड करना जरूरी नहीं। हर गाइड अपनी dependencies, जरूरी files और वैकल्पिक हिस्से बताती है; कई Application classes copy न करें और एक ही SDK एक से ज्यादा बार install न करें।

## इन गाइड का इस्तेमाल कैसे करें

- **बुनियादी code:** सिर्फ app का data और SDK wiring घोषित करता है; SDK defaults बनाए रखता है; नमूना JSON example का configuration रखता है।
- **वैकल्पिक tables:** default, कब बदलना है और कहाँ configure करना है यह बताते हैं। पूरी table अपने code या Firebase में copy करना जरूरी नहीं।
- **App placements:** [AppAdPlacement.kt](examples/ads-onboarding/AppAdPlacement.kt) OB keys और आपकी app screens को एक जगह रखता है। Placement key ही किसी ad slot की एकमात्र पहचान है — ad unit ID कई placements में साझा होता है इसलिए उनमें फर्क नहीं कर सकता; ad unit IDs JSON में रहते हैं।
- **नमूना files:** [ad_config.json](examples/ads-onboarding/ad_config.json) और [ad_config_debug.json](examples/ads-onboarding/ad_config_debug.json) दोनों test ad IDs इस्तेमाल करते हैं। इन्हें `app/src/main/assets/` में copy करें; release से पहले असली file के IDs बदलें।
- **Module READMEs:** जरूरत पड़ने पर और APIs, lifecycle और customization यहाँ देखें।

## Copy करने के लिए नमूना files

| Feature | नमूना | कहाँ जाता है |
| --- | --- | --- |
| Ads + OB | [Production JSON](examples/ads-onboarding/ad_config.json), [debug JSON](examples/ads-onboarding/ad_config_debug.json), [AppAdPlacement](examples/ads-onboarding/AppAdPlacement.kt), [OnboardKitSetup](examples/ads-onboarding/OnboardKitSetup.kt), [PartnerApp](examples/ads-onboarding/PartnerApp.kt) | Assets, catalog और SDK initialization; दोनों JSON files test ad IDs इस्तेमाल करती हैं। |
| अपने UI के साथ billing | [BillingProducts.kt](examples/billing/BillingProducts.kt) | Product/base plan/offer catalog; अपनी app के Play products से बदलें। |
| Paywall | [paywall_config.json](examples/paywall/paywall_config.json), [paywall_strings.xml](examples/paywall/paywall_strings.xml) | `res/raw`, `res/values`; example के fields पूरे हैं, catalog/copy/URLs बदलें। |
| App events | [AppEvents.kt](examples/trackkit/AppEvents.kt) | Event/param keys एक जगह, सिर्फ अपनी app को चाहिए वही events रखें। |
| Debug dashboard | [AdTracerSink](examples/adtracer/debug/AdTracerSink.kt), [debug entry](examples/adtracer/debug/DebugSinks.kt), [release no-op](examples/adtracer/release/DebugSinks.kt) | debug/release source sets; आपकी app के समान package। |

Firebase वही `google-services.json` इस्तेमाल करता है जो Console आपकी app के लिए देता है; copy करने के लिए कोई नमूना credentials file नहीं है।

सभी dependency उदाहरण आपकी app की `gradle.properties` से एक ही `adlogicSdkVersion` पढ़ते हैं; [build setup](../README.hi.md#build-setup) देखें। SDK upgrade करने के लिए अपनी app में वह property बदलें।

[SDK README](../README.hi.md) · [Ads + OnboardKit से शुरू करें](ads-onboarding-integration.hi.md)
