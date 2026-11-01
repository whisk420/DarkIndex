package com.whisk.hackexlogger

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.CustomCredential
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AccountProvider implementation for the googlePlay build flavor.
 * Handles Google Account sign-in via Credential Manager and local session persistence.
 */
object FlavorAccountProvider : AccountProvider {

    override val isGoogleAuthSupported: Boolean = true

    private const val PREFS_NAME = "AccountPrefs"
    private const val KEY_DISPLAY_NAME = "google_display_name"
    private const val KEY_EMAIL = "google_email"
    private const val KEY_PHOTO_URL = "google_photo_url"
    private const val KEY_ID_TOKEN = "google_id_token"
    private const val KEY_CLOUD_SYNC_ENABLED = "cloud_sync_enabled"
    private const val KEY_LAST_SYNC_TIME = "last_sync_time"
    private const val KEY_CLOUD_BACKUP_JSON = "cloud_backup_json"

    @Volatile
    private var prefs: SharedPreferences? = null

    private fun getPrefs(context: Context): SharedPreferences {
        var p = prefs
        if (p == null) {
            p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs = p
        }
        return p
    }

    override fun init(context: Context) {
        getPrefs(context)
    }

    override fun getCurrentUser(): GoogleAccountUser? {
        val p = prefs ?: return null
        val email = p.getString(KEY_EMAIL, null) ?: return null
        val displayName = p.getString(KEY_DISPLAY_NAME, null)
        val photoUrl = p.getString(KEY_PHOTO_URL, null)
        val idToken = p.getString(KEY_ID_TOKEN, null)
        return GoogleAccountUser(displayName, email, photoUrl, idToken)
    }

    override fun isSignedIn(): Boolean = getCurrentUser() != null

    override fun saveUser(context: Context, user: GoogleAccountUser) {
        getPrefs(context).edit()
            .putString(KEY_DISPLAY_NAME, user.displayName)
            .putString(KEY_EMAIL, user.email)
            .putString(KEY_PHOTO_URL, user.photoUrl)
            .putString(KEY_ID_TOKEN, user.idToken)
            .apply()
    }

    private fun clearUser(context: Context) {
        getPrefs(context).edit()
            .remove(KEY_DISPLAY_NAME)
            .remove(KEY_EMAIL)
            .remove(KEY_PHOTO_URL)
            .remove(KEY_ID_TOKEN)
            .remove(KEY_CLOUD_SYNC_ENABLED)
            .remove(KEY_CLOUD_BACKUP_JSON)
            .apply()
    }

    override suspend fun signOut(context: Context) = withContext(Dispatchers.IO) {
        clearUser(context)
        try {
            val credentialManager = CredentialManager.create(context)
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
        } catch (e: Exception) {
            // Ignored
        }
    }

    override fun isCloudSyncEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_CLOUD_SYNC_ENABLED, false)
    }

    override fun setCloudSyncEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_CLOUD_SYNC_ENABLED, enabled).apply()
    }

    override fun getLastSyncTime(context: Context): Long {
        return getPrefs(context).getLong(KEY_LAST_SYNC_TIME, 0L)
    }

    override fun setLastSyncTime(context: Context, timestamp: Long) {
        getPrefs(context).edit().putLong(KEY_LAST_SYNC_TIME, timestamp).apply()
    }

    override fun saveCloudBackup(context: Context, jsonString: String) {
        getPrefs(context).edit().putString(KEY_CLOUD_BACKUP_JSON, jsonString).apply()
        setLastSyncTime(context, System.currentTimeMillis())
    }

    override fun getCloudBackup(context: Context): String? {
        return getPrefs(context).getString(KEY_CLOUD_BACKUP_JSON, null)
    }

    override fun clearCloudBackup(context: Context) {
        getPrefs(context).edit().remove(KEY_CLOUD_BACKUP_JSON).apply()
    }

    override suspend fun signInWithGoogle(activity: Activity): Result<GoogleAccountUser> = withContext(Dispatchers.IO) {
        try {
            val credentialManager = CredentialManager.create(activity)

            val googleIdOption = GetGoogleIdOption.Builder()
                .setServerClientId("1000000000000-hackexlogger.apps.googleusercontent.com")
                .setFilterByAuthorizedAccounts(false)
                .setAutoSelectEnabled(false)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            val result = credentialManager.getCredential(activity, request)
            val credential = result.credential

            if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                val user = GoogleAccountUser(
                    displayName = googleIdTokenCredential.displayName ?: googleIdTokenCredential.id,
                    email = googleIdTokenCredential.id,
                    photoUrl = googleIdTokenCredential.profilePictureUri?.toString(),
                    idToken = googleIdTokenCredential.idToken
                )
                saveUser(activity, user)
                Result.success(user)
            } else {
                Result.failure(IllegalStateException("Unrecognized credential type received."))
            }
        } catch (e: Exception) {
            val msg = e.message ?: "Sign-in error"
            if (msg.contains("cancel", ignoreCase = true)) {
                Result.failure(IllegalStateException("Sign-in cancelled by user."))
            } else {
                Result.failure(e)
            }
        }
    }
}
