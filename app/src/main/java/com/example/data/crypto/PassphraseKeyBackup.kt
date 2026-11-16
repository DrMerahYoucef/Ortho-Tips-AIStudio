package com.example.data.crypto

import android.util.Base64
import com.example.data.prefs.TelegramDriveRepository
import com.example.data.sync.ManifestCodec
import com.example.data.telegram.DriveLog
import com.example.data.telegram.RemoteDocument
import com.example.data.telegram.TelegramClient
import com.example.data.telegram.TelegramUploadEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.experimental.xor

/**
 * Lets the drive be opened on another phone (or after a reinstall) with a
 * passphrase the user chose.
 *
 * The files in the drive stay encrypted with the random content key. That key is
 * additionally sealed with a key derived from the passphrase (PBKDF2-HMAC-SHA256)
 * and stored in the drive channel as a small "key backup" document whose caption
 * starts with `otk1:`. Changing the passphrase only re-seals that key, so nothing
 * has to be re-uploaded.
 *
 * Anyone who gets both the channel and the passphrase can read the drive, so the
 * passphrase is only as strong as it is long: a sentence is much safer than a word.
 */
class PassphraseKeyBackup(
    private val client: TelegramClient,
    private val drive: TelegramDriveRepository,
    private val wrappedKeys: WrappedKeyRepository,
    private val manifestCodec: ManifestCodec,
    private val workDir: File
) {

    /** What was found in the channel. */
    data class DriveKeyState(
        /** Key backups in the channel, newest first. */
        val backups: List<Backup>,
        /** Objects this phone cannot decrypt with its current key. */
        val lockedObjects: Int,
        /** Objects this phone can read. */
        val readableObjects: Int
    ) {
        val hasBackup: Boolean get() = backups.isNotEmpty()
        val needsUnlock: Boolean get() = lockedObjects > 0
    }

    data class Backup(val messageId: Long, val fingerprint: String, val payload: JSONObject)

    sealed interface UnlockResult {
        data object NoBackup : UnlockResult
        data object WrongPassphrase : UnlockResult
        data class Unlocked(val keyChanged: Boolean) : UnlockResult
    }

    class PassphraseException(message: String) : Exception(message)

    /** Scans the channel once for key backups and unreadable objects. */
    suspend fun inspectDrive(): DriveKeyState = withContext(Dispatchers.IO) {
        val chatId = drive.chatId() ?: return@withContext DriveKeyState(emptyList(), 0, 0)
        val backups = mutableListOf<Backup>()
        var locked = 0
        var readable = 0
        var from = 0L
        var pages = 0
        while (pages++ < MAX_PAGES) {
            val page = client.fetchDocuments(chatId, from, PAGE_SIZE)
            for (doc in page.documents) {
                val caption = doc.caption
                when {
                    caption.startsWith(PREFIX) -> parse(doc)?.let(backups::add)
                    manifestCodec.isLocked(caption) -> locked++
                    manifestCodec.decode(caption) != null -> readable++
                }
            }
            from = page.nextFromMessageId
            if (from == 0L) break
        }
        DriveKeyState(backups.sortedByDescending { it.messageId }, locked, readable)
    }

    /**
     * Seals this phone's content key with [passphrase] and stores it in the drive.
     * Refuses while the drive holds data this phone cannot read: publishing a
     * different key then would hide the backup of the key that data needs.
     */
    suspend fun setPassphrase(passphrase: String) = withContext(Dispatchers.IO) {
        require(passphrase.trim().length >= MIN_LENGTH) { "Passphrase too short" }
        val chatId = drive.chatId() ?: throw PassphraseException("Telegram Drive is not set up")
        val state = inspectDrive()
        if (state.needsUnlock) {
            throw PassphraseException(
                "This drive holds data encrypted with another key. Unlock it with its passphrase first."
            )
        }

        val contentKey = wrappedKeys.getOrCreate(CryptoKeys.CONTENT)
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val derived = pbkdf2(passphrase.trim(), salt, ITERATIONS)
        val sealed = aesGcm(Cipher.ENCRYPT_MODE, derived, contentKey)
        val fingerprint = fingerprint(contentKey)

        val payload = JSONObject()
            .put("v", 1)
            .put("kdf", "pbkdf2-sha256")
            .put("i", ITERATIONS)
            .put("s", b64(salt))
            .put("w", b64(sealed))
            .put("f", fingerprint)
        val caption = PREFIX + b64(payload.toString().toByteArray(Charsets.UTF_8))

        workDir.mkdirs()
        val file = File(workDir, "ortho-tips-key-backup.txt")
        file.writeText(
            "Ortho Tips encryption key backup.\n" +
                "It is sealed with your passphrase. Do not delete this message: " +
                "without it the drive cannot be opened on another phone.\n"
        )
        val uploaded = try {
            var doc: RemoteDocument? = null
            client.uploadDocument(
                chatId = chatId,
                localPath = file.absolutePath,
                fileName = "ortho-tips-key-backup.txt",
                mimeType = "text/plain",
                caption = caption
            ).collect { event ->
                if (event is TelegramUploadEvent.Completed) {
                    doc = event.document
                }
            }
            doc ?: throw PassphraseException("Key backup upload did not complete")
        } finally {
            file.delete()
        }

        // Older backups of this same key are now redundant. Backups of a different
        // key are kept: they may be the only way to open older data.
        val stale = state.backups
            .filter { it.fingerprint == fingerprint && it.messageId != uploaded.messageId }
            .map { it.messageId }
        runCatching { client.deleteMessages(chatId, stale) }
        DriveLog.i(TAG, "Key backup stored (fingerprint $fingerprint)")
    }

    /**
     * Opens the drive's key backup with [passphrase] and installs the key on this
     * phone. Tries every backup, newest first, preferring one that matches the data.
     */
    suspend fun unlock(passphrase: String): UnlockResult = withContext(Dispatchers.IO) {
        val state = inspectDrive()
        if (!state.hasBackup) return@withContext UnlockResult.NoBackup

        val candidates = state.backups.mapNotNull { backup -> open(backup, passphrase.trim()) }
        if (candidates.isEmpty()) return@withContext UnlockResult.WrongPassphrase

        val current = wrappedKeys.get(CryptoKeys.CONTENT)
        val chosen = candidates.first()
        if (current != null && current.contentEquals(chosen)) {
            return@withContext UnlockResult.Unlocked(keyChanged = false)
        }
        if (current != null) {
            // Keep the replaced key instead of destroying it, in case anything was
            // uploaded with it before the drive was unlocked.
            wrappedKeys.store(CryptoKeys.CONTENT + "_replaced_" + System.currentTimeMillis(), current)
        }
        wrappedKeys.store(CryptoKeys.CONTENT, chosen)
        DriveLog.i(TAG, "Drive unlocked with passphrase (fingerprint ${fingerprint(chosen)})")
        UnlockResult.Unlocked(keyChanged = true)
    }

    private fun open(backup: Backup, passphrase: String): ByteArray? = runCatching {
        val p = backup.payload
        val salt = unb64(p.getString("s"))
        val iterations = p.getInt("i")
        val derived = pbkdf2(passphrase, salt, iterations)
        aesGcm(Cipher.DECRYPT_MODE, derived, unb64(p.getString("w")))
    }.getOrElse { error ->
        if (error !is AEADBadTagException) DriveLog.w(TAG, "Key backup unreadable: ${error.message}")
        null
    }

    private fun parse(doc: RemoteDocument): Backup? = runCatching {
        val json = JSONObject(String(unb64(doc.caption.removePrefix(PREFIX)), Charsets.UTF_8))
        Backup(doc.messageId, json.optString("f"), json)
    }.getOrNull()

    private fun aesGcm(mode: Int, key: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = SecretKeySpec(key, "AES")
        return if (mode == Cipher.ENCRYPT_MODE) {
            cipher.init(mode, spec)
            cipher.iv + cipher.doFinal(data)
        } else {
            cipher.init(mode, spec, GCMParameterSpec(128, data, 0, 12))
            cipher.doFinal(data, 12, data.size - 12)
        }
    }

    companion object {
        private const val TAG = "PassphraseKeyBackup"
        private const val PREFIX = "otk1:"
        private const val ITERATIONS = 210_000
        private const val PAGE_SIZE = 100
        private const val MAX_PAGES = 200
        const val MIN_LENGTH = 6

        private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
        private fun unb64(text: String) = Base64.decode(text, Base64.NO_WRAP)

        fun fingerprint(key: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(key).take(4)
                .joinToString("") { "%02X".format(it) }

        /**
         * PBKDF2-HMAC-SHA256 written out, because the platform only provides it
         * from Android 8 and the app still supports Android 7.
         */
        fun pbkdf2(passphrase: String, salt: ByteArray, iterations: Int, length: Int = 32): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(passphrase.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            val out = ByteArray(length)
            var block = 1
            var offset = 0
            while (offset < length) {
                mac.update(salt)
                mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
                var u = mac.doFinal()
                val t = u.copyOf()
                repeat(iterations - 1) {
                    u = mac.doFinal(u)
                    for (i in t.indices) t[i] = t[i] xor u[i]
                }
                val n = minOf(t.size, length - offset)
                System.arraycopy(t, 0, out, offset, n)
                offset += n
                block++
            }
            return out
        }
    }
}
