# ImpulsoTV v3 ProGuard Rules

# Mantém todas as classes do app
-keep class com.impulsolab.impulsotv.** { *; }

# Gson — mantém classes de modelo para serialização/desserialização JSON
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Media3 / ExoPlayer
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# Coil
-keep class coil.** { *; }
-dontwarn coil.**

# Coroutines
-dontwarn kotlinx.coroutines.**
