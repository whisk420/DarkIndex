package com.whisk.hackexlogger

import android.app.Activity
import android.content.Context

/**
 * AccountProvider implementation for the standalone (offline) build flavor.
 * Google Auth and internet-based features are disabled by design.
 */
object FlavorAccountProvider : AccountProvider {

    override val isGoogleAuthSupported: Boolean = false

    override fun init(context: Context) {
        // No-op in standalone build
    }

    override fun getCurrentUser(): GoogleAccountUser? = null

    override fun isSignedIn(): Boolean = false

    override suspend fun signOut(context: Context) {
        // No-op in standalone build
    }

    override fun isCloudSyncEnabled(context: Context): Boolean = false

    override fun setCloudSyncEnabled(context: Context, enabled: Boolean) {
        // No-op in standalone build
    }

    override fun getLastSyncTime(context: Context): Long = 0L

    override fun setLastSyncTime(context: Context, timestamp: Long) {
        // No-op in standalone build
    }

    override suspend fun signInWithGoogle(activity: Activity): Result<GoogleAccountUser> {
        return Result.failure(IllegalStateException("Google Auth is not supported in the standalone offline build."))
    }
}
