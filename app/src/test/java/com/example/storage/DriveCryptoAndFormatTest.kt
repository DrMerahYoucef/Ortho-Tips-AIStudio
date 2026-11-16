package com.example.storage

import com.example.data.crypto.StreamCrypto
import com.example.data.storage.StorageKey
import com.example.data.storage.StorageObjectKind
import com.example.data.sync.DriveManifest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import kotlin.random.Random

/**
 * These cover the parts of the Telegram Drive backend that are pure Kotlin and
 * therefore genuinely verifiable without a device: the encryption format, the
 * caption manifest wire format, and storage-key validation.
 *
 * The cipher tests matter most. A silent truncation or a chunk swap in a
 * radiograph is the worst possible failure mode here, because it produces a file
 * that opens and is wrong rather than a file that obviously fails.
 */
class DriveCryptoAndFormatTest {

    private val crypto = StreamCrypto()
    private val key = ByteArray(32) { it.toByte() }

    @Test
    fun `stream round trip preserves an empty payload`() {
        assertRoundTrip(ByteArray(0))
    }

    @Test
    fun `stream round trip preserves a payload smaller than one chunk`() {
        assertRoundTrip(Random(1).nextBytes(4_096))
    }

    @Test
    fun `stream round trip preserves a payload on an exact chunk boundary`() {
        assertRoundTrip(Random(2).nextBytes(StreamCrypto.CHUNK_SIZE))
    }

    @Test
    fun `stream round trip preserves a multi chunk payload`() {
        assertRoundTrip(Random(3).nextBytes(StreamCrypto.CHUNK_SIZE * 2 + 7_777))
    }

    @Test
    fun `ciphertext differs between runs for identical input`() {
        val plaintext = Random(4).nextBytes(1_000)
        val first = encrypt(plaintext)
        val second = encrypt(plaintext)
        assertNotEquals(
            "A fresh salt must make every upload of the same file distinct",
            first.toList(),
            second.toList()
        )
    }

    @Test(expected = Exception::class)
    fun `a flipped ciphertext byte fails authentication`() {
        val sealed = encrypt(Random(5).nextBytes(50_000))
        // Land well past the header so the failure comes from the GCM tag rather
        // than from the magic-number check.
        sealed[sealed.size / 2] = (sealed[sealed.size / 2] + 1).toByte()
        decrypt(sealed)
    }

    @Test(expected = EOFException::class)
    fun `a truncated stream is rejected rather than decrypted short`() {
        val sealed = encrypt(Random(6).nextBytes(StreamCrypto.CHUNK_SIZE * 2))
        decrypt(sealed.copyOf(sealed.size - 64))
    }

    @Test
    fun `wrong key cannot decrypt`() {
        val sealed = encrypt(Random(7).nextBytes(2_048))
        val otherKey = ByteArray(32) { (it + 1).toByte() }
        val failed = runCatching {
            ByteArrayOutputStream().use { out ->
                crypto.decryptStream(otherKey, ByteArrayInputStream(sealed), out)
            }
        }.isFailure
        assertTrue("Decryption with the wrong key must fail", failed)
    }

    @Test
    fun `byte helper round trips small payloads`() {
        val payload = "radiograph metadata".toByteArray()
        assertArrayEquals(payload, crypto.decryptBytes(key, crypto.encryptBytes(key, payload)))
    }

    @Test
    fun `encrypted size prediction matches the real output`() {
        listOf(0L, 1L, 4_096L, StreamCrypto.CHUNK_SIZE.toLong(), StreamCrypto.CHUNK_SIZE * 2L + 5)
            .forEach { size ->
                val actual = encrypt(ByteArray(size.toInt())).size.toLong()
                assertEquals(
                    "Size prediction is used for quota checks before upload",
                    actual,
                    crypto.encryptedSize(size)
                )
            }
    }

    // ---- Manifest ----------------------------------------------------------

    @Test
    fun `manifest survives a serialization round trip`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        val manifest = DriveManifest(
            storageKey = "patients/p-1/images/img-9.jpg",
            name = "img-9.jpg",
            kind = StorageObjectKind.PATIENT_IMAGE.name,
            mimeType = "image/jpeg",
            sizeBytes = 2_400_000,
            contentHash = "abc123",
            encrypted = true,
            createdAt = 1_700_000_000_000,
            modifiedAt = 1_700_000_500_000,
            revision = 3,
            patientId = "p-1"
        )
        val decoded = json.decodeFromString(
            DriveManifest.serializer(),
            json.encodeToString(DriveManifest.serializer(), manifest)
        )
        assertEquals(manifest, decoded)
        assertEquals(StorageObjectKind.PATIENT_IMAGE, decoded.objectKind)
    }

    @Test
    fun `manifest stays inside the telegram caption limit`() {
        val json = Json { encodeDefaults = false }
        val manifest = DriveManifest(
            storageKey = "patients/${"p".repeat(40)}/documents/${"d".repeat(40)}.pdf",
            name = "${"d".repeat(40)}.pdf",
            kind = StorageObjectKind.PATIENT_DOCUMENT.name,
            mimeType = "application/pdf",
            sizeBytes = Long.MAX_VALUE,
            contentHash = "f".repeat(64),
            encrypted = true,
            createdAt = Long.MAX_VALUE,
            modifiedAt = Long.MAX_VALUE,
            revision = 9_999,
            patientId = "p".repeat(40)
        )
        val encoded = json.encodeToString(DriveManifest.serializer(), manifest)
        // Encrypted captions are base64 of the ciphertext, so allow generous
        // headroom against Telegram's 1024-character non-Premium limit.
        assertTrue(
            "Worst-case manifest is ${encoded.length} chars before encoding",
            encoded.length < 600
        )
    }

    @Test
    fun `unknown manifest fields are ignored so older clients keep reading`() {
        val json = Json { ignoreUnknownKeys = true }
        val payload = """{"v":1,"k":"snapshots/a.json","n":"a.json","kd":"DATABASE_SNAPSHOT",""" +
                """"m":"application/json","s":10,"ct":1,"mt":2,"futureField":"ignored"}"""
        val decoded = json.decodeFromString(DriveManifest.serializer(), payload)
        assertEquals("snapshots/a.json", decoded.storageKey)
    }

    // ---- Storage keys ------------------------------------------------------

    @Test
    fun `patient keys match the existing on disk layout`() {
        assertEquals(
            "patients/p-1/images/img-2.jpg",
            StorageKey.patientImage("p-1", "img-2").value
        )
        assertEquals(
            "patients/p-1/images/thumbs/img-2.jpg",
            StorageKey.patientThumbnail("p-1", "img-2").value
        )
        assertEquals(
            "patients/p-1/documents/doc-3.pdf",
            StorageKey.patientDocument("p-1", "doc-3", "pdf").value
        )
    }

    @Test
    fun `key exposes file name and extension`() {
        val key = StorageKey.patientDocument("p", "d", "pdf")
        assertEquals("d.pdf", key.fileName)
        assertEquals("pdf", key.extension)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `absolute keys are rejected`() {
        StorageKey("/etc/passwd")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `traversing keys are rejected`() {
        StorageKey("patients/../../secrets")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `blank keys are rejected`() {
        StorageKey("   ")
    }

    // ---- Helpers -----------------------------------------------------------

    private fun assertRoundTrip(plaintext: ByteArray) {
        assertArrayEquals(plaintext, decrypt(encrypt(plaintext)))
    }

    private fun encrypt(plaintext: ByteArray): ByteArray =
        ByteArrayOutputStream().use { out ->
            crypto.encryptStream(key, ByteArrayInputStream(plaintext), out)
            out.toByteArray()
        }

    private fun decrypt(sealed: ByteArray): ByteArray =
        ByteArrayOutputStream().use { out ->
            crypto.decryptStream(key, ByteArrayInputStream(sealed), out)
            out.toByteArray()
        }
}
