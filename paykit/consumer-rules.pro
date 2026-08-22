# kotlinx.serialization — keep generated serializers for PayKit DTOs
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class io.paykit.** {
    *** Companion;
}
-keepclasseswithmembers class io.paykit.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class io.paykit.**$$serializer { *; }

# Public API: partner code and Java hosts call these by name, so R8 cannot see the call sites.
-keep class io.paykit.PayKit { public *; }
-keep class io.paykit.PayKitConfig { public *; }
-keep class io.paykit.PayKitConfigBuilder { public *; }
-keep class io.paykit.PayKitConfigKt { public *; }
-keep class io.paykit.PayKitLogLevel { *; }
-keep class io.paykit.PayKitState { *; }
-keep class io.paykit.PayKitState$* { *; }
-keep class io.paykit.PaywallPlacement { *; }
-keep class io.paykit.PaywallPlacement$* { *; }
-keep class io.paykit.PaywallResult { *; }
-keep class io.paykit.PaywallResult$* { *; }
-keep class io.paykit.PaywallContract { public *; }

# open class, not an interface: hosts subclass it and R8 must keep the overridable members.
-keep class io.paykit.PaywallListener { public protected *; }

-keep interface io.paykit.config.PaywallConfigSource { *; }
-keep class io.paykit.config.StaticConfigSource { public *; }
-keep class io.paykit.config.RawResourceConfigSource { public *; }

-keep class io.paykit.model.** { public *; }
-keep class io.paykit.design.PaywallTheme { public *; }
-keep class io.paykit.design.PaywallTheme$* { public *; }
-keep class io.paykit.design.TokenResolver { public *; }

-keep interface io.paykit.ui.PaywallRenderer { *; }
-keep interface io.paykit.ui.PaywallActions { *; }
-keep class io.paykit.ui.PaywallUiState { *; }
-keep class io.paykit.ui.PaywallUiState$* { *; }
-keep class io.paykit.ui.PaywallAction { *; }
-keep class io.paykit.ui.PaywallAction$* { *; }
-keep class io.paykit.ui.DefaultPaywallRenderer { public *; }

# :ads is compileOnly, so on a paywall-without-ads host BillingBridge's app-resume reference
# dangles and R8 would otherwise fail on the missing class.
-dontwarn com.ads.module.admob.AppOpenManager

# :onboardkitorigin is compileOnly, so on a host that ships the paywall without onboarding these
# references dangle and an unconditional -keep would fail R8 on the missing superinterface.
-dontwarn io.onboardkit.**
-if class io.onboardkit.paywall.PaywallGate
-keep class io.paykit.integration.** { *; }
