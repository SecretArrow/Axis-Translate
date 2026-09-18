# Axis Translate R8 / ProGuard rules

# ---- llama.cpp JNI bridge ----
# Keep the class name and native method names so JNI symbol resolution works.
-keepclasseswithmembernames class com.axis.translate.inference.** { *; }

# ---- kotlinx.serialization ----
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.axis.translate.**$$serializer { *; }
-keepclassmembers class com.axis.translate.** { *** Companion; }
-keepclasseswithmembers class com.axis.translate.** { kotlinx.serialization.KSerializer serializer(...); }

# ---- Room ----
-keep class androidx.room.RoomDatabase { *; }
-dontwarn androidx.room.paging.**

# ---- ML Kit ----
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# ---- OkHttp ----
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**

# ---- Coroutines ----
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# ---- CameraX ----
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# ---- Remove debug logs from release ----
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
