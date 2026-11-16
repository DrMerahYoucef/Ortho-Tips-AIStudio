package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.local.dao.BoneDao
import com.example.data.local.dao.ChapterDao
import com.example.data.local.dao.ContentDao
import com.example.data.local.dao.ImageDao
import com.example.data.local.dao.PatientDao
import com.example.data.local.dao.PatientDocumentDao
import com.example.data.local.dao.PatientImageDao
import com.example.data.local.dao.RemoteObjectDao
import com.example.data.local.dao.SearchDao
import com.example.data.local.dao.SuggestionDao
import com.example.data.local.dao.ShiftDao
import com.example.data.local.dao.SurgeryDao
import com.example.data.local.dao.SyncTombstoneDao
import com.example.data.local.dao.TimelineEventDao
import com.example.data.local.dao.TransferQueueDao
import com.example.data.local.dao.UserDataDao
import com.example.data.local.entity.BookmarkEntity
import com.example.data.local.entity.BoneEntity
import com.example.data.local.entity.ChapterEntity
import com.example.data.local.entity.FieldPairEntity
import com.example.data.local.entity.FieldSuggestionEntity
import com.example.data.local.entity.ImageEntity
import com.example.data.local.entity.NoteEntity
import com.example.data.local.entity.PatientDocumentEntity
import com.example.data.local.entity.PatientEntity
import com.example.data.local.entity.PatientImageEntity
import com.example.data.local.entity.RemoteObjectEntity
import com.example.data.local.entity.SectionEntity
import com.example.data.local.entity.ShiftEntity
import com.example.data.local.entity.SurgeryEntity
import com.example.data.local.entity.SyncTombstoneEntity
import com.example.data.local.entity.TimelineEventEntity
import com.example.data.local.entity.TransferQueueEntity

@Database(
    entities = [
        ChapterEntity::class,
        BoneEntity::class,
        SectionEntity::class,
        ImageEntity::class,
        BookmarkEntity::class,
        NoteEntity::class,
        PatientEntity::class,
        TimelineEventEntity::class,
        SurgeryEntity::class,
        PatientImageEntity::class,
        PatientDocumentEntity::class,
        FieldSuggestionEntity::class,
        FieldPairEntity::class,
        // Telegram Drive backend (schema v5, additive only)
        RemoteObjectEntity::class,
        TransferQueueEntity::class,
        SyncTombstoneEntity::class,
        ShiftEntity::class
    ],
    version = 10,
    exportSchema = true
)
abstract class OrthoDatabase : RoomDatabase() {
    abstract fun chapterDao(): ChapterDao

    abstract fun shiftDao(): ShiftDao
    abstract fun boneDao(): BoneDao
    abstract fun contentDao(): ContentDao
    abstract fun imageDao(): ImageDao
    abstract fun userDataDao(): UserDataDao
    abstract fun searchDao(): SearchDao
    abstract fun suggestionDao(): SuggestionDao

    // Patient management module
    abstract fun patientDao(): PatientDao
    abstract fun timelineEventDao(): TimelineEventDao
    abstract fun surgeryDao(): SurgeryDao
    abstract fun patientImageDao(): PatientImageDao
    abstract fun patientDocumentDao(): PatientDocumentDao

    // Telegram Drive backend
    abstract fun remoteObjectDao(): RemoteObjectDao
    abstract fun transferQueueDao(): TransferQueueDao
    abstract fun syncTombstoneDao(): SyncTombstoneDao

    companion object {
        @Volatile
        private var INSTANCE: OrthoDatabase? = null

        /**
         * Adds the patient management tables. Existing clinical reference data
         * (chapters/bones/sections/images/bookmarks/notes) is left untouched.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `patients` (
                        `id` TEXT NOT NULL,
                        `patientCode` TEXT NOT NULL,
                        `fullName` TEXT NOT NULL,
                        `dateOfBirth` INTEGER,
                        `gender` TEXT NOT NULL,
                        `phone` TEXT NOT NULL,
                        `email` TEXT NOT NULL,
                        `address` TEXT NOT NULL,
                        `emergencyContactName` TEXT NOT NULL,
                        `emergencyContactPhone` TEXT NOT NULL,
                        `insuranceProvider` TEXT NOT NULL,
                        `insuranceNumber` TEXT NOT NULL,
                        `medicalHistory` TEXT NOT NULL,
                        `allergies` TEXT NOT NULL,
                        `chronicDiseases` TEXT NOT NULL,
                        `previousSurgeries` TEXT NOT NULL,
                        `medications` TEXT NOT NULL,
                        `generalNotes` TEXT NOT NULL,
                        `diagnosis` TEXT NOT NULL,
                        `bodyRegion` TEXT NOT NULL,
                        `side` TEXT NOT NULL,
                        `injuryDate` INTEGER,
                        `traumaMechanism` TEXT NOT NULL,
                        `fractureClassification` TEXT NOT NULL,
                        `imagingFindings` TEXT NOT NULL,
                        `treatmentPlan` TEXT NOT NULL,
                        `isArchived` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_patients_patientCode` ON `patients` (`patientCode`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_patients_fullName` ON `patients` (`fullName`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_patients_diagnosis` ON `patients` (`diagnosis`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_patients_bodyRegion` ON `patients` (`bodyRegion`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_patients_isArchived` ON `patients` (`isArchived`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `timeline_events` (
                        `id` TEXT NOT NULL,
                        `patientId` TEXT NOT NULL,
                        `eventDate` INTEGER NOT NULL,
                        `title` TEXT NOT NULL,
                        `description` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`patientId`) REFERENCES `patients`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_timeline_events_patientId` ON `timeline_events` (`patientId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_timeline_events_eventDate` ON `timeline_events` (`eventDate`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `surgeries` (
                        `id` TEXT NOT NULL,
                        `patientId` TEXT NOT NULL,
                        `surgeryDate` INTEGER NOT NULL,
                        `hospitalClinic` TEXT NOT NULL,
                        `surgeon` TEXT NOT NULL,
                        `assistants` TEXT NOT NULL,
                        `anesthesiaType` TEXT NOT NULL,
                        `diagnosis` TEXT NOT NULL,
                        `procedure` TEXT NOT NULL,
                        `materialsUsed` TEXT NOT NULL,
                        `complications` TEXT NOT NULL,
                        `postOpInstructions` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`patientId`) REFERENCES `patients`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_surgeries_patientId` ON `surgeries` (`patientId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_surgeries_surgeryDate` ON `surgeries` (`surgeryDate`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `patient_images` (
                        `id` TEXT NOT NULL,
                        `patientId` TEXT NOT NULL,
                        `timelineEventId` TEXT,
                        `surgeryId` TEXT,
                        `category` TEXT NOT NULL,
                        `filePath` TEXT NOT NULL,
                        `thumbnailPath` TEXT,
                        `ocrText` TEXT NOT NULL,
                        `annotationsJson` TEXT NOT NULL,
                        `notes` TEXT NOT NULL,
                        `capturedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`patientId`) REFERENCES `patients`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`timelineEventId`) REFERENCES `timeline_events`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL,
                        FOREIGN KEY(`surgeryId`) REFERENCES `surgeries`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_patient_images_patientId` ON `patient_images` (`patientId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_patient_images_timelineEventId` ON `patient_images` (`timelineEventId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_patient_images_surgeryId` ON `patient_images` (`surgeryId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_patient_images_category` ON `patient_images` (`category`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `patient_documents` (
                        `id` TEXT NOT NULL,
                        `patientId` TEXT NOT NULL,
                        `timelineEventId` TEXT,
                        `surgeryId` TEXT,
                        `title` TEXT NOT NULL,
                        `filePath` TEXT NOT NULL,
                        `mimeType` TEXT NOT NULL,
                        `ocrText` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`patientId`) REFERENCES `patients`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`timelineEventId`) REFERENCES `timeline_events`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL,
                        FOREIGN KEY(`surgeryId`) REFERENCES `surgeries`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_patient_documents_patientId` ON `patient_documents` (`patientId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_patient_documents_timelineEventId` ON `patient_documents` (`timelineEventId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_patient_documents_surgeryId` ON `patient_documents` (`surgeryId`)")
            }
        }

        /**
         * Adds the operative-protocol field to surgeries and the learned-suggestions
         * table backing the smart autocomplete inputs. Nothing else is touched.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE surgeries ADD COLUMN operativeProtocol TEXT NOT NULL DEFAULT ''")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `field_suggestions` (
                        `fieldKey` TEXT NOT NULL,
                        `value` TEXT NOT NULL,
                        `useCount` INTEGER NOT NULL,
                        `lastUsedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`fieldKey`, `value`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_field_suggestions_fieldKey` ON `field_suggestions` (`fieldKey`)")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Ensure table integrity between versions
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `field_suggestions` (
                        `fieldKey` TEXT NOT NULL,
                        `value` TEXT NOT NULL,
                        `useCount` INTEGER NOT NULL,
                        `lastUsedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`fieldKey`, `value`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_field_suggestions_fieldKey` ON `field_suggestions` (`fieldKey`)")
            }
        }

        /**
         * Telegram Drive backend. Purely additive: three new tables and their
         * indices, no ALTER on anything that already holds patient data. An
         * install that never enables Telegram Drive simply leaves them empty.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `remote_objects` (
                        `storageKey` TEXT NOT NULL,
                        `kind` TEXT NOT NULL,
                        `chatId` INTEGER NOT NULL,
                        `messageId` INTEGER NOT NULL,
                        `remoteFileId` TEXT NOT NULL,
                        `uniqueFileId` TEXT NOT NULL,
                        `sizeBytes` INTEGER NOT NULL,
                        `contentHash` TEXT,
                        `mimeType` TEXT NOT NULL,
                        `encrypted` INTEGER NOT NULL,
                        `modifiedAt` INTEGER NOT NULL,
                        `syncedAt` INTEGER NOT NULL,
                        `deletedAt` INTEGER,
                        `revision` INTEGER NOT NULL,
                        PRIMARY KEY(`storageKey`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_remote_objects_uniqueFileId` ON `remote_objects` (`uniqueFileId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_remote_objects_kind` ON `remote_objects` (`kind`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_remote_objects_deletedAt` ON `remote_objects` (`deletedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_remote_objects_contentHash` ON `remote_objects` (`contentHash`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `transfer_queue` (
                        `id` INTEGER NOT NULL,
                        `storageKey` TEXT NOT NULL,
                        `direction` TEXT NOT NULL,
                        `localPath` TEXT NOT NULL,
                        `kind` TEXT NOT NULL,
                        `mimeType` TEXT NOT NULL,
                        `state` TEXT NOT NULL,
                        `attemptCount` INTEGER NOT NULL,
                        `nextAttemptAt` INTEGER NOT NULL,
                        `bytesTransferred` INTEGER NOT NULL,
                        `totalBytes` INTEGER NOT NULL,
                        `lastError` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transfer_queue_state` ON `transfer_queue` (`state`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transfer_queue_storageKey` ON `transfer_queue` (`storageKey`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transfer_queue_nextAttemptAt` ON `transfer_queue` (`nextAttemptAt`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `sync_tombstones` (
                        `storageKey` TEXT NOT NULL,
                        `deletedAt` INTEGER NOT NULL,
                        `reason` TEXT NOT NULL,
                        `propagated` INTEGER NOT NULL,
                        PRIMARY KEY(`storageKey`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_tombstones_propagated` ON `sync_tombstones` (`propagated`)")
            }
        }

        /**
         * Learned field associations for smarter input. Additive: one new table,
         * nothing existing is altered.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `field_pairs` (
                        `sourceField` TEXT NOT NULL,
                        `sourceValue` TEXT NOT NULL,
                        `targetField` TEXT NOT NULL,
                        `targetValue` TEXT NOT NULL,
                        `count` INTEGER NOT NULL,
                        `lastUsedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`sourceField`, `sourceValue`, `targetField`, `targetValue`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_field_pairs_sourceField_sourceValue_targetField` " +
                        "ON `field_pairs` (`sourceField`, `sourceValue`, `targetField`)"
                )
            }
        }

        /**
         * French text for the reference library. Additive: nullable columns only,
         * so an existing English-only database keeps working untouched and every
         * row simply has NULL where a translation would go.
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf(
                    "ALTER TABLE chapters ADD COLUMN titleFr TEXT",
                    "ALTER TABLE chapters ADD COLUMN descriptionFr TEXT",
                    "ALTER TABLE bones ADD COLUMN nameFr TEXT",
                    "ALTER TABLE bones ADD COLUMN subtitleFr TEXT",
                    "ALTER TABLE bones ADD COLUMN regionFr TEXT",
                    "ALTER TABLE bones ADD COLUMN classificationSummaryFr TEXT",
                    "ALTER TABLE sections ADD COLUMN titleFr TEXT",
                    "ALTER TABLE sections ADD COLUMN contentFr TEXT",
                    "ALTER TABLE sections ADD COLUMN pearlsFr TEXT",
                    "ALTER TABLE sections ADD COLUMN warningsFr TEXT",
                    "ALTER TABLE images ADD COLUMN titleFr TEXT",
                    "ALTER TABLE images ADD COLUMN descriptionFr TEXT"
                ).forEach(db::execSQL)
            }
        }

        /** Videos join the patient media table. */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf(
                    "ALTER TABLE patient_images ADD COLUMN mediaType TEXT NOT NULL DEFAULT 'IMAGE'",
                    "ALTER TABLE patient_images ADD COLUMN durationMs INTEGER NOT NULL DEFAULT 0"
                ).forEach(db::execSQL)
            }
        }

        /** On-call days, shown in the app and on the home-screen widget. */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS shifts (" +
                        "date TEXT NOT NULL PRIMARY KEY, " +
                        "label TEXT NOT NULL DEFAULT '', " +
                        "createdAt INTEGER NOT NULL DEFAULT 0)"
                )
            }
        }

        /** Attendance and a short remark on each appointment. */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf(
                    "ALTER TABLE timeline_events ADD COLUMN attendance TEXT NOT NULL DEFAULT 'UNKNOWN'",
                    "ALTER TABLE timeline_events ADD COLUMN remark TEXT NOT NULL DEFAULT ''"
                ).forEach(db::execSQL)
            }
        }

        fun getInstance(context: Context): OrthoDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): OrthoDatabase {
            val db = Room.databaseBuilder(
                context.applicationContext,
                OrthoDatabase::class.java,
                "ortho_tips.db"
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
                .fallbackToDestructiveMigration(true)
                .fallbackToDestructiveMigrationOnDowngrade(true)
                .build()

            return try {
                // Eagerly verify SQLite connectivity and schema integrity
                db.openHelper.writableDatabase
                db
            } catch (e: Throwable) {
                // If any schema mismatch or database corruption exists on disk,
                // safely wipe the invalid database and re-create it cleanly
                try {
                    db.close()
                } catch (_: Throwable) {
                }
                context.applicationContext.deleteDatabase("ortho_tips.db")
                val cleanDb = Room.databaseBuilder(
                    context.applicationContext,
                    OrthoDatabase::class.java,
                    "ortho_tips.db"
                )
                    .fallbackToDestructiveMigration(true)
                    .fallbackToDestructiveMigrationOnDowngrade(true)
                    .build()
                cleanDb.openHelper.writableDatabase
                cleanDb
            }
        }
    }
}
