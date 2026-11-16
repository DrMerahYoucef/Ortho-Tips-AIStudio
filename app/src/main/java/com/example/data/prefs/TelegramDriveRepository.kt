package com.example.data.prefs

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.crypto.KeystoreManager
import com.example.data.storage.StorageBackend
import com.example.data.telegram.TelegramCredentials
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Settings for the optional Telegram Drive backend.
 *
 * Deliberately a separate DataStore file from `ortho_settings`: the existing
 * [SettingsRepository] is untouched, so a user who never enables Telegram Drive
 * has the exact preference file they had before, and clearing drive state cannot
 * disturb theme or font settings.
 *
 * The API id and hash are the user's own credentials from my.telegram.org. They
 * are sealed with the Android Keystore master key before they hit disk, because
 * a rooted-device dump of a plain DataStore would otherwise hand over the ability
 * to impersonate this app against the user's account.
 */
val Context.telegramDriveDataStore by preferencesDataStore(name = "ortho_telegram_drive")

/**
 * When the drive is brought up to date.
 *
 * The device is always the working copy — patients, surgeries and media are
 * written locally first and remain readable offline. This only controls how often
 * the backup runs.
 */
enum class SyncFrequency(val periodHours: Long?) {
    /** Only when the surgeon taps Sync now. */
    MANUAL(null),

    /** Upload as soon as anything is saved. Safest, uses the most data. */
    ON_CHANGE(null),

    DAILY(24),
    WEEKLY(24 * 7),
    MONTHLY(24 * 30);

    val isScheduled: Boolean get() = periodHours != null

    val label: String
        get() = when (this) {
            MANUAL -> "Manual only"
            ON_CHANGE -> "On every change"
            DAILY -> "Daily"
            WEEKLY -> "Weekly"
            MONTHLY -> "Monthly"
        }
}

/** What is kept on the device once an object is safely in the drive. */
enum class CachePolicy {
    /** Everything stays local as well; Telegram is a pure mirror. */
    KEEP_EVERYTHING,

    /** Recently opened objects stay; the rest are fetched on demand. */
    HYBRID,

    /** Only thumbnails stay local. Lowest storage, needs connectivity. */
    MINIMAL
}

data class TelegramDriveSettings(
    val backend: StorageBackend = StorageBackend.LOCAL,
    val setupCompleted: Boolean = false,
    val chatId: Long? = null,
    val accountName: String = "",
    val accountPhoneSuffix: String = "",
    val syncFrequency: SyncFrequency = SyncFrequency.ON_CHANGE,
    val syncOnWifiOnly: Boolean = false,
    val syncWhileChargingOnly: Boolean = false,
    val backgroundSyncEnabled: Boolean = true,
    val encryptUploads: Boolean = true,
    val keepVersionHistory: Boolean = false,
    val cachePolicy: CachePolicy = CachePolicy.HYBRID,
    val cacheBudgetMb: Int = 512,
    val lastSyncAt: Long = 0,
    val lastSyncError: String = "",
    /** When a record table last changed locally. */
    val recordsDirtyAt: Long = 0,
    /** Creation time of the newest snapshot this device uploaded. */
    val snapshotPushedAt: Long = 0,
    /** Creation time of the newest snapshot this device merged in. */
    val snapshotImportedAt: Long = 0,
    /** Deterministic content hash of the newest pushed or imported snapshot tables. */
    val lastSnapshotHash: String = ""
)

class TelegramDriveRepository(
    private val context: Context,
    private val keystoreManager: KeystoreManager
) {

    private object Keys {
        val BACKEND = stringPreferencesKey("storage_backend")
        val SETUP_COMPLETED = booleanPreferencesKey("setup_completed")
        val CHAT_ID = longPreferencesKey("drive_chat_id")
        val ACCOUNT_NAME = stringPreferencesKey("account_name")
        val ACCOUNT_PHONE_SUFFIX = stringPreferencesKey("account_phone_suffix")
        val API_ID = stringPreferencesKey("sealed_api_id")
        val API_HASH = stringPreferencesKey("sealed_api_hash")
        val SYNC_FREQUENCY = stringPreferencesKey("sync_frequency")
        val WIFI_ONLY = booleanPreferencesKey("sync_wifi_only")
        val CHARGING_ONLY = booleanPreferencesKey("sync_charging_only")
        val BACKGROUND_SYNC = booleanPreferencesKey("background_sync")
        val ENCRYPT_UPLOADS = booleanPreferencesKey("encrypt_uploads")
        val VERSION_HISTORY = booleanPreferencesKey("keep_version_history")
        val CACHE_POLICY = stringPreferencesKey("cache_policy")
        val CACHE_BUDGET_MB = intPreferencesKey("cache_budget_mb")
        val LAST_SYNC_AT = longPreferencesKey("last_sync_at")
        val RECORDS_DIRTY_AT = longPreferencesKey("records_dirty_at")
        val SNAPSHOT_PUSHED_AT = longPreferencesKey("snapshot_pushed_at")
        val SNAPSHOT_IMPORTED_AT = longPreferencesKey("snapshot_imported_at")
        val LAST_SNAPSHOT_HASH = stringPreferencesKey("last_snapshot_hash")
        val LAST_SYNC_ERROR = stringPreferencesKey("last_sync_error")
    }

    val settings: Flow<TelegramDriveSettings> = context.telegramDriveDataStore.data.map { prefs ->
        TelegramDriveSettings(
            backend = StorageBackend.parse(prefs[Keys.BACKEND]),
            setupCompleted = prefs[Keys.SETUP_COMPLETED] ?: false,
            chatId = prefs[Keys.CHAT_ID]?.takeIf { it != 0L },
            accountName = prefs[Keys.ACCOUNT_NAME].orEmpty(),
            accountPhoneSuffix = prefs[Keys.ACCOUNT_PHONE_SUFFIX].orEmpty(),
            syncFrequency = runCatching {
                SyncFrequency.valueOf(prefs[Keys.SYNC_FREQUENCY].orEmpty())
            }.getOrDefault(SyncFrequency.ON_CHANGE),
            syncOnWifiOnly = prefs[Keys.WIFI_ONLY] ?: false,
            syncWhileChargingOnly = prefs[Keys.CHARGING_ONLY] ?: false,
            backgroundSyncEnabled = prefs[Keys.BACKGROUND_SYNC] ?: true,
            encryptUploads = prefs[Keys.ENCRYPT_UPLOADS] ?: true,
            keepVersionHistory = prefs[Keys.VERSION_HISTORY] ?: false,
            cachePolicy = runCatching { CachePolicy.valueOf(prefs[Keys.CACHE_POLICY].orEmpty()) }
                .getOrDefault(CachePolicy.HYBRID),
            cacheBudgetMb = prefs[Keys.CACHE_BUDGET_MB] ?: 512,
            lastSyncAt = prefs[Keys.LAST_SYNC_AT] ?: 0,
            lastSyncError = prefs[Keys.LAST_SYNC_ERROR].orEmpty(),
            recordsDirtyAt = prefs[Keys.RECORDS_DIRTY_AT] ?: 0,
            snapshotPushedAt = prefs[Keys.SNAPSHOT_PUSHED_AT] ?: 0,
            snapshotImportedAt = prefs[Keys.SNAPSHOT_IMPORTED_AT] ?: 0,
            lastSnapshotHash = prefs[Keys.LAST_SNAPSHOT_HASH].orEmpty()
        )
    }

    suspend fun current(): TelegramDriveSettings = settings.first()

    suspend fun backend(): StorageBackend = current().backend

    suspend fun chatId(): Long? = current().chatId

    suspend fun encryptUploads(): Boolean = current().encryptUploads

    suspend fun keepVersionHistory(): Boolean = current().keepVersionHistory

    /**
     * Switching backends never touches stored bytes. Both providers keep their
     * data; migration is an explicit, separate action the user starts from
     * settings. This is the guard against "I flipped a toggle and lost my
     * patients' radiographs".
     */
    suspend fun setBackend(backend: StorageBackend) = edit { it[Keys.BACKEND] = backend.name }

    suspend fun setSetupCompleted(completed: Boolean) =
        edit { it[Keys.SETUP_COMPLETED] = completed }

    suspend fun setChatId(chatId: Long?) = edit { prefs ->
        if (chatId == null) prefs.remove(Keys.CHAT_ID) else prefs[Keys.CHAT_ID] = chatId
    }

    suspend fun setAccount(name: String, phoneNumber: String) = edit { prefs ->
        prefs[Keys.ACCOUNT_NAME] = name
        // Only the last four digits are retained, so the settings screen can
        // confirm which account is linked without storing the full number.
        prefs[Keys.ACCOUNT_PHONE_SUFFIX] = phoneNumber.takeLast(4)
    }

    suspend fun setSyncFrequency(frequency: SyncFrequency) =
        edit { it[Keys.SYNC_FREQUENCY] = frequency.name }

    suspend fun syncFrequency(): SyncFrequency = current().syncFrequency

    suspend fun setSyncOnWifiOnly(enabled: Boolean) = edit { it[Keys.WIFI_ONLY] = enabled }

    suspend fun setSyncWhileChargingOnly(enabled: Boolean) =
        edit { it[Keys.CHARGING_ONLY] = enabled }

    suspend fun setBackgroundSyncEnabled(enabled: Boolean) =
        edit { it[Keys.BACKGROUND_SYNC] = enabled }

    suspend fun setEncryptUploads(enabled: Boolean) = edit { it[Keys.ENCRYPT_UPLOADS] = enabled }

    suspend fun setKeepVersionHistory(enabled: Boolean) =
        edit { it[Keys.VERSION_HISTORY] = enabled }

    suspend fun setCachePolicy(policy: CachePolicy) = edit { it[Keys.CACHE_POLICY] = policy.name }

    suspend fun setCacheBudgetMb(megabytes: Int) =
        edit { it[Keys.CACHE_BUDGET_MB] = megabytes.coerceIn(64, 8192) }

    suspend fun recordSyncSuccess(at: Long = System.currentTimeMillis()) = edit { prefs ->
        prefs[Keys.LAST_SYNC_AT] = at
        prefs[Keys.LAST_SYNC_ERROR] = ""
    }

    suspend fun recordSyncFailure(message: String) = edit { it[Keys.LAST_SYNC_ERROR] = message }

    // ---- Record snapshot bookkeeping ----------------------------------------

    suspend fun markRecordsDirty(at: Long = System.currentTimeMillis()) =
        edit { it[Keys.RECORDS_DIRTY_AT] = at }

    suspend fun markSnapshotPushed(createdAt: Long, contentHash: String = "") = edit { prefs ->
        prefs[Keys.SNAPSHOT_PUSHED_AT] = createdAt
        if (contentHash.isNotBlank()) prefs[Keys.LAST_SNAPSHOT_HASH] = contentHash
        // Anything edited before this snapshot was taken is now safely up.
        prefs[Keys.RECORDS_DIRTY_AT] = 0
    }

    suspend fun markSnapshotImported(createdAt: Long, contentHash: String = "") = edit { prefs ->
        prefs[Keys.SNAPSHOT_IMPORTED_AT] = createdAt
        if (contentHash.isNotBlank()) prefs[Keys.LAST_SNAPSHOT_HASH] = contentHash
    }

    /** True when local record edits have not reached the drive yet. */
    suspend fun hasUnpushedRecords(): Boolean {
        val settings = current()
        return settings.recordsDirtyAt > settings.snapshotPushedAt
    }

    /**
     * The point past which a remote snapshot is genuinely newer than anything
     * this device has seen. Importing an older one would overwrite good rows.
     */
    suspend fun snapshotWatermark(): Long {
        val settings = current()
        return maxOf(settings.snapshotPushedAt, settings.snapshotImportedAt)
    }

    // ---- Credentials ---------------------------------------------------------

    suspend fun storeCredentials(credentials: TelegramCredentials) = edit { prefs ->
        prefs[Keys.API_ID] = seal(credentials.apiId.toString())
        prefs[Keys.API_HASH] = seal(credentials.apiHash)
    }

    suspend fun credentials(): TelegramCredentials? {
        val prefs = context.telegramDriveDataStore.data.first()
        val apiId = prefs[Keys.API_ID]?.let { unseal(it) }?.toIntOrNull() ?: return null
        val apiHash = prefs[Keys.API_HASH]?.let { unseal(it) } ?: return null
        if (apiHash.isBlank()) return null
        return TelegramCredentials(apiId, apiHash)
    }

    suspend fun hasCredentials(): Boolean = credentials() != null

    /**
     * Clears the link to Telegram. Stored bytes on either backend are untouched,
     * and the backend falls back to LOCAL so the app stays fully usable offline.
     */
    suspend fun clearAccountState() = edit { prefs ->
        prefs.remove(Keys.CHAT_ID)
        prefs.remove(Keys.ACCOUNT_NAME)
        prefs.remove(Keys.ACCOUNT_PHONE_SUFFIX)
        prefs[Keys.SETUP_COMPLETED] = false
        prefs[Keys.BACKEND] = StorageBackend.LOCAL.name
    }

    private fun seal(value: String): String = Base64.encodeToString(
        keystoreManager.encrypt(value.toByteArray(Charsets.UTF_8)),
        Base64.NO_WRAP
    )

    private fun unseal(value: String): String? = runCatching {
        String(keystoreManager.decrypt(Base64.decode(value, Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrNull()

    private suspend inline fun edit(
        crossinline block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit
    ) {
        context.telegramDriveDataStore.edit { block(it) }
    }
}
