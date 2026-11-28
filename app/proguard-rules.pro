# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.fengkao.timetable.**$$serializer { *; }
-keepclassmembers class com.fengkao.timetable.** { *** Companion; }
-keepclasseswithmembers class com.fengkao.timetable.** { kotlinx.serialization.KSerializer serializer(...); }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**


# Retrofit / Gson model retention
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keep,allowobfuscation interface com.fengkao.timetable.data.** { *; }
-keepclassmembers,allowobfuscation class * {
    @retrofit2.http.* <methods>;
}
-keep class com.fengkao.timetable.data.** { *; }
-keepclassmembers class com.fengkao.timetable.data.** { *; }
-dontwarn retrofit2.**
-dontwarn okhttp3.**
-dontwarn javax.annotation.**
