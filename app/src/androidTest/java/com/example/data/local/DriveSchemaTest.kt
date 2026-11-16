package com.example.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.local.entity.PatientEntity
import com.example.data.local.entity.RemoteObjectEntity
import com.example.data.local.entity.SyncTombstoneEntity
import com.example.data.local.entity.TransferDirection
import com.example.data.local.entity.TransferQueueEntity
import com.example.data.local.entity.TransferState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies the schema v5 additions behave as claimed: the new tables work, and
 * adding them leaves patient data untouched.
 *
 * This is an on-device test rather than a `MigrationTestHelper` one because the
 * project did not export schemas before v5, so there is no `4.json` to migrate
 * from. Once a v5 build has run, `app/schemas/` will contain `5.json`; from that
 * point a real migration test can be added for v5 to v6.
 */
@RunWith(AndroidJUnit4::class)
class DriveSchemaTest {

    private lateinit var database: OrthoDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            OrthoDatabase::class.java
        ).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun patientDataIsUnaffectedByTheSyncTables() = runBlocking {
        val patient = PatientEntity(
            id = "p-1",
            patientCode = "OT-0001",
            fullName = "Test Patient",
            diagnosis = "Distal radius fracture"
        )
        database.patientDao().insertPatient(patient)

        database.remoteObjectDao().upsert(sampleRemoteObject())
        database.transferQueueDao().insert(sampleTransfer())

        val stored = database.patientDao().getPatientDirect("p-1")
        assertNotNull("Patient rows must survive alongside the drive tables", stored)
        assertEquals("Distal radius fracture", stored?.diagnosis)
    }

    @Test
    fun remoteObjectsSupportSoftDeleteAndRestore() = runBlocking {
        val dao = database.remoteObjectDao()
        dao.upsert(sampleRemoteObject())

        assertEquals(1, dao.getAllLive().size)

        dao.markDeleted("patients/p-1/images/img-1.jpg", 1_700_000_000_000)
        assertTrue("A soft-deleted object leaves the live set", dao.getAllLive().isEmpty())
        assertNotNull("but the row itself is retained", dao.get("patients/p-1/images/img-1.jpg"))

        dao.restore("patients/p-1/images/img-1.jpg")
        assertEquals("and can be brought back", 1, dao.getAllLive().size)
    }

    @Test
    fun duplicateDetectionFindsAnIdenticalHash() = runBlocking {
        val dao = database.remoteObjectDao()
        dao.upsert(sampleRemoteObject())
        assertNotNull(dao.findByHash("hash-1"))
        assertNull(dao.findByHash("hash-absent"))
    }

    @Test
    fun prefixQueryScopesToOnePatient() = runBlocking {
        val dao = database.remoteObjectDao()
        dao.upsert(sampleRemoteObject())
        dao.upsert(
            sampleRemoteObject().copy(
                storageKey = "patients/p-2/images/img-9.jpg",
                uniqueFileId = "uid-2",
                contentHash = "hash-2"
            )
        )
        assertEquals(1, dao.getByPrefix("patients/p-1/").size)
        assertEquals(2, dao.getByPrefix("patients/").size)
    }

    @Test
    fun interruptedTransfersAreRequeuedRatherThanLost() = runBlocking {
        val dao = database.transferQueueDao()
        val id = dao.insert(sampleTransfer().copy(state = TransferState.RUNNING.name))

        dao.requeueInterrupted(System.currentTimeMillis())

        val row = dao.get(id)
        assertEquals(
            "A row left RUNNING by a killed process must return to the queue",
            TransferState.PENDING.name,
            row?.state
        )
    }

    @Test
    fun scheduledRetriesAreNotClaimedEarly() = runBlocking {
        val dao = database.transferQueueDao()
        val future = System.currentTimeMillis() + 60_000
        dao.insert(sampleTransfer().copy(nextAttemptAt = future))

        assertTrue(
            "Backoff must be honoured",
            dao.claimBatch(System.currentTimeMillis(), 10).isEmpty()
        )
        assertEquals(1, dao.claimBatch(future + 1, 10).size)
    }

    @Test
    fun failedTransfersCanBeRetriedInBulk() = runBlocking {
        val dao = database.transferQueueDao()
        dao.insert(
            sampleTransfer().copy(
                state = TransferState.FAILED.name,
                attemptCount = 4,
                lastError = "network"
            )
        )

        dao.retryAllFailed(System.currentTimeMillis())

        val row = dao.claimBatch(System.currentTimeMillis(), 10).single()
        assertEquals(TransferState.PENDING.name, row.state)
        assertEquals(0, row.attemptCount)
        assertNull(row.lastError)
    }

    @Test
    fun tombstonesTrackPropagationState() = runBlocking {
        val dao = database.syncTombstoneDao()
        dao.upsert(
            SyncTombstoneEntity(
                storageKey = "patients/p-1/images/img-1.jpg",
                deletedAt = System.currentTimeMillis(),
                reason = "user deleted image"
            )
        )
        assertEquals(1, dao.getPending().size)

        dao.markPropagated("patients/p-1/images/img-1.jpg")
        assertTrue(
            "A propagated deletion is not resent on the next sync",
            dao.getPending().isEmpty()
        )
    }

    private fun sampleRemoteObject() = RemoteObjectEntity(
        storageKey = "patients/p-1/images/img-1.jpg",
        kind = "PATIENT_IMAGE",
        chatId = -100_1,
        messageId = 42,
        remoteFileId = "remote-1",
        uniqueFileId = "uid-1",
        sizeBytes = 1_024,
        contentHash = "hash-1",
        mimeType = "image/jpeg",
        encrypted = true,
        modifiedAt = 1_700_000_000_000,
        syncedAt = 1_700_000_000_000
    )

    private fun sampleTransfer() = TransferQueueEntity(
        storageKey = "patients/p-1/images/img-1.jpg",
        direction = TransferDirection.UPLOAD.name,
        localPath = "/data/data/com.example/files/patients/p-1/images/img-1.jpg",
        kind = "PATIENT_IMAGE",
        mimeType = "image/jpeg"
    )
}
