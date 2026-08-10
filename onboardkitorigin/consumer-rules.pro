# kotlinx.serialization — keep generated serializers for OnboardKit DTOs
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class io.onboardkit.** {
    *** Companion;
}
-keepclasseswithmembers class io.onboardkit.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class io.onboardkit.**$$serializer { *; }
