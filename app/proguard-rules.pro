# Retrofit / OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keepattributes Signature
-keepattributes *Annotation*

# kotlinx.serialization
-keepclassmembers class com.zch.immich.tv.api.** {
    *** Companion;
}
-keepclasseswithmembers class com.zch.immich.tv.api.** {
    kotlinx.serialization.KSerializer serializer(...);
}
