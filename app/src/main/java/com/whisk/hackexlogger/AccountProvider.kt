package com.whisk.hackexlogger

import android.app.Activity
import android.content.Context

/**
 * Interface defining the Google Account session management contract.
 */
interface AccountProvider {
    val isGoogleAuthSupported: Boolean
    fun init(context: Context)
    fun getCurrentUser(): GoogleAccountUser?
    fun isSignedIn(): Boolean
    suspend fun signOut(context: Context)
    fun isCloudSyncEnabled(context: Context): Boolean
    fun setCloudSyncEnabled(context: Context, enabled: Boolean)
    fun getLastSyncTime(context: Context): Long
    fun setLastSyncTime(context: Context, timestamp: Long)
    suspend fun signInWithGoogle(activity: Activity): Result<GoogleAccountUser>
}
