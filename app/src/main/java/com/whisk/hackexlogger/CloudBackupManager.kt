package com.whisk.hackexlogger

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CloudBackupManager manages serialization, backup state, and cloud synchronization triggers.
 * Operates purely on user-controlled JSON data bundles with safety guards against data loss.
 */
object CloudBackupManager {

    private const val TAG = "CloudBackupManager"
    private const val AUTO_SYNC_DELAY_MS = 300_000L // 5 minutes
    private val gson = Gson()

    private var autoSyncJob: Job? = null
    private val syncScope = CoroutineScope(Dispatchers.IO)

    /**
     * Schedules a debounced auto-sync backup. Waits 5 minutes after the last database modification
     * before executing a single batch backup to prevent rapid API calls or CPU thrashing.
     */
    fun scheduleDebouncedAutoSync(context: Context) {
        autoSyncJob?.cancel()
        autoSyncJob = syncScope.launch {
            delay(AUTO_SYNC_DELAY_MS)
            try {
                performBackup(context)
                Log.d(TAG, "5-minute debounced auto-sync backup executed successfully.")
            } catch (e: Exception) {
                Log.e(TAG, "Debounced auto-sync failed: ${e.message}", e)
            }
        }
    }

    /**
     * Instantly flushes any pending cloud backup (e.g. when app is backgrounded, closed, or user logs in).
     */
    fun flushPendingBackup(context: Context) {
        autoSyncJob?.cancel()
        autoSyncJob = null
        syncScope.launch {
            try {
                if (AccountManager.isCloudSyncEnabled(context)) {
                    performBackup(context)
                    Log.d(TAG, "Flushed pending cloud backup on lifecycle event.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Flush pending backup failed: ${e.message}", e)
            }
        }
    }

    /**
     * Performs a backup of the current local database to account persistent storage.
     * Prevents wiping an existing cloud backup if the current local database is empty.
     * Returns the count of target records currently stored in the cloud backup.
     */
    fun performBackup(context: Context, forceEmpty: Boolean = false): Int {
        val records = DatabaseManager.getAllRecords()
        val existingSavedCount = getSavedBackupTargetCount(context)

        // Safety Guard: Do not automatically overwrite a non-empty cloud backup with an empty (0 records) local database
        if (records.isEmpty() && existingSavedCount > 0 && !forceEmpty) {
            Log.w(TAG, "Local database is empty. Refusing to overwrite existing cloud backup ($existingSavedCount records).")
            return existingSavedCount
        }

        val bundleJson = DatabaseManager.exportDataBundle()
        AccountManager.saveCloudBackup(context, bundleJson)
        Log.d(TAG, "Successfully saved backup bundle (${bundleJson.length} chars, ${records.size} records) to account storage.")
        return records.size
    }

    /**
     * Retrieves the count of target records currently stored in the account cloud backup.
     */
    fun getSavedBackupTargetCount(context: Context): Int {
        val backupJson = AccountManager.getCloudBackup(context) ?: return 0
        return try {
            val bundle = gson.fromJson(backupJson, ConsoleBundle::class.java)
            bundle?.targets?.size ?: 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse saved backup target count: ${e.message}", e)
            0
        }
    }

    /**
     * Performs a restore from saved account backup JSON into the local database.
     */
    fun performRestore(context: Context): ImportResult {
        val backupJson = AccountManager.getCloudBackup(context)
        Log.d(TAG, "performRestore called. Account backup exists: ${!backupJson.isNullOrEmpty()}")

        if (backupJson.isNullOrEmpty()) {
            throw IllegalStateException("No account cloud backup found to restore.")
        }

        val result = DatabaseManager.importDataBundle(backupJson)
        Log.d(TAG, "Successfully restored ${result.targetCount} targets and ${result.ingestCount} ingests into local database.")
        return result
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
}
