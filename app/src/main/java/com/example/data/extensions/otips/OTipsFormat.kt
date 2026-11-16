package com.example.data.extensions.otips

import com.example.data.extensions.ExtensionManifest

/**
 * The `.OTips` container: every extension the app installs arrives in one.
 *
 * Binary layout (all integers big-endian):
 *
 * ```
 * magic            8 bytes   "OTIPS" 0D 0A 1A 00  (catches text-mode transfers and truncation)
 * containerVersion u16       CONTAINER_VERSION
 * flags            u16       FLAG_* bits; unknown bits are refused
 * headerLength     u32       ≤ OTipsLimits.maxHeaderBytes
 * header block     keyIdLength(u8) keyId salt(32) nonce(12) AES-GCM(json)   (see OTipsCrypto.sealHeader)
 *                  json = {"manifest":{…},
 *                          "payload":{"format":"zip","size":n,"plainSize":p,"sha256":"<of the plain ZIP>"},
 *                          "encryption":{"algorithm":"AES-256-GCM-CHUNKED","kdf":"HKDF-SHA256","nonce":"<hex>"},
 *                          "createdAt":ms,"createdBy":"…"}
 * payload          n bytes   the ZIP of the content files, encrypted (see OTipsCrypto)
 * signatureLength  u32       0 unless FLAG_SIGNED — reserved for publisher signatures
 * signature        bytes
 * trailer          32 bytes  SHA-256 of every byte above
 * ```
 *
 * Protection is layered:
 * - the header is encrypted: without the app nothing about the extension, not
 *   even its name, can be read, and it cannot be edited;
 * - the payload is encrypted and authenticated chunk by chunk, each chunk
 *   bound to the exact header, so the content cannot be read or altered
 *   without the app, nor the header swapped;
 * - the trailer hashes the whole file, so damage is reported as such, cheaply,
 *   before anything is decrypted;
 * - the plain payload hash identifies the content (the same content always
 *   hashes the same), which is how "already installed" is detected.
 *
 * Version 1 (phases before encryption) stored the ZIP in the clear; such files
 * are refused as outdated. The signature block is reserved for publisher
 * signatures, checked through [OTipsSignatureVerifier].
 */
object OTipsFormat {
    /** User-facing file extension. Matching is case-insensitive. */
    const val FILE_EXTENSION = "OTips"
    const val MIME_TYPE = "application/octet-stream"

    val MAGIC: ByteArray = byteArrayOf(0x4F, 0x54, 0x49, 0x50, 0x53, 0x0D, 0x0A, 0x1A)

    /** 2: encrypted payload (mandatory). 1: plaintext, refused as outdated. */
    const val CONTAINER_VERSION = 2
    const val PLAINTEXT_CONTAINER_VERSION = 1

    const val FLAG_ENCRYPTED = 1 shl 0
    const val FLAG_SIGNED = 1 shl 1
    const val KNOWN_FLAGS = FLAG_ENCRYPTED or FLAG_SIGNED

    const val PAYLOAD_FORMAT_ZIP = "zip"
    const val HASH_ALGORITHM = "SHA-256"
    const val HASH_BYTES = 32

    fun fileName(id: String): String = "$id.$FILE_EXTENSION"

    fun hasPackageExtension(name: String?): Boolean =
        name?.substringAfterLast('.', "")?.equals(FILE_EXTENSION, ignoreCase = true) == true
}

/** What the header of a package says, read without touching the payload. */
data class OTipsHeader(
    val containerVersion: Int,
    val flags: Int,
    val manifest: ExtensionManifest,
    val payloadFormat: String,
    /** Bytes of payload in the file (ciphertext when encrypted). */
    val payloadSize: Long,
    /** SHA-256 of the plain ZIP: the identity of the content. */
    val payloadSha256: String,
    val createdAt: Long,
    val createdBy: String,
    val encryption: OTipsCrypto.Params? = null,
    /** SHA-256 of the header bytes, bound into every encrypted chunk. */
    val headerHash: ByteArray = ByteArray(0)
) {
    val isEncrypted: Boolean get() = flags and OTipsFormat.FLAG_ENCRYPTED != 0
    val isSigned: Boolean get() = flags and OTipsFormat.FLAG_SIGNED != 0
}

/** Ceilings that keep a hostile or broken file from exhausting the phone. */
data class OTipsLimits(
    val maxHeaderBytes: Int = 1 shl 20,
    val maxPayloadBytes: Long = 1L shl 30,
    val maxUncompressedBytes: Long = 2L shl 30,
    val maxEntryBytes: Long = 512L shl 20,
    val maxEntries: Int = 50_000,
    val maxSignatureBytes: Int = 64 * 1024
)

enum class OTipsError {
    /** Not an `.OTips` file at all (wrong magic). */
    NOT_A_PACKAGE,

    /** Truncated, altered or damaged: a hash or a length does not match. */
    CORRUPTED,

    /** Made by a newer app: container version or flags this build cannot read. */
    UNSUPPORTED_FORMAT,

    /** Made by a pre-encryption build of the app; it has to be exported again. */
    OUTDATED,

    INVALID_MANIFEST,
    TOO_LARGE,
    IO
}

class OTipsException(val error: OTipsError, message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * Checks a publisher signature (reserved; see [OTipsFormat.FLAG_SIGNED]).
 * [signedDigest] is the SHA-256 of every byte before the signature block.
 * When a verifier is given, a signed package whose signature does not verify
 * is refused; unsigned packages are unaffected.
 */
fun interface OTipsSignatureVerifier {
    fun verify(header: OTipsHeader, signedDigest: ByteArray, signature: ByteArray): Boolean
}

internal fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "odd hex length" }
    return ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
