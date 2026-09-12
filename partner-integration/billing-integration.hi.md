# BillingKit — अपनी UI के साथ purchases

[← गाइड चुनें](README.hi.md) · [BillingKit API](../billingkit/README.md)

इसे तब इस्तेमाल करें जब आपकी app **अपनी purchase screen खुद बनाती है**। [PayKit](paywall-integration.hi.md) की UI इस्तेमाल करते हों तो उसी गाइड को follow करें; PayKit पहले ही BillingKit और catalogue initialize कर देता है।

सिर्फ [BillingProducts.kt](examples/billing/BillingProducts.kt) नया है; बाकी सब आपके मौजूदा Gradle, Application और purchase screen में मिल जाता है। अलग JSON या BillingClient की ज़रूरत नहीं।

## 1. तैयारी करें और dependency जोड़ें

[साझा build setup](../README.hi.md#build-setup) पूरा करें: JDK 17, `minSdk 24+`, `compileSdk 36+`, `adlogicSdkVersion` property और repositories। सिर्फ billing इस्तेमाल करने वाली app को mediation repositories, AdMob IDs या Firebase नहीं चाहिए।

```groovy
// app/build.gradle
def sdkVersion = providers.gradleProperty('adlogicSdkVersion').get()
dependencies {
    implementation "com.github.truongvimit.adlogic-partner-sdk:billingkit:$sdkVersion"
}
```

उदाहरण `AppCompatActivity`, `lifecycleScope` और `repeatOnLifecycle` इस्तेमाल करते हैं; आपकी app को AndroidX AppCompat और `androidx.lifecycle:lifecycle-runtime-ktx` चाहिए, जैसा [BillingKit build setup](../billingkit/README.md#install) में है। SDK runtime पर BillingClient के साथ Trackkit और coroutines APIs पहले ही देता है।

अपने product IDs और base plans/offers Play Console में बनाएँ। उदाहरण के IDs **साझा test products नहीं हैं**। Test करते समय अपनी app का package name और license tester account इस्तेमाल करें, और जाँचें कि dialog में test payment method मिलता है; internal test track खुद से license tester अधिकार नहीं देता। [Google: Billing test करें](https://developer.android.com/google/play/billing/test)।

## 2. Catalogue और Application

`BillingProducts.kt` copy करें, `com.example.app` और IDs/plans/offers बदलें; सिर्फ वही products रखें जो आपकी app बेचती है। इन constants को अपने purchase buttons पर इस्तेमाल करें।

इसे अपनी मौजूदा Application में मिलाएँ; Application अभी न हो तो नीचे की class बनाएँ और manifest में `android:name` घोषित करें:

```kotlin
package com.example.app

import android.app.Application
import com.ads.module.billing.AppPurchase
import com.ads.module.billing.Billing
import com.ads.module.billing.BillingKit

class BillingApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Analytics इस्तेमाल कर रहे हैं? इस billing block से पहले Tracker और उसके sinks install करें.
        BillingKit.setDevMode(false)
        AppPurchase.getInstance().initBilling(this, BillingProducts.items)
        Billing.install(this)
    }
}
```

`setDevMode(false)` रखें ताकि debug builds में भी Play इस्तेमाल हो; यह line हटाने पर billing ads का dev flag अपना सकता है और transactions simulate कर सकता है। Application में एक बार initialize करें; हर screen पर connection open/close न करें।

## 3. परिणाम और premium state देखें

नीचे दिया function अपनी purchase screen के **`onCreate` में एक बार** बुलाएँ: `onPremium` access अपडेट करता है, `onPurchase` transaction state अपडेट करता है।

```kotlin
package com.example.app

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ads.module.billing.Billing
import com.ads.module.billing.PurchaseEvent
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

fun AppCompatActivity.observeBilling(
    onPremium: (Boolean) -> Unit,
    onPurchase: (PurchaseEvent) -> Unit,
) {
    lifecycleScope.launch {
        // Play जब app को ढकता है तब collector ज़िंदा रखें; यह flow replay नहीं होता.
        Billing.purchaseEvents.collect { onPurchase(it) }
    }
    lifecycleScope.launch {
        repeatOnLifecycle(Lifecycle.State.STARTED) {
            Billing.isPremium.collect { onPremium(it) }
        }
    }
}
```

| परिणाम | आपकी app क्या करे |
| --- | --- |
| `Purchased`, `AlreadyOwned` | संदेश अपडेट करें; premium access `Billing.isPremium` से लें। |
| `Pending` | Payment pending दिखाएँ; purchase अभी न दें। |
| `Canceled` | Loading बंद करें और उपयोगकर्ता को दोबारा कार्य करने दें। |
| `Error(code, message)` | Error/retry दिखाएँ; premium खुद कभी चालू न करें। |

`purchaseEvents` पुराने events replay नहीं करता। UI access `Billing.isPremium` से बहाल करें (startup पर cache, Play जाँच के बाद अपडेट)। Consumables में वस्तु आपकी app को खुद देनी होती है; हर `Purchased` को premium न मानें।

## 4. Prices और purchase button

`Billing.awaitReady()` connection और purchase verification का इंतज़ार करता है; prices अलग load होते हैं। Sample price के लिए अधिकतम 5 सेकंड इंतज़ार करता है ताकि UI retry कर सके; यह timeout sample का है।

```kotlin
package com.example.app

import com.ads.module.billing.AppPurchase
import com.ads.module.billing.Billing
import com.ads.module.billing.ReadyResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

suspend fun lifetimePriceOrNull(): String? {
    if (Billing.awaitReady() != ReadyResult.Ready) return null
    val purchase = AppPurchase.getInstance()
    purchase.refreshProductDetails()
    return withTimeoutOrNull(5_000) {
        while (purchase.getPrice(BillingProducts.LIFETIME).isNullOrBlank()) delay(200)
        purchase.getPrice(BillingProducts.LIFETIME)
    }
}
```

इसे `lifecycleScope.launch` के अंदर बुलाएँ: इंतज़ार के दौरान purchase button disable रखें, Play से आया price दिखाएँ और price मिलते ही button enable करें; `null` पर retry करने दें। Prices या currency hardcode न करें।

Activity के purchase click से नीचे दिए दो में से एक function बुलाएँ और `LaunchResult` संभालें:

```kotlin
package com.example.app

import android.app.Activity
import com.ads.module.billing.AppPurchase
import com.ads.module.billing.LaunchResult

fun Activity.buyLifetime(): LaunchResult =
    AppPurchase.getInstance().purchaseProduct(this, BillingProducts.LIFETIME)

fun Activity.buyYearly(): LaunchResult {
    val purchase = AppPurchase.getInstance()
    val token = purchase.resolveOfferToken(
        BillingProducts.YEARLY, BillingProducts.YEARLY_BASE_PLAN, BillingProducts.YEARLY_OFFER,
    )
    if (token.isBlank()) return LaunchResult.NO_OFFER
    return purchase.subscribeProduct(this, BillingProducts.YEARLY, token)
}
```

`LAUNCHED` का मतलब सिर्फ इतना है कि Play UI खुल गई; event और premium state का इंतज़ार करें। कोई भी दूसरा परिणाम loading state खत्म करे, ताकि error/retry संभाला जा सके; `DEV_MODE` simulation है।

Subscriptions: `getPriceSub(productId, token)` चुने गए offer की **पहली phase** का price देता है; `getPriceSub(productId)` Play data के आखिरी offer का renewal price देता है, token से चुने बिना। Sample हर product के लिए एक plan इस्तेमाल करता है; कई plans/offers हों तो जाँचें कि दिखने वाला price, billing period और trial खरीदे जा रहे offer से मेल खाते हैं। Resolver में fallback है; उदाहरण के ID नामों से trial न मानें।

**Restore** button coroutine में `Billing.restore()` बुलाता है। `Restored(productIds)`, `NothingToRestore` और `Error(code, message)` संभालें; SDK `isPremium` अपडेट करता है। हर restore से पहले billing दोबारा initialize करने की ज़रूरत नहीं।

## 5. Configuration table और वैकल्पिक integrations

| Configuration | Default / कब ज़रूरत है |
| --- | --- |
| `BillingKit.setDevMode` | Unset हो तो ads dev flag मौजूद होने पर वही अपनाता है, वरना `false`। Sample स्पष्ट रूप से `false` सेट करता है; `true` सिर्फ simulated UI आज़माने के लिए। |
| `PurchaseItem.type` | `PURCHASE`, `SUBSCRIPTION` या `CONSUMABLE` स्पष्ट रूप से चुनें; Play पर आपके लिए कोई catalogue नहीं बनता। |
| Base plan / offer | असली coordinates सेट करें। `offerId = ""` से SDK plan के अंदर का offer चुनता है और **बिना offer वाले base plan की purchase ज़बरदस्ती नहीं कराता**। मेल न खाने पर resolver दूसरा offer चुन सकता है। |
| `Billing.awaitReady(timeoutMs)` | 5 सेकंड। Splash timeout/error पर app में सहेजे premium state के साथ आगे बढ़ें; purchase screen पर अभी price न हो तो retry करने दें। |
| `Billing.restore()` | अंदरूनी 10 सेकंड का timeout, कोई public timeout parameter नहीं। |
| अपने backend से receipt verification | `AppPurchase.getInstance().setPurchaseVerifier(...)`; unset हो तो आपकी अपनी कोई server जाँच नहीं होती। Hook सिर्फ billing flow की purchases पर लागू होता है, **startup/restore sweep पर नहीं**। [PurchaseVerifier](../billingkit/src/main/java/com/ads/module/billing/PurchaseVerifier.java) देखें। |
| आपकी app का account/profile | खाता जोड़ना हो तो launch से पहले `AppPurchase.getInstance().setObfuscatedAccountId(...)` / `setObfuscatedProfileId(...)`। |
| Analytics / Adjust | Billing से पहले [Trackkit](trackkit-integration.hi.md); Adjust [ads/Adjust wiring](ads-onboarding-integration.hi.md#adjust-token-और-verification) इस्तेमाल करता है और duplicate purchase events नहीं भेजता। |

### जब आपकी app में ads/onboarding हो

मानक flow वही premium state इस्तेमाल करता है जो BillingKit app में सहेजता है। `Billing.install()` उस source को ad gate से जोड़ता है; PayKit भी यही कदम करता है। अपनी मौजूदा `ObSplashActivity` में जोड़ें:

```kotlin
override suspend fun onInitBilling() {
    com.ads.module.billing.Billing.awaitReady()
}
```

**Splash timeout/error पर app में सहेजे premium state के साथ onboarding जारी रखें:** premium हो तो ads छूटते हैं, premium न हो तो ads config के अनुसार चलता है। कोई waiting screen न जोड़ें, premium reset न करें और premium को खुद `false` न करें। Play से verification सफल आने पर BillingKit cache, `Billing.isPremium` और ad gate अपडेट करता है; verification fail होने पर पिछला value बना रहता है।

सफल purchase पर preload किए गए ads छोड़ने के लिए `AdGate.installPremiumObserver(appScope, Billing.isPremium)` एक बार ऐसे scope के साथ बुलाएँ जो Application जितना जीता है।

**सिर्फ तब जब आपकी app premium अपने source से संभालती है:** BillingKit आपकी app का cache/repository नहीं पढ़ता। `Billing.install()` या `PayKit.install()` के बाद `com.ads.module.helper.Entitlement.install(source)` install करें; `source`, `EntitlementSource.isPremium(context)` लागू करता है और आपकी app के मौजूदा source से पहले ही load किया गया premium value पढ़ता है। purchase/restore/backend के परिणाम मिलते रहने पर आपकी app उस source को sync रखती है। यह configuration सिर्फ ads रोकने वाला source बदलता है; `Billing.isPremium` या PayKit का premium state नहीं बदलता। सिर्फ इसलिए `setPurchase(false)` न बुलाएँ कि Play ने अभी जवाब नहीं दिया।

## 6. जाँच

- [ ] Catalogue Play से मेल खाता है; purchase button enable होने से पहले असली price मौजूद है।
- [ ] success, cancel, error और pending test करें; `LAUNCHED` खुद से premium unlock नहीं करता।
- [ ] App दोबारा खोलने/restore पर सही entitlement मिलता है; consumables premium नहीं देते।
- [ ] Splash timeout: premium cache पर ads फिर भी छूटते हैं; premium न होने वाला cache ads config के अनुसार चलता है। Purchase/restore, UI और ad gate जिस premium source को इस्तेमाल करता है, दोनों अपडेट करता है।
- [ ] Play transactions license tester और `setDevMode(false)` इस्तेमाल करते हैं, इन्हें local simulation से न मिलाएँ।

Price खाली: catalogue, test account और Play connection जाँचें। गलत offer: product का असली plan/offer जाँचें।
