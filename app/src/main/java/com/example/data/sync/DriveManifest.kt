package com.example.data.sync

import android.util.Base64
import com.example.data.crypto.CryptoKeys
import com.example.data.crypto.StreamCrypto
import com.example.data.crypto.WrappedKeyRepository
import com.example.data.storage.StorageObjectKind
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Metadata embedded in every uploaded document's caption. Keys are shortened to
 * fit Telegram's 1024-character caption limit.
 *
 * This is what makes the drive channel self-describing: after a reinstall, or on
 * a second device, the local index can be rebuilt by replaying captions with no
 * other state. Adapted from TeleDrive's `RemoteFileManifest`, with folder paths
 * replaced by Ortho Tips storage keys and patient linkage added.
 */
@Serializable
data class DriveManifest(
    @SerialName("v") val version: Int = VERSION,
    /** The logical storage key, which is also the identity of the object. */
    @SerialName("k") val storageKey: String,
    @SerialName("n") val name: String,
    @SerialName("kd") val kind: String,
    @SerialName("m") val mimeType: String,
    @SerialName("s") val sizeBytes: Long,
    /** SHA-256 of the plaintext, always present so integrity survives encryption. */
    @SerialName("h") val contentHash: String? = null,
    @SerialName("e") val encrypted: Boolean = false,
    @SerialName("ct") val createdAt: Long,
    @SerialName("mt") val modifiedAt: Long,
    /** Set on tombstones so a deletion propagates to other devices. */
    @SerialName("del") val deletedAt: Long? = null,
    @SerialName("rev") val revision: Int = 1,
    /** Owning patient, when the object belongs to one. Never a patient name. */
    @SerialName("pid") val patientId: String? = null
) {
    val objectKind: StorageObjectKind
        get() = runCatching { StorageObjectKind.valueOf(kind) }
            .getOrDefault(StorageObjectKind.OTHER)

    companion object {
        const val VERSION = 1
    }
}

/**
 * Caption wire format:
 *  - plain:     "ot1:"  + JSON manifest
 *  - encrypted: "ote1:" + base64(AES-GCM(JSON)) under the content key, so a
 *    Telegram-side leak reveals no patient identifiers or file structure.
 *
 * Patient data is medical, so encryption is the default for this app rather than
 * the opt-in it is in TeleDrive. The plain form is still decoded, because a drive
 * created before a key rotation must stay readable.
 */
class ManifestCodec(
    private val streamCrypto: StreamCrypto,
    private val wrappedKeyRepository: WrappedKeyRepository
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    fun encode(manifest: DriveManifest, encrypt: Boolean): String {
        val payload = json.encodeToString(DriveManifest.serializer(), manifest)
        return if (encrypt) {
            val key = wrappedKeyRepository.getOrCreate(CryptoKeys.CONTENT)
            PREFIX_ENCRYPTED + Base64.encodeToString(
                streamCrypto.encryptBytes(key, payload.toByteArray(Charsets.UTF_8)),
                Base64.NO_WRAP
            )
        } else {
            PREFIX_PLAIN + payload
        }
    }

    /**
     * Why a caption did not yield a manifest. The distinction matters: a foreign
     * caption is noise, while an unreadable one means real content in the drive
     * that this device has lost the key for — a very different problem, and one
     * the user has to be told about rather than have silently skipped.
     */
    sealed interface Decoded {
        data class Ok(val manifest: DriveManifest) : Decoded
        data object NotOurs : Decoded
        data object Unreadable : Decoded
    }

    fun inspect(caption: String): Decoded = when {
        caption.startsWith(PREFIX_PLAIN) ->
            runCatching {
                Decoded.Ok(
                    json.decodeFromString(
                        DriveManifest.serializer(),
                        caption.removePrefix(PREFIX_PLAIN)
                    )
                )
            }.getOrDefault(Decoded.Unreadable)

        caption.startsWith(PREFIX_ENCRYPTED) -> {
            val key = wrappedKeyRepository.get(CryptoKeys.CONTENT)
            if (key == null) {
                Decoded.Unreadable
            } else {
                runCatching {
                    val plaintext = streamCrypto.decryptBytes(
                        key,
                        Base64.decode(caption.removePrefix(PREFIX_ENCRYPTED), Base64.NO_WRAP)
                    )
                    Decoded.Ok(
                        json.decodeFromString(
                            DriveManifest.serializer(),
                            String(plaintext, Charsets.UTF_8)
                        )
                    )
                }.getOrDefault(Decoded.Unreadable)
            }
        }

        else -> Decoded.NotOurs
    }

    /** Returns null for captions that are not Ortho Tips manifests. */
    fun decode(caption: String): DriveManifest? =
        (inspect(caption) as? Decoded.Ok)?.manifest

    fun isEncryptedManifest(caption: String): Boolean = caption.startsWith(PREFIX_ENCRYPTED)

    /**
     * True when the caption is one of ours but this device cannot read it.
     *
     * The earlier version only checked whether *a* content key existed, which
     * missed the case that actually happens: a key exists, but it is a different
     * one than the object was sealed with, so every decrypt silently fails and
     * the content looks like it was never there.
     */
    fun isLocked(caption: String): Boolean =
        isEncryptedManifest(caption) && inspect(caption) is Decoded.Unreadable

    companion object {
        private const val PREFIX_PLAIN = "ot1:"
        private const val PREFIX_ENCRYPTED = "ote1:"
    }
}
