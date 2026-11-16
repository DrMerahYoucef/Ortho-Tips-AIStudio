package com.example.utils

import android.content.Context
import java.io.File

/**
 * Resolves the on-disk layout for patient media, kept entirely under app-private
 * storage (never MediaStore) so it is sandboxed, requires no runtime storage
 * permission, and is fully captured by the database ZIP export/import.
 *
 * Layout:
 *   <filesDir>/patients/<patientId>/images/<imageId>.jpg
 *   <filesDir>/patients/<patientId>/images/thumbs/<imageId>.jpg
 *   <filesDir>/patients/<patientId>/documents/<documentId>.pdf
 */
object PatientMediaStorage {

    private const val ROOT_DIR_NAME = "patients"
    private const val IMAGES_DIR_NAME = "images"
    private const val THUMBS_DIR_NAME = "thumbs"
    private const val DOCUMENTS_DIR_NAME = "documents"
    private const val VIDEOS_DIR_NAME = "videos"

    fun rootDir(context: Context): File = File(context.filesDir, ROOT_DIR_NAME)

    fun patientDir(context: Context, patientId: String): File =
        File(rootDir(context), patientId)

    fun imagesDir(context: Context, patientId: String): File =
        File(patientDir(context, patientId), IMAGES_DIR_NAME).also { it.mkdirs() }

    fun thumbsDir(context: Context, patientId: String): File =
        File(imagesDir(context, patientId), THUMBS_DIR_NAME).also { it.mkdirs() }

    fun documentsDir(context: Context, patientId: String): File =
        File(patientDir(context, patientId), DOCUMENTS_DIR_NAME).also { it.mkdirs() }

    fun newImageFile(context: Context, patientId: String, imageId: String): File =
        File(imagesDir(context, patientId), "$imageId.jpg")

    fun newThumbnailFile(context: Context, patientId: String, imageId: String): File =
        File(thumbsDir(context, patientId), "$imageId.jpg")

    fun videosDir(context: Context, patientId: String): File =
        File(patientDir(context, patientId), VIDEOS_DIR_NAME).also { it.mkdirs() }

    fun newVideoFile(context: Context, patientId: String, mediaId: String, extension: String = "mp4"): File =
        File(videosDir(context, patientId), "$mediaId.$extension")

    fun newDocumentFile(context: Context, patientId: String, documentId: String, extension: String): File =
        File(documentsDir(context, patientId), "$documentId.$extension")

    fun deletePatientFiles(context: Context, patientId: String) {
        val dir = patientDir(context, patientId)
        if (dir.exists()) dir.deleteRecursively()
    }
}
