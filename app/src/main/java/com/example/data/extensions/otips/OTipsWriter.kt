package com.example.data.extensions.otips

import com.example.data.extensions.ExtensionManifest
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes `.OTips` packages (layout in [OTipsFormat]), always encrypted.
 *
 * The plain ZIP is deterministic: entries are sorted and carry a fixed
 * timestamp, so the same content always produces the same payload hash, even
 * though every package is encrypted with its own random key and nonce. That
 * is what lets the app tell "already installed" from "changed".
 *
 * Plain Java only, so the build can package the bundled extensions with the
 * very same code the app uses to read them.
 */
object OTipsWriter {

    /** Packages [entries] (path → bytes). Suited to small content such as one HTML file. */
    fun write(
        manifest: ExtensionManifest,
        entries: Map<String, ByteArray>,
        output: OutputStream,
        createdBy: String,
        createdAt: Long = System.currentTimeMillis(),
        encrypt: Boolean = true
    ) {
        val zip = java.io.ByteArrayOutputStream()
        zipEntries(entries.toSortedMap().entries.map { it.key to it.value }, zip)
        val payload = zip.toByteArray()
        writeContainer(manifest, payload.size.toLong(), sha256(payload), output, createdBy, createdAt, encrypt) { out ->
            out.write(payload)
        }
    }

    /**
     * Packages every file under [contentDir]. The ZIP is staged in [workDir]
     * because the header must state its size and hash before its bytes.
     */
    fun write(
        manifest: ExtensionManifest,
        contentDir: File,
        output: OutputStream,
        workDir: File,
        createdBy: String,
        createdAt: Long = System.currentTimeMillis(),
        encrypt: Boolean = true
    ) {
        workDir.mkdirs()
        val staged = File.createTempFile("payload", ".zip", workDir)
        try {
            val files = contentDir.walkTopDown().filter { it.isFile }
                .map { it.relativeTo(contentDir).invariantSeparatorsPath to it }
                .sortedBy { it.first }
                .toList()
            staged.outputStream().buffered().use { out ->
                val zip = ZipOutputStream(NonClosingOutputStream(out))
                for ((path, file) in files) {
                    require(ExtensionManifest.isSafeRelativePath(path)) { "Unsafe file name: $path" }
                    zip.putNextEntry(entry(path))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
                zip.finish()
            }
            val hash = staged.inputStream().use { sha256(it) }
            writeContainer(manifest, staged.length(), hash, output, createdBy, createdAt, encrypt) { out ->
                staged.inputStream().use { it.copyTo(out) }
            }
        } finally {
            staged.delete()
        }
    }

    // ---- internals ---------------------------------------------------------

    /**
     * [plainSize] and [plainSha256] describe the plain ZIP that [writePayload]
     * produces. With [encrypt] (always, outside tests) it is encrypted on the fly.
     */
    private fun writeContainer(
        manifest: ExtensionManifest,
        plainSize: Long,
        plainSha256: String,
        output: OutputStream,
        createdBy: String,
        createdAt: Long,
        encrypt: Boolean,
        writePayload: (OutputStream) -> Unit
    ) {
        val params = if (encrypt) OTipsCrypto.newParams() else null
        val storedSize = if (params != null) OTipsCrypto.cipherSize(plainSize) else plainSize
        val headerJson = JSONObject()
            .put("manifest", manifest.toJson())
            .put(
                "payload",
                JSONObject()
                    .put("format", OTipsFormat.PAYLOAD_FORMAT_ZIP)
                    .put("size", storedSize)
                    .put("plainSize", plainSize)
                    .put("sha256", plainSha256)
            )
            .put("createdAt", createdAt)
            .put("createdBy", createdBy)
        if (params != null) {
            headerJson.put(
                "encryption",
                JSONObject()
                    .put("algorithm", OTipsCrypto.ALGORITHM)
                    .put("kdf", OTipsCrypto.KDF)
                    .put("nonce", params.nonce.toHex())
            )
        }
        val version = if (params != null) OTipsFormat.CONTAINER_VERSION else OTipsFormat.PLAINTEXT_CONTAINER_VERSION
        val flags = if (params != null) OTipsFormat.FLAG_ENCRYPTED else 0
        val plainHeader = headerJson.toString().toByteArray(Charsets.UTF_8)
        val header = if (params != null) {
            val prefix = java.nio.ByteBuffer.allocate(OTipsFormat.MAGIC.size + 4)
                .put(OTipsFormat.MAGIC).putShort(version.toShort()).putShort(flags.toShort()).array()
            OTipsCrypto.sealHeader(plainHeader, params, prefix)
        } else {
            plainHeader
        }

        val digest = MessageDigest.getInstance(OTipsFormat.HASH_ALGORITHM)
        val buffered = BufferedOutputStream(NonClosingOutputStream(output))
        val hashed = DigestOutputStream(buffered, digest)
        val data = DataOutputStream(hashed)
        data.write(OTipsFormat.MAGIC)
        data.writeShort(version)
        data.writeShort(flags)
        data.writeInt(header.size)
        data.write(header)
        data.flush()
        val counted = CountingOutputStream(hashed)
        if (params != null) {
            val sealed = OTipsCrypto.encryptingStream(counted, params, OTipsCrypto.sha256(header))
            writePayload(sealed)
            sealed.close() // seals the last chunk; the file stream stays open
        } else {
            writePayload(counted)
        }
        check(counted.count == storedSize) { "Payload is ${counted.count} bytes, header says $storedSize" }
        data.writeInt(0) // signature length: unsigned
        data.flush()
        hashed.on(false)
        buffered.write(digest.digest())
        buffered.flush()
    }

    private fun zipEntries(entries: List<Pair<String, ByteArray>>, output: OutputStream) {
        val zip = ZipOutputStream(output)
        for ((path, bytes) in entries) {
            require(ExtensionManifest.isSafeRelativePath(path)) { "Unsafe file name: $path" }
            zip.putNextEntry(entry(path))
            zip.write(bytes)
            zip.closeEntry()
        }
        zip.finish()
    }

    private fun entry(path: String) = ZipEntry(path).apply { time = fixedEntryTime() }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance(OTipsFormat.HASH_ALGORITHM).digest(bytes).toHex()

    private fun sha256(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance(OTipsFormat.HASH_ALGORITHM)
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
        return digest.digest().toHex()
    }

    /**
     * Midnight, 1 January 2000, in the local time zone. ZIP stores local "DOS"
     * time, so a fixed local date (not a fixed instant) is what makes the same
     * content produce the same bytes on every machine, whatever its time zone.
     */
    private fun fixedEntryTime(): Long = java.util.Calendar.getInstance().apply {
        clear()
        set(2000, java.util.Calendar.JANUARY, 1, 0, 0, 0)
    }.timeInMillis
}

private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
    var count = 0L
        private set

    override fun write(b: Int) {
        out.write(b)
        count++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len)
        count += len
    }

    override fun close() = flush()
}

private class NonClosingOutputStream(out: OutputStream) : FilterOutputStream(out) {
    override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
    override fun close() = flush()
}
