# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# --- Production Logging Configuration ---
# Strips verbose and debug log statements in production release builds for privacy and performance.
# Debug builds (isMinifyEnabled = false) ignore this rule and retain all logs.
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}

# --- Preserve Generic Signatures & Gson Reflections ---
-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.google.gson.** { *; }
-keepclassmembers class * extends com.google.gson.reflect.TypeToken { *; }
-keep class com.google.gson.reflect.TypeToken { *; }

# --- Keep All App Classes & Data Models ---
-keep class com.whisk.hackexlogger.** { *; }
-keepclassmembers class com.whisk.hackexlogger.** { *; }

# --- Keep Services & Accessibility Components ---
-keep class com.whisk.hackexlogger.ScraperAccessibilityService { *; }
-keep class com.whisk.hackexlogger.OverlayService { *; }

# --- Google Identity & Credential Manager Keep Rules ---
-keep class com.google.android.libraries.identity.googleid.** { *; }
-keep class androidx.credentials.** { *; }
-dontwarn androidx.credentials.**
-dontwarn com.google.android.libraries.identity.googleid.**
