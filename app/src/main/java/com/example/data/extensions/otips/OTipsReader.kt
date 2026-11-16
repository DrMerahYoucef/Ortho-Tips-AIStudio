package com.example.data.extensions.otips

import com.example.data.extensions.ExtensionManifest
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * Reads `.OTips` packages (layout in [OTipsFormat]).
 *
 * Everything is streamed: a package with hundreds of megabytes of figures is
 * never held in memory, and nothing is trusted until the last byte has been
 * checked. [extract] writes into a directory the caller owns and must delete if
 * an exception escapes — a failed extraction can leave partial files behind but
 * is never reported as success.
 */
object OTipsReader {

    /** Reads only the fixed prefix and the header, for listing and previews. */
    fun readHeader(input: InputStream, limits: OTipsLimits = OTipsLimits()): OTipsHeader =
        guard { parseHeader(DataInputStream(BufferedInputStream(input)), limits) }

    /**
     * Verifies the whole package and unpacks its content into [destination].
     *
     * @param maxPayloadBytes a tighter, kind-specific ceiling decided by the
     *   caller once it has seen the header (see [readHeader]).
     */
    fun extract(
        input: InputStream,
        destination: File,
        limits: OTipsLimits = OTipsLimits(),
        maxPayloadBytes: Long = limits.maxPayloadBytes,
        signatureVerifier: OTipsSignatureVerifier? = null
    ): OTipsHeader = guard {
        val fileDigest = MessageDigest.getInstance(OTipsFormat.HASH_ALGORITHM)
        val digesting = DigestInputStream(BufferedInputStream(input, BUFFER), fileDigest)
        val data = DataInputStream(digesting)

        val header = parseHeader(data, limits)
        val params = header.encryption ?: corrupted("missing encryption parameters")
        if (header.payloadSize > minOf(maxPayloadBytes, limits.maxPayloadBytes)) {
            throw OTipsException(OTipsError.TOO_LARGE, "Payload of ${header.payloadSize} bytes exceeds the limit")
        }

        val payloadDigest = MessageDigest.getInstance(OTipsFormat.HASH_ALGORITHM)
        val payload = BoundedInputStream(digesting, header.payloadSize)
        val plain = OTipsCrypto.decryptingStream(payload, params, header.headerHash, header.payloadSize)
        val hashedPlain = DigestInputStream(plain, payloadDigest)
        unzip(NonClosingInputStream(hashedPlain), destination, limits)
        // ZipInputStream stops at the central directory; the rest still counts,
        // and reading it to the end authenticates the final chunk.
        hashedPlain.drain()
        if (payload.remaining != 0L) corrupted("payload is longer than its chunks")
        if (!payloadDigest.digest().toHex().equals(header.payloadSha256, ignoreCase = true)) {
            corrupted("payload checksum mismatch")
        }

        val signatureLength = data.readInt()
        if (signatureLength < 0 || signatureLength > limits.maxSignatureBytes) corrupted("bad signature length")
        if (!header.isSigned && signatureLength != 0) corrupted("unexpected signature block")
        // What a publisher signs: every byte before the signature block.
        val signedDigest = (fileDigest.clone() as MessageDigest).digest()
        // Read, never skip(): skipping would bypass the running file digest.
        val signature = ByteArray(signatureLength)
        data.readFully(signature)
        if (header.isSigned && signatureVerifier != null && !signatureVerifier.verify(header, signedDigest, signature)) {
            corrupted("publisher signature does not verify")
        }

        digesting.on(false)
        val expected = fileDigest.digest()
        val trailer = ByteArray(OTipsFormat.HASH_BYTES)
        data.readFully(trailer)
        if (!MessageDigest.isEqual(expected, trailer)) corrupted("file checksum mismatch")
        if (data.read() != -1) corrupted("unexpected data after the trailer")
        header
    }

    // ---- internals ---------------------------------------------------------

    private fun parseHeader(data: DataInputStream, limits: OTipsLimits): OTipsHeader {
        val magic = ByteArray(OTipsFormat.MAGIC.size)
        try {
            data.readFully(magic)
        } catch (e: EOFException) {
            throw OTipsException(OTipsError.NOT_A_PACKAGE, "File is too short to be a package", e)
        }
        if (!magic.contentEquals(OTipsFormat.MAGIC)) {
            throw OTipsException(OTipsError.NOT_A_PACKAGE, "Not an .OTips package")
        }

        val version = data.readUnsignedShort()
        if (version == OTipsFormat.PLAINTEXT_CONTAINER_VERSION) {
            throw OTipsException(OTipsError.OUTDATED, "Unencrypted package from an earlier version")
        }
        if (version != OTipsFormat.CONTAINER_VERSION) {
            throw OTipsException(OTipsError.UNSUPPORTED_FORMAT, "Container version $version is not supported")
        }
        val flags = data.readUnsignedShort()
        if (flags and OTipsFormat.KNOWN_FLAGS.inv() != 0) {
            throw OTipsException(OTipsError.UNSUPPORTED_FORMAT, "Unknown package flags $flags")
        }
        if (flags and OTipsFormat.FLAG_ENCRYPTED == 0) corrupted("version 2 packages are always encrypted")

        val headerLength = data.readInt()
        if (headerLength <= 0 || headerLength > limits.maxHeaderBytes) corrupted("bad header length")
        val headerBytes = ByteArray(headerLength)
        data.readFully(headerBytes)

        // The fixed prefix is authenticated with the header: flags cannot be edited either.
        val prefix = java.nio.ByteBuffer.allocate(OTipsFormat.MAGIC.size + 4)
            .put(OTipsFormat.MAGIC).putShort(version.toShort()).putShort(flags.toShort()).array()
        val (keyId, salt, plainHeader) = OTipsCrypto.openHeader(headerBytes, prefix)
        val json = try {
            JSONObject(String(plainHeader, Charsets.UTF_8))
        } catch (e: JSONException) {
            throw OTipsException(OTipsError.CORRUPTED, "Header is not valid JSON", e)
        }
        val manifest = ExtensionManifest.fromJson(
            json.optJSONObject("manifest") ?: throw OTipsException(OTipsError.INVALID_MANIFEST, "Missing manifest")
        )
        val payload = json.optJSONObject("payload") ?: corrupted("missing payload description")
        val format = payload.optString("format")
        if (format != OTipsFormat.PAYLOAD_FORMAT_ZIP) {
            throw OTipsException(OTipsError.UNSUPPORTED_FORMAT, "Payload format '$format' is not supported")
        }
        val size = payload.optLong("size", -1)
        val sha = payload.optString("sha256")
        if (size < 0 || !SHA256_HEX.matches(sha)) corrupted("bad payload description")

        val encryption = json.optJSONObject("encryption") ?: corrupted("missing encryption parameters")
        if (encryption.optString("algorithm") != OTipsCrypto.ALGORITHM || encryption.optString("kdf") != OTipsCrypto.KDF) {
            throw OTipsException(OTipsError.UNSUPPORTED_FORMAT, "Unsupported encryption ${encryption.optString("algorithm")}")
        }
        val nonce = try {
            encryption.optString("nonce").hexToBytes()
        } catch (e: IllegalArgumentException) {
            corrupted("bad encryption parameters")
        }
        if (nonce.size != OTipsCrypto.NONCE_BYTES) corrupted("bad encryption parameters")
        val params = OTipsCrypto.Params(keyId = keyId, salt = salt, nonce = nonce)

        return OTipsHeader(
            containerVersion = version,
            flags = flags,
            manifest = manifest,
            payloadFormat = format,
            payloadSize = size,
            payloadSha256 = sha.lowercase(),
            createdAt = json.optLong("createdAt", 0L),
            createdBy = json.optString("createdBy"),
            encryption = params,
            headerHash = OTipsCrypto.sha256(headerBytes)
        )
    }

    private fun unzip(input: InputStream, destination: File, limits: OTipsLimits) {
        destination.mkdirs()
        val root = destination.canonicalFile
        val seen = HashSet<String>()
        var total = 0L
        val buffer = ByteArray(BUFFER)
        val zip = ZipInputStream(input)
        while (true) {
            val entry = zip.nextEntry ?: break
            val name = entry.name.trimEnd('/')
            if (entry.isDirectory) continue
            if (!ExtensionManifest.isSafeRelativePath(name)) corrupted("unsafe entry name '$name'")
            if (!seen.add(name)) corrupted("duplicate entry '$name'")
            if (seen.size > limits.maxEntries) throw OTipsException(OTipsError.TOO_LARGE, "Too many files")

            val target = File(root, name)
            // Belt and braces over isSafeRelativePath: resolve and re-check.
            if (!target.canonicalPath.startsWith(root.path + File.separator)) corrupted("entry escapes the package")
            target.parentFile?.mkdirs()
            var written = 0L
            target.outputStream().use { out ->
                while (true) {
                    val n = zip.read(buffer)
                    if (n < 0) break
                    written += n
                    total += n
                    if (written > limits.maxEntryBytes || total > limits.maxUncompressedBytes) {
                        throw OTipsException(OTipsError.TOO_LARGE, "Content is too large once unpacked")
                    }
                    out.write(buffer, 0, n)
                }
            }
        }
        if (seen.isEmpty()) corrupted("the package is empty")
    }

    private inline fun <T> guard(block: () -> T): T = try {
        block()
    } catch (e: OTipsException) {
        throw e
    } catch (e: EOFException) {
        throw OTipsException(OTipsError.CORRUPTED, "The package is truncated", e)
    } catch (e: ZipException) {
        throw OTipsException(OTipsError.CORRUPTED, "The package content is damaged", e)
    } catch (e: IOException) {
        throw OTipsException(OTipsError.IO, e.message ?: "Could not read the package", e)
    }

    private fun corrupted(reason: String): Nothing =
        throw OTipsException(OTipsError.CORRUPTED, "Corrupted package: $reason")

    private fun InputStream.drain() {
        val buffer = ByteArray(BUFFER)
        while (read(buffer) >= 0) Unit
    }

    private val SHA256_HEX = Regex("[0-9a-fA-F]{64}")
    private const val BUFFER = 64 * 1024
}

/** Exposes exactly [limit] bytes of [input]; [remaining] tells whether all of them were there. */
internal class BoundedInputStream(input: InputStream, limit: Long) : FilterInputStream(input) {
    var remaining: Long = limit
        private set

    override fun read(): Int {
        if (remaining <= 0) return -1
        val b = super.read()
        if (b >= 0) remaining-- else throw EOFException("payload truncated")
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0) return -1
        val n = super.read(b, off, minOf(len.toLong(), remaining).toInt())
        if (n < 0) throw EOFException("payload truncated")
        remaining -= n
        return n
    }

    override fun skip(n: Long): Long {
        val skipped = super.skip(minOf(n, remaining))
        remaining -= skipped
        return skipped
    }

    override fun available(): Int = minOf(super.available().toLong(), remaining).toInt()
    override fun markSupported(): Boolean = false
    override fun close() = Unit
}

/** Lets ZipInputStream be dropped without closing the stream it was reading. */
internal class NonClosingInputStream(input: InputStream) : FilterInputStream(input) {
    override fun close() = Unit
}

internal fun ByteArray.toHex(): String {
    val chars = CharArray(size * 2)
    forEachIndexed { i, byte ->
        val v = byte.toInt() and 0xFF
        chars[i * 2] = HEX[v ushr 4]
        chars[i * 2 + 1] = HEX[v and 0x0F]
    }
    return String(chars)
}

private val HEX = "0123456789abcdef".toCharArray()
