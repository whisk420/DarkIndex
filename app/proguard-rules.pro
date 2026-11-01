# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# --- Production Logging Configuration ---
# Removes Log calls in production release builds
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int i(...);
    public static int w(...);
    public static int d(...);
    public static int e(...);
}

# --- Gson Serialization Keep Rules ---
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.google.gson.** { *; }

# Preserve HackEx Logger Data Models from R8 Obfuscation
-keep class com.whisk.hackexlogger.TargetRecord { *; }
-keep class com.whisk.hackexlogger.SoftwareEntry { *; }
-keep class com.whisk.hackexlogger.TheftEvent { *; }
-keep class com.whisk.hackexlogger.ConsoleBundle { *; }
-keep class com.whisk.hackexlogger.ConsoleIngest { *; }
-keep class com.whisk.hackexlogger.ImportResult { *; }
-keep class com.whisk.hackexlogger.GoogleAccountUser { *; }

# --- Google Identity & Credential Manager Keep Rules ---
-keep class com.google.android.libraries.identity.googleid.** { *; }
-keep class androidx.credentials.** { *; }
-dontwarn androidx.credentials.**
-dontwarn com.google.android.libraries.identity.googleid.**
