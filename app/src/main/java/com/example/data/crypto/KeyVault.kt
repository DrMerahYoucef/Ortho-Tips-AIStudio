package com.example.data.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.example.data.telegram.TdlibDatabaseKeyProvider
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Names of the wrapped raw keys managed by [WrappedKeyRepository]. */
object CryptoKeys {
    const val CONTENT = "content"
    const val THUMBNAIL = "thumbnail"
    const val TDLIB_DATABASE = "tdlib_db"
}

/**
 * Wraps a non-exportable AES-256 key held in the Android Keystore. Used only to
 * encrypt small blobs (the wrapped content keys); bulk patient media goes through
 * [StreamCrypto] with raw keys unwrapped here.
 *
 * Ported from TeleDrive's `KeystoreManager`. The alias is app-specific so an
 * Ortho Tips reinstall never touches another app's key material.
 */
class KeystoreManager {

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    private fun masterKey(): SecretKey {
        (keyStore.getKey(MASTER_KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                MASTER_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    /** Output layout: IV (12 bytes) || ciphertext+tag. */
    fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        return cipher.iv + cipher.doFinal(plaintext)
    }

    fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size > IV_LENGTH) { "Encrypted blob too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            masterKey(),
            GCMParameterSpec(TAG_BITS, blob, 0, IV_LENGTH)
        )
        return cipher.doFinal(blob, IV_LENGTH, blob.size - IV_LENGTH)
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val MASTER_KEY_ALIAS = "orthotips_drive_master_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH = 12
        private const val TAG_BITS = 128
    }
}

/**
 * Persists random raw keys wrapped by the Keystore master key. Raw keys never
 * touch disk unencrypted; unwrapped copies are cached in memory only.
 */
class WrappedKeyRepository(
    private val context: Context,
    private val keystoreManager: KeystoreManager
) {

    private val cache = mutableMapOf<String, ByteArray>()
    private val secureRandom = SecureRandom()

    @Synchronized
    fun getOrCreate(name: String, sizeBytes: Int = 32): ByteArray {
        cache[name]?.let { return it }
        val file = keyFile(name)
        val key = if (file.exists()) {
            keystoreManager.decrypt(file.readBytes())
        } else {
            val fresh = ByteArray(sizeBytes).also(secureRandom::nextBytes)
            file.parentFile?.mkdirs()
            file.writeBytes(keystoreManager.encrypt(fresh))
            fresh
        }
        cache[name] = key
        return key
    }

    /**
     * Reads a key without ever creating one. Decryption paths must use this:
     * minting a fresh key there would silently make existing data unreadable.
     */
    @Synchronized
    fun get(name: String): ByteArray? {
        cache[name]?.let { return it }
        val file = keyFile(name)
        if (!file.exists()) return null
        return keystoreManager.decrypt(file.readBytes()).also { cache[name] = it }
    }

    fun exists(name: String): Boolean = cache.containsKey(name) || keyFile(name).exists()

    @Synchronized
    fun store(name: String, key: ByteArray) {
        val file = keyFile(name)
        file.parentFile?.mkdirs()
        file.writeBytes(keystoreManager.encrypt(key))
        cache[name] = key
    }

    /**
     * A short fingerprint of the content key, shown in diagnostics so two devices
     * can be compared without ever displaying key material.
     */
    fun fingerprint(name: String): String? {
        val key = get(name) ?: return null
        return MessageDigest.getInstance("SHA-256")
            .digest(key)
            .take(4)
            .joinToString("") { "%02X".format(it) }
    }

    private fun keyFile(name: String): File = File(File(context.filesDir, "keys"), "$name.bin")
}

/** Supplies TDLib's own database encryption key from the wrapped key store. */
class KeystoreTdlibKeyProvider(
    private val wrappedKeyRepository: WrappedKeyRepository
) : TdlibDatabaseKeyProvider {

    override fun databaseKey(): ByteArray =
        wrappedKeyRepository.getOrCreate(CryptoKeys.TDLIB_DATABASE)
}
