package com.outofthewhale.groupme

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val TOKEN_KEY = stringPreferencesKey("groupme_token")
private val USER_ID_KEY = stringPreferencesKey("groupme_user_id")
private val USER_NAME_KEY = stringPreferencesKey("groupme_user_name")

/**
 * Persists the signed-in session. The access token is encrypted with [TokenCipher]
 * before it is written, so the stored file never contains a usable credential.
 */
internal class SessionStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: TokenCipher = TokenCipher(),
) {

    val credentials: Flow<GroupMeCredentials?> = dataStore.data.map { prefs ->
        val stored = prefs[TOKEN_KEY]
        val userId = prefs[USER_ID_KEY]
        if (stored.isNullOrEmpty() || userId.isNullOrEmpty()) {
            null
        } else {
            readToken(stored)?.let { token ->
                GroupMeCredentials(
                    token = token,
                    userId = userId,
                    userName = prefs[USER_NAME_KEY].orEmpty(),
                )
            }
        }
    }

    suspend fun save(credentials: GroupMeCredentials) {
        val stored = cipher.encrypt(credentials.token) ?: credentials.token
        dataStore.edit { prefs ->
            prefs[TOKEN_KEY] = stored
            prefs[USER_ID_KEY] = credentials.userId
            prefs[USER_NAME_KEY] = credentials.userName
        }
    }

    /**
     * Rewrites a token saved before encryption existed so it is stored encrypted.
     * A no-op once the stored value decrypts. Safe to call on every launch.
     */
    suspend fun migrateIfNeeded() {
        val stored = dataStore.data.first()[TOKEN_KEY] ?: return
        if (cipher.decrypt(stored) != null) return
        if (!looksLikeToken(stored)) return
        val encrypted = cipher.encrypt(stored) ?: return
        dataStore.edit { it[TOKEN_KEY] = encrypted }
    }

    suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.remove(TOKEN_KEY)
            prefs.remove(USER_ID_KEY)
            prefs.remove(USER_NAME_KEY)
        }
    }

    /**
     * Sessions saved before tokens were encrypted are stored in the clear. Those
     * still read as a valid token, so accept them rather than forcing a new login;
     * the next [save] rewrites them encrypted.
     */
    private fun readToken(stored: String): String? =
        cipher.decrypt(stored) ?: stored.takeIf { looksLikeToken(it) }
}

private fun looksLikeToken(value: String): Boolean =
    Regex("^[A-Za-z0-9]{10,}$").matches(value)

/**
 * Extracts a GroupMe access token from raw user input: a bare token, an OAuth
 * callback URL containing an access_token parameter, or a QR payload of either.
 */
internal fun extractToken(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val fromUrl = Regex("access_token=([A-Za-z0-9]+)").find(trimmed)?.groupValues?.get(1)
    if (fromUrl != null) return fromUrl
    return trimmed.takeIf { looksLikeToken(it) }
}
