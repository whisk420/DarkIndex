package com.whisk.hackexlogger

/**
 * Data model representing a authenticated Google Account user session.
 */
data class GoogleAccountUser(
    val displayName: String?,
    val email: String?,
    val photoUrl: String?,
    val idToken: String?
)
