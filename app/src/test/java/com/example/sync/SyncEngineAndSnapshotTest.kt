package com.example.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.crypto.CryptoKeys
import com.example.data.crypto.Hashing
import com.example.data.crypto.KeystoreManager
import com.example.data.crypto.StreamCrypto
import com.example.data.crypto.WrappedKeyRepository
import com.example.data.local.OrthoDatabase
import com.example.data.local.entity.Attendance
import com.example.data.local.entity.PatientEntity
import com.example.data.local.entity.RemoteObjectEntity
import com.example.data.local.entity.SurgeryEntity
import com.example.data.local.entity.TimelineEventEntity
import com.example.data.local.entity.TransferDirection
import com.example.data.local.entity.TransferQueueEntity
import com.example.data.local.entity.TransferState
import com.example.data.prefs.CachePolicy
import com.example.data.prefs.TelegramDriveRepository
import com.example.data.repository.PatientRepository
import com.example.data.storage.LocalStorageProvider
import com.example.data.storage.StorageBackend
import com.example.data.storage.StorageKey
import com.example.data.storage.StorageObjectKind
import com.example.data.storage.TelegramStorageProvider
import com.example.data.sync.ConflictPolicy
import com.example.data.sync.DatabaseSnapshot
import com.example.data.sync.DriveManifest
import com.example.data.sync.ManifestCodec
import com.example.data.sync.SyncEngine
import com.example.data.sync.SyncScope
import com.example.data.telegram.RemoteDocument
import com.example.data.telegram.RemoteDocumentPage
import com.example.data.telegram.StorageChannel
import com.example.data.telegram.TelegramAuthState
import com.example.data.telegram.TelegramClient
import com.example.data.telegram.TelegramConnectionState
import com.example.data.telegram.TelegramCredentials
import com.example.data.telegram.TelegramDownloadEvent
import com.example.data.telegram.TelegramLimits
import com.example.data.telegram.TelegramUploadEvent
import com.example.data.telegram.TelegramUser
import com.example.utils.PatientExportBundle
import com.example.utils.PatientZipExporter
import com.example.utils.PatientZipImporter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SyncEngineAndSnapshotTest {

    private lateinit var context: Context
    private lateinit var db: OrthoDatabase
    private lateinit var testRoot: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, OrthoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        testRoot = File(context.cacheDir, "sync-test-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        db.close()
        testRoot.deleteRecursively()
        File(context.filesDir, "patients").deleteRecursively()
        File(context.filesDir, ".trash").deleteRecursively()
    }

    @Test
    fun `snapshot contentHash is deterministic across timestamps and triggers import callback`() = runBlocking {
        val importCallbacks = AtomicInteger(0)
        val snapshot = DatabaseSnapshot(db) { importCallbacks.incrementAndGet() }

        db.patientDao().insertPatient(
            PatientEntity(id = "p-1", patientCode = "C-101", fullName = "Alice Ortho")
        )

        val firstFile = File(testRoot, "snap1.json")
        val secondFile = File(testRoot, "snap2.json")
        val first = snapshot.export(firstFile)
        Thread.sleep(5)
        val second = snapshot.export(secondFile)

        assertTrue(first.contentHash.isNotEmpty())
        assertEquals(first.contentHash, second.contentHash)

        // Modifying a column must change the deterministic hash.
        db.patientDao().insertPatient(
            PatientEntity(id = "p-1", patientCode = "C-101", fullName = "Alice Edited")
        )
        val third = snapshot.export(File(testRoot, "snap3.json"))
        assertNotEquals(first.contentHash, third.contentHash)

        // Import into a fresh in-memory DB should restore rows and invoke callback.
        val targetDb = Room.inMemoryDatabaseBuilder(context, OrthoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val targetSnapshot = DatabaseSnapshot(targetDb) { importCallbacks.incrementAndGet() }
            val imported = targetSnapshot.import(firstFile)
            assertEquals(1, imported.rowCount)
            assertEquals(1, importCallbacks.get())
            assertEquals("Alice Ortho", targetDb.patientDao().getPatientDirect("p-1")?.fullName)
        } finally {
            targetDb.close()
        }
    }

    @Test
    fun `patient child mutations touch owning patient updatedAt`() = runBlocking {
        val repo = PatientRepository(db)
        val initialTime = 1_000L
        db.patientDao().insertPatient(
            PatientEntity(
                id = "p-dirty",
                patientCode = "D-1",
                fullName = "Dirty Tracked",
                updatedAt = initialTime
            )
        )

        repo.saveTimelineEvent(
            TimelineEventEntity(
                id = "evt-1",
                patientId = "p-dirty",
                eventDate = 5_000L,
                title = "Follow-up"
            )
        )
        val afterEvent = db.patientDao().getPatientDirect("p-dirty")!!.updatedAt
        assertTrue("saveTimelineEvent should bump patient.updatedAt", afterEvent > initialTime)

        db.patientDao().touchUpdatedAt("p-dirty", 2_000L)
        repo.setAttendance("evt-1", Attendance.ATTENDED)
        val afterAttendance = db.patientDao().getPatientDirect("p-dirty")!!.updatedAt
        assertTrue("setAttendance should bump patient.updatedAt", afterAttendance > 2_000L)

        db.patientDao().touchUpdatedAt("p-dirty", 3_000L)
        repo.saveSurgery(
            SurgeryEntity(
                id = "surg-1",
                patientId = "p-dirty",
                surgeryDate = 6_000L,
                procedure = "ORIF"
            )
        )
        val afterSurgery = db.patientDao().getPatientDirect("p-dirty")!!.updatedAt
        assertTrue("saveSurgery should bump patient.updatedAt", afterSurgery > 3_000L)
    }

    @Test
    fun `zip export and import round trips attendance remark and operativeProtocol`() = runBlocking {
        val repo = PatientRepository(db)
        val patient = PatientEntity(
            id = "zip-p1",
            patientCode = "ZIP-001",
            fullName = "Zip Patient"
        )
        val event = TimelineEventEntity(
            id = "zip-e1",
            patientId = patient.id,
            eventDate = 10_000L,
            title = "Post-op Day 14",
            attendance = Attendance.MISSED.name,
            remark = "Rescheduled by phone"
        )
        val surgery = SurgeryEntity(
            id = "zip-s1",
            patientId = patient.id,
            surgeryDate = 8_000L,
            procedure = "IM Nail",
            operativeProtocol = "1. Closed reduction\n2. Proximal locking"
        )

        val zipFile = File(testRoot, "patient.zip")
        PatientZipExporter.export(
            context,
            listOf(PatientExportBundle(patient, listOf(event), listOf(surgery), emptyList(), emptyList())),
            zipFile
        )

        var doneProgress = false
        PatientZipImporter.import(context, repo, zipFile) { p ->
            if (p.isComplete) doneProgress = true
        }
        assertTrue(doneProgress)

        val restoredPatient = db.patientDao().getPatientsByIds(db.patientDao().getAllPatientIds())
            .first { it.patientCode == "ZIP-001" }
        val detail = db.timelineEventDao()
        // Query via patientId using repository direct queries
        val importedSurgeries = db.openHelper.readableDatabase.query(
            "SELECT operativeProtocol FROM surgeries WHERE patientId = ?",
            arrayOf(restoredPatient.id)
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getString(0)
        }
        assertEquals("1. Closed reduction\n2. Proximal locking", importedSurgeries)

        val (att, rem) = db.openHelper.readableDatabase.query(
            "SELECT attendance, remark FROM timeline_events WHERE patientId = ?",
            arrayOf(restoredPatient.id)
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getString(0) to cursor.getString(1)
        }
        assertEquals(Attendance.MISSED.name, att)
        assertEquals("Rescheduled by phone", rem)
    }

    @Test
    fun `passphrase inspectDrive continues across non-document pages`() = runBlocking {
        val chatId = 777L
        val fakeClient = FakeTelegramClient(testRoot)
        val keystore = KeystoreManager()
        val driveRepo = TelegramDriveRepository(context, keystore).apply {
            setChatId(chatId)
        }
        val streamCrypto = StreamCrypto()
        val wrappedKeys = WrappedKeyRepository(context, keystore)
        val codec = ManifestCodec(streamCrypto, wrappedKeys)
        val backup = com.example.data.crypto.PassphraseKeyBackup(
            client = fakeClient,
            drive = driveRepo,
            wrappedKeys = wrappedKeys,
            manifestCodec = codec,
            workDir = File(testRoot, "kb").apply { mkdirs() }
        )

        fakeClient.pages[0L] = RemoteDocumentPage(documents = emptyList(), nextFromMessageId = 100L)
        fakeClient.pages[100L] = RemoteDocumentPage(
            documents = listOf(
                RemoteDocument(
                    chatId = chatId,
                    messageId = 90L,
                    remoteFileId = "rf_locked",
                    uniqueFileId = "u_locked",
                    fileName = "locked.otenc",
                    mimeType = "application/octet-stream",
                    sizeBytes = 10L,
                    caption = "ote1:dummy",
                    dateSeconds = 10
                )
            ),
            nextFromMessageId = 0L
        )

        val state = backup.inspectDrive()
        assertEquals(1, state.lockedObjects)
        assertTrue(state.needsUnlock)
    }

    @Test
    fun `sync engine handles revision guards non-doc paging snapshot pull dedup remote deletions and cache policy`() = runBlocking {
        // Test only runs against plain ("ot1:") manifests so AndroidKeyStore is never touched.
        val chatId = 999L
        val fakeClient = FakeTelegramClient(testRoot)
        val stubKeystore = KeystoreManager()
        val driveRepo = TelegramDriveRepository(context, stubKeystore).apply {
            setChatId(chatId)
            setBackend(StorageBackend.TELEGRAM)
            setSetupCompleted(true)
            setEncryptUploads(false)
            setCachePolicy(CachePolicy.HYBRID)
        }
        val streamCrypto = StreamCrypto()
        val wrappedKeys = WrappedKeyRepository(context, stubKeystore)
        val codec = ManifestCodec(streamCrypto, wrappedKeys)
        val localProvider = LocalStorageProvider(context, context.filesDir)
        val telegramProvider = TelegramStorageProvider(
            context = context,
            client = fakeClient,
            remoteObjectDao = db.remoteObjectDao(),
            manifestCodec = codec,
            streamCrypto = streamCrypto,
            wrappedKeys = wrappedKeys,
            driveRepository = driveRepo
        )
        val importCount = AtomicInteger(0)
        val snapshot = DatabaseSnapshot(db) { importCount.incrementAndGet() }
        val engine = SyncEngine(
            registry = com.example.data.storage.StorageProviderRegistry(localProvider, telegramProvider, driveRepo),
            telegram = telegramProvider,
            local = localProvider,
            client = fakeClient,
            remoteObjectDao = db.remoteObjectDao(),
            transferQueueDao = db.transferQueueDao(),
            tombstoneDao = db.syncTombstoneDao(),
            manifestCodec = codec,
            databaseSnapshot = snapshot,
            driveRepository = driveRepo,
            localRootDir = context.filesDir,
            snapshotWorkDir = File(testRoot, "snapwork").apply { mkdirs() }
        )

        // 1. Setup multi-page manifest with non-document page in the middle and rev 2 before rev 1
        val thumbKey = StorageKey.patientThumbnail("p-remote", "img-1")
        val fullKey = StorageKey.patientImage("p-remote", "img-1")
        val deletedKey = StorageKey.patientImage("p-local-del", "img-2")

        // Create local copy + active upload for deletedKey to test remote tombstone cleanup
        val deletedLocal = File(context.filesDir, deletedKey.value).apply {
            parentFile?.mkdirs()
            writeText("to-be-trashed")
        }
        db.transferQueueDao().insert(
            TransferQueueEntity(
                storageKey = deletedKey.value,
                direction = TransferDirection.UPLOAD.name,
                localPath = deletedLocal.absolutePath,
                kind = StorageObjectKind.PATIENT_IMAGE.name,
                mimeType = "image/jpeg",
                state = TransferState.PENDING.name
            )
        )

        val thumbBytes = "thumb-bytes".toByteArray()
        val fullBytesV2 = "full-bytes-v2".toByteArray()
        val fullBytesV1 = "full-bytes-v1".toByteArray()
        val thumbHash = Hashing.sha256(thumbBytes)
        val v2Hash = Hashing.sha256(fullBytesV2)
        val v1Hash = Hashing.sha256(fullBytesV1)

        // Remote snapshot containing a patient from Device A
        val remoteDb = Room.inMemoryDatabaseBuilder(context, OrthoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val snapFile = File(testRoot, "remote-snap.json")
        remoteDb.patientDao().insertPatient(
            PatientEntity(id = "p-from-remote", patientCode = "R-01", fullName = "Remote Synced")
        )
        val snapSummary = DatabaseSnapshot(remoteDb).export(snapFile)
        remoteDb.close()
        val snapKey = StorageKey.snapshot(System.currentTimeMillis() + 5_000L, snapSummary.rowCount)
        val snapBytes = snapFile.readBytes()

        fakeClient.putBlob("rf_thumb", thumbBytes)
        fakeClient.putBlob("rf_full_v2", fullBytesV2)
        fakeClient.putBlob("rf_full_v1", fullBytesV1)
        fakeClient.putBlob("rf_del", "deleted".toByteArray())
        fakeClient.putBlob("rf_snap", snapBytes)

        fakeClient.pages[0L] = RemoteDocumentPage(
            documents = listOf(
                RemoteDocument(
                    chatId = chatId,
                    messageId = 500L,
                    remoteFileId = "rf_snap",
                    uniqueFileId = "u_snap",
                    fileName = "snap.json",
                    mimeType = "application/json",
                    sizeBytes = snapBytes.size.toLong(),
                    caption = codec.encode(
                        DriveManifest(
                            storageKey = snapKey.value,
                            name = snapKey.fileName,
                            kind = StorageObjectKind.DATABASE_SNAPSHOT.name,
                            mimeType = "application/json",
                            sizeBytes = snapBytes.size.toLong(),
                            contentHash = Hashing.sha256(snapBytes),
                            encrypted = false,
                            createdAt = snapSummary.createdAt,
                            modifiedAt = snapSummary.createdAt
                        ),
                        encrypt = false
                    ),
                    dateSeconds = 100
                ),
                RemoteDocument(
                    chatId = chatId,
                    messageId = 400L,
                    remoteFileId = "rf_full_v2",
                    uniqueFileId = "u_full_v2",
                    fileName = "v2.jpg",
                    mimeType = "image/jpeg",
                    sizeBytes = fullBytesV2.size.toLong(),
                    caption = codec.encode(
                        DriveManifest(
                            storageKey = fullKey.value,
                            name = fullKey.fileName,
                            kind = StorageObjectKind.PATIENT_IMAGE.name,
                            mimeType = "image/jpeg",
                            sizeBytes = fullBytesV2.size.toLong(),
                            contentHash = v2Hash,
                            encrypted = false,
                            createdAt = 1000L,
                            modifiedAt = 2000L,
                            revision = 2
                        ),
                        encrypt = false
                    ),
                    dateSeconds = 90
                )
            ),
            nextFromMessageId = 350L
        )
        // Page with 0 documents (e.g. non-document service messages) must NOT stop paging!
        fakeClient.pages[350L] = RemoteDocumentPage(
            documents = emptyList(),
            nextFromMessageId = 300L
        )
        fakeClient.pages[300L] = RemoteDocumentPage(
            documents = listOf(
                RemoteDocument(
                    chatId = chatId,
                    messageId = 250L,
                    remoteFileId = "rf_full_v1",
                    uniqueFileId = "u_full_v1",
                    fileName = "v1.jpg",
                    mimeType = "image/jpeg",
                    sizeBytes = fullBytesV1.size.toLong(),
                    caption = codec.encode(
                        DriveManifest(
                            storageKey = fullKey.value,
                            name = fullKey.fileName,
                            kind = StorageObjectKind.PATIENT_IMAGE.name,
                            mimeType = "image/jpeg",
                            sizeBytes = fullBytesV1.size.toLong(),
                            contentHash = v1Hash,
                            encrypted = false,
                            createdAt = 1000L,
                            modifiedAt = 1000L,
                            revision = 1
                        ),
                        encrypt = false
                    ),
                    dateSeconds = 80
                ),
                RemoteDocument(
                    chatId = chatId,
                    messageId = 200L,
                    remoteFileId = "rf_thumb",
                    uniqueFileId = "u_thumb",
                    fileName = "thumb.jpg",
                    mimeType = "image/jpeg",
                    sizeBytes = thumbBytes.size.toLong(),
                    caption = codec.encode(
                        DriveManifest(
                            storageKey = thumbKey.value,
                            name = thumbKey.fileName,
                            kind = StorageObjectKind.PATIENT_THUMBNAIL.name,
                            mimeType = "image/jpeg",
                            sizeBytes = thumbBytes.size.toLong(),
                            contentHash = thumbHash,
                            encrypted = false,
                            createdAt = 1000L,
                            modifiedAt = 2000L,
                            revision = 1
                        ),
                        encrypt = false
                    ),
                    dateSeconds = 70
                ),
                RemoteDocument(
                    chatId = chatId,
                    messageId = 150L,
                    remoteFileId = "rf_del",
                    uniqueFileId = "u_del",
                    fileName = "del.jpg",
                    mimeType = "image/jpeg",
                    sizeBytes = 7L,
                    caption = codec.encode(
                        DriveManifest(
                            storageKey = deletedKey.value,
                            name = deletedKey.fileName,
                            kind = StorageObjectKind.PATIENT_IMAGE.name,
                            mimeType = "image/jpeg",
                            sizeBytes = 7L,
                            contentHash = "x",
                            encrypted = false,
                            createdAt = 1000L,
                            modifiedAt = 1500L,
                            deletedAt = 3000L,
                            revision = 1
                        ),
                        encrypt = false
                    ),
                    dateSeconds = 60
                )
            ),
            nextFromMessageId = 0L
        )

        // Add local patient too, verifying safe multi-device merge preserves local rows.
        db.patientDao().insertPatient(
            PatientEntity(id = "p-local-only", patientCode = "L-01", fullName = "Local Unpushed")
        )

        val report1 = engine.sync(SyncScope.INCREMENTAL, ConflictPolicy.NEWEST_WINS)
        assertTrue("Sync should succeed: ${report1.errors}", report1.succeeded)
        assertTrue("Should have pulled newer remote snapshot", report1.snapshotPulled)
        assertTrue("Should push merged local+remote snapshot", report1.snapshotPushed)
        assertEquals(1, importCount.get())

        // Both patients exist after safe merge
        assertNotNull(db.patientDao().getPatientDirect("p-from-remote"))
        assertNotNull(db.patientDao().getPatientDirect("p-local-only"))

        // Manifest kept revision 2 (didn't let revision 1 overwrite it across pages)
        val fullEntry = db.remoteObjectDao().get(fullKey.value)
        assertNotNull(fullEntry)
        assertEquals(2, fullEntry?.revision)
        assertEquals(400L, fullEntry?.messageId)

        // Remote deletion moved local file to trash & cancelled active queue row
        assertFalse(deletedLocal.exists())
        assertEquals(null, db.transferQueueDao().findActiveFor(deletedKey.value))

        // HYBRID downloads thumbnail only (not fullKey) on incremental sync
        assertNotNull(db.transferQueueDao().findActiveFor(thumbKey.value))
        assertEquals(null, db.transferQueueDao().findActiveFor(fullKey.value))

        // KEEP_EVERYTHING downloads fullKey too on incremental sync
        driveRepo.setCachePolicy(CachePolicy.KEEP_EVERYTHING)
        val report2 = engine.sync(SyncScope.INCREMENTAL, ConflictPolicy.NEWEST_WINS)
        assertTrue(report2.succeeded)
        // Snapshot dedup: DB unchanged -> no second snapshot upload!
        assertFalse("Snapshot push must deduplicate when tables did not change", report2.snapshotPushed)
        assertNotNull("KEEP_EVERYTHING must enqueue full-size missing media", db.transferQueueDao().findActiveFor(fullKey.value))
    }

    @Test
    fun `snapshot exports and imports strictly patient tables ignoring shifts and atlas`() = runBlocking {
        // Populate shifts + chapter + field suggestion alongside one patient + image.
        val writable = db.openHelper.writableDatabase
        writable.execSQL("INSERT INTO shifts (date, label, createdAt) VALUES ('2026-11-16', 'Night ER', 100)")
        writable.execSQL(
            "INSERT INTO chapters (id, title, description, icon, orderIndex) VALUES ('ch-1', 'Local Chapter', 'Desc', 'bone', 1)"
        )
        writable.execSQL(
            "INSERT INTO field_suggestions (fieldKey, value, useCount, lastUsedAt) VALUES ('diag', 'Colles', 3, 200)"
        )
        db.patientDao().insertPatient(
            PatientEntity(id = "p-only", patientCode = "PO-1", fullName = "Patient Only")
        )
        db.patientImageDao().insertImage(
            com.example.data.local.entity.PatientImageEntity(
                id = "img-only",
                patientId = "p-only",
                filePath = File(context.filesDir, "patients/p-only/images/img-only.jpg").absolutePath,
                thumbnailPath = File(context.filesDir, "patients/p-only/images/thumbs/img-only.jpg").absolutePath
            )
        )

        val snapFile = File(testRoot, "patients-only.json")
        val exported = DatabaseSnapshot(db, context.filesDir).export(snapFile)
        assertEquals(1, exported.patientCount)
        assertEquals(2, exported.rowCount) // 1 patient + 1 image (shifts/chapters/suggestions excluded)

        val json = org.json.JSONObject(snapFile.readText())
        val tablesJson = json.getJSONObject("tables")
        assertFalse(tablesJson.has("shifts"))
        assertFalse(tablesJson.has("chapters"))
        assertFalse(tablesJson.has("field_suggestions"))
        assertTrue(tablesJson.has("patients"))

        // Legacy mixed snapshot from another device (contains shifts + chapters + cross-device filePath).
        val legacySnapFile = File(testRoot, "legacy-mixed.json")
        val legacyRoot = org.json.JSONObject().apply {
            put("format", 1)
            put("createdAt", 5_000L)
            put("tables", org.json.JSONObject().apply {
                put("shifts", org.json.JSONArray().put(org.json.JSONObject().apply {
                    put("date", "2026-11-16")
                    put("label", "REMOTE OVERWRITE ATTEMPT")
                    put("createdAt", 999L)
                }))
                put("chapters", org.json.JSONArray().put(org.json.JSONObject().apply {
                    put("id", "ch-1")
                    put("title", "REMOTE CHAPTER")
                    put("description", "Remote")
                    put("icon", "bone")
                    put("orderIndex", 1)
                }))
                put("patients", org.json.JSONArray().put(org.json.JSONObject().apply {
                    put("id", "p-legacy")
                    put("patientCode", "LEG-1")
                    put("fullName", "Legacy Patient")
                    put("gender", "")
                    put("phone", "")
                    put("email", "")
                    put("address", "")
                    put("emergencyContactName", "")
                    put("emergencyContactPhone", "")
                    put("insuranceProvider", "")
                    put("insuranceNumber", "")
                    put("medicalHistory", "")
                    put("allergies", "")
                    put("chronicDiseases", "")
                    put("previousSurgeries", "")
                    put("medications", "")
                    put("generalNotes", "")
                    put("diagnosis", "")
                    put("bodyRegion", "")
                    put("side", "")
                    put("traumaMechanism", "")
                    put("fractureClassification", "")
                    put("imagingFindings", "")
                    put("treatmentPlan", "")
                    put("isArchived", 0)
                    put("createdAt", 100L)
                    put("updatedAt", 100L)
                }))
                put("patient_images", org.json.JSONArray().put(org.json.JSONObject().apply {
                    put("id", "img-legacy")
                    put("patientId", "p-legacy")
                    put("category", "OTHER")
                    put("filePath", "/data/user/10/other.pkg/files/patients/p-legacy/images/img-legacy.jpg")
                    put("thumbnailPath", "/data/user/10/other.pkg/files/patients/p-legacy/images/thumbs/img-legacy.jpg")
                    put("ocrText", "")
                    put("annotationsJson", "")
                    put("notes", "")
                    put("mediaType", "IMAGE")
                    put("durationMs", 0L)
                    put("capturedAt", 100L)
                }))
            })
        }
        legacySnapFile.writeText(legacyRoot.toString())

        val targetDb = Room.inMemoryDatabaseBuilder(context, OrthoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val targetWritable = targetDb.openHelper.writableDatabase
            targetWritable.execSQL("INSERT INTO shifts (date, label, createdAt) VALUES ('2026-11-16', 'Keep My Shift', 1)")
            targetWritable.execSQL(
                "INSERT INTO chapters (id, title, description, icon, orderIndex) VALUES ('ch-1', 'Keep My Atlas', 'Desc', 'bone', 1)"
            )

            val snapHelper = DatabaseSnapshot(targetDb, context.filesDir)
            val inspected = snapHelper.inspect(legacySnapFile)!!
            assertEquals(1, inspected.patientCount)
            assertEquals(2, inspected.rowCount)

            snapHelper.import(legacySnapFile)

            assertNotNull(targetDb.patientDao().getPatientDirect("p-legacy"))
            val restoredImg = targetDb.patientImageDao().getImageDirect("img-legacy")
            assertEquals(
                File(context.filesDir, "patients/p-legacy/images/img-legacy.jpg").absolutePath,
                restoredImg?.filePath
            )
            val localShiftLabel = targetDb.openHelper.readableDatabase
                .query("SELECT label FROM shifts WHERE date = '2026-11-16'")
                .use { c -> assertTrue(c.moveToFirst()); c.getString(0) }
            assertEquals("Keep My Shift", localShiftLabel)
            val localChapterTitle = targetDb.openHelper.readableDatabase
                .query("SELECT title FROM chapters WHERE id = 'ch-1'")
                .use { c -> assertTrue(c.moveToFirst()); c.getString(0) }
            assertEquals("Keep My Atlas", localChapterTitle)
        } finally {
            targetDb.close()
        }
    }

    @Test
    fun `zero patient device auto restores older patient backup behind newer empty r1475 snapshot and stale watermark`() = runBlocking {
        val chatId = 888L
        val fakeClient = FakeTelegramClient(testRoot)
        val keystore = KeystoreManager()
        val driveRepo = TelegramDriveRepository(context, keystore).apply {
            setChatId(chatId)
            setBackend(StorageBackend.TELEGRAM)
            setSetupCompleted(true)
            setEncryptUploads(false)
            // Simulate device already having pushed/seen timestamp 5000 from an empty atlas snapshot.
            markSnapshotPushed(5000L, "stale-empty-hash")
        }
        val streamCrypto = StreamCrypto()
        val wrappedKeys = WrappedKeyRepository(context, keystore)
        val codec = ManifestCodec(streamCrypto, wrappedKeys)
        val localProvider = LocalStorageProvider(context, context.filesDir)
        val telegramProvider = TelegramStorageProvider(
            context = context,
            client = fakeClient,
            remoteObjectDao = db.remoteObjectDao(),
            manifestCodec = codec,
            streamCrypto = streamCrypto,
            wrappedKeys = wrappedKeys,
            driveRepository = driveRepo
        )
        val monitor = com.example.data.sync.SyncProgressMonitor()
        val snapshot = DatabaseSnapshot(db, context.filesDir)
        val engine = SyncEngine(
            registry = com.example.data.storage.StorageProviderRegistry(localProvider, telegramProvider, driveRepo),
            telegram = telegramProvider,
            local = localProvider,
            client = fakeClient,
            remoteObjectDao = db.remoteObjectDao(),
            transferQueueDao = db.transferQueueDao(),
            tombstoneDao = db.syncTombstoneDao(),
            manifestCodec = codec,
            databaseSnapshot = snapshot,
            driveRepository = driveRepo,
            localRootDir = context.filesDir,
            snapshotWorkDir = File(testRoot, "snapwork2").apply { mkdirs() },
            progressMonitor = monitor
        )

        // Newer legacy snapshot containing 0 patients (atlas/shift only -> r1475).
        val emptyAtlasBytes = org.json.JSONObject().apply {
            put("format", 1)
            put("createdAt", 5000L)
            put("tables", org.json.JSONObject().apply {
                put("shifts", org.json.JSONArray().put(org.json.JSONObject().apply {
                    put("date", "2026-11-16")
                    put("label", "Empty Snap Shift")
                    put("createdAt", 10L)
                }))
                put("patients", org.json.JSONArray())
            })
        }.toString().toByteArray()

        // Older snapshot containing the actual recovered patient.
        val remoteDb = Room.inMemoryDatabaseBuilder(context, OrthoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        remoteDb.patientDao().insertPatient(
            PatientEntity(id = "p-recovered", patientCode = "REC-1", fullName = "Recovered Patient")
        )
        val realSnapFile = File(testRoot, "real-snap.json")
        val realSummary = DatabaseSnapshot(remoteDb, context.filesDir).export(realSnapFile)
        remoteDb.close()
        val realBytes = realSnapFile.readBytes()

        val emptyKey = StorageKey.snapshot(5000L, 1475) // Legacy key without -p0
        val realKey = StorageKey.snapshot(3000L, 1476)
        fakeClient.putBlob("rf_empty", emptyAtlasBytes)
        fakeClient.putBlob("rf_real", realBytes)

        fakeClient.pages[0L] = RemoteDocumentPage(
            documents = listOf(
                RemoteDocument(
                    chatId = chatId,
                    messageId = 200L,
                    remoteFileId = "rf_empty",
                    uniqueFileId = "u_empty",
                    fileName = "empty.json",
                    mimeType = "application/json",
                    sizeBytes = emptyAtlasBytes.size.toLong(),
                    caption = codec.encode(
                        DriveManifest(
                            storageKey = emptyKey.value,
                            name = emptyKey.fileName,
                            kind = StorageObjectKind.DATABASE_SNAPSHOT.name,
                            mimeType = "application/json",
                            sizeBytes = emptyAtlasBytes.size.toLong(),
                            contentHash = Hashing.sha256(emptyAtlasBytes),
                            encrypted = false,
                            createdAt = 5000L,
                            modifiedAt = 5000L
                        ),
                        encrypt = false
                    ),
                    dateSeconds = 200
                ),
                RemoteDocument(
                    chatId = chatId,
                    messageId = 100L,
                    remoteFileId = "rf_real",
                    uniqueFileId = "u_real",
                    fileName = "real.json",
                    mimeType = "application/json",
                    sizeBytes = realBytes.size.toLong(),
                    caption = codec.encode(
                        DriveManifest(
                            storageKey = realKey.value,
                            name = realKey.fileName,
                            kind = StorageObjectKind.DATABASE_SNAPSHOT.name,
                            mimeType = "application/json",
                            sizeBytes = realBytes.size.toLong(),
                            contentHash = Hashing.sha256(realBytes),
                            encrypted = false,
                            createdAt = 3000L,
                            modifiedAt = 3000L
                        ),
                        encrypt = false
                    ),
                    dateSeconds = 100
                )
            ),
            nextFromMessageId = 0L
        )

        // Local device has 0 patients + a local shift that must NOT be overwritten.
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO shifts (date, label, createdAt) VALUES ('2026-11-16', 'My Local Shift', 5)"
        )

        val report = engine.sync(SyncScope.FULL, ConflictPolicy.NEWEST_WINS)
        assertTrue("Sync should succeed: ${report.errors}", report.succeeded)
        assertTrue("Sync must auto-pull the older snapshot with patients", report.snapshotPulled)
        assertEquals("Recovered Patient", db.patientDao().getPatientDirect("p-recovered")?.fullName)

        // Monitor tracks restoration explanation + stays visible until dismissed.
        val status = monitor.status.value
        assertEquals(1, status.restoredPatientCount)
        assertTrue(status.explanations.any {
            it.code == com.example.data.sync.ExplanationCode.AUTO_RESTORED_PATIENTS
        })
        assertFalse(status.dismissed)
        monitor.dismiss()
        assertTrue(monitor.status.value.dismissed)
        monitor.onSyncStarted(SyncScope.INCREMENTAL, 1)
        assertFalse("New sync run must reopen dismissed banner", monitor.status.value.dismissed)
    }

    @Test
    fun `storage key snapshot parser and diagnostic report formatting support v2 and v3 keys`() {
        val v3 = StorageKey.snapshot(1234L, rowCount = 12, patientCount = 3)
        assertEquals(12, StorageKey.snapshotRowCount(v3))
        assertEquals(3, StorageKey.snapshotPatientCount(v3))

        val v2 = StorageKey.snapshot(1234L, rowCount = 1475)
        assertEquals(1475, StorageKey.snapshotRowCount(v2))
        assertEquals(null, StorageKey.snapshotPatientCount(v2))

        val resolved = com.example.ui.components.buildResolvedExplanations(
            live = com.example.data.sync.SyncLiveStatus(),
            failedTransfers = 2,
            lastSyncError = ""
        )
        assertTrue(resolved.any { it.code == com.example.data.sync.ExplanationCode.TRANSFER_FAILURES })

        val dump = com.example.ui.components.buildSyncDiagnosticReport(
            context = context,
            live = com.example.data.sync.SyncLiveStatus(
                phase = com.example.data.sync.SyncPhase.COMPLETED,
                scope = SyncScope.FULL,
                manifestRowsSeen = 31
            ),
            totalPatients = 3,
            pendingUploads = 1,
            pendingDownloads = 2,
            failedTransfers = 0,
            explanations = resolved,
            logsDump = "18:40:59 I/SyncEngine: Sync full done"
        )
        assertTrue(dump.contains("patients & patient media only"))
        assertTrue(dump.contains("18:40:59 I/SyncEngine: Sync full done"))
    }

    @Test
    fun `default wifi policy allows mobile data and ignoreConstraints overrides background limits`() {
        val defaults = com.example.data.prefs.TelegramDriveSettings()
        assertFalse("syncOnWifiOnly should default to false so 5G transfers run automatically", defaults.syncOnWifiOnly)

        val constrained = com.example.work.SyncScheduler.buildTransferConstraints(
            wifiOnly = true,
            chargingOnly = true,
            ignoreConstraints = false
        )
        assertEquals(androidx.work.NetworkType.UNMETERED, constrained.requiredNetworkType)
        assertTrue(constrained.requiresCharging())

        val immediate = com.example.work.SyncScheduler.buildTransferConstraints(
            wifiOnly = true,
            chargingOnly = true,
            ignoreConstraints = true
        )
        assertEquals(androidx.work.NetworkType.CONNECTED, immediate.requiredNetworkType)
        assertFalse(immediate.requiresCharging())
    }

    @Test
    fun `monitor transfer batch lifecycle and queue pause explanations diagnose wait reasons cleanly`() {
        val monitor = com.example.data.sync.SyncProgressMonitor()
        assertFalse(monitor.status.value.isTransferring)

        monitor.onTransferBatchStarted()
        assertTrue(monitor.status.value.isTransferring)

        monitor.onTransferItemProgress("patients/p1/images/x.jpg", 0.45f)
        assertEquals("patients/p1/images/x.jpg", monitor.status.value.currentTransferKey)
        assertEquals(0.45f, monitor.status.value.currentTransferFraction, 0.001f)

        monitor.onTransferBatchFinished(nextRetryAt = 123_456L)
        assertFalse(monitor.status.value.isTransferring)
        assertEquals(null, monitor.status.value.currentTransferKey)
        assertEquals(123_456L, monitor.status.value.nextRetryAt)

        val wifiSettings = com.example.data.prefs.TelegramDriveSettings(syncOnWifiOnly = true)
        val cellularEnv = com.example.ui.components.QueueEnvironment(
            hasInternet = true,
            hasUnmeteredWifi = false,
            isCharging = false,
            telegramReady = true
        )
        val wifiPaused = com.example.ui.components.detectQueuePauseExplanation(
            settings = wifiSettings,
            pendingCount = 4,
            activeTransfers = emptyList(),
            environment = cellularEnv,
            nowMillis = 1_000L
        )
        assertEquals(com.example.data.sync.ExplanationCode.TRANSFERS_WAITING_WIFI, wifiPaused?.code)

        val chargingPaused = com.example.ui.components.detectQueuePauseExplanation(
            settings = com.example.data.prefs.TelegramDriveSettings(syncOnWifiOnly = false, syncWhileChargingOnly = true),
            pendingCount = 2,
            activeTransfers = emptyList(),
            environment = cellularEnv,
            nowMillis = 1_000L
        )
        assertEquals(com.example.data.sync.ExplanationCode.TRANSFERS_WAITING_CHARGING, chargingPaused?.code)

        val offlinePaused = com.example.ui.components.detectQueuePauseExplanation(
            settings = com.example.data.prefs.TelegramDriveSettings(),
            pendingCount = 2,
            activeTransfers = emptyList(),
            environment = cellularEnv.copy(hasInternet = false),
            nowMillis = 1_000L
        )
        assertEquals(com.example.data.sync.ExplanationCode.TRANSFERS_OFFLINE, offlinePaused?.code)

        val sessionPaused = com.example.ui.components.detectQueuePauseExplanation(
            settings = com.example.data.prefs.TelegramDriveSettings(),
            pendingCount = 2,
            activeTransfers = emptyList(),
            environment = cellularEnv.copy(telegramReady = false),
            nowMillis = 1_000L
        )
        assertEquals(com.example.data.sync.ExplanationCode.TRANSFERS_SESSION_CONNECTING, sessionPaused?.code)

        val backoffRow = com.example.data.local.entity.TransferQueueEntity(
            storageKey = "patients/p1/images/x.jpg",
            direction = com.example.data.local.entity.TransferDirection.UPLOAD.name,
            localPath = "/tmp/x.jpg",
            kind = com.example.data.storage.StorageObjectKind.PATIENT_IMAGE.name,
            mimeType = "image/jpeg",
            state = com.example.data.local.entity.TransferState.PENDING.name,
            nextAttemptAt = 46_000L,
            lastError = "FLOOD_WAIT_30"
        )
        val backoffPaused = com.example.ui.components.buildResolvedExplanations(
            live = com.example.data.sync.SyncLiveStatus(),
            failedTransfers = 0,
            lastSyncError = "",
            settings = com.example.data.prefs.TelegramDriveSettings(),
            pendingCount = 1,
            isQueuePaused = true,
            activeTransfers = listOf(backoffRow),
            environment = cellularEnv,
            nowMillis = 1_000L
        )
        val exp = backoffPaused.firstOrNull { it.code == com.example.data.sync.ExplanationCode.TRANSFERS_RETRY_BACKOFF }
        assertNotNull(exp)
        assertTrue(exp!!.detail!!.contains("45s"))
        assertTrue(exp.detail!!.contains("FLOOD_WAIT_30"))
    }

    private class FakeTelegramClient(private val root: File) : TelegramClient {
        private val _authState = MutableStateFlow<TelegramAuthState>(TelegramAuthState.Ready)
        override val authState: StateFlow<TelegramAuthState> = _authState.asStateFlow()
        private val _connectionState = MutableStateFlow(TelegramConnectionState.READY)
        override val connectionState: StateFlow<TelegramConnectionState> = _connectionState.asStateFlow()

        val pages = mutableMapOf<Long, RemoteDocumentPage>()
        private val blobs = mutableMapOf<String, ByteArray>()
        private var nextMessageId = 1000L

        fun putBlob(id: String, bytes: ByteArray) {
            blobs[id] = bytes
        }

        override suspend fun start(credentials: TelegramCredentials) = Unit
        override suspend fun submitPhoneNumber(phoneNumber: String) = Unit
        override suspend fun submitCode(code: String) = Unit
        override suspend fun submitPassword(password: String) = Unit
        override suspend fun submitEmailAddress(email: String) = Unit
        override suspend fun submitEmailCode(code: String) = Unit
        override suspend fun resendCode() = Unit
        override suspend fun requestQrCodeAuthentication() = Unit
        override suspend fun restartAuthentication() = Unit
        override suspend fun logout() = Unit
        override suspend fun getCurrentUser() = TelegramUser(1L, "Test", "User", "test", "000", false)
        override suspend fun getLimits() = TelegramLimits.REGULAR
        override suspend fun chatExists(chatId: Long) = true
        override suspend fun ensureStorageChat(knownChatId: Long?) = 999L
        override suspend fun listStorageChannels() = emptyList<StorageChannel>()

        override fun uploadDocument(
            chatId: Long,
            localPath: String,
            fileName: String,
            mimeType: String,
            caption: String,
            thumbnailPath: String?
        ): Flow<TelegramUploadEvent> = flow {
            val bytes = File(localPath).readBytes()
            val msgId = nextMessageId++
            val rf = "rf_$msgId"
            blobs[rf] = bytes
            val doc = RemoteDocument(
                chatId = chatId,
                messageId = msgId,
                remoteFileId = rf,
                uniqueFileId = "u_$msgId",
                fileName = fileName,
                mimeType = mimeType,
                sizeBytes = bytes.size.toLong(),
                caption = caption,
                dateSeconds = (System.currentTimeMillis() / 1000).toInt()
            )
            emit(TelegramUploadEvent.Started(msgId))
            emit(TelegramUploadEvent.Completed(doc))
        }

        override fun downloadDocument(remoteFileId: String): Flow<TelegramDownloadEvent> = flow {
            val bytes = blobs[remoteFileId] ?: error("Missing blob $remoteFileId")
            val dest = File(root, "dl_$remoteFileId.bin").apply { writeBytes(bytes) }
            emit(TelegramDownloadEvent.Completed(dest.absolutePath, bytes.size.toLong()))
        }

        override suspend fun fetchDocuments(chatId: Long, fromMessageId: Long, limit: Int): RemoteDocumentPage =
            pages[fromMessageId] ?: RemoteDocumentPage(emptyList(), 0L)

        override suspend fun getDocument(chatId: Long, messageId: Long): RemoteDocument? = null
        override suspend fun editCaption(chatId: Long, messageId: Long, caption: String) = Unit
        override suspend fun deleteMessages(chatId: Long, messageIds: List<Long>) = Unit
        override suspend fun discardLocalCopy(remoteFileId: String) = Unit
    }
}
