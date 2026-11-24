# Keep the public API surface of our own model classes used by kotlinx.serialization.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class com.aisocial.copilot.** {
    *** Companion;
}
-keepclasseswithmembers class com.aisocial.copilot.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.aisocial.copilot.**$$serializer { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
