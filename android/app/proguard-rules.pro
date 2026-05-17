# Memory Keeper - ProGuard rules

# Keep kotlinx.serialization metadata
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keep,includedescriptorclasses class com.everydayecho.memorykeeper.**$$serializer { *; }
-keepclassmembers class com.everydayecho.memorykeeper.** {
    *** Companion;
}
-keepclasseswithmembers class com.everydayecho.memorykeeper.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp / Okio
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Coroutines
-keepclassmembers class kotlinx.coroutines.** { *; }
