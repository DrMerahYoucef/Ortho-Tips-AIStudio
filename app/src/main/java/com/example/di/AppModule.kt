package com.example.di

import android.content.Context
import com.example.data.backup.BackupManager
import com.example.data.crypto.KeystoreManager
import com.example.data.crypto.KeystoreTdlibKeyProvider
import com.example.data.crypto.StreamCrypto
import com.example.data.crypto.WrappedKeyRepository
import com.example.data.extensions.ClassificationsExtensionHandler
import com.example.data.extensions.ExtensionDatabaseAccess
import com.example.data.extensions.ExtensionHandlerRegistry
import com.example.data.extensions.ExtensionPermission
import com.example.data.extensions.ExtensionRepository
import com.example.data.extensions.HtmlExtensionHandler
import com.example.data.extensions.OperativeGuideExtensionHandler
import com.example.data.extensions.OperativeGuideMigration
import com.example.data.importer.BoneDatabaseImporter
import com.example.data.local.OrthoDatabase
import com.example.data.prefs.PdfOptionsRepository
import com.example.data.prefs.ActivationRepository
import com.example.data.prefs.SettingsRepository
import com.example.data.prefs.SurgeonProfileRepository
import com.example.data.prefs.TelegramDriveRepository
import com.example.data.repository.ClassificationRepository
import com.example.data.repository.OrthoRepository
import com.example.data.repository.PatientRepository
import com.example.data.repository.SuggestionRepository
import com.example.data.storage.LocalStorageProvider
import com.example.data.storage.PatientMediaGateway
import com.example.data.storage.StorageProviderRegistry
import com.example.data.storage.TelegramStorageProvider
import com.example.data.sync.DatabaseSnapshot
import com.example.data.sync.ManifestCodec
import com.example.data.sync.SyncEngine
import com.example.data.telegram.TdLibTelegramClient
import com.example.data.telegram.TelegramClient
import com.example.data.telegram.TdlibDatabaseKeyProvider
import com.example.utils.PatientMediaStorage
import java.io.File

/**
 * The application's object graph.
 *
 * Everything below the "Telegram Drive backend" divider is new; everything above
 * it is unchanged. The Telegram graph is built entirely from `by lazy`
 * properties, so an install that never enables the drive never constructs a
 * TDLib client, never touches the Keystore and never pays a millisecond of
 * startup cost for a feature it does not use.
 */
class AppModule(private val context: Context) {

    val activationRepository: ActivationRepository by lazy {
        ActivationRepository(context)
    }

    val database: OrthoDatabase by lazy {
        OrthoDatabase.getInstance(context)
    }

    private val importedFiguresDir: File by lazy {
        File(context.filesDir, BoneDatabaseImporter.IMAGES_DIR_NAME)
    }

    val orthoRepository: OrthoRepository by lazy {
        OrthoRepository(database, importedFiguresDir)
    }

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(context)
    }

    val backupManager: BackupManager by lazy {
        BackupManager(context, database)
    }

    val boneDatabaseImporter: BoneDatabaseImporter by lazy {
        BoneDatabaseImporter(context, database)
    }

    // Classification reference, read from the installed classification extensions
    val classificationRepository: ClassificationRepository by lazy {
        ClassificationRepository(extensionRepository)
    }

    // Extensions: .OTips packages (see docs/extensions/guide.md). Each kind the
    // app can install is one handler here; adding a kind adds one line.
    val extensionRepository: ExtensionRepository by lazy {
        ExtensionRepository(
            context = context,
            // Deleted, data cleared, or updated without `notifications`: its reminders go.
            onExtensionEvent = { id, granted ->
                if (granted == null || ExtensionPermission.NOTIFICATIONS !in granted) extensionReminders.cancelAll(id)
                if (granted == null || ExtensionPermission.BACKGROUND !in granted) {
                    com.example.ui.screens.extensions.ExtensionMedia.stopFor(context, id)
                }
            },
            onExtensionsChanged = { list ->
                bankSmsInbox.setConsumers(list.filter { it.has(ExtensionPermission.SMS) }.map { it.id })
            },
            handlers = ExtensionHandlerRegistry(
                listOf(
                    HtmlExtensionHandler(),
                    OperativeGuideExtensionHandler { orthoRepository },
                    ClassificationsExtensionHandler()
                )
            )
        )
    }

    /** Moves operative guide data from before the extension system into its extension. */
    val operativeGuideMigration: OperativeGuideMigration by lazy {
        OperativeGuideMigration(extensionRepository, orthoRepository)
    }

    /** Drafts of the classification editor; published as classification extensions. */
    val classificationDraftRepository: com.example.data.classification.ClassificationDraftRepository by lazy {
        com.example.data.classification.ClassificationDraftRepository(context, extensionRepository)
    }

    /** Bank movements from SMS for extensions holding the `sms` permission. */
    val bankSmsInbox: com.example.data.sms.BankSmsInbox by lazy {
        com.example.data.sms.BankSmsInbox(context)
    }

    /** Reminders scheduled by extensions (`notifications` permission), shown as Android notifications. */
    val extensionReminders: com.example.data.reminders.ExtensionReminders by lazy {
        com.example.data.reminders.ExtensionReminders(context) { id -> extensionRepository.get(id) }
    }

    /** Gemini (Réglages → Gemini) and the patient AI assistant. */
    val geminiService: com.example.data.ai.GeminiService by lazy { com.example.data.ai.GeminiService(context) }

    /** Patient records for extensions holding the `patients` permission (never deletion). */
    val extensionPatients: com.example.data.extensions.ExtensionPatients by lazy {
        com.example.data.extensions.ExtensionPatients(context, patientRepository)
    }

    /** Read-only reference data for extensions holding the `database` permission. */
    val extensionDatabaseAccess: ExtensionDatabaseAccess by lazy {
        ExtensionDatabaseAccess(database)
    }

    // Patient management module
    private val patientMediaRootDir: File by lazy {
        PatientMediaStorage.rootDir(context)
    }

    val patientRepository: PatientRepository by lazy {
        PatientRepository(database, patientMediaRootDir, patientMediaGateway, context)
    }

    val surgeonProfileRepository: SurgeonProfileRepository by lazy {
        SurgeonProfileRepository(context)
    }

    val pdfOptionsRepository: PdfOptionsRepository by lazy {
        PdfOptionsRepository(context)
    }

    val shiftRepository: com.example.data.repository.ShiftRepository by lazy {
        com.example.data.repository.ShiftRepository(database, context)
    }

    val suggestionRepository: SuggestionRepository by lazy {
        SuggestionRepository(database, settingsRepository)
    }

    // ---- Telegram Drive backend ---------------------------------------------

    val keystoreManager: KeystoreManager by lazy { KeystoreManager() }

    val wrappedKeyRepository: WrappedKeyRepository by lazy {
        WrappedKeyRepository(context, keystoreManager)
    }

    val streamCrypto: StreamCrypto by lazy { StreamCrypto() }

    private val tdlibDatabaseKeyProvider: TdlibDatabaseKeyProvider by lazy {
        KeystoreTdlibKeyProvider(wrappedKeyRepository)
    }

    val telegramDriveRepository: TelegramDriveRepository by lazy {
        TelegramDriveRepository(context, keystoreManager)
    }

    val telegramClient: TelegramClient by lazy {
        TdLibTelegramClient(context, tdlibDatabaseKeyProvider)
    }

    val manifestCodec: ManifestCodec by lazy {
        ManifestCodec(streamCrypto, wrappedKeyRepository)
    }

    /** Passphrase-sealed copy of the content key, kept in the drive channel. */
    val passphraseKeyBackup: com.example.data.crypto.PassphraseKeyBackup by lazy {
        com.example.data.crypto.PassphraseKeyBackup(
            client = telegramClient,
            drive = telegramDriveRepository,
            wrappedKeys = wrappedKeyRepository,
            manifestCodec = manifestCodec,
            workDir = File(context.cacheDir, "key-backup")
        )
    }

    /**
     * The local provider resolves keys against `filesDir`, which is exactly where
     * [PatientMediaStorage] already writes. That identity is deliberate: existing
     * installs keep reading and writing the same bytes with no migration.
     */
    val localStorageProvider: LocalStorageProvider by lazy {
        LocalStorageProvider(context, context.filesDir)
    }

    val telegramStorageProvider: TelegramStorageProvider by lazy {
        TelegramStorageProvider(
            context = context,
            client = telegramClient,
            remoteObjectDao = database.remoteObjectDao(),
            manifestCodec = manifestCodec,
            streamCrypto = streamCrypto,
            wrappedKeys = wrappedKeyRepository,
            driveRepository = telegramDriveRepository
        )
    }

    val storageRegistry: StorageProviderRegistry by lazy {
        StorageProviderRegistry(
            local = localStorageProvider,
            telegram = telegramStorageProvider,
            driveRepository = telegramDriveRepository
        )
    }

    val databaseSnapshot: DatabaseSnapshot by lazy {
        DatabaseSnapshot(database) {
            com.example.widget.OrthoWidgetProvider.refreshAll(context)
        }
    }

    private val snapshotWorkDir: File by lazy {
        File(context.cacheDir, "snapshots").also { it.mkdirs() }
    }

    /**
     * Routes patient media through the storage abstraction. Constructed lazily
     * like everything else here, so it costs nothing until a record is saved.
     */
    val patientMediaGateway: PatientMediaGateway by lazy {
        PatientMediaGateway(
            context = context,
            registry = storageRegistry,
            syncEngine = syncEngine,
            remoteObjectDao = database.remoteObjectDao(),
            driveRepository = telegramDriveRepository
        )
    }

    val syncEngine: SyncEngine by lazy {
        SyncEngine(
            registry = storageRegistry,
            telegram = telegramStorageProvider,
            local = localStorageProvider,
            client = telegramClient,
            remoteObjectDao = database.remoteObjectDao(),
            transferQueueDao = database.transferQueueDao(),
            tombstoneDao = database.syncTombstoneDao(),
            manifestCodec = manifestCodec,
            databaseSnapshot = databaseSnapshot,
            driveRepository = telegramDriveRepository,
            localRootDir = context.filesDir,
            snapshotWorkDir = snapshotWorkDir
        )
    }
}
