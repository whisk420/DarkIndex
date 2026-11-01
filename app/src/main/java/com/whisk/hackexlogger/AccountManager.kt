package com.whisk.hackexlogger

import android.app.Activity
import android.content.Context

/**
 * Main AccountManager delegating calls to the flavor-specific FlavorAccountProvider.
 */
object AccountManager : AccountProvider {
    private val delegate: AccountProvider = FlavorAccountProvider

    override val isGoogleAuthSupported: Boolean
        get() = delegate.isGoogleAuthSupported

    override fun init(context: Context) = delegate.init(context)

    override fun getCurrentUser(): GoogleAccountUser? = delegate.getCurrentUser()

    override fun isSignedIn(): Boolean = delegate.isSignedIn()

    override suspend fun signOut(context: Context) = delegate.signOut(context)

    override fun isCloudSyncEnabled(context: Context): Boolean = delegate.isCloudSyncEnabled(context)

    override fun setCloudSyncEnabled(context: Context, enabled: Boolean) = delegate.setCloudSyncEnabled(context, enabled)

    override fun getLastSyncTime(context: Context): Long = delegate.getLastSyncTime(context)

    override fun setLastSyncTime(context: Context, timestamp: Long) = delegate.setLastSyncTime(context, timestamp)

    override suspend fun signInWithGoogle(activity: Activity): Result<GoogleAccountUser> = delegate.signInWithGoogle(activity)
}
