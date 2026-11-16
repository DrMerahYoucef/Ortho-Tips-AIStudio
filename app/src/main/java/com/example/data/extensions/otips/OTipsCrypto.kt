package com.example.data.extensions.otips

import java.io.EOFException
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encryption of `.OTips` payloads: AES-256-GCM over fixed-size chunks.
 *
 * Why chunks: a single GCM message must be fully buffered before Android's
 * provider releases a byte of plaintext, which does not scale to large packages.
 * Chunking keeps decryption streaming while every chunk stays authenticated:
 *
 * - each package gets its own key, derived with HKDF-SHA256 from the app key
 *   and a random salt stored in the header;
 * - chunk *i* uses the random base nonce with *i* folded into its last four
 *   bytes, so no nonce repeats under a key;
 * - every chunk authenticates the SHA-256 of the whole header, its index and
 *   whether it is the last one, so a chunk cannot be altered, moved, dropped,
 *   duplicated or paired with a different header, and a truncated file (no
 *   final chunk) is refused.
 *
 * The app key is part of the app ([OTipsKeyring]); files are therefore opaque
 * and tamper-evident to anyone without the app. See docs/extensions/guide.md
 * for what this does and does not protect against.
 */
object OTipsCrypto {

    const val ALGORITHM = "AES-256-GCM-CHUNKED"
    const val KDF = "HKDF-SHA256"
    const val CHUNK_SIZE = 64 * 1024
    const val TAG_BYTES = 16
    const val SALT_BYTES = 32
    const val NONCE_BYTES = 12
    private const val PAYLOAD_INFO = "OTips payload v2"
    private const val HEADER_INFO = "OTips header v2"

    /** Parameters stored in the header's `encryption` object. */
    data class Params(val keyId: String, val salt: ByteArray, val nonce: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is Params && keyId == other.keyId && salt.contentEquals(other.salt) && nonce.contentEquals(other.nonce)

        override fun hashCode(): Int = keyId.hashCode() * 31 + salt.contentHashCode()
    }

    fun newParams(random: SecureRandom = SecureRandom()): Params = Params(
        keyId = OTipsKeyring.CURRENT_KEY_ID,
        salt = ByteArray(SALT_BYTES).also(random::nextBytes),
        nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
    )

    /** Size of the ciphertext for [plainSize] bytes: every chunk adds a tag; there is always one chunk. */
    fun cipherSize(plainSize: Long): Long {
        val chunks = if (plainSize == 0L) 1L else (plainSize + CHUNK_SIZE - 1) / CHUNK_SIZE
        return plainSize + chunks * TAG_BYTES
    }

    fun encryptingStream(out: OutputStream, params: Params, headerHash: ByteArray): OutputStream =
        EncryptingStream(out, packageKey(params), params.nonce, headerHash)

    /** [cipherSize] is the exact ciphertext length from the header. */
    fun decryptingStream(input: InputStream, params: Params, headerHash: ByteArray, cipherSize: Long): InputStream =
        DecryptingStream(input, packageKey(params), params.nonce, headerHash, cipherSize)

    private fun packageKey(params: Params): SecretKeySpec = derive(params.keyId, params.salt, PAYLOAD_INFO)

    private fun derive(keyId: String, salt: ByteArray, info: String): SecretKeySpec {
        val master = OTipsKeyring.key(keyId)
            ?: throw OTipsException(OTipsError.UNSUPPORTED_FORMAT, "Unknown key '$keyId'")
        return SecretKeySpec(hkdf(master, salt, info.toByteArray(Charsets.UTF_8), 32), "AES")
    }

    /**
     * The header block: `keyIdLength(u8) keyId salt(32) nonce(12) AES-GCM(json)`.
     * The JSON (manifest, sizes, hashes, payload nonce) is encrypted with its
     * own derived key, so nothing about the extension is readable without the
     * app; [aad] binds it to the file's fixed prefix (magic, version, flags).
     */
    fun sealHeader(json: ByteArray, params: Params, aad: ByteArray, random: SecureRandom = SecureRandom()): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, derive(params.keyId, params.salt, HEADER_INFO), GCMParameterSpec(TAG_BYTES * 8, nonce))
        cipher.updateAAD(aad)
        cipher.updateAAD(params.keyId.toByteArray(Charsets.UTF_8))
        val sealed = cipher.doFinal(json)
        val id = params.keyId.toByteArray(Charsets.UTF_8)
        require(id.size in 1..255) { "bad key id" }
        return ByteBuffer.allocate(1 + id.size + SALT_BYTES + NONCE_BYTES + sealed.size)
            .put(id.size.toByte()).put(id).put(params.salt).put(nonce).put(sealed).array()
    }

    /** Opens a header block; returns the key id, the salt and the JSON. */
    fun openHeader(block: ByteArray, aad: ByteArray): Triple<String, ByteArray, ByteArray> {
        fun corrupt(): Nothing = throw OTipsException(OTipsError.CORRUPTED, "Corrupted package: unreadable header")
        if (block.isEmpty()) corrupt()
        val idLength = block[0].toInt() and 0xFF
        val prefix = 1 + idLength + SALT_BYTES + NONCE_BYTES
        if (idLength == 0 || block.size < prefix + TAG_BYTES) corrupt()
        val keyId = String(block, 1, idLength, Charsets.UTF_8)
        if (OTipsKeyring.key(keyId) == null) {
            throw OTipsException(OTipsError.UNSUPPORTED_FORMAT, "Package key '$keyId' is unknown to this version")
        }
        val salt = block.copyOfRange(1 + idLength, 1 + idLength + SALT_BYTES)
        val nonce = block.copyOfRange(1 + idLength + SALT_BYTES, prefix)
        val json = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, derive(keyId, salt, HEADER_INFO), GCMParameterSpec(TAG_BYTES * 8, nonce))
            cipher.updateAAD(aad)
            cipher.updateAAD(keyId.toByteArray(Charsets.UTF_8))
            cipher.doFinal(block, prefix, block.size - prefix)
        } catch (e: GeneralSecurityException) {
            throw OTipsException(OTipsError.CORRUPTED, "Corrupted package: header failed authentication", e)
        }
        return Triple(keyId, salt, json)
    }

    /** RFC 5869, SHA-256. */
    internal fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var produced = 0
        var counter = 1
        while (produced < length) {
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            val n = minOf(previous.size, length - produced)
            System.arraycopy(previous, 0, out, produced, n)
            produced += n
            counter++
        }
        return out
    }

    private fun chunkNonce(base: ByteArray, index: Int): ByteArray {
        val nonce = base.copyOf()
        for (i in 0 until 4) {
            nonce[NONCE_BYTES - 1 - i] = (nonce[NONCE_BYTES - 1 - i].toInt() xor ((index ushr (8 * i)) and 0xFF)).toByte()
        }
        return nonce
    }

    private fun chunkAad(headerHash: ByteArray, index: Int, last: Boolean): ByteArray =
        ByteBuffer.allocate(headerHash.size + 5).put(headerHash).putInt(index).put(if (last) 1 else 0).array()

    private class EncryptingStream(
        out: OutputStream,
        private val key: SecretKeySpec,
        private val nonce: ByteArray,
        private val headerHash: ByteArray
    ) : FilterOutputStream(out) {
        private val buffer = ByteArray(CHUNK_SIZE)
        private var filled = 0
        private var index = 0
        private var finished = false

        override fun write(b: Int) {
            if (filled == CHUNK_SIZE) emit(last = false)
            buffer[filled++] = b.toByte()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var offset = off
            var left = len
            while (left > 0) {
                // A full buffer is only sealed once more data arrives, so the
                // final chunk is always the one sealed by close().
                if (filled == CHUNK_SIZE) emit(last = false)
                val n = minOf(left, CHUNK_SIZE - filled)
                System.arraycopy(b, offset, buffer, filled, n)
                filled += n
                offset += n
                left -= n
            }
        }

        private fun emit(last: Boolean) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, chunkNonce(nonce, index)))
            cipher.updateAAD(chunkAad(headerHash, index, last))
            out.write(cipher.doFinal(buffer, 0, filled))
            filled = 0
            index++
        }

        /** Seals the last chunk. Does not close the underlying stream. */
        override fun close() {
            if (finished) return
            finished = true
            emit(last = true)
            out.flush()
        }
    }

    private class DecryptingStream(
        private val input: InputStream,
        private val key: SecretKeySpec,
        private val nonce: ByteArray,
        private val headerHash: ByteArray,
        private var remaining: Long
    ) : InputStream() {
        private var plain = ByteArray(0)
        private var position = 0
        private var index = 0
        private var done = false

        override fun read(): Int {
            if (!fill()) return -1
            return plain[position++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!fill()) return -1
            val n = minOf(len, plain.size - position)
            System.arraycopy(plain, position, b, off, n)
            position += n
            return n
        }

        private fun fill(): Boolean {
            while (position >= plain.size) {
                if (done) return false
                val chunk = minOf(remaining, (CHUNK_SIZE + TAG_BYTES).toLong()).toInt()
                if (chunk < TAG_BYTES) throw OTipsException(OTipsError.CORRUPTED, "Corrupted package: truncated chunk")
                val last = remaining == chunk.toLong()
                val sealed = ByteArray(chunk)
                var read = 0
                while (read < chunk) {
                    val n = input.read(sealed, read, chunk - read)
                    if (n < 0) throw EOFException("payload truncated")
                    read += n
                }
                remaining -= chunk
                plain = try {
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, chunkNonce(nonce, index)))
                    cipher.updateAAD(chunkAad(headerHash, index, last))
                    cipher.doFinal(sealed)
                } catch (e: GeneralSecurityException) {
                    throw OTipsException(OTipsError.CORRUPTED, "Corrupted package: chunk $index failed authentication", e)
                }
                position = 0
                index++
                if (last) done = true
            }
            return true
        }
    }

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}

/**
 * The app keys `.OTips` payloads are encrypted with, by id. New packages use
 * [CURRENT_KEY_ID]; older ids stay here so packages made with them still open,
 * which is how the key can be rotated without breaking existing files.
 *
 * The key is stored masked rather than as a literal, so it does not appear
 * as-is in the APK. Anything shipped inside an app can ultimately be extracted
 * by a determined reverse engineer; the guide explains the protection level.
 */
object OTipsKeyring {
    const val CURRENT_KEY_ID = "k1"

    private val K1_A = intArrayOf(
        0x3a, 0x91, 0x5c, 0xe4, 0x07, 0xb8, 0x62, 0x1f, 0xd3, 0x48, 0xa6, 0x7d, 0x19, 0xf0, 0x85, 0x2e,
        0xc7, 0x5b, 0x33, 0x9a, 0x6e, 0x04, 0xbd, 0x71, 0x28, 0xe9, 0x96, 0x4f, 0x0c, 0xa3, 0x57, 0xd6
    )
    private val K1_B = intArrayOf(
        0x8e, 0x27, 0xf1, 0x40, 0xba, 0x6c, 0x13, 0x95, 0x4d, 0xe2, 0x39, 0xc8, 0x76, 0x0a, 0x5f, 0xa1,
        0x2b, 0x9e, 0xd4, 0x63, 0x18, 0xf7, 0x42, 0x8c, 0xb5, 0x0e, 0x6a, 0xd1, 0x97, 0x3c, 0xe8, 0x51
    )

    fun key(id: String): ByteArray? = when (id) {
        "k1" -> ByteArray(32) { (K1_A[it] xor K1_B[it] xor (it * 29 + 7)).toByte() }
        else -> null
    }
}
