package com.example.data.extensions

import java.io.File
import java.io.IOException

/**
 * `OrthoTips.files` (extension guide, section 5, `storage` permission): a
 * private folder per extension for data too big for the key/value store.
 * Paths are relative and checked like package entries, so an extension can
 * never read or write outside its own folder.
 */
class ExtensionFiles(private val root: File) {

    fun write(path: String, bytes: ByteArray) {
        val target = resolve(path)
        if (bytes.size > MAX_FILE_BYTES) throw IOException("File exceeds ${MAX_FILE_BYTES / (1024 * 1024)} MB")
        val existing = if (target.isFile) target.length() else 0L
        if (usedBytes() - existing + bytes.size > MAX_TOTAL_BYTES) throw IOException("Extension storage is full")
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, ".${target.name}.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) {
                tmp.delete()
                throw IOException("Could not write $path")
            }
        }
    }

    fun read(path: String): ByteArray? = resolve(path).takeIf { it.isFile }?.readBytes()

    fun delete(path: String): Boolean = resolve(path).let { it.isFile && it.delete() }

    /** Sorted relative paths starting with [prefix] (`""` for all). */
    fun list(prefix: String): List<String> {
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown()
            .filter { it.isFile && !it.name.startsWith(".") }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .filter { it.startsWith(prefix) }
            .sorted()
            .toList()
    }

    private fun usedBytes(): Long =
        if (root.isDirectory) root.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L

    private fun resolve(path: String): File {
        val clean = path.trim().trimStart('/')
        if (!ExtensionManifest.isSafeRelativePath(clean)) throw IllegalArgumentException("Invalid path '$path'")
        return File(root, clean)
    }

    companion object {
        const val MAX_FILE_BYTES = 50 * 1024 * 1024
        const val MAX_TOTAL_BYTES = 256L * 1024 * 1024
    }
}
