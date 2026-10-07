# Retrofit / OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keepattributes Signature
-keepattributes *Annotation*

# kotlinx.serialization
-keepclassmembers class dev.immichtv.api.** {
    *** Companion;
}
-keepclasseswithmembers class dev.immichtv.api.** {
    kotlinx.serialization.KSerializer serializer(...);
}
