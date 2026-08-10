# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# =====================
# Moshi
# =====================
-dontwarn com.squareup.moshi.**
-keep class com.squareup.moshi.** { *; }

# Keep classes annotated with @JsonClass
-keepnames @com.squareup.moshi.JsonClass class *

# Keep generated JsonAdapter classes
-keep class **JsonAdapter {
    <init>(...);
}

# Keep custom JsonAdapter.Factory implementations
-keep class * implements com.squareup.moshi.JsonAdapter$Factory {
    <init>(...);
}

# Keep methods annotated with @FromJson and @ToJson
-keepclassmembers class * {
    @com.squareup.moshi.FromJson <methods>;
    @com.squareup.moshi.ToJson <methods>;
}

# Keep JsonQualifier interfaces
-keep @com.squareup.moshi.JsonQualifier interface *

# Kotlin metadata - required for reflection-based adapters
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes Exceptions
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# Keep parameter names for Kotlin reflection (KotlinJsonAdapterFactory)
-keepattributes RuntimeVisibleParameterAnnotations
-keepclassmembers class kotlin.Metadata {
    public <methods>;
}

# Keep classes that use reflection-based adapters (KotlinJsonAdapterFactory)
# AdRemoteConfig uses KotlinJsonAdapterFactory, so we need to keep it
-keep class com.itg.template.ads.AdRemoteConfig { *; }
-keep class com.itg.template.ads.AdRemoteConfig$Companion { *; }

# =====================
# Other libs
# =====================
-keep class com.skydoves.sandwich.** { *; }

-keepclassmembers class androidx.lifecycle.ViewModel { *; }
-keepnames class androidx.lifecycle.LiveData { *; }

-keep class com.itg.template.ads.AdUnitConfig
-keep class com.itg.template.data.model.ForceUpdateConfig
