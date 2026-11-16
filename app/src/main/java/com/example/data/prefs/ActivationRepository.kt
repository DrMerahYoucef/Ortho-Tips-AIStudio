package com.example.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.activationDataStore by preferencesDataStore(name = "app_activation")

class ActivationRepository(private val context: Context) {

    private object Keys {
        val ACTIVATED = booleanPreferencesKey("activated")
    }

    private companion object {
        private const val HASH_SALT = "Ortho-Tips-activation-v1:"
        private const val ACTIVATION_HASH = "bb35e8814f6e802f782cc176909dfa854cd506e860f84bccf4e3f58b85019003"
    }

    val isActivated: Flow<Boolean> = context.activationDataStore.data.map { preferences ->
        preferences[Keys.ACTIVATED] ?: false
    }

    suspend fun activateIfValid(code: String): Boolean {
        val candidate = sha256Hex(HASH_SALT + code)
        val isValid = MessageDigest.isEqual(
            candidate.toByteArray(StandardCharsets.US_ASCII),
            ACTIVATION_HASH.toByteArray(StandardCharsets.US_ASCII)
        )
        if (isValid) {
            context.activationDataStore.edit { preferences ->
                preferences[Keys.ACTIVATED] = true
            }
        }
        return isValid
    }

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
