package com.example.extensions

import com.example.data.extensions.ExtensionDatabaseQuery
import com.example.data.extensions.ExtensionError
import com.example.data.extensions.ExtensionFiles
import com.example.data.extensions.ExtensionInstallException
import com.example.data.extensions.ExtensionKinds
import com.example.data.extensions.ExtensionManifest
import com.example.data.extensions.ExtensionMetadata
import com.example.data.extensions.ExtensionPermission
import com.example.data.extensions.ExtensionVersion
import com.example.data.extensions.HtmlExtensionHandler
import com.example.data.extensions.otips.OTipsCrypto
import com.example.data.extensions.otips.OTipsError
import com.example.data.extensions.otips.OTipsException
import com.example.data.extensions.otips.OTipsReader
import com.example.data.extensions.otips.OTipsWriter
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.random.Random

/**
 * The `.OTips` container is the only gate between a file from anywhere and
 * the app, so these check that it refuses every kind of damage before anything
 * is installed. Robolectric only supplies a working `org.json`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OTipsPackageTest {

    private lateinit var tmp: File

    private val html = """<!DOCTYPE html><html><head><meta charset="utf-8">
        <meta name="ortho-ext-id" content="dems-programme">
        <meta name="ortho-ext-name" content="Programme DEMS">
        <meta name="ortho-ext-version" content="2.1">
        <meta name="ortho-ext-permissions" content="internet, database">
        </head><body>x</body></html>"""

    @Before
    fun setUp() {
        tmp = Files.createTempDirectory("otips").toFile()
    }

    @After
    fun tearDown() {
        tmp.deleteRecursively()
    }

    private fun pack(manifest: ExtensionManifest = ExtensionMetadata.manifestFor(html, null)): ByteArray {
        val out = ByteArrayOutputStream()
        OTipsWriter.write(manifest, mapOf("index.html" to html.toByteArray(), "data/q.json" to "{}".toByteArray()), out, "test", 0)
        return out.toByteArray()
    }

    @Test
    fun roundTripKeepsManifestAndContent() {
        val bytes = pack()
        val header = OTipsReader.extract(ByteArrayInputStream(bytes), File(tmp, "out"))
        assertEquals("dems-programme", header.manifest.id)
        assertEquals(ExtensionKinds.HTML, header.manifest.kind)
        assertEquals(setOf(ExtensionPermission.INTERNET, ExtensionPermission.DATABASE), header.manifest.permissions)
        assertEquals(html, File(tmp, "out/index.html").readText())
        assertEquals("{}", File(tmp, "out/data/q.json").readText())
    }

    @Test
    fun sameContentGivesSamePayloadHash() {
        val a = OTipsReader.readHeader(ByteArrayInputStream(pack()))
        val b = OTipsReader.readHeader(ByteArrayInputStream(pack()))
        assertEquals(a.payloadSha256, b.payloadSha256)
    }

    @Test
    fun everyAlteredByteIsRefused() {
        val bytes = pack()
        for (position in listOf(0, 9, 13, 20, bytes.size / 2, bytes.size - 40, bytes.size - 1)) {
            val damaged = bytes.copyOf().also { it[position] = (it[position].toInt() xor 1).toByte() }
            try {
                OTipsReader.extract(ByteArrayInputStream(damaged), File(tmp, "c$position"))
                fail("A flipped bit at $position was accepted")
            } catch (expected: OTipsException) {
                // refused
            }
        }
    }

    @Test
    fun truncatedOrPaddedFilesAreCorrupted() {
        val bytes = pack()
        assertError(OTipsError.CORRUPTED) { OTipsReader.extract(ByteArrayInputStream(bytes.copyOf(bytes.size - 10)), File(tmp, "t")) }
        assertError(OTipsError.CORRUPTED) { OTipsReader.extract(ByteArrayInputStream(bytes + byteArrayOf(1)), File(tmp, "p")) }
    }

    @Test
    fun plainHtmlIsNotAPackage() {
        assertError(OTipsError.NOT_A_PACKAGE) { OTipsReader.readHeader(ByteArrayInputStream(html.toByteArray())) }
    }

    @Test
    fun unknownFlagsNeedANewerApp() {
        val bytes = pack().also { it[11] = 0x10 }
        assertError(OTipsError.UNSUPPORTED_FORMAT) { OTipsReader.readHeader(ByteArrayInputStream(bytes)) }
    }

    @Test
    fun kindLimitIsEnforcedBeforeUnpacking() {
        assertError(OTipsError.TOO_LARGE) {
            OTipsReader.extract(ByteArrayInputStream(pack()), File(tmp, "l"), maxPayloadBytes = 10)
        }
        assertTrue(File(tmp, "l").listFiles().isNullOrEmpty())
    }

    @Test
    fun directoryPackagingKeepsBinaryFiles() {
        val source = File(tmp, "src").apply { File(this, "img").mkdirs() }
        File(source, "index.html").writeText(html)
        val blob = ByteArray(300_000) { (it * 7).toByte() }
        File(source, "img/a.bin").writeBytes(blob)
        val out = ByteArrayOutputStream()
        OTipsWriter.write(ExtensionMetadata.manifestFor(html, null), source, out, File(tmp, "work"), "test")
        OTipsReader.extract(ByteArrayInputStream(out.toByteArray()), File(tmp, "o"))
        assertArrayEquals(blob, File(tmp, "o/img/a.bin").readBytes())
    }

    @Test
    fun manifestRejectsUnsafeValues() {
        val base = ExtensionMetadata.manifestFor(html, null).toJson()
        for ((key, value) in listOf("id" to "../x", "kind" to "Bad Kind", "entry" to "../index.html", "name" to "")) {
            assertError(OTipsError.INVALID_MANIFEST) { ExtensionManifest.fromJson(JSONObject(base.toString()).put(key, value)) }
        }
        for (path in listOf("../x", "/x", "a/./b", "a\\b", "")) assertTrue(!ExtensionManifest.isSafeRelativePath(path))
    }

    @Test
    fun versionsOrderNumerically() {
        assertTrue(ExtensionVersion.compare("2.10", "2.9") > 0)
        assertEquals(0, ExtensionVersion.compare("1.0", "1"))
        assertTrue(ExtensionVersion.compare("1.0", "1.0-beta") > 0)
        assertTrue(ExtensionVersion.compare("3.0.1", "3.0") > 0)
    }

    @Test
    fun programsWithoutPermissionTagKeepTheirExport() {
        val legacy = ExtensionMetadata.manifestFor(
            "<html><head><title>Mon outil</title><meta name=\"ortho-ext-network\" content=\"allow\"></head></html>",
            null
        )
        assertEquals("mon-outil", legacy.id)
        assertEquals(setOf(ExtensionPermission.IMPORT_EXPORT, ExtensionPermission.INTERNET), legacy.permissions)

        val strict = ExtensionMetadata.manifestFor(
            "<html><head><meta name=\"ortho-ext-permissions\" content=\"\"></head></html>",
            "outil.html"
        )
        assertEquals(emptySet<ExtensionPermission>(), strict.permissions)
    }

    @Test
    fun htmlHandlerChecksTheEntryPage() {
        val content = File(tmp, "c").apply { mkdirs() }
        File(content, "index.html").writeText(html)
        File(content, "data.json").writeText("{}")
        val manifest = ExtensionMetadata.manifestFor(html, null)
        HtmlExtensionHandler().validate(manifest, content)
        try {
            HtmlExtensionHandler().validate(manifest.copy(entry = "data.json"), content)
            fail("JSON accepted as a page")
        } catch (e: ExtensionInstallException) {
            assertEquals(ExtensionError.NOT_HTML, e.error)
        }
    }

    @Test
    fun privateFilesStayInsideTheirFolder() {
        val files = ExtensionFiles(File(tmp, "files"))
        files.write("notes/a.txt", "hello".toByteArray())
        assertEquals("hello", String(files.read("notes/a.txt")!!))
        assertEquals(listOf("notes/a.txt"), files.list(""))
        for (bad in listOf("../x", "a/../../b", "a\\b")) {
            try {
                files.write(bad, byteArrayOf(1))
                fail("$bad accepted")
            } catch (expected: IllegalArgumentException) {
                // refused
            }
        }
        assertTrue(files.delete("notes/a.txt"))
        assertNull(files.read("notes/a.txt"))
    }

    @Test
    fun databaseQueriesOnlyReachReferenceColumns() {
        val sql = ExtensionDatabaseQuery.build(
            JSONObject("""{"table":"bones","where":{"chapterId":"c1"},"search":"50%","limit":9999}""")
        )
        assertTrue(sql.sql.endsWith("LIMIT 500 OFFSET 0"))
        assertEquals("c1", sql.args.first())
        for (spec in listOf(
            """{"table":"patients"}""",
            """{"table":"bones","where":{"imagePath":"x"}}""",
            """{"table":"images","orderBy":"imagePath"}"""
        )) {
            try {
                ExtensionDatabaseQuery.build(JSONObject(spec))
                fail("$spec accepted")
            } catch (expected: IllegalArgumentException) {
                // refused
            }
        }
    }

    // ---- encryption (Phase 5) ------------------------------------------------

    /** What an attacker can do without the key: change bytes, then recompute the file checksum. */
    private fun retrail(bytes: ByteArray): ByteArray {
        val body = bytes.copyOf(bytes.size - 32)
        return body + MessageDigest.getInstance("SHA-256").digest(body)
    }

    private fun packRandom(size: Int, seed: Int = size): ByteArray {
        val out = ByteArrayOutputStream()
        OTipsWriter.write(
            ExtensionMetadata.manifestFor(html, null),
            mapOf("index.html" to html.toByteArray(), "blob.bin" to Random(seed).nextBytes(size)),
            out, "test", 0
        )
        return out.toByteArray()
    }

    @Test
    fun nothingIsReadableWithoutTheApp() {
        val text = String(pack(), Charsets.ISO_8859_1)
        for (secret in listOf("Programme DEMS", "dems-programme", "<html", "ortho-ext", "manifest", "index.html")) {
            assertTrue("'$secret' is visible in the file", !text.contains(secret))
        }
    }

    @Test
    fun everyPackageIsEncryptedDifferentlyButKeepsItsContentIdentity() {
        val a = pack()
        val b = pack()
        assertTrue(!a.contentEquals(b))
        assertEquals(
            OTipsReader.readHeader(ByteArrayInputStream(a)).payloadSha256,
            OTipsReader.readHeader(ByteArrayInputStream(b)).payloadSha256
        )
    }

    @Test
    fun chunkBoundariesRoundTrip() {
        val chunk = OTipsCrypto.CHUNK_SIZE
        for (size in listOf(0, 1, chunk - 200, chunk, chunk + 1, 3 * chunk + 17)) {
            val out = File(tmp, "s$size")
            OTipsReader.extract(ByteArrayInputStream(packRandom(size)), out)
            assertArrayEquals("size $size", Random(size).nextBytes(size), File(out, "blob.bin").readBytes())
        }
    }

    @Test
    fun editedContentIsRefusedEvenWithARecomputedChecksum() {
        val bytes = packRandom(3 * OTipsCrypto.CHUNK_SIZE)
        val payloadByte = bytes.size - 32 - 4 - 1000
        val edited = bytes.copyOf().also { it[payloadByte] = (it[payloadByte].toInt() xor 1).toByte() }
        assertError(OTipsError.CORRUPTED) { OTipsReader.extract(ByteArrayInputStream(retrail(edited)), File(tmp, "e")) }
    }

    @Test
    fun reorderedChunksAreRefused() {
        val bytes = packRandom(3 * OTipsCrypto.CHUNK_SIZE)
        val sealed = OTipsCrypto.CHUNK_SIZE + OTipsCrypto.TAG_BYTES
        val payloadEnd = bytes.size - 32 - 4
        // Swap the first two full chunks of the payload.
        val header = OTipsReader.readHeader(ByteArrayInputStream(bytes))
        val start = payloadEnd - header.payloadSize.toInt()
        val swapped = bytes.copyOf()
        System.arraycopy(bytes, start, swapped, start + sealed, sealed)
        System.arraycopy(bytes, start + sealed, swapped, start, sealed)
        assertError(OTipsError.CORRUPTED) { OTipsReader.extract(ByteArrayInputStream(retrail(swapped)), File(tmp, "r")) }
    }

    @Test
    fun editedHeaderOrFlagsAreRefused() {
        val bytes = pack()
        val inHeader = bytes.copyOf().also { it[60] = (it[60].toInt() xor 1).toByte() }
        assertError(OTipsError.CORRUPTED) { OTipsReader.readHeader(ByteArrayInputStream(retrail(inHeader))) }
        val noFlags = bytes.copyOf().also { it[11] = 0 }
        assertError(OTipsError.CORRUPTED) { OTipsReader.readHeader(ByteArrayInputStream(retrail(noFlags))) }
    }

    @Test
    fun unencryptedPackagesFromEarlierBuildsAreOutdated() {
        val out = ByteArrayOutputStream()
        OTipsWriter.write(ExtensionMetadata.manifestFor(html, null), mapOf("index.html" to html.toByteArray()), out, "old", 0, encrypt = false)
        assertError(OTipsError.OUTDATED) { OTipsReader.readHeader(ByteArrayInputStream(out.toByteArray())) }
    }

    private fun assertError(expected: OTipsError, block: () -> Unit) {
        try {
            block()
            fail("Expected $expected")
        } catch (e: OTipsException) {
            assertEquals(expected, e.error)
        }
    }
}
