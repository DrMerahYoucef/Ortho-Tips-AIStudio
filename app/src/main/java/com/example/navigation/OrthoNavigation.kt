package com.example.navigation

import android.net.Uri

object OrthoDestinations {
    const val MAIN = "main"
    const val SEARCH = "search"
    const val SAVED = "saved"
    const val SETTINGS = "settings"
    const val CHAPTER_BONES = "chapter/{chapterId}/{chapterTitle}"
    const val BONE_DETAIL = "bone/{boneId}"
    const val IMAGE_VIEWER = "image/{imageId}"
    const val IMPORT = "import"

    // Classifications module (static asset-backed reference)
    const val CLASSIFICATION_CHAPTER_DETAIL = "classification_chapter/{chapterFile}"

    // Extensions: .OTips packages (see docs/extensions/guide.md)
    const val EXTENSION_RUNNER = "extension/{extensionId}"
    const val EXTENSION_IMPORT = "extension_import"

    // Classification editor (Word-style)
    const val CLASSIFICATION_EDITOR = "classification_editor"
    const val CLASSIFICATION_DRAFT = "classification_editor/{draftId}"
    const val CLASSIFICATION_DOCUMENT = "classification_editor/{draftId}/document/{chapterFile}"

    // Patient management module
    const val PATIENT_DETAIL = "patient/{patientId}"
    const val PATIENT_ASSISTANT = "patient/{patientId}/assistant"
    const val GEMINI_SETTINGS = "settings/gemini"
    const val PATIENT_EDIT = "patient_edit?patientId={patientId}"
    const val TIMELINE_EVENT_EDIT = "patient/{patientId}/timeline_edit?eventId={eventId}"
    const val SURGERY_EDIT = "patient/{patientId}/surgery_edit?surgeryId={surgeryId}"
    const val SURGEON_PROFILE = "surgeon_profile"
    const val PATIENT_DATA_IMPORT_EXPORT = "patient_data_transfer"
    const val MEDIA_CAPTURE = "patient/{patientId}/capture"
    const val IMAGE_ANNOTATE = "image_annotate/{imageId}"
    const val VIDEO_PLAYER = "video/{mediaId}"
    const val QR_SCANNER = "qr_scanner"

    // Optional Telegram Drive storage backend
    const val TELEGRAM_DRIVE_SETTINGS = "telegram_drive"
    const val TELEGRAM_DRIVE_SETUP = "telegram_drive_setup"
    const val TELEGRAM_DRIVE_DIAGNOSTICS = "telegram_drive_diagnostics"

    fun chapterBones(chapterId: String, chapterTitle: String): String {
        return "chapter/${Uri.encode(chapterId)}/${Uri.encode(chapterTitle)}"
    }

    fun boneDetail(boneId: String): String {
        return "bone/${Uri.encode(boneId)}"
    }

    fun imageViewer(imageId: String): String {
        return "image/${Uri.encode(imageId)}"
    }

    /** [chapterFile] is the "file" field from a classification ChapterRef, e.g. "data/epaule.json". */
    fun classificationChapterDetail(chapterFile: String): String {
        return "classification_chapter/${Uri.encode(chapterFile)}"
    }

    fun extensionRunner(extensionId: String): String = "extension/${Uri.encode(extensionId)}"

    fun classificationDraft(draftId: String): String = "classification_editor/${Uri.encode(draftId)}"

    fun classificationDocument(draftId: String, chapterFile: String): String =
        "classification_editor/${Uri.encode(draftId)}/document/${Uri.encode(chapterFile)}"

    fun patientAssistant(patientId: String): String = "patient/${Uri.encode(patientId)}/assistant"

    fun patientDetail(patientId: String): String {
        return "patient/${Uri.encode(patientId)}"
    }

    /** [patientId] null/blank means "create a new patient". */
    fun patientEdit(patientId: String? = null): String {
        return if (patientId.isNullOrBlank()) "patient_edit" else "patient_edit?patientId=${Uri.encode(patientId)}"
    }

    fun timelineEventEdit(patientId: String, eventId: String? = null): String {
        val base = "patient/${Uri.encode(patientId)}/timeline_edit"
        return if (eventId.isNullOrBlank()) base else "$base?eventId=${Uri.encode(eventId)}"
    }

    fun surgeryEdit(patientId: String, surgeryId: String? = null): String {
        val base = "patient/${Uri.encode(patientId)}/surgery_edit"
        return if (surgeryId.isNullOrBlank()) base else "$base?surgeryId=${Uri.encode(surgeryId)}"
    }

    fun mediaCapture(patientId: String): String = "patient/${Uri.encode(patientId)}/capture"

    fun imageAnnotate(imageId: String): String = "image_annotate/${Uri.encode(imageId)}"

    fun videoPlayer(mediaId: String): String = "video/${Uri.encode(mediaId)}"
}
