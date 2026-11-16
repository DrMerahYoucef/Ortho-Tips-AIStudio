package com.example.utils

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import com.example.data.local.entity.PatientEntity
import com.example.data.local.entity.SurgeryEntity
import com.example.data.local.entity.TimelineEventEntity
import com.example.data.local.model.calculateAge
import com.example.data.prefs.SurgeonProfile
import java.io.File
import java.io.FileOutputStream

/** Which sections the surgeon chose to include when exporting a patient's chart to PDF. */
data class PatientChartPdfOptions(
    val includeContact: Boolean = true,
    val includeInsurance: Boolean = true,
    val includeMedicalHistory: Boolean = true,
    val includeAllergies: Boolean = true,
    val includeChronicDiseases: Boolean = true,
    val includePreviousSurgeries: Boolean = true,
    val includeMedications: Boolean = true,
    val includeNotes: Boolean = true,
    val includeOrthopedicInfo: Boolean = true,
    val includeTimeline: Boolean = true,
    val includeSurgeries: Boolean = true,
    /** Encodes the patient's id — left off by default whenever [PatientChartPdfGenerator.generate]'s
     *  own `anonymize` flag is on, since it would otherwise defeat the point of anonymizing. */
    val includeQrCode: Boolean = true
)

/**
 * Renders the patient's full chart (demographics, medical & orthopedic info, timeline,
 * surgery summaries) as a paginated PDF, using the same manual-layout approach as
 * [OperativeProtocolGenerator] — no third-party PDF library needed.
 */
object PatientChartPdfGenerator {

    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val SIDEBAR_WIDTH = 178f
    private const val SIDEBAR_PADDING = 18f
    private const val MAIN_LEFT = SIDEBAR_WIDTH + 26f
    private const val MAIN_RIGHT = PAGE_WIDTH - 26f
    private const val MAIN_CONTENT_WIDTH = MAIN_RIGHT - MAIN_LEFT
    private const val TOP_MARGIN = 36f
    private const val FOOTER_RESERVE = 46f
    private const val QR_SIZE = 68f

    private val COLOR_SIDEBAR_BG = 0xFFF6F6F7.toInt()
    private val COLOR_DIVIDER = 0xFFD9D9DC.toInt()
    private val COLOR_TEXT_DARK = 0xFF1A1A1A.toInt()
    private val COLOR_TEXT_MUTED = 0xFF6B6B6E.toInt()
    private val COLOR_ACCENT = 0xFF14495E.toInt()
    private val COLOR_LOGO_FILL = 0xFFE4E9EC.toInt()
    private val COLOR_LOGO_BORDER = 0xFFB3BCC2.toInt()
    private val COLOR_LOGO_TEXT = 0xFF37474F.toInt()

    fun generate(
        context: Context,
        patient: PatientEntity,
        timelineEvents: List<TimelineEventEntity>,
        surgeries: List<SurgeryEntity>,
        surgeon: SurgeonProfile,
        anonymize: Boolean,
        options: PatientChartPdfOptions = PatientChartPdfOptions()
    ): File {
        val document = PdfDocument()
        var pageNumber = 1
        lateinit var page: PdfDocument.Page
        lateinit var canvas: Canvas
        var y = TOP_MARGIN

        val titlePaint = Paint().apply { textSize = 20f; isFakeBoldText = true; isAntiAlias = true; color = COLOR_TEXT_DARK }
        val subheaderPaint = Paint().apply { textSize = 11.5f; isFakeBoldText = true; isAntiAlias = true; color = COLOR_ACCENT; letterSpacing = 0.06f }
        val sectionPaint = Paint().apply { textSize = 12.5f; isFakeBoldText = true; isAntiAlias = true; color = COLOR_ACCENT }
        val bodyPaint = Paint().apply { textSize = 11f; isAntiAlias = true; color = COLOR_TEXT_DARK }
        val labelPaint = Paint().apply { textSize = 8.6f; isAntiAlias = true; color = COLOR_TEXT_MUTED; letterSpacing = 0.03f }
        val valuePaint = Paint().apply { textSize = 10.8f; isFakeBoldText = true; isAntiAlias = true; color = COLOR_TEXT_DARK }
        val dividerPaint = Paint().apply { color = COLOR_DIVIDER; strokeWidth = 1f }
        val captionPaint = Paint().apply { textSize = 8f; isAntiAlias = true; color = COLOR_TEXT_MUTED }
        val sidebarNamePaint = Paint().apply { textSize = 14f; isFakeBoldText = true; isAntiAlias = true; color = COLOR_TEXT_DARK; textAlign = Paint.Align.CENTER }
        val sidebarRolePaint = Paint().apply { textSize = 9.5f; isAntiAlias = true; color = COLOR_ACCENT; textAlign = Paint.Align.CENTER }
        val sidebarSubPaint = Paint().apply { textSize = 8.6f; isAntiAlias = true; color = COLOR_TEXT_MUTED; textAlign = Paint.Align.CENTER }
        val sidebarClinicPaint = Paint().apply { textSize = 11f; isFakeBoldText = true; isAntiAlias = true; color = COLOR_TEXT_DARK; textAlign = Paint.Align.CENTER }
        val sidebarLabelPaint = Paint().apply { textSize = 8f; isFakeBoldText = true; isAntiAlias = true; color = COLOR_TEXT_MUTED; letterSpacing = 0.08f; textAlign = Paint.Align.CENTER }
        val sidebarDetailPaint = Paint().apply { textSize = 9f; isAntiAlias = true; color = COLOR_TEXT_DARK }
        val logoInitialsPaint = Paint().apply { textSize = 19f; isFakeBoldText = true; isAntiAlias = true; color = COLOR_LOGO_TEXT; textAlign = Paint.Align.CENTER }

        fun drawSidebarWrapped(text: String, startY: Float): Float {
            if (text.isBlank()) return startY
            val maxWidth = SIDEBAR_WIDTH - SIDEBAR_PADDING * 2
            var line = StringBuilder()
            var cy = startY
            text.split(" ").forEach { word ->
                val trial = if (line.isEmpty()) word else "$line $word"
                if (sidebarDetailPaint.measureText(trial) > maxWidth) {
                    canvas.drawText(line.toString(), SIDEBAR_PADDING, cy, sidebarDetailPaint)
                    cy += 11f
                    line = StringBuilder(word)
                } else line = StringBuilder(trial)
            }
            if (line.isNotEmpty()) {
                canvas.drawText(line.toString(), SIDEBAR_PADDING, cy, sidebarDetailPaint)
                cy += 11f
            }
            return cy
        }

        fun drawSidebar() {
            canvas.drawRect(0f, 0f, SIDEBAR_WIDTH, PAGE_HEIGHT.toFloat(), Paint().apply { color = COLOR_SIDEBAR_BG })
            canvas.drawLine(SIDEBAR_WIDTH, 0f, SIDEBAR_WIDTH, PAGE_HEIGHT.toFloat(), dividerPaint)
            val centerX = SIDEBAR_WIDTH / 2f
            var sy = 46f
            val logoSize = 60f
            val logoBitmap = surgeon.logoImagePath?.takeIf { File(it).exists() }?.let(BitmapFactory::decodeFile)
            if (logoBitmap != null && logoBitmap.width > 0 && logoBitmap.height > 0) {
                val scale = minOf(logoSize / logoBitmap.width, logoSize / logoBitmap.height)
                val logoWidth = logoBitmap.width * scale
                val logoHeight = logoBitmap.height * scale
                canvas.drawBitmap(
                    logoBitmap,
                    null,
                    RectF(centerX - logoWidth / 2f, sy, centerX + logoWidth / 2f, sy + logoHeight),
                    null
                )
                logoBitmap.recycle()
            } else {
                val radius = 30f
                canvas.drawCircle(centerX, sy + radius, radius, Paint().apply { color = COLOR_LOGO_FILL; isAntiAlias = true })
                canvas.drawCircle(centerX, sy + radius, radius, Paint().apply { color = COLOR_LOGO_BORDER; style = Paint.Style.STROKE; strokeWidth = 1.4f; isAntiAlias = true })
                val initials = surgeon.name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.take(2)
                    .mapNotNull { it.firstOrNull()?.uppercaseChar() }.joinToString("").ifBlank { "OT" }
                canvas.drawText(initials, centerX, sy + radius + 7f, logoInitialsPaint)
            }
            sy += logoSize + 16f
            canvas.drawText(if (anonymize) "Surgeon not set" else surgeon.name.ifBlank { "Surgeon not set" }, centerX, sy, sidebarNamePaint)
            sy += 15f
            canvas.drawText("Orthopedic Surgeon", centerX, sy, sidebarRolePaint)
            sy += 12f
            canvas.drawText(if (!anonymize && surgeon.specialty.isNotBlank()) surgeon.specialty else "Traumatology & Orthopedics", centerX, sy, sidebarSubPaint)
            sy += 18f
            canvas.drawLine(SIDEBAR_PADDING, sy, SIDEBAR_WIDTH - SIDEBAR_PADDING, sy, dividerPaint)
            sy += 18f
            if (surgeon.hospital.isNotBlank()) {
                canvas.drawText(surgeon.hospital, centerX, sy, sidebarClinicPaint)
                sy += 20f
            }
            if (!anonymize) {
                val contactLines = listOfNotNull(surgeon.phone.ifBlank { null }, surgeon.email.ifBlank { null }, surgeon.address.ifBlank { null })
                if (contactLines.isNotEmpty()) {
                    canvas.drawText("CONTACT", centerX, sy, sidebarLabelPaint)
                    sy += 14f
                    contactLines.forEach { sy = drawSidebarWrapped(it, sy) + 2f }
                    sy += 6f
                    canvas.drawLine(SIDEBAR_PADDING, sy, SIDEBAR_WIDTH - SIDEBAR_PADDING, sy, dividerPaint)
                    sy += 18f
                }
                val medicalLines = listOfNotNull(
                    surgeon.specialty.ifBlank { null }?.let { "SPECIALTY" to it },
                    surgeon.registrationNumber.ifBlank { null }?.let { "REGISTRATION NO." to it }
                )
                if (medicalLines.isNotEmpty()) {
                    canvas.drawText("MEDICAL INFORMATION", centerX, sy, sidebarLabelPaint)
                    sy += 14f
                    medicalLines.forEach { (label, value) ->
                        canvas.drawText(label, SIDEBAR_PADDING, sy, labelPaint)
                        sy += 11f
                        sy = drawSidebarWrapped(value, sy) + 8f
                    }
                }
            }
        }

        fun drawFooter() {
            val footerY = PAGE_HEIGHT - 24f
            canvas.drawLine(MAIN_LEFT, footerY - 12f, MAIN_RIGHT, footerY - 12f, dividerPaint)
            canvas.drawText("Ortho Tips • Patient Chart", MAIN_LEFT, footerY, captionPaint)
            val pageLabel = "Page $pageNumber"
            canvas.drawText(pageLabel, MAIN_RIGHT - captionPaint.measureText(pageLabel), footerY, captionPaint)
        }

        fun startPage() {
            page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
            canvas = page.canvas
            drawSidebar()
            y = TOP_MARGIN
        }

        fun finishPage() {
            drawFooter()
            document.finishPage(page)
        }

        fun newPage() {
            finishPage()
            pageNumber += 1
            startPage()
        }

        fun newPageIfNeeded(neededHeight: Float) {
            if (y + neededHeight > PAGE_HEIGHT - FOOTER_RESERVE) newPage()
        }

        fun drawWrapped(text: String, paint: Paint, lineHeight: Float, startX: Float = MAIN_LEFT, maxWidth: Float = MAIN_CONTENT_WIDTH) {
            if (text.isBlank()) return
            text.lines().forEach { rawLine ->
                var line = StringBuilder()
                rawLine.split(" ").forEach { word ->
                    val trial = if (line.isEmpty()) word else "$line $word"
                    if (paint.measureText(trial) > maxWidth) {
                        newPageIfNeeded(lineHeight)
                        canvas.drawText(line.toString(), startX, y, paint)
                        y += lineHeight
                        line = StringBuilder(word)
                    } else line = StringBuilder(trial)
                }
                if (line.isNotEmpty()) {
                    newPageIfNeeded(lineHeight)
                    canvas.drawText(line.toString(), startX, y, paint)
                    y += lineHeight
                }
            }
        }

        fun drawSubheader(title: String) {
            y += 8f
            newPageIfNeeded(22f)
            canvas.drawText(title.uppercase(), MAIN_LEFT, y, subheaderPaint)
            y += 6f
            canvas.drawLine(MAIN_LEFT, y, MAIN_RIGHT, y, dividerPaint)
            y += 14f
        }

        fun drawSection(title: String, body: String) {
            if (body.isBlank()) return
            y += 6f
            newPageIfNeeded(20f)
            canvas.drawText(title.uppercase(), MAIN_LEFT, y, sectionPaint)
            y += 15f
            drawWrapped(body, bodyPaint, 14.5f)
        }

        fun drawInfoGrid(fields: List<Pair<String, String>>, columns: Int = 2) {
            val visible = fields.filter { it.second.isNotBlank() }
            if (visible.isEmpty()) return
            val colWidth = MAIN_CONTENT_WIDTH / columns
            val rowHeight = 30f
            visible.forEachIndexed { index, (label, value) ->
                val col = index % columns
                if (col == 0) newPageIfNeeded(rowHeight)
                val x = MAIN_LEFT + col * colWidth
                canvas.drawText(label.uppercase(), x, y, labelPaint)
                canvas.drawText(value.take(70), x, y + 13f, valuePaint)
                if (col == columns - 1 || index == visible.lastIndex) y += rowHeight
            }
        }

        startPage()
        canvas.drawText("Patient Chart", MAIN_LEFT, y, titlePaint)
        y += 10f
        canvas.drawLine(MAIN_LEFT, y, MAIN_RIGHT, y, dividerPaint)
        y += 16f

        if (options.includeQrCode && !anonymize) {
            QrCodeGenerator.generate(QrPatientLink.encode(patient.id), sizePx = 240)?.let { qrBitmap ->
                val qrTop = y
                val qrLeft = MAIN_RIGHT - QR_SIZE
                canvas.drawBitmap(qrBitmap, null, RectF(qrLeft, qrTop, MAIN_RIGHT, qrTop + QR_SIZE), null)
                val caption = "Scan to verify"
                canvas.drawText(caption, MAIN_RIGHT - captionPaint.measureText(caption), qrTop + QR_SIZE + 10f, captionPaint)
                qrBitmap.recycle()
                y = qrTop + QR_SIZE + 22f
            }
        }

        drawSubheader("Patient Information")
        val age = calculateAge(patient.dateOfBirth)
        drawInfoGrid(listOf(
            "Full name" to (if (anonymize) patient.patientCode else patient.fullName),
            "File number" to patient.patientCode,
            "Date of birth" to DateFormat.formatDate(patient.dateOfBirth).takeIf { it != "—" }.orEmpty(),
            "Age" to (age?.let { "$it yrs" } ?: ""),
            "Gender" to patient.gender,
            "Phone" to (if (anonymize) "" else patient.phone),
            "Address" to (if (anonymize) "" else patient.address)
        ))

        if (!anonymize && options.includeContact) {
            drawSection("Emergency contact", listOf(patient.emergencyContactName, patient.emergencyContactPhone).filter { it.isNotBlank() }.joinToString(" — "))
        }
        if (!anonymize && options.includeInsurance) {
            drawSection("Insurance", listOfNotNull(patient.insuranceProvider.ifBlank { null }, patient.insuranceNumber.ifBlank { null }).joinToString(" — "))
        }

        drawSubheader("Medical Information")
        if (options.includeMedicalHistory) drawSection("Medical history", patient.medicalHistory)
        if (options.includeAllergies) drawSection("Allergies", patient.allergies)
        if (options.includeChronicDiseases) drawSection("Chronic diseases", patient.chronicDiseases)
        if (options.includePreviousSurgeries) drawSection("Previous surgeries", patient.previousSurgeries)
        if (options.includeMedications) drawSection("Medications", patient.medications)
        if (options.includeNotes) drawSection("Notes", patient.generalNotes)

        if (options.includeOrthopedicInfo) {
            drawSubheader("Orthopedic Information")
            drawInfoGrid(listOf(
                "Diagnosis" to patient.diagnosis,
                "Body region" to patient.bodyRegion,
                "Side" to patient.side.takeIf { it.isNotBlank() && it != "NOT_APPLICABLE" }.orEmpty(),
                "Injury date" to DateFormat.formatDate(patient.injuryDate).takeIf { it != "—" }.orEmpty()
            ))
            drawSection("Trauma mechanism", patient.traumaMechanism)
            drawSection("Fracture classification", patient.fractureClassification)
            drawSection("Imaging findings", patient.imagingFindings)
            drawSection("Treatment plan", patient.treatmentPlan)
        }

        if (options.includeTimeline && timelineEvents.isNotEmpty()) {
            drawSubheader("Timeline")
            timelineEvents.sortedByDescending { it.eventDate }.forEach { event ->
                drawSection(DateFormat.formatDate(event.eventDate) + " — " + event.title, event.description)
            }
        }

        if (options.includeSurgeries && surgeries.isNotEmpty()) {
            drawSubheader("Surgeries")
            surgeries.sortedByDescending { it.surgeryDate }.forEach { surgery ->
                drawSection(
                    DateFormat.formatDate(surgery.surgeryDate) + " — " + surgery.procedure.ifBlank { "Untitled procedure" },
                    listOfNotNull(
                        surgery.diagnosis.ifBlank { null }?.let { "Diagnosis: $it" },
                        surgery.surgeon.ifBlank { null }?.takeUnless { anonymize }?.let { "Surgeon: $it" },
                        surgery.hospitalClinic.ifBlank { null }?.let { "Hospital: $it" }
                    ).joinToString("\n")
                )
            }
        }

        finishPage()

        val outputDir = File(context.filesDir, "protocols").apply { mkdirs() }
        val fileName = "chart_${patient.id}${if (anonymize) "_anon" else ""}.pdf"
        val outputFile = File(outputDir, fileName)
        FileOutputStream(outputFile).use { document.writeTo(it) }
        document.close()
        return outputFile
    }
}
