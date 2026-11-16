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

# ---------------------------------------------------------------------------
# Telegram Drive backend
# ---------------------------------------------------------------------------
# TDLib calls back into Java from native code by name. Renaming or stripping any
# of org.drinkless.tdlib breaks the client silently at runtime rather than at
# build time, so the whole package is kept along with its native method bindings.
-keep class org.drinkless.tdlib.** { *; }
-keepclassmembers class org.drinkless.tdlib.** {
    native <methods>;
    <init>(...);
    <fields>;
}
-dontwarn org.drinkless.tdlib.**

# kotlinx.serialization writes the caption manifest. The generated serializer is
# only reachable reflectively, so both it and the data class must survive.
-keepattributes *Annotation*, InnerClasses, Signature
-keepclassmembers class com.example.data.sync.DriveManifest {
    *** Companion;
}
-keepclasseswithmembers class com.example.data.sync.DriveManifest {
    kotlinx.serialization.KSerializer serializer(...);
}

# WorkManager instantiates workers by class name.
-keep class com.example.work.** { *; }

# The app's own code is small; keeping all of it avoids breaking reflection-based
# pieces (kotlinx.serialization, Room, navigation arguments) while R8 still removes
# the unused parts of the big libraries.
-keep class com.example.** { *; }
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

# ML Kit / Play Services: their components are created by name from the manifest
# (ComponentRegistrar). R8 removed them, which crashed the document scanner with a
# NullPointerException in GmsDocumentScanning.getClient().
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-keep class com.google.android.gms.common.** { *; }
-keep class com.google.android.gms.tasks.** { *; }
-keep class com.google.firebase.components.** { *; }
-keep class * implements com.google.firebase.components.ComponentRegistrar { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**

# Keep readable class names so release crash logs can be understood.
# Unused code is still removed, so the size gain is kept.
-dontobfuscate
