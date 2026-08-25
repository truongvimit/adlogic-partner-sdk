# Rules a minifying partner build needs. Kept deliberately narrow: the blanket androidx keeps in
# proguard-rules.pro would defeat shrinking in every consumer app.

# Public API: partner code and Java hosts call the engine by name across module boundaries.
-keep class com.ads.module.ads.** { public protected *; }
-keep class com.ads.module.helper.** { public protected *; }
-keep class com.ads.module.config.** { public protected *; }
-keep class com.ads.module.consent.** { public protected *; }
-keep class com.ads.module.application.AdsMultiDexApplication { public protected *; }

# Adjust reads these by reflection through Google Play Services.
-keep class com.adjust.sdk.** { *; }
-keep class com.google.android.gms.common.ConnectionResult {
    int SUCCESS;
}
-keep class com.google.android.gms.ads.identifier.AdvertisingIdClient {
    com.google.android.gms.ads.identifier.AdvertisingIdClient$Info getAdvertisingIdInfo(android.content.Context);
}
-keep class com.google.android.gms.ads.identifier.AdvertisingIdClient$Info {
    java.lang.String getId();
    boolean isLimitAdTrackingEnabled();
}
-keep public class com.android.installreferrer.** { *; }

# Mediation adapters resolve their networks reflectively; without these the adapter no-ops at runtime.
-keep class com.bytedance.sdk.openadsdk.** { *; }
-keep class com.mbridge.** { *; }
-keep interface com.mbridge.** { *; }
-dontwarn com.mbridge.**
-keep class **.R$* { public static final int mbridge*; }
-keepattributes Signature
-keepattributes *Annotation*
