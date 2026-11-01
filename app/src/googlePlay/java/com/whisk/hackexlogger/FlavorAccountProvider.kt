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

    private lateinit var prefs: SharedPreferences

    override fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    override fun getCurrentUser(): GoogleAccountUser? {
        if (!::prefs.isInitialized) return null
        val email = prefs.getString(KEY_EMAIL, null) ?: return null
        val displayName = prefs.getString(KEY_DISPLAY_NAME, null)
        val photoUrl = prefs.getString(KEY_PHOTO_URL, null)
        val idToken = prefs.getString(KEY_ID_TOKEN, null)
        return GoogleAccountUser(displayName, email, photoUrl, idToken)
    }

    override fun isSignedIn(): Boolean = getCurrentUser() != null

    private fun saveUser(user: GoogleAccountUser) {
        prefs.edit()
            .putString(KEY_DISPLAY_NAME, user.displayName)
            .putString(KEY_EMAIL, user.email)
            .putString(KEY_PHOTO_URL, user.photoUrl)
            .putString(KEY_ID_TOKEN, user.idToken)
            .apply()
    }

    private fun clearUser() {
        prefs.edit()
            .remove(KEY_DISPLAY_NAME)
            .remove(KEY_EMAIL)
            .remove(KEY_PHOTO_URL)
            .remove(KEY_ID_TOKEN)
            .remove(KEY_CLOUD_SYNC_ENABLED)
            .apply()
    }

    override suspend fun signOut(context: Context) = withContext(Dispatchers.IO) {
        if (!::prefs.isInitialized) init(context)
        clearUser()
        try {
            val credentialManager = CredentialManager.create(context)
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
        } catch (e: Exception) {
            // Ignored
        }
    }

    override fun isCloudSyncEnabled(context: Context): Boolean {
        if (!::prefs.isInitialized) init(context)
        return prefs.getBoolean(KEY_CLOUD_SYNC_ENABLED, false)
    }

    override fun setCloudSyncEnabled(context: Context, enabled: Boolean) {
        if (!::prefs.isInitialized) init(context)
        prefs.edit().putBoolean(KEY_CLOUD_SYNC_ENABLED, enabled).apply()
    }

    override fun getLastSyncTime(context: Context): Long {
        if (!::prefs.isInitialized) init(context)
        return prefs.getLong(KEY_LAST_SYNC_TIME, 0L)
    }

    override fun setLastSyncTime(context: Context, timestamp: Long) {
        if (!::prefs.isInitialized) init(context)
        prefs.edit().putLong(KEY_LAST_SYNC_TIME, timestamp).apply()
    }

    override suspend fun signInWithGoogle(activity: Activity): Result<GoogleAccountUser> = withContext(Dispatchers.IO) {
        try {
            val credentialManager = CredentialManager.create(activity)

            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId("dummy-client-id-for-google-auth.apps.googleusercontent.com")
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
                    displayName = googleIdTokenCredential.displayName,
                    email = googleIdTokenCredential.id,
                    photoUrl = googleIdTokenCredential.profilePictureUri?.toString(),
                    idToken = googleIdTokenCredential.idToken
                )
                saveUser(user)
                Result.success(user)
            } else {
                Result.failure(IllegalStateException("Unrecognized credential type received."))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
