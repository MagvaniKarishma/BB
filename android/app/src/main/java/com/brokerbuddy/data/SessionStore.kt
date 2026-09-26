package com.brokerbuddy.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.brokerbuddy.BuildConfig
import com.brokerbuddy.core.model.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "session")

data class Session(val serverUrl: String, val token: String?, val user: User?) {
    val isLoggedIn: Boolean get() = token != null && user != null
}

/** Persists the API server URL and the signed-in user's token. */
class SessionStore(private val context: Context, private val json: Json) {
    private val serverKey = stringPreferencesKey("server_url")
    private val tokenKey = stringPreferencesKey("token")
    private val userKey = stringPreferencesKey("user")

    val session: Flow<Session> = context.dataStore.data.map { p ->
        Session(
            serverUrl = p[serverKey] ?: BuildConfig.DEFAULT_API_URL,
            token = p[tokenKey],
            user = p[userKey]?.let { runCatching { json.decodeFromString(User.serializer(), it) }.getOrNull() },
        )
    }

    suspend fun current(): Session = session.first()

    suspend fun setServerUrl(url: String) {
        context.dataStore.edit { it[serverKey] = normalizeServerUrl(url) }
    }

    suspend fun signIn(token: String, user: User) {
        context.dataStore.edit {
            it[tokenKey] = token
            it[userKey] = json.encodeToString(User.serializer(), user)
        }
    }

    suspend fun signOut() {
        context.dataStore.edit {
            it.remove(tokenKey)
            it.remove(userKey)
        }
    }

    companion object {
        /** "192.168.1.5:4000" → "http://192.168.1.5:4000/" ; always ends with "/". */
        fun normalizeServerUrl(raw: String): String {
            var url = raw.trim()
            if (!url.startsWith("http://") && !url.startsWith("https://")) url = "http://$url"
            return if (url.endsWith("/")) url else "$url/"
        }
    }
}
