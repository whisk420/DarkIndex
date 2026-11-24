# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# --- Production Logging Configuration ---
# Strips verbose, debug and info log statements from release builds for privacy and performance.
# Only warnings and errors (no screen text) remain. Debug builds (isMinifyEnabled = false) keep all logs.
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
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
