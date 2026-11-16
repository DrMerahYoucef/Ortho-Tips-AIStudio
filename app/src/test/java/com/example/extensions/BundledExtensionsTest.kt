package com.example.extensions

import com.example.data.extensions.ClassificationsExtensionHandler
import com.example.data.extensions.ExtensionHandler
import com.example.data.extensions.ExtensionKinds
import com.example.data.extensions.ExtensionManifest
import com.example.data.extensions.HtmlExtensionHandler
import com.example.data.extensions.OperativeGuideExtensionHandler
import com.example.data.extensions.otips.OTipsReader
import com.example.data.extensions.otips.OTipsWriter
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

/**
 * Every bundled extension source (`src/extensions/<id>/`) must pack into an
 * `.OTips` package that the app reads back and whose handler accepts it —
 * the same packing the build does in `packBundledExtensions`. A broken
 * manifest, a missing chapter file or a missing figure fails here, not on a
 * phone after installing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BundledExtensionsTest {

    private val sources = File("src/extensions")

    private val handlers: Map<String, ExtensionHandler> = listOf(
        HtmlExtensionHandler(),
        OperativeGuideExtensionHandler { error("Not used when validating") },
        ClassificationsExtensionHandler()
    ).associateBy { it.kind }

    private fun folders(): List<File> =
        sources.listFiles()?.filter { File(it, MANIFEST).isFile }?.sortedBy { it.name }.orEmpty()

    @Test
    fun everyBundledSourcePacksIntoAnInstallablePackage() {
        val folders = folders()
        assertTrue("No extension sources found in ${sources.absolutePath}", folders.isNotEmpty())

        for (folder in folders) {
            val manifest = ExtensionManifest.fromJson(JSONObject(File(folder, MANIFEST).readText()))
            assertEquals("Folder name must be the id", folder.name, manifest.id)
            val handler = handlers[manifest.kind] ?: error("No handler for kind ${manifest.kind} (${folder.name})")

            val packed = ByteArrayOutputStream().also { out ->
                OTipsWriter.write(manifest, File(folder, CONTENT), out, Files.createTempDirectory("otips").toFile(), "test", 0L)
            }.toByteArray()
            val unpacked = Files.createTempDirectory("unpacked").toFile()
            val header = OTipsReader.extract(packed.inputStream(), unpacked, maxPayloadBytes = handler.maxPayloadBytes)

            assertEquals(manifest, header.manifest)
            handler.validate(header.manifest, unpacked)
        }
    }

    @Test
    fun theAppShipsTheOperativeGuideAndTheClassifications() {
        val kinds = folders().associate { it.name to ExtensionManifest.fromJson(JSONObject(File(it, MANIFEST).readText())).kind }
        assertEquals(ExtensionKinds.OPERATIVE_GUIDE, kinds[OperativeGuideExtensionHandler.ID])
        assertEquals(ExtensionKinds.CLASSIFICATIONS, kinds[ClassificationsExtensionHandler.ID])
    }

    @Test
    fun theOperativeGuideShipsWithoutItsData() {
        // The guide's content is the user's own archive, imported after install.
        val content = File(sources, "${OperativeGuideExtensionHandler.ID}/$CONTENT")
        val size = content.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        assertTrue("The bundled guide must stay small, was $size bytes", size < 64 * 1024)
    }

    private companion object {
        const val MANIFEST = "manifest.json"
        const val CONTENT = "content"
    }
}
