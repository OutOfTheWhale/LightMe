package com.outofthewhale.groupme

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val TOKEN_KEY = stringPreferencesKey("groupme_token")
private val USER_ID_KEY = stringPreferencesKey("groupme_user_id")
private val USER_NAME_KEY = stringPreferencesKey("groupme_user_name")

internal class SessionStore(private val dataStore: DataStore<Preferences>) {

    val credentials: Flow<GroupMeCredentials?> = dataStore.data.map { prefs ->
        val token = prefs[TOKEN_KEY]
        val userId = prefs[USER_ID_KEY]
        if (token.isNullOrEmpty() || userId.isNullOrEmpty()) {
            null
        } else {
            GroupMeCredentials(
                token = token,
                userId = userId,
                userName = prefs[USER_NAME_KEY].orEmpty(),
            )
        }
    }

    suspend fun save(credentials: GroupMeCredentials) {
        dataStore.edit { prefs ->
            prefs[TOKEN_KEY] = credentials.token
            prefs[USER_ID_KEY] = credentials.userId
            prefs[USER_NAME_KEY] = credentials.userName
        }
    }

    suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.remove(TOKEN_KEY)
            prefs.remove(USER_ID_KEY)
            prefs.remove(USER_NAME_KEY)
        }
    }
}

/**
 * Extracts a GroupMe access token from raw user input: a bare token, an OAuth
 * callback URL containing an access_token parameter, or a QR payload of either.
 */
internal fun extractToken(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val fromUrl = Regex("access_token=([A-Za-z0-9]+)").find(trimmed)?.groupValues?.get(1)
    if (fromUrl != null) return fromUrl
    return if (Regex("^[A-Za-z0-9]{10,}$").matches(trimmed)) trimmed else null
}
