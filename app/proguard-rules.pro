# App Downloader release shrinking rules.
#
# The app's own code is kept unchanged: it is small, and keeping it avoids surprises with
# serialization, the install-result receiver and WorkManager workers on devices we can't test here.
# Libraries are shrunk using the rules they publish.
-keep class io.github.edrock200.appdownloader.** { *; }

# kotlinx.serialization: keep generated serializers and companion lookups.
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class ** { kotlinx.serialization.KSerializer serializer(...); }

# OkHttp's optional TLS providers aren't used on Android.
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# Conservative until verified on real devices: remove unused code but don't rename anything, and keep
# libraries that find classes by name at runtime whole (WorkManager and its Room database, App
# Startup, ML Kit / Play services scanner). A broken release would also break self-update.
-dontobfuscate
-keep class androidx.work.** { *; }
-keep class androidx.room.** { *; }
-keep class androidx.startup.** { *; }
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.** { *; }
-keep class com.google.android.odml.** { *; }
