# PayKit — तैयार paywall

[← एक गाइड चुनें](README.hi.md) · [PayKit API](../paykit/README.md)

आपकी app catalog, copy, दोनों terms/privacy URLs और paywall कहाँ खुले यह देती है; PayKit UI, Play की कीमतें, purchase/restore और premium संभालता है। **`AppPurchase.initBilling` अलग से न बुलाएँ**: PayKit पहले ही BillingKit initialize कर चुका है।

सिर्फ दो resources नए हैं, कदम 2 में; बाकी सब आपकी मौजूदा Gradle file, Application और paywall entry point में मिल जाता है।

## 1. Dependency जोड़ें

[build setup](../README.hi.md#build-setup) पूरा करें और वही `adlogicSdkVersion` साझा करें। JDK 17, `minSdk 24+`, `compileSdk 36+`; ads न हों तो mediation repositories की ज़रूरत नहीं।

```groovy
def sdkVersion = providers.gradleProperty('adlogicSdkVersion').get()
dependencies {
    implementation "com.github.truongvimit.adlogic-partner-sdk:paykit:$sdkVersion"
    // Sample BillingKit.setDevMode कॉल करता है, इसलिए billing API सीधे declare करें.
    implementation "com.github.truongvimit.adlogic-partner-sdk:billingkit:$sdkVersion"
}
```

`billingkit` dependency असली Play mode पर जाने और `Billing` पढ़ने देती है; Trackkit पहले से उपलब्ध है। अपना BillingClient, paywall Activity या layout नहीं चाहिए।

## 2. Catalog और resources copy करें

| नमूना file | Copy कहाँ | आपकी app क्या बदलती है |
| --- | --- | --- |
| [paywall_config.json](examples/paywall/paywall_config.json) | `app/src/main/res/raw/paywall_config.json` | Product IDs, base plan/offer, copy और हर package की configuration। |
| [paywall_strings.xml](examples/paywall/paywall_strings.xml) | `app/src/main/res/values/paywall_strings.xml` | आपके product से मेल खाती copy/benefits और दोनों असली URLs; `values-<language>` में translations जोड़ें। |

JSON में [example](../app/src/main/res/raw/paywall_config.json) की पूरी संरचना है; जब तक आपकी app के पास असली offer न हो, badge/discount खाली/0 रखें। IDs/plans/offers की जगह Play Console में बनाए अपने products रखें; **कोई साझा test product ID नहीं है**।

आपकी app के `pw_*` resources SDK की सामग्री override करते हैं; नाम टकराएँ तो उन्हें अपने मौजूदा resources में मिलाएँ। दोनों `example.com` URLs की जगह अपनी app के असली pages रखें।

एक local JSON काफी है। Debug के लिए अलग catalog चाहिए तो वही file name `app/src/debug/res/raw/paywall_config.json` पर रखें; PayKit `*_debug` file नहीं ढूँढ़ता।

## 3. अपनी Application में install करें

इसे अपनी मौजूदा Application में मिलाएँ: app analytics इकट्ठा करती हो तो Tracker के बाद, OB के अंदर paywall इस्तेमाल करें तो `OnboardingSdk.install` से पहले। Package और अपनी app का `R` import बदलें।

```kotlin
package com.example.app

import android.app.Application
import com.ads.module.billing.BillingKit
import io.paykit.PayKit
import io.paykit.PaywallPlacement
import io.paykit.payKitConfig

class PaywallApp : Application() {
    override fun onCreate() {
        super.onCreate()
        BillingKit.setDevMode(false)
        val config = payKitConfig {
            termsUrl = getString(R.string.paywall_terms_url)
            privacyUrl = getString(R.string.paywall_privacy_url)
            defaultPlacements = setOf(PaywallPlacement.SETTING)
            fallbackConfigRes = R.raw.paywall_config
        }.getOrThrow()
        PayKit.install(this, config)
    }
}
```

आपकी app में अभी Application न हो तो उसे `android:name` से register करें; paywall Activity और theme SDK से merge हो जाते हैं। दोनों URLs host वाले HTTP(S) होने चाहिए; वरना `getOrThrow()` तुरंत fail होता है।

नमूना Settings से खोलने की अनुमति देता है। **`defaultPlacements` सेट करना ज़रूरी है** क्योंकि SDK का default खाली है; local JSON का `placements` कोई slot enable नहीं करता। `isReady()` सिर्फ configuration की पुष्टि करता है, Play से आने वाली कीमतें या entitlement की नहीं।

## 4. उपयोगकर्ता की क्रिया से paywall खोलें

किसी Activity में अपने Premium button से इसे बुलाएँ; UI या navigation `onFinished` में अपडेट करें।

```kotlin
package com.example.app

import android.app.Activity
import io.paykit.PayKit
import io.paykit.PaywallListener
import io.paykit.PaywallPlacement
import io.paykit.PaywallResult

fun Activity.openPremium(onFinished: (PaywallResult) -> Unit) {
    PayKit.launch(this, PaywallPlacement.SETTING, object : PaywallListener() {
        override fun onFinished(placement: PaywallPlacement, result: PaywallResult) {
            onFinished(result)
        }
    })
}
```

परिणाम: `Purchased(productId)`, `ContinueWithAds`, `Dismissed`, `Error(code, message)`। बंद placement, पहले से premium उपयोगकर्ता या दोहराया गया click `Dismissed` लौटाता है; हर dismissal को उपयोगकर्ता द्वारा screen बंद करना न समझें। Entitlement `PayKit.isPremium()` से पढ़ें या [Billing.isPremium](billing-integration.hi.md#3-परिणाम-और-premium-state-देखें) देखें।

`ContinueWithAds` सिर्फ paywall बंद करता है, कोई ad नहीं दिखाता। Consumables में वस्तु आपकी app को खुद देनी होती है; `Purchased` का मतलब हमेशा premium नहीं। SDK purchase और Restore buttons पहले ही संभालता है।

## 5. JSON और वैकल्पिक configuration की तालिकाएँ

### JSON के fields

| Field | नमूना / कैसे इस्तेमाल करें |
| --- | --- |
| `config_version` | JSON metadata; नमूना file जैसा ही रखें। |
| `packages[].id`, `type` | आपके असली IDs; `subs`, `inapp`, `consumable`। कम से कम एक valid package चाहिए। |
| `base_plan_id`, `offer_id` | Subscription के coordinates। `offer_id` छोड़ दें ताकि SDK बिना offer वाले base plan को मजबूर करने के बजाय plan के अंदर का offer चुने। मेल न खाने पर resolver fallback कर सकता है। |
| `title_key`, `subtitle_key` | App/SDK string resources के नाम। Literal `title`, `subtitle` मौजूद हों तो keys पर भारी पड़ते हैं और localize नहीं होते। |
| `badge`, `discount_percent` | नमूने में खाली/0; valid discount 0–99 है। ये सिर्फ label और तुलना वाली कीमत दिखाते हैं; Play की कीमत नहीं बदलते। |
| `preselected` | शुरुआती package चुनता है; कई packages true हों तो SDK पहला रखता है। कोई true न हो तो पहला package चुना जाता है। |
| `copy` | `headline_key`, `benefit_keys`, `cta_key`; copy remote से आए तो literal `headline`, `benefits`, `cta` supported हैं। |
| `tokens` | example जैसे सारे colors: `text_primary`, `text_secondary`, `accent`, `background`, `surface`, `on_accent`, `cta_gradient`। अलग look चाहिए तभी बदलें। |
| `exit_button` | नमूने में enabled, delay 3000 ms; object न हो तब भी enabled रहता है। JSON का `delay_ms` local value को override करता है, 0 भी; local इस्तेमाल करने के लिए delay छोड़ दें। |
| `continue_with_ads`, `restore` | नमूना दोनों enable करता है। आपकी app में ads न हों तो `continue_with_ads` बंद करें; यह button कोई ad load नहीं करता। दोनों objects छोड़ देने पर parser इन दोनों features को default रूप से बंद रखता है। |
| `placements` | सिर्फ remote/cache से आया JSON या गैर-खाली list वाला `applySnapshot` ही local set को override करता है। खाली array `defaultPlacements` पर fallback करता है; यह kill switch नहीं है। |

### Kotlin में configuration

| विकल्प | SDK default / कब बदलें |
| --- | --- |
| `defaultPlacements` | खाली; नमूना `SETTING` इस्तेमाल करता है। नीचे दिए enum से और जोड़ें। |
| `fallbackConfigRes` | 0 SDK का demo catalog इस्तेमाल करता है; नमूना आपकी app का resource देता है ताकि remote के बिना भी चले। |
| `termsUrl`, `privacyUrl` | ज़रूरी; आपकी app के HTTP(S) URLs। |
| `exitButtonDelayMs` | 0; सिर्फ तब इस्तेमाल होता है जब JSON में delay न हो। नमूना JSON पहले ही 3000 सेट करता है। |
| `singleClickWindowMs` | 700 ms; launch के आसपास अपना debounce नहीं चाहिए। |
| `logLevel` | `WARN`; parsing/configuration देखना हो तो `PayKitLogLevel.DEBUG` इस्तेमाल करें। |
| `PayKit.sync(timeoutMs)` | 5 सेकंड; तभी बुलाएँ जब remote source installed हो, failure मौजूदा configuration बनाए रखता है। |
| `BillingKit.setDevMode` | नमूना Play के साथ test करने के लिए `false` सेट करता है; `true` सिर्फ local पर simulate करता है और असली products/offers नहीं जाँचता। |
| Custom UI | `PayKit.renderer(...)` जब अपना renderer चाहिए; standard flow के लिए ज़रूरी नहीं। |

`PaywallPlacement`: `SPLASH`, `AFTER_ONBOARDING`, `HOME`, `SETTING`, `FEATURE_LOCK`, `OTHER`; इनसे मेल खाती JSON keys lowercase हैं। Call site पर enum इस्तेमाल करें; अपनी app की दूसरी screens के लिए `OTHER` इस्तेमाल करें।

**Catalog की सीमा:** PayKit UI हर product का renewal price और trial पढ़ता है, जबकि purchase base plan/offer के अनुसार चुनता है; कई plans/offers होने पर जो दिखता है वह खरीदे गए offer से अलग हो सकता है। नमूना हर product के लिए एक base plan इस्तेमाल करता है; release से पहले जाँचें कि कीमत, billing period और trial Play से मेल खाते हैं।

## 6. दूसरे SDKs से जोड़ें

- **Firebase:** source install करें, फिर [Firebase गाइड](firebase-integration.hi.md#4-remote-paywall-json) के अनुसार `PayKit.sync()` बुलाएँ। सिर्फ local वाले setup को sync नहीं चाहिए; valid remote cache bundled JSON पर भारी पड़ता है।
- **Ads/premium:** splash में [billing के इंतज़ार वाला hook](billing-integration.hi.md#जब-आपकी-app-में-adsonboarding-हो) जोड़ें। PayKit premium को ad gate से पहले ही जोड़ चुका है।
- **Analytics/Adjust:** PayKit से पहले Tracker/sinks install करें; SDK purchase/paywall events पहले ही भेजता है, `onFinished` में revenue दोबारा न भेजें।

**OB के अंदर paywall:** `onboardkitorigin` declare करें, OnboardKit से पहले PayKit install करें; `OnboardingSdk.install` block में `paywallGate = io.paykit.integration.OnboardKitPaywallGate()` जोड़ें। ज़रूरी slots को `defaultPlacements` में enable करें:

| OB checkpoint | PayKit का `PaywallPlacement` |
| --- | --- |
| Splash inter से पहले | `SPLASH` |
| Onboarding या नए user के survey के बाद | `AFTER_ONBOARDING` |
| पुराने user के survey के बाद | `OTHER` |

SDK उसे checkpoint पर खोलता है; OB पूरा होने वाले listener से दोबारा launch न करें। `OTHER` पुराने user वाले checkpoint और आपकी app के अपने `OTHER` call site के बीच साझा है।

## 7. जाँच

- [ ] `PayKit.isReady()` true है, enable किए गए enum value से खुलता है; URLs/copy आपकी app के हैं।
- [ ] कीमत, billing period और offer/trial Play से मेल खाते हैं; नमूने के product IDs बदल दिए गए हैं। [license tester कैसे तैयार करें](billing-integration.hi.md#1-तैयारी-करें-और-dependency-जोड़ें) देखें।
- [ ] purchase, cancel, error और restore test करें; पहले से premium उपयोगकर्ता को paywall दोबारा नहीं मिलता। Continue with ads सिर्फ callback लौटाता है।
- [ ] Firebase के बिना/offline और टूटे JSON के साथ test करें; remote cache को अपना नया resource न समझें।
- [ ] OB से paywall तक जाने पर वह सिर्फ एक बार खुलता है; splash में billing का इंतज़ार करें, और timeout पर सहेजे गए premium state के साथ आगे बढ़ें।

Parse errors: `PayKit` का log पढ़ें। खाली कीमतें: catalog और Play account जाँचें।
