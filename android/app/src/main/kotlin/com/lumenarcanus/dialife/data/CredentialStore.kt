package com.lumenarcanus.dialife.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** This phone's connection: which server, and who it is there. */
data class Credentials(
    val server: String,
    val token: String,
    val deviceName: String,
    val role: String,
    val displayUnits: String,
    val deviceId: Long = 0, // 0 for phones connected before it was stored: LiveRepository asks /api/me
)

private val Context.store by preferencesDataStore(name = "credentials")

class CredentialStore(private val context: Context) {
    private object K {
        val server = stringPreferencesKey("server")
        val token = stringPreferencesKey("token_enc") // TokenVault ciphertext, never the token
        val name = stringPreferencesKey("device_name")
        val role = stringPreferencesKey("role")
        val units = stringPreferencesKey("display_units")
        val id = longPreferencesKey("device_id")
    }

    /** Null until connected, or if the stored token can no longer be decrypted. */
    val credentials: Flow<Credentials?> = context.store.data.map { p ->
        val server = p[K.server] ?: return@map null
        val token = p[K.token]?.let(TokenVault::decrypt) ?: return@map null
        Credentials(server, token, p[K.name].orEmpty(), p[K.role].orEmpty(), p[K.units] ?: "mg/dl", p[K.id] ?: 0)
    }

    suspend fun save(c: Credentials) {
        val enc = TokenVault.encrypt(c.token)
        context.store.edit { p ->
            p[K.server] = c.server
            p[K.token] = enc
            p[K.name] = c.deviceName
            p[K.role] = c.role
            p[K.units] = c.displayUnits
            p[K.id] = c.deviceId
        }
    }

    suspend fun clear() {
        context.store.edit { it.clear() }
    }
}
