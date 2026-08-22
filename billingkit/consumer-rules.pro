# Public API: partner code and Java hosts call the engine by name across module boundaries.
-keep class com.ads.module.billing.** { public protected *; }
-keep interface com.ads.module.funtion.PurchaseListener { *; }
-keep interface com.ads.module.funtion.BillingListener { *; }
-keep interface com.ads.module.funtion.UpdatePurchaseListener { *; }
-keep class com.ads.module.funtion.PurchaseCallback { public protected *; }

# :ads is compileOnly, so on a host that ships billing without ads these references dangle and
# R8 would otherwise fail on the missing classes.
-dontwarn com.ads.module.helper.Entitlement
-dontwarn com.ads.module.helper.EntitlementSource
-dontwarn com.ads.module.util.AppUtil
