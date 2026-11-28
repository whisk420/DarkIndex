package com.whisk.hackexlogger

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * The overlay + accessibility permission walkthrough, including the Android 13+ "Allow restricted settings" steps
 * for sideloaded apps. Moved out of MainActivity (2026-11-28) without changing behaviour.
 */
class PermissionOnboarding(private val activity: AppCompatActivity) {

    /** Set when we sent the user to a settings screen, so the activity re-checks (and re-prompts) on return. */
    var expectingResult = false

    /** Both permissions granted, so the 📸 overlay can run. */
    fun hasAll(): Boolean = Settings.canDrawOverlays(activity) && isAccessibilityServiceEnabled()

    /**
     * Starts the overlay if both permissions are granted; otherwise explains the next step. [force] shows the dialog
     * even if the user chose "Don't Ask Again" (used when they tap a button that needs the permissions).
     */
    fun showDialog(force: Boolean) {
        val hasOverlay = Settings.canDrawOverlays(activity)
        val hasAccessibility = isAccessibilityServiceEnabled()

        if (hasOverlay && hasAccessibility) {
            activity.startService(Intent(activity, OverlayService::class.java))
            return
        }

        val prefs = activity.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val hideWarning = prefs.getBoolean("hide_permission_warning", false)
        if (!force && hideWarning) return

        val packageName = activity.packageName
        val builder = AlertDialog.Builder(activity)

        if (!hasOverlay) {
            builder.setTitle("Step 1: Overlay Permission")
            if (Build.VERSION.SDK_INT >= 33) {
                // Android blocks sideloaded apps from this permission too, and only offers "Allow restricted
                // settings" after it has blocked you once (developer, Android 17).
                builder.setMessage("To use the on-screen scraping overlay, DarkIndex requires the 'Display over other apps' permission.\n\nIf the toggle is greyed out:\n\n1. Tap 'Grant Overlay' and tap the greyed-out toggle anyway. Android shows 'App was denied access'. Close it and come back.\n\n2. Tap 'App Info', tap the 3 dots (⋮) in the top-right corner and select 'Allow restricted settings'. (It only appears after step 1.) Come back.\n\n3. Tap 'Grant Overlay' again and turn it on.")
                builder.setNeutralButton("App Info") { _, _ ->
                    expectingResult = true
                    activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    })
                }
            } else {
                builder.setMessage("To use the on-screen scraping overlay, DarkIndex requires the 'Display over other apps' permission.")
            }
            builder.setPositiveButton("Grant Overlay") { _, _ ->
                expectingResult = true
                activity.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
            if (!force && Build.VERSION.SDK_INT < 33) {
                builder.setNeutralButton("Don't Ask Again") { _, _ ->
                    prefs.edit().putBoolean("hide_permission_warning", true).apply()
                }
            }
        } else if (!hasAccessibility) {
            if (Build.VERSION.SDK_INT >= 33) {
                builder.setTitle("Step 2: Android 13+ Security Unlock")
                builder.setMessage("Android blocks sideloaded accessibility apps by default. If you already allowed restricted settings for the overlay, just tap '1. Accessibility' and turn DarkIndex on. Otherwise follow these exact steps:\n\n1. Tap '1. Accessibility' below, find DarkIndex, and try to turn it on. Android will block you with a 'Restricted setting' popup. Hit OK and come back.\n\n2. Tap '2. App Info' below, tap the 3 dots (⋮) in the top-right corner, and select 'Allow restricted settings'. Come back.\n\n3. Tap '1. Accessibility' one last time to successfully turn it on.")
                builder.setPositiveButton("1. Accessibility") { _, _ ->
                    expectingResult = true
                    activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                builder.setNeutralButton("2. App Info") { _, _ ->
                    expectingResult = true
                    activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    })
                }
            } else {
                builder.setTitle("Step 2: Accessibility Permission")
                builder.setMessage("DarkIndex requires the 'Accessibility' permission to read the game screen.")
                builder.setPositiveButton("Grant Accessibility") { _, _ ->
                    expectingResult = true
                    activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                if (!force) {
                    builder.setNeutralButton("Don't Ask Again") { _, _ ->
                        prefs.edit().putBoolean("hide_permission_warning", true).apply()
                    }
                }
            }
        }

        builder.setNegativeButton(if (force) "Cancel" else "Not Now", null)
        builder.show()
    }

    /** True if DarkIndex's [ScraperAccessibilityService] is turned on in Android's accessibility settings. */
    fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponentName = ComponentName(activity, ScraperAccessibilityService::class.java)
        val enabledServicesSetting = Settings.Secure.getString(
            activity.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServicesSetting)
        while (splitter.hasNext()) {
            val enabledComponent = ComponentName.unflattenFromString(splitter.next())
            if (enabledComponent != null && enabledComponent == expectedComponentName) return true
        }
        return false
    }
}
