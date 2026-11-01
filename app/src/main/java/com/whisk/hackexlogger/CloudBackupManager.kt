package com.whisk.hackexlogger

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CloudBackupManager manages serialization, backup state, and cloud synchronization triggers.
 * Operates purely on user-controlled JSON data bundles.
 */
object CloudBackupManager {

    /**
     * Prepares a JSON backup bundle using DatabaseManager's export logic.
     */
    fun createBackupBundle(): String {
        return DatabaseManager.exportDataBundle()
    }

    /**
     * Restores data from a JSON backup bundle.
     */
    fun restoreFromBundle(jsonString: String): ImportResult {
        return DatabaseManager.importDataBundle(jsonString)
    }

    /**
     * Returns a human-readable formatted timestamp of the last backup or "Never".
     */
    fun getFormattedLastSyncTime(context: Context): String {
        val lastSync = AccountManager.getLastSyncTime(context)
        if (lastSync <= 0L) return "Never"
        val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
        return sdf.format(Date(lastSync))
    }

    /**
     * Record a successful cloud backup event.
     */
    fun recordSync(context: Context) {
        AccountManager.setLastSyncTime(context, System.currentTimeMillis())
    }
}
