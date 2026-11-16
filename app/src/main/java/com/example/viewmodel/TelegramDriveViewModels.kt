package com.example.viewmodel

import com.example.R
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.crypto.CryptoKeys
import com.example.data.crypto.PassphraseKeyBackup
import com.example.data.local.entity.TransferDirection
import com.example.data.local.entity.TransferQueueEntity
import com.example.data.prefs.CachePolicy
import com.example.data.prefs.SyncFrequency
import com.example.data.prefs.TelegramDriveRepository
import com.example.data.prefs.TelegramDriveSettings
import com.example.data.storage.StorageBackend
import com.example.data.storage.StorageUsage
import com.example.data.sync.ConflictPolicy
import com.example.data.sync.IntegrityReport
import com.example.data.sync.SyncReport
import com.example.data.sync.SyncScope
import com.example.data.telegram.DriveLog
import com.example.data.telegram.TelegramAuthState
import com.example.data.telegram.TelegramConnectionState
import com.example.data.telegram.TelegramCredentials
import com.example.di.AppModule
import com.example.work.SyncScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Setup wizard
// ---------------------------------------------------------------------------

/**
 * Ordered steps of first-time setup. The wizard is linear because every step
 * depends on the one before it: there is no drive to verify before the account
 * is linked, and no manifest to build before the drive exists.
 */
enum class SetupStep {
    WELCOME,
    REQUIREMENTS,
    CREDENTIALS,
    TELEGRAM_LOGIN,
    NOTIFICATIONS,
    DRIVE_INITIALISATION,
    STORAGE_VERIFICATION,
    CONNECTION_TEST,
    MANIFEST,
    INITIAL_SYNC,
    COMPLETE;

    val isLast: Boolean get() = this == COMPLETE

    val index: Int get() = ordinal

    /**
     * Steps that perform work rather than collect input. These run as soon as
     * they are shown; Continue unlocks once the work succeeds.
     *
     * TELEGRAM_LOGIN is not in this set even though it talks to the network: it
     * is driven by what the user types, and its gate is the auth state.
     */
    val isAutomatic: Boolean
        get() = this in setOf(
            DRIVE_INITIALISATION,
            STORAGE_VERIFICATION,
            CONNECTION_TEST,
            MANIFEST,
            INITIAL_SYNC
        )

    companion object {
        val total: Int get() = entries.size
    }
}

data class SetupUiState(
    val step: SetupStep = SetupStep.WELCOME,
    val apiId: String = "",
    val apiHash: String = "",
    val phoneNumber: String = "",
    val code: String = "",
    val password: String = "",
    val email: String = "",
    val authState: TelegramAuthState = TelegramAuthState.Uninitialized,
    val connection: TelegramConnectionState = TelegramConnectionState.CONNECTING,
    val busy: Boolean = false,
    val statusLine: String = "",
    val error: String? = null,
    val accountName: String = "",
    val chatId: Long? = null,
    val verifiedBytes: Long = 0,
    val manifestEntries: Int = 0,
    val initialSyncReport: SyncReport? = null,
    val existingDriveFound: Boolean = false,
    /** The drive holds data encrypted with a key this phone does not have. */
    val driveLocked: Boolean = false,
    val lockedObjects: Int = 0,
    val driveHasKeyBackup: Boolean = false,
    val passphrase: PassphraseUi = PassphraseUi(),
    /** Automatic steps whose work has finished successfully. */
    val completedSteps: Set<SetupStep> = emptySet()
) {
    /**
     * Gating an automatic step on its own output would deadlock: the output is
     * produced by work that only runs once the step is allowed to proceed. So
     * automatic steps gate on having completed, and the work is started by the
     * screen when the step appears.
     */
    val canAdvance: Boolean
        get() = !busy && when {
            step == SetupStep.CREDENTIALS ->
                apiId.toIntOrNull() != null && apiHash.length >= 16

            step == SetupStep.TELEGRAM_LOGIN -> authState == TelegramAuthState.Ready
            step == SetupStep.MANIFEST -> step in completedSteps && !driveLocked
            step.isAutomatic -> step in completedSteps
            else -> true
        }

    /** True while an automatic step is running or waiting to be retried. */
    val isStepPending: Boolean
        get() = step.isAutomatic && step !in completedSteps
}

/**
 * Drives first-time setup. Each step's work is idempotent, so backing out and
 * retrying never leaves half-created state on the account — important because a
 * failed run must not litter the user's Telegram with orphan channels.
 */
class TelegramSetupViewModel(
    private val appModule: AppModule,
    private val applicationContext: Context
) : ViewModel() {

    /** Text in the app's chosen language (the application context alone ignores it below Android 13). */
    private fun text(@androidx.annotation.StringRes id: Int, vararg args: Any): String =
        com.example.util.AppLocale.wrap(applicationContext).getString(id, *args)

    private val _uiState = MutableStateFlow(SetupUiState())
    val uiState: StateFlow<SetupUiState> = _uiState.asStateFlow()

    private val client get() = appModule.telegramClient
    private val drive get() = appModule.telegramDriveRepository

    init {
        viewModelScope.launch {
            client.authState.collect { state ->
                _uiState.value = _uiState.value.copy(
                    authState = state,
                    error = (state as? TelegramAuthState.Failed)?.message
                )
            }
        }
        viewModelScope.launch {
            client.connectionState.collect { _uiState.value = _uiState.value.copy(connection = it) }
        }
        viewModelScope.launch {
            drive.credentials()?.let { existing ->
                _uiState.value = _uiState.value.copy(
                    apiId = existing.apiId.toString(),
                    apiHash = existing.apiHash
                )
            }
        }
    }

    fun onApiId(value: String) {
        _uiState.value = _uiState.value.copy(apiId = value.filter { it.isDigit() }, error = null)
    }

    fun onApiHash(value: String) {
        _uiState.value = _uiState.value.copy(apiHash = value.trim(), error = null)
    }

    fun onPhoneNumber(value: String) {
        _uiState.value = _uiState.value.copy(phoneNumber = value, error = null)
    }

    fun onCode(value: String) {
        _uiState.value = _uiState.value.copy(code = value.filter { it.isDigit() }, error = null)
    }

    fun onPassword(value: String) {
        _uiState.value = _uiState.value.copy(password = value, error = null)
    }

    fun onEmail(value: String) {
        _uiState.value = _uiState.value.copy(email = value.trim(), error = null)
    }

    fun back() {
        val current = _uiState.value.step
        if (current == SetupStep.WELCOME) return
        goTo(SetupStep.entries[current.ordinal - 1])
    }

    /**
     * Runs the work for an automatic step. Called by the screen when the step
     * appears, and again by Retry after a failure. Re-entry is cheap: a step that
     * already succeeded returns immediately, so a recomposition cannot start a
     * second drive creation.
     */
    fun runCurrentStep() {
        val state = _uiState.value
        val step = state.step
        if (!step.isAutomatic || state.busy || step in state.completedSteps) return
        viewModelScope.launch {
            setBusy(true)
            _uiState.value = _uiState.value.copy(error = null)
            val ok = runCatching {
                when (step) {
                    SetupStep.DRIVE_INITIALISATION -> initialiseDrive()
                    SetupStep.STORAGE_VERIFICATION -> verifyStorage()
                    SetupStep.CONNECTION_TEST -> testConnection()
                    SetupStep.MANIFEST -> buildManifest()
                    SetupStep.INITIAL_SYNC -> runInitialSync()
                    else -> true
                }
            }.getOrElse { error ->
                DriveLog.e(TAG, "Setup step $step failed", error)
                _uiState.value = _uiState.value.copy(
                    error = error.message ?: text(R.string.drive_err_step)
                )
                false
            }
            setBusy(false)
            if (ok) {
                _uiState.value = _uiState.value.copy(
                    completedSteps = _uiState.value.completedSteps + step
                )
            }
        }
    }

    /** Clears the failure and runs the step again. */
    fun retryCurrentStep() {
        _uiState.value = _uiState.value.copy(error = null, statusLine = "")
        runCurrentStep()
    }

    /**
     * Moves to the next step. Input steps do their small amount of work here;
     * automatic steps have already done theirs, so this is pure navigation.
     */
    fun advance() {
        val state = _uiState.value
        if (!state.canAdvance) return
        viewModelScope.launch {
            val ok = when (state.step) {
                SetupStep.CREDENTIALS -> runCatching { startTelegram() }.getOrElse { error ->
                    _uiState.value = _uiState.value.copy(
                        error = error.message ?: text(R.string.drive_err_start)
                    )
                    false
                }

                SetupStep.COMPLETE -> {
                    runCatching { finish() }
                    true
                }

                else -> true
            }
            if (ok && !state.step.isLast) {
                goTo(SetupStep.entries[state.step.ordinal + 1])
            }
        }
    }

    /** Single place that changes step, so stale status text never carries over. */
    private fun goTo(step: SetupStep) {
        _uiState.value = _uiState.value.copy(step = step, error = null, statusLine = "")
    }

    private suspend fun startTelegram(): Boolean {
        val state = _uiState.value
        val credentials = TelegramCredentials(
            apiId = state.apiId.toIntOrNull() ?: return false,
            apiHash = state.apiHash
        )
        drive.storeCredentials(credentials)
        status(text(R.string.drive_st_starting))
        client.start(credentials)
        return true
    }

    fun submitPhoneNumber() = runStep(text(R.string.drive_st_sending_code)) {
        client.submitPhoneNumber(_uiState.value.phoneNumber.trim())
    }

    fun submitCode() = runStep(text(R.string.drive_st_checking_code)) { client.submitCode(_uiState.value.code) }

    fun submitPassword() = runStep(text(R.string.drive_st_checking_password)) {
        client.submitPassword(_uiState.value.password)
    }

    fun submitEmail() = runStep(text(R.string.drive_st_sending_email)) {
        client.submitEmailAddress(_uiState.value.email)
    }

    fun submitEmailCode() = runStep(text(R.string.drive_st_checking_email)) {
        client.submitEmailCode(_uiState.value.code)
    }

    fun resendCode() = runStep(text(R.string.drive_st_resending)) { client.resendCode() }

    fun useQrLogin() = runStep(text(R.string.drive_st_qr)) { client.requestQrCodeAuthentication() }

    fun usePhoneLogin() = runStep(text(R.string.drive_st_restart)) { client.restartAuthentication() }

    private suspend fun initialiseDrive(): Boolean {
        status(text(R.string.drive_st_looking))
        val existing = runCatching { client.listStorageChannels() }.getOrDefault(emptyList())
        val chatId = client.ensureStorageChat(drive.chatId())
        drive.setChatId(chatId)
        val user = runCatching { client.getCurrentUser() }.getOrNull()
        if (user != null) {
            drive.setAccount(user.displayName, user.phoneNumber)
            _uiState.value = _uiState.value.copy(accountName = user.displayName)
        }
        _uiState.value = _uiState.value.copy(
            chatId = chatId,
            existingDriveFound = existing.any { it.chatId == chatId }
        )
        status(text(R.string.drive_st_ready))
        return true
    }

    private suspend fun verifyStorage(): Boolean {
        status(text(R.string.drive_st_space))
        val usage = appModule.storageRegistry.local.usage()
        _uiState.value = _uiState.value.copy(verifiedBytes = usage.availableBytes)
        if (usage.availableBytes < MIN_FREE_BYTES) {
            _uiState.value = _uiState.value.copy(
                error = text(R.string.drive_err_space)
            )
            return false
        }
        // Creating the content key here rather than on first upload means a
        // failure surfaces during setup, not halfway through a patient's imaging.
        appModule.wrappedKeyRepository.getOrCreate(CryptoKeys.CONTENT)
        return true
    }

    private suspend fun testConnection(): Boolean {
        status(text(R.string.drive_st_connection))
        val user = client.getCurrentUser()
        val limits = client.getLimits()
        status(
            text(R.string.drive_st_connected_as, user.displayName, limits.maxFileBytes / (1024 * 1024))
        )
        return true
    }

    private suspend fun buildManifest(): Boolean {
        status(text(R.string.drive_st_manifest))
        val entries = appModule.syncEngine.rebuildManifest()
        val keys = runCatching { appModule.passphraseKeyBackup.inspectDrive() }.getOrNull()
        _uiState.value = _uiState.value.copy(
            manifestEntries = entries,
            driveLocked = keys?.needsUnlock == true,
            lockedObjects = keys?.lockedObjects ?: 0,
            driveHasKeyBackup = keys?.hasBackup == true,
            passphrase = PassphraseUi(checked = keys != null, hasBackup = keys?.hasBackup == true)
        )
        status(
            if (entries == 0) text(R.string.drive_st_new_drive)
            else text(R.string.drive_st_found_entries, entries)
        )
        return true
    }

    private suspend fun runInitialSync(): Boolean {
        status(text(R.string.drive_st_first_sync))
        drive.setBackend(StorageBackend.TELEGRAM)
        drive.setSetupCompleted(true)
        // An existing drive means this is a second device or a reinstall, so the
        // records it already holds are pulled before anything local is pushed.
        if (_uiState.value.manifestEntries > 0) {
            runCatching { appModule.syncEngine.pullDatabaseSnapshot() }
                .onSuccess { restored ->
                    if (restored == null) {
                        DriveLog.w(TAG, "Drive holds no snapshot with records")
                    } else {
                        status(text(R.string.drive_st_restored, restored.rowCount))
                    }
                }
                .onFailure { DriveLog.e(TAG, "Snapshot restore failed", it) }
        }
        val report = appModule.syncEngine.sync(SyncScope.FULL, ConflictPolicy.NEWEST_WINS)
        _uiState.value = _uiState.value.copy(initialSyncReport = report)
        SyncScheduler(applicationContext).kickTransfers(ignoreConstraints = true)
        return true
    }

    private suspend fun finish(): Boolean {
        SyncScheduler(applicationContext).applyPreferences()
        return true
    }

    /** Opens a drive created on another phone (or before a reinstall). */
    fun unlockDrive(passphrase: String) {
        val backup = appModule.passphraseKeyBackup
        setPassphraseUi { it.copy(working = true, outcome = null) }
        viewModelScope.launch {
            val outcome = runCatching {
                when (val result = backup.unlock(passphrase)) {
                    PassphraseKeyBackup.UnlockResult.NoBackup -> PassphraseOutcome.NO_BACKUP
                    PassphraseKeyBackup.UnlockResult.WrongPassphrase -> PassphraseOutcome.WRONG
                    is PassphraseKeyBackup.UnlockResult.Unlocked -> {
                        val entries = appModule.syncEngine.rebuildManifest()
                        val keys = runCatching { backup.inspectDrive() }.getOrNull()
                        _uiState.value = _uiState.value.copy(
                            manifestEntries = entries,
                            driveLocked = keys?.needsUnlock == true,
                            lockedObjects = keys?.lockedObjects ?: 0,
                            driveHasKeyBackup = true
                        )
                        PassphraseOutcome.UNLOCKED
                    }
                }
            }.getOrElse { error ->
                DriveLog.e(TAG, "Unlock failed", error)
                PassphraseOutcome.FAILED
            }
            setPassphraseUi { it.copy(working = false, outcome = outcome, hasBackup = _uiState.value.driveHasKeyBackup) }
        }
    }

    /** Continues without the old data; it stays in the drive, unreadable here. */
    fun skipUnlock() {
        _uiState.value = _uiState.value.copy(driveLocked = false)
    }

    fun savePassphrase(passphrase: String) {
        setPassphraseUi { it.copy(working = true, outcome = null) }
        viewModelScope.launch {
            val outcome = savePassphraseOutcome(appModule, passphrase)
            if (outcome == PassphraseOutcome.SAVED) {
                _uiState.value = _uiState.value.copy(driveHasKeyBackup = true)
            }
            setPassphraseUi { it.copy(working = false, outcome = outcome, hasBackup = _uiState.value.driveHasKeyBackup) }
        }
    }

    private fun setPassphraseUi(change: (PassphraseUi) -> PassphraseUi) {
        _uiState.value = _uiState.value.copy(passphrase = change(_uiState.value.passphrase))
    }

    /** Abandons setup, leaving the backend on LOCAL and no data touched. */
    fun cancel() {
        viewModelScope.launch {
            drive.setSetupCompleted(false)
            drive.setBackend(StorageBackend.LOCAL)
        }
    }

    private fun runStep(message: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            setBusy(true)
            status(message)
            runCatching { block() }.onFailure { error ->
                _uiState.value = _uiState.value.copy(error = error.message ?: text(R.string.drive_err_request))
            }
            setBusy(false)
        }
    }

    private fun setBusy(busy: Boolean) {
        _uiState.value = _uiState.value.copy(busy = busy)
    }

    private fun status(line: String) {
        _uiState.value = _uiState.value.copy(statusLine = line)
    }

    companion object {
        private const val TAG = "TelegramSetupVM"
        private const val MIN_FREE_BYTES = 200L * 1024 * 1024

        fun provideFactory(
            appModule: AppModule,
            applicationContext: Context
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                TelegramSetupViewModel(appModule, applicationContext) as T
        }
    }
}

// ---------------------------------------------------------------------------
// Settings
// ---------------------------------------------------------------------------

data class DriveSettingsUiState(
    val settings: TelegramDriveSettings = TelegramDriveSettings(),
    val authState: TelegramAuthState = TelegramAuthState.Uninitialized,
    val connection: TelegramConnectionState = TelegramConnectionState.CONNECTING,
    val localUsage: StorageUsage? = null,
    val driveUsage: StorageUsage? = null,
    val pendingUploads: Int = 0,
    val pendingDownloads: Int = 0,
    val failedTransfers: Int = 0,
    val history: List<TransferQueueEntity> = emptyList(),
    val keyFingerprint: String? = null,
    val busyLabel: String? = null,
    val message: String? = null,
    val lastReport: SyncReport? = null,
    val integrity: IntegrityReport? = null,
    val passphrase: PassphraseUi = PassphraseUi()
)

class TelegramDriveSettingsViewModel(
    private val appModule: AppModule,
    private val applicationContext: Context
) : ViewModel() {

    /** Text in the app's chosen language (the application context alone ignores it below Android 13). */
    private fun text(@androidx.annotation.StringRes id: Int, vararg args: Any): String =
        com.example.util.AppLocale.wrap(applicationContext).getString(id, *args)

    private val drive: TelegramDriveRepository get() = appModule.telegramDriveRepository
    private val queueDao get() = appModule.database.transferQueueDao()
    private val scheduler = SyncScheduler(applicationContext)

    private val _uiState = MutableStateFlow(DriveSettingsUiState())
    val uiState: StateFlow<DriveSettingsUiState> = _uiState.asStateFlow()

    /** Ring buffer shared with the whole drive layer; see [DriveLog]. */
    val logs: StateFlow<List<DriveLog.Entry>> = DriveLog.entries

    init {
        viewModelScope.launch {
            drive.settings.collect { settings ->
                _uiState.value = _uiState.value.copy(settings = settings)
                refreshUsage()
            }
        }
        viewModelScope.launch {
            appModule.telegramClient.authState.collect {
                _uiState.value = _uiState.value.copy(authState = it)
            }
        }
        viewModelScope.launch {
            appModule.telegramClient.connectionState.collect {
                _uiState.value = _uiState.value.copy(connection = it)
            }
        }
        viewModelScope.launch {
            queueDao.observePendingCount(TransferDirection.UPLOAD.name).collect {
                _uiState.value = _uiState.value.copy(pendingUploads = it)
            }
        }
        viewModelScope.launch {
            queueDao.observePendingCount(TransferDirection.DOWNLOAD.name).collect {
                _uiState.value = _uiState.value.copy(pendingDownloads = it)
            }
        }
        viewModelScope.launch {
            queueDao.observeFailedCount().collect {
                _uiState.value = _uiState.value.copy(failedTransfers = it)
            }
        }
        viewModelScope.launch {
            queueDao.observeHistory(HISTORY_LIMIT).collect {
                _uiState.value = _uiState.value.copy(history = it)
            }
        }
        _uiState.value = _uiState.value.copy(
            keyFingerprint = appModule.wrappedKeyRepository.fingerprint(CryptoKeys.CONTENT)
        )
        viewModelScope.launch {
            if (drive.current().setupCompleted) refreshPassphrase()
        }
    }

    // ---- Encryption passphrase -----------------------------------------------

    fun refreshPassphrase() {
        viewModelScope.launch {
            val keys = kotlinx.coroutines.withTimeoutOrNull(90_000) {
                runCatching { appModule.passphraseKeyBackup.inspectDrive() }.getOrNull()
            } ?: return@launch
            setPassphraseUi {
                it.copy(
                    checked = true,
                    hasBackup = keys.hasBackup,
                    needsUnlock = keys.needsUnlock,
                    lockedObjects = keys.lockedObjects
                )
            }
        }
    }

    fun savePassphrase(passphrase: String) {
        setPassphraseUi { it.copy(working = true, outcome = null) }
        viewModelScope.launch {
            val outcome = savePassphraseOutcome(appModule, passphrase)
            setPassphraseUi {
                it.copy(
                    working = false,
                    outcome = outcome,
                    hasBackup = it.hasBackup || outcome == PassphraseOutcome.SAVED,
                    needsUnlock = it.needsUnlock || outcome == PassphraseOutcome.LOCKED_DRIVE
                )
            }
        }
    }

    /**
     * Installs the drive's key from its passphrase, then rebuilds the index and
     * brings the patients and their media back.
     */
    fun unlockDrive(passphrase: String) {
        setPassphraseUi { it.copy(working = true, outcome = null) }
        viewModelScope.launch {
            val outcome = runCatching {
                when (appModule.passphraseKeyBackup.unlock(passphrase)) {
                    PassphraseKeyBackup.UnlockResult.NoBackup -> PassphraseOutcome.NO_BACKUP
                    PassphraseKeyBackup.UnlockResult.WrongPassphrase -> PassphraseOutcome.WRONG
                    is PassphraseKeyBackup.UnlockResult.Unlocked -> {
                        appModule.syncEngine.rebuildManifest()
                        runCatching { appModule.syncEngine.pullDatabaseSnapshot() }
                            .onFailure { DriveLog.e(TAG, "Restore after unlock failed", it) }
                        val report = appModule.syncEngine.sync(SyncScope.FULL, ConflictPolicy.PREFER_REMOTE)
                        _uiState.value = _uiState.value.copy(
                            lastReport = report,
                            keyFingerprint = appModule.wrappedKeyRepository.fingerprint(CryptoKeys.CONTENT)
                        )
                        val settings = drive.current()
                        scheduler.kickTransfers(
                            wifiOnly = settings.syncOnWifiOnly,
                            chargingOnly = settings.syncWhileChargingOnly,
                            ignoreConstraints = true
                        )
                        PassphraseOutcome.UNLOCKED
                    }
                }
            }.getOrElse { error ->
                DriveLog.e(TAG, "Unlock failed", error)
                PassphraseOutcome.FAILED
            }
            setPassphraseUi { it.copy(working = false, outcome = outcome) }
            if (outcome == PassphraseOutcome.UNLOCKED) refreshPassphrase()
        }
    }

    fun clearPassphraseOutcome() = setPassphraseUi { it.copy(outcome = null) }

    private fun setPassphraseUi(change: (PassphraseUi) -> PassphraseUi) {
        _uiState.value = _uiState.value.copy(passphrase = change(_uiState.value.passphrase))
    }

    // ---- Backend -------------------------------------------------------------

    /**
     * Switching backends is metadata only. Both stores keep every byte they hold;
     * moving content between them is the separate, explicit migration action.
     */
    fun selectBackend(backend: StorageBackend) = launchWith(text(R.string.drive_busy_switching)) {
        if (backend == StorageBackend.TELEGRAM && !drive.current().setupCompleted) {
            message(text(R.string.drive_msg_setup_first))
            return@launchWith
        }
        drive.setBackend(backend)
        scheduler.applyPreferences()
        message(
            if (backend == StorageBackend.LOCAL) {
                text(R.string.drive_msg_now_local)
            } else {
                text(R.string.drive_msg_now_drive)
            }
        )
    }

    // ---- Sync preferences ----------------------------------------------------

    fun setSyncFrequency(frequency: SyncFrequency) = launchWith(null) {
        drive.setSyncFrequency(frequency)
        scheduler.applyPreferences()
        if (frequency == SyncFrequency.ON_CHANGE) {
            // Anything saved while the drive was on a slower schedule is still
            // waiting locally, so catch it up rather than making the surgeon
            // wonder why the switch changed nothing.
            syncNow(full = false)
        }
    }

    fun setWifiOnly(enabled: Boolean) = launchWith(null) {
        drive.setSyncOnWifiOnly(enabled)
        scheduler.applyPreferences()
        val settings = drive.current()
        scheduler.restartTransfers(enabled, settings.syncWhileChargingOnly)
    }

    fun setChargingOnly(enabled: Boolean) = launchWith(null) {
        drive.setSyncWhileChargingOnly(enabled)
        scheduler.applyPreferences()
        val settings = drive.current()
        scheduler.restartTransfers(settings.syncOnWifiOnly, enabled)
    }

    fun setBackgroundSync(enabled: Boolean) = launchWith(null) {
        drive.setBackgroundSyncEnabled(enabled)
        scheduler.applyPreferences()
    }

    fun setEncryptUploads(enabled: Boolean) = launchWith(null) {
        drive.setEncryptUploads(enabled)
        message(
            if (enabled) text(R.string.drive_msg_encrypt_on)
            else text(R.string.drive_msg_encrypt_off)
        )
    }

    fun setKeepVersionHistory(enabled: Boolean) = launchWith(null) {
        drive.setKeepVersionHistory(enabled)
    }

    fun setCachePolicy(policy: CachePolicy) = launchWith(null) { drive.setCachePolicy(policy) }

    fun setCacheBudgetMb(megabytes: Int) = launchWith(null) { drive.setCacheBudgetMb(megabytes) }

    // ---- Actions -------------------------------------------------------------

    fun syncNow(full: Boolean = false) = launchWith(text(R.string.drive_busy_sync)) {
        val report = appModule.syncEngine.sync(
            if (full) SyncScope.FULL else SyncScope.INCREMENTAL
        )
        _uiState.value = _uiState.value.copy(lastReport = report)
        // Always kick: rows already waiting are not counted as "queued" by the
        // sync, so this button used to leave them pending.
        queueDao.resetPendingBackoff(System.currentTimeMillis())
        val settings = drive.current()
        scheduler.kickTransfers(
            wifiOnly = settings.syncOnWifiOnly,
            chargingOnly = settings.syncWhileChargingOnly,
            ignoreConstraints = true
        )
        message(
            if (report.succeeded) {
                text(R.string.drive_msg_queued_both, report.uploadsQueued, report.downloadsQueued)
            } else {
                report.errors.first()
            }
        )
    }

    fun retryFailedTransfers() = launchWith(text(R.string.drive_busy_retry)) {
        val now = System.currentTimeMillis()
        queueDao.retryAllFailed(now)
        queueDao.resetPendingBackoff(now)
        val settings = drive.current()
        scheduler.kickTransfers(
            wifiOnly = settings.syncOnWifiOnly,
            chargingOnly = settings.syncWhileChargingOnly,
            ignoreConstraints = true
        )
        message(text(R.string.drive_msg_requeued))
    }

    fun reconnect() = launchWith(text(R.string.drive_busy_reconnect)) {
        val credentials = drive.credentials()
        if (credentials == null) {
            message(text(R.string.drive_msg_no_credentials))
            return@launchWith
        }
        appModule.telegramClient.start(credentials)
        message(text(R.string.drive_msg_reconnecting))
    }

    /**
     * Signs out of Telegram and reverts to local storage. Nothing is deleted:
     * local files stay, and the drive channel stays in the user's account.
     */
    fun logout() = launchWith(text(R.string.drive_busy_signout)) {
        runCatching { appModule.telegramClient.logout() }
        scheduler.cancelAll()
        drive.clearAccountState()
        message(text(R.string.drive_msg_signed_out))
    }

    fun rebuildManifest() = launchWith(text(R.string.drive_busy_rebuild)) {
        val entries = appModule.syncEngine.rebuildManifest()
        message(text(R.string.drive_msg_rebuilt, entries))
    }

    fun verifyIntegrity() = launchWith(text(R.string.drive_busy_verify)) {
        val report = appModule.syncEngine.verifyIntegrity()
        _uiState.value = _uiState.value.copy(integrity = report)
        message(
            if (report.healthy) text(R.string.drive_msg_verified, report.checked)
            else text(R.string.drive_msg_integrity_issues, report.missingRemotely.size, report.hashMismatches.size)
        )
    }

    fun clearCache() = launchWith(text(R.string.drive_busy_cache)) {
        val freed = appModule.telegramStorageProvider.clearCache()
        refreshUsage()
        message(text(R.string.drive_msg_cache_freed, freed / (1024 * 1024)))
    }

    fun migrateLocalToDrive() = launchWith(text(R.string.drive_busy_copy)) {
        val report = appModule.syncEngine.sync(SyncScope.FULL, ConflictPolicy.PREFER_LOCAL)
        _uiState.value = _uiState.value.copy(lastReport = report)
        val settings = drive.current()
        scheduler.kickTransfers(
            wifiOnly = settings.syncOnWifiOnly,
            chargingOnly = settings.syncWhileChargingOnly,
            ignoreConstraints = true
        )
        message(text(R.string.drive_msg_queued_uploads, report.uploadsQueued))
    }

    fun migrateDriveToLocal() = launchWith(text(R.string.drive_busy_restore)) {
        // Not runCatching: a failed record restore is the whole point of this
        // action, and swallowing it made an empty database look like success.
        val restored = appModule.syncEngine.pullDatabaseSnapshot()
        val report = appModule.syncEngine.sync(SyncScope.FULL, ConflictPolicy.PREFER_REMOTE)
        _uiState.value = _uiState.value.copy(lastReport = report)
        val settings = drive.current()
        scheduler.kickTransfers(
            wifiOnly = settings.syncOnWifiOnly,
            chargingOnly = settings.syncWhileChargingOnly,
            ignoreConstraints = true
        )
        message(
            if (restored == null) {
                text(R.string.drive_msg_no_records, report.downloadsQueued)
            } else {
                text(R.string.drive_msg_restored_queued, restored.rowCount, report.downloadsQueued)
            }
        )
    }

    fun clearLogs() {
        DriveLog.clear()
    }

    fun dismissMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    private suspend fun refreshUsage() {
        val local = runCatching { appModule.storageRegistry.local.usage() }.getOrNull()
        val remote = runCatching { appModule.telegramStorageProvider.usage() }.getOrNull()
        _uiState.value = _uiState.value.copy(localUsage = local, driveUsage = remote)
    }

    private fun message(text: String) {
        _uiState.value = _uiState.value.copy(message = text)
    }

    private fun launchWith(label: String?, block: suspend () -> Unit) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(busyLabel = label)
            runCatching { block() }.onFailure { error ->
                DriveLog.e(TAG, "Settings action failed", error)
                message(error.message ?: text(R.string.drive_err_action))
            }
            _uiState.value = _uiState.value.copy(busyLabel = null)
        }
    }

    companion object {
        private const val TAG = "DriveSettingsVM"
        private const val HISTORY_LIMIT = 50

        fun provideFactory(
            appModule: AppModule,
            applicationContext: Context
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                TelegramDriveSettingsViewModel(appModule, applicationContext) as T
        }
    }
}

/** Shared by setup and settings: seals the key with [passphrase] and maps the result. */
internal suspend fun savePassphraseOutcome(appModule: AppModule, passphrase: String): PassphraseOutcome {
    if (passphrase.trim().length < PassphraseKeyBackup.MIN_LENGTH) return PassphraseOutcome.TOO_SHORT
    return runCatching { appModule.passphraseKeyBackup.setPassphrase(passphrase) }
        .fold(
            onSuccess = { PassphraseOutcome.SAVED },
            onFailure = { error ->
                DriveLog.e("Passphrase", "Saving passphrase failed", error)
                if (error is PassphraseKeyBackup.PassphraseException &&
                    error.message?.contains("another key") == true
                ) PassphraseOutcome.LOCKED_DRIVE else PassphraseOutcome.FAILED
            }
        )
}
