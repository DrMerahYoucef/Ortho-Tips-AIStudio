package com.example.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import com.example.data.local.entity.PatientEntity
import com.example.data.local.entity.SurgeryEntity
import com.example.data.local.model.calculateAge
import com.example.data.prefs.SurgeonProfile
import java.io.File
import java.io.FileOutputStream

enum class ProtocolLanguage { FRENCH, ENGLISH }

/** Which sections the surgeon chose to include when exporting a surgery record to PDF. */
data class SurgeryPdfOptions(
    val includeHospital: Boolean = true,
    val includeSurgeon: Boolean = true,
    val includeAssistants: Boolean = true,
    val includeAnesthesia: Boolean = true,
    val includeDiagnosis: Boolean = true,
    val includeProcedure: Boolean = true,
    val includeOperativeSteps: Boolean = true,
    val includeMaterials: Boolean = true,
    val includeComplications: Boolean = true,
    val includePostOp: Boolean = true,
    val includeSignature: Boolean = true,
    /** Encodes the patient's id — left off by default whenever [OperativeProtocolGenerator.generate]'s
     *  own `anonymize` flag is on, since it would otherwise defeat the point of anonymizing. */
    val includeQrCode: Boolean = true
)

/**
 * Renders a two-column, letterhead-style operative report using Android's built-in
 * [PdfDocument] — no third-party PDF library needed. The left sidebar (~30% width) is the
 * surgeon/clinic's identity card and repeats on every page; the right column (~70% width)
 * carries the patient block, the surgery summary, and the operative narrative, paginated
 * manually with simple word-wrapping (A4 at 72dpi: 595x842 pt).
 *
 * Some fields in the requested template (e.g. a standalone "Indication opératoire" text, or
 * a fully structured implants Reference/Size/Quantity breakdown) have no dedicated column in
 * [SurgeryEntity] today. Where reasonable those sections are derived from the closest
 * existing field (the implants table parses [SurgeryEntity.materialsUsed] line by line,
 * splitting on "|" if the surgeon typed one); where there's truly no backing data the
 * section is simply skipped, the same way any other blank field is skipped elsewhere in
 * this generator. Nothing is invented on the patient's or surgeon's behalf.
 */
object OperativeProtocolGenerator {

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
    private const val SIGNATURE_BLOCK_HEIGHT = 172f

    private val COLOR_SIDEBAR_BG = 0xFFF6F6F7.toInt()
    private val COLOR_DIVIDER = 0xFFD9D9DC.toInt()
    private val COLOR_TEXT_DARK = 0xFF1A1A1A.toInt()
    private val COLOR_TEXT_MUTED = 0xFF6B6B6E.toInt()
    private val COLOR_ACCENT = 0xFF14495E.toInt()
    private val COLOR_LOGO_FILL = 0xFFE4E9EC.toInt()
    private val COLOR_LOGO_BORDER = 0xFFB3BCC2.toInt()
    private val COLOR_LOGO_TEXT = 0xFF37474F.toInt()
    private val COLOR_TABLE_HEADER_BG = 0xFFEFF2F3.toInt()
    private val COLOR_TABLE_ROW_LINE = 0xFFEDEDEE.toInt()
    private val COLOR_SIGNATURE_LINE = 0xFF999999.toInt()
    private val COLOR_PLACEHOLDER_BORDER = 0xFFB0B0B0.toInt()

    fun generate(
        context: Context,
        patient: PatientEntity,
        surgery: SurgeryEntity,
        surgeon: SurgeonProfile,
        language: ProtocolLanguage,
        anonymize: Boolean = false,
        options: SurgeryPdfOptions = SurgeryPdfOptions()
    ): File {
        val strings = Strings.forLanguage(language)
        val document = PdfDocument()

        // ---- Paints ----
        val titlePaint = Paint().apply { textSize = 20f; isFakeBoldText = true; isAntiAlias = true; color = COLOR_TEXT_DARK }
        val subheaderPaint = Paint().apply {
            textSize = 11.5f; isFakeBoldText = true; isAntiAlias = true; color = COLOR_ACCENT; letterSpacing = 0.06f
        }
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

        val signatureLinePaint = Paint().apply { color = COLOR_SIGNATURE_LINE; strokeWidth = 1f }
        val signatureLabelPaint = Paint().apply { textSize = 8.5f; isAntiAlias = true; color = COLOR_TEXT_MUTED; letterSpacing = 0.05f; textAlign = Paint.Align.RIGHT }
        val signatureNamePaint = Paint().apply { textSize = 11f; isFakeBoldText = true; isAntiAlias = true; color = COLOR_TEXT_DARK; textAlign = Paint.Align.RIGHT }
        val signatureSpecialtyPaint = Paint().apply { textSize = 9.5f; isAntiAlias = true; color = COLOR_TEXT_MUTED; textAlign = Paint.Align.RIGHT }
        val dateLabelPaint = Paint().apply { textSize = 8.5f; isAntiAlias = true; color = COLOR_TEXT_MUTED; letterSpacing = 0.05f; textAlign = Paint.Align.LEFT }

        // ---- Page/pagination state ----
        var pageNumber = 1
        lateinit var page: PdfDocument.Page
        lateinit var canvas: Canvas
        var y = TOP_MARGIN

        fun truncateToWidth(text: String, paint: Paint, maxWidth: Float): String {
            if (paint.measureText(text) <= maxWidth) return text
            var end = text.length
            while (end > 0 && paint.measureText(text.substring(0, end) + "…") > maxWidth) end--
            return text.substring(0, end).trimEnd() + "…"
        }

        fun drawSidebarWrapped(text: String, paint: Paint, startY: Float): Float {
            if (text.isBlank()) return startY
            val maxWidth = SIDEBAR_WIDTH - SIDEBAR_PADDING * 2
            val words = text.split(" ")
            var line = StringBuilder()
            var cy = startY
            val lineHeight = 11f
            words.forEach { word ->
                val trial = if (line.isEmpty()) word else "$line $word"
                if (paint.measureText(trial) > maxWidth) {
                    canvas.drawText(line.toString(), SIDEBAR_PADDING, cy, paint)
                    cy += lineHeight
                    line = StringBuilder(word)
                } else {
                    line = StringBuilder(trial)
                }
            }
            if (line.isNotEmpty()) {
                canvas.drawText(line.toString(), SIDEBAR_PADDING, cy, paint)
                cy += lineHeight
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
                canvas.drawCircle(
                    centerX, sy + radius, radius,
                    Paint().apply { color = COLOR_LOGO_BORDER; style = Paint.Style.STROKE; strokeWidth = 1.4f; isAntiAlias = true }
                )
                val initials = surgeon.name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                    .take(2).mapNotNull { it.firstOrNull()?.uppercaseChar() }.joinToString("").ifBlank { "OT" }
                canvas.drawText(initials, centerX, sy + radius + 7f, logoInitialsPaint)
            }
            sy += logoSize + 16f

            val doctorName = if (anonymize) strings.unnamedSurgeon else surgeon.name.ifBlank { strings.unnamedSurgeon }
            canvas.drawText(doctorName, centerX, sy, sidebarNamePaint)
            sy += 15f
            canvas.drawText(strings.sidebarRole, centerX, sy, sidebarRolePaint)
            sy += 12f
            val tagline = if (!anonymize && surgeon.specialty.isNotBlank()) surgeon.specialty else strings.sidebarTagline
            canvas.drawText(tagline, centerX, sy, sidebarSubPaint)
            sy += 18f

            canvas.drawLine(SIDEBAR_PADDING, sy, SIDEBAR_WIDTH - SIDEBAR_PADDING, sy, dividerPaint)
            sy += 18f

            if (surgeon.hospital.isNotBlank()) {
                canvas.drawText(surgeon.hospital, centerX, sy, sidebarClinicPaint)
                sy += 20f
            }

            if (!anonymize) {
                val contactLines = listOfNotNull(
                    surgeon.phone.ifBlank { null },
                    surgeon.email.ifBlank { null },
                    surgeon.address.ifBlank { null }
                )
                if (contactLines.isNotEmpty()) {
                    canvas.drawText(strings.contact, centerX, sy, sidebarLabelPaint)
                    sy += 14f
                    contactLines.forEach { line ->
                        sy = drawSidebarWrapped(line, sidebarDetailPaint, sy)
                        sy += 2f
                    }
                    sy += 6f
                    canvas.drawLine(SIDEBAR_PADDING, sy, SIDEBAR_WIDTH - SIDEBAR_PADDING, sy, dividerPaint)
                    sy += 18f
                }

                val medicalLines = listOfNotNull(
                    surgeon.specialty.ifBlank { null }?.let { strings.specialty to it },
                    surgeon.registrationNumber.ifBlank { null }?.let { strings.registrationNumber to it }
                )
                if (medicalLines.isNotEmpty()) {
                    canvas.drawText(strings.medicalInfo, centerX, sy, sidebarLabelPaint)
                    sy += 14f
                    medicalLines.forEach { (label, value) ->
                        canvas.drawText(label.uppercase(), SIDEBAR_PADDING, sy, labelPaint)
                        sy += 11f
                        sy = drawSidebarWrapped(value, sidebarDetailPaint, sy)
                        sy += 8f
                    }
                }
            }
        }

        fun drawFooter() {
            val footerY = PAGE_HEIGHT - 24f
            canvas.drawLine(MAIN_LEFT, footerY - 12f, MAIN_RIGHT, footerY - 12f, dividerPaint)
            canvas.drawText(strings.footerBrand, MAIN_LEFT, footerY, captionPaint)
            val pageLabel = "${strings.page} $pageNumber"
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
            val words = text.split(" ")
            var line = StringBuilder()
            words.forEach { word ->
                val trial = if (line.isEmpty()) word else "$line $word"
                if (paint.measureText(trial) > maxWidth) {
                    newPageIfNeeded(lineHeight)
                    canvas.drawText(line.toString(), startX, y, paint)
                    y += lineHeight
                    line = StringBuilder(word)
                } else {
                    line = StringBuilder(trial)
                }
            }
            if (line.isNotEmpty()) {
                newPageIfNeeded(lineHeight)
                canvas.drawText(line.toString(), startX, y, paint)
                y += lineHeight
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

        fun drawBulletSection(title: String, content: String) {
            if (content.isBlank()) return
            y += 6f
            newPageIfNeeded(20f)
            canvas.drawText(title.uppercase(), MAIN_LEFT, y, sectionPaint)
            y += 15f

            val textIndent = MAIN_LEFT + 12f
            val maxWidth = MAIN_RIGHT - textIndent
            val lineHeight = 14.5f

            content.lines().forEach { rawLine ->
                val line = rawLine.trim().removePrefix("\u2022").removePrefix("-").trim()
                if (line.isEmpty()) return@forEach
                newPageIfNeeded(lineHeight)
                canvas.drawText("\u2022", MAIN_LEFT, y, bodyPaint)
                drawWrapped(line, bodyPaint, lineHeight, startX = textIndent, maxWidth = maxWidth)
            }
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
                canvas.drawText(truncateToWidth(value, valuePaint, colWidth - 6f), x, y + 13f, valuePaint)
                if (col == columns - 1 || index == visible.lastIndex) y += rowHeight
            }
        }

        fun drawImplantsTable(rawMaterials: String) {
            val lines = rawMaterials.lines().map { it.trim() }.filter { it.isNotBlank() }
            if (lines.isEmpty()) return

            y += 6f
            newPageIfNeeded(20f)
            canvas.drawText(strings.implants.uppercase(), MAIN_LEFT, y, sectionPaint)
            y += 12f

            val colWidths = floatArrayOf(
                MAIN_CONTENT_WIDTH * 0.40f,
                MAIN_CONTENT_WIDTH * 0.28f,
                MAIN_CONTENT_WIDTH * 0.14f,
                MAIN_CONTENT_WIDTH * 0.18f
            )
            val colX = floatArrayOf(
                MAIN_LEFT,
                MAIN_LEFT + colWidths[0],
                MAIN_LEFT + colWidths[0] + colWidths[1],
                MAIN_LEFT + colWidths[0] + colWidths[1] + colWidths[2]
            )
            val headers = arrayOf(strings.tableItem, strings.tableReference, strings.tableSize, strings.tableQuantity)
            val headerPaint = Paint().apply { textSize = 9f; isFakeBoldText = true; isAntiAlias = true; color = COLOR_TEXT_DARK }
            val cellPaint = Paint().apply { textSize = 9.5f; isAntiAlias = true; color = COLOR_TEXT_DARK }
            val rowLinePaint = Paint().apply { color = COLOR_TABLE_ROW_LINE; strokeWidth = 0.75f }
            val rowHeight = 17f
            val headerHeight = 18f

            fun drawTableHeader() {
                newPageIfNeeded(headerHeight + rowHeight)
                canvas.drawRect(MAIN_LEFT, y, MAIN_RIGHT, y + headerHeight, Paint().apply { color = COLOR_TABLE_HEADER_BG })
                headers.forEachIndexed { i, h -> canvas.drawText(h.uppercase(), colX[i] + 4f, y + 12f, headerPaint) }
                y += headerHeight
                canvas.drawLine(MAIN_LEFT, y, MAIN_RIGHT, y, dividerPaint)
            }

            drawTableHeader()
            lines.forEach { line ->
                if (y + rowHeight > PAGE_HEIGHT - FOOTER_RESERVE) {
                    newPage()
                    drawTableHeader()
                }
                val parts = if (line.contains("|")) line.split("|").map { it.trim() } else listOf(line)
                for (i in 0 until 4) {
                    val text = parts.getOrNull(i).orEmpty()
                    if (text.isNotBlank()) {
                        val maxWidth = colWidths[i] - 8f
                        canvas.drawText(truncateToWidth(text, cellPaint, maxWidth), colX[i] + 4f, y + 12f, cellPaint)
                    }
                }
                y += rowHeight
                canvas.drawLine(MAIN_LEFT, y, MAIN_RIGHT, y, rowLinePaint)
            }
            y += 6f
        }

        fun drawSignatureBlock() {
            if (!options.includeSignature) return

            newPageIfNeeded(SIGNATURE_BLOCK_HEIGHT)

            val blockRight = MAIN_RIGHT
            val blockLeft = MAIN_RIGHT - 170f
            var sy = y

            val hasImage = !anonymize && surgeon.includeSignatureInPdf && surgeon.signatureImagePath != null &&
                File(surgeon.signatureImagePath).exists()

            canvas.drawText(strings.signature, blockRight, sy, signatureLabelPaint)
            sy += 17f

            val imageAreaHeight = 66f
            if (hasImage) {
                val decoded = BitmapFactory.decodeFile(surgeon.signatureImagePath)
                if (decoded != null && decoded.width > 0) {
                    val flattened = flattenOntoWhite(decoded)
                    decoded.recycle()
                    val targetWidth = 150f
                    val targetHeight = (targetWidth * flattened.height / flattened.width).coerceAtMost(imageAreaHeight)
                    canvas.drawBitmap(
                        flattened,
                        null,
                        RectF(blockRight - targetWidth, sy, blockRight, sy + targetHeight),
                        null
                    )
                    flattened.recycle()
                }
            } else if (!anonymize) {
                val placeholderPaint = Paint().apply {
                    color = COLOR_PLACEHOLDER_BORDER
                    style = Paint.Style.STROKE
                    strokeWidth = 1f
                    pathEffect = android.graphics.DashPathEffect(floatArrayOf(4f, 3f), 0f)
                }
                canvas.drawRect(blockLeft, sy, blockRight, sy + imageAreaHeight, placeholderPaint)
                val stampCaptionPaint = Paint().apply {
                    textSize = 9f; isAntiAlias = true; color = COLOR_PLACEHOLDER_BORDER; textAlign = Paint.Align.CENTER
                }
                canvas.drawText(strings.stampPlaceholder, (blockLeft + blockRight) / 2f, sy + imageAreaHeight / 2f + 3f, stampCaptionPaint)
            }
            sy += imageAreaHeight + 5f

            canvas.drawLine(blockLeft, sy, blockRight, sy, signatureLinePaint)
            sy += 14f

            if (!anonymize) {
                canvas.drawText(surgeon.name.ifBlank { strings.unnamedSurgeon }, blockRight, sy, signatureNamePaint)
                if (surgeon.specialty.isNotBlank()) {
                    sy += 12f
                    canvas.drawText(surgeon.specialty, blockRight, sy, signatureSpecialtyPaint)
                }
            } else {
                canvas.drawText(strings.unnamedSurgeon, blockRight, sy, signatureNamePaint)
            }
            sy += 16f

            canvas.drawText("${strings.date}:", blockLeft, sy + 3f, dateLabelPaint)
            canvas.drawLine(blockLeft + 34f, sy + 3f, blockRight, sy + 3f, signatureLinePaint)
            sy += 14f

            y = sy
        }

        // ---- Begin rendering ----
        startPage()

        canvas.drawText(strings.title, MAIN_LEFT, y, titlePaint)
        y += 10f
        canvas.drawLine(MAIN_LEFT, y, MAIN_RIGHT, y, dividerPaint)
        y += 16f

        // QR + digital-verification caption, reserved as a gutter above the Patient
        // Information block so the header/grid below never overlaps it. Skipped whenever
        // the report itself is anonymized: encoding the patient's id would quietly undo
        // the point of anonymizing.
        if (options.includeQrCode && !anonymize) {
            QrCodeGenerator.generate(QrPatientLink.encode(patient.id), sizePx = 240)?.let { qrBitmap ->
                val qrTop = y
                val qrLeft = MAIN_RIGHT - QR_SIZE
                canvas.drawBitmap(qrBitmap, null, RectF(qrLeft, qrTop, MAIN_RIGHT, qrTop + QR_SIZE), null)
                qrBitmap.recycle()
                val captionText = strings.verifyCaption
                canvas.drawText(
                    captionText,
                    MAIN_RIGHT - captionPaint.measureText(captionText),
                    qrTop + QR_SIZE + 10f,
                    captionPaint
                )
                y = qrTop + QR_SIZE + 22f
            }
        }

        // Patient information
        drawSubheader(strings.patientInfo)
        val age = calculateAge(patient.dateOfBirth)
        drawInfoGrid(
            listOf(
                strings.fullName to (if (anonymize) patient.patientCode else patient.fullName),
                strings.fileNumber to patient.patientCode,
                strings.dateOfBirth to DateFormat.formatDate(patient.dateOfBirth).takeIf { it != "—" }.orEmpty(),
                strings.age to (age?.let { "$it ${strings.years}" } ?: ""),
                strings.gender to patient.gender,
                strings.phone to (if (anonymize) "" else patient.phone),
                strings.address to (if (anonymize) "" else patient.address)
            )
        )

        // Surgery information
        drawSubheader(strings.surgeryInfo)
        drawInfoGrid(
            listOf(
                strings.date to DateFormat.formatDate(surgery.surgeryDate),
                strings.hospital to (if (options.includeHospital) surgery.hospitalClinic else ""),
                strings.surgeon to (if (!anonymize && options.includeSurgeon) surgery.surgeon else ""),
                strings.assistants to (if (options.includeAssistants) surgery.assistants else ""),
                strings.anesthesia to (if (options.includeAnesthesia) surgery.anesthesiaType else ""),
                strings.diagnosis to (if (options.includeDiagnosis) surgery.diagnosis else ""),
                strings.operatedSide to patient.side.takeIf { it.isNotBlank() && it != "NOT_APPLICABLE" }.orEmpty()
            )
        )

        // Operative narrative
        if (options.includeDiagnosis) drawSection(strings.diagnosis, surgery.diagnosis)
        if (options.includeProcedure) drawSection(strings.procedure, surgery.procedure)
        if (options.includeOperativeSteps) drawBulletSection(strings.operativeSteps, surgery.operativeProtocol)
        if (options.includeMaterials) drawImplantsTable(surgery.materialsUsed)
        if (options.includeComplications) drawSection(strings.complications, surgery.complications.ifBlank { strings.none })
        if (options.includePostOp) drawSection(strings.postOp, surgery.postOpInstructions)

        drawSignatureBlock()
        finishPage()

        val outputDir = File(context.filesDir, "protocols").apply { mkdirs() }
        val fileName = "protocol_${surgery.id}_${language.name.lowercase()}${if (anonymize) "_anon" else ""}.pdf"
        val outputFile = File(outputDir, fileName)
        FileOutputStream(outputFile).use { document.writeTo(it) }
        document.close()

        return outputFile
    }

    /**
     * Composites [source] (which may have transparent areas) onto a plain white bitmap of
     * the same size, using a normal in-memory [Canvas] — standard bitmap-to-bitmap alpha
     * blending, entirely separate from [PdfDocument]'s own (unreliable) alpha handling.
     * The result is fully opaque everywhere, which is what actually gets drawn into the PDF.
     */
    private fun flattenOntoWhite(source: Bitmap): Bitmap {
        val flattened = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(flattened)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(source, 0f, 0f, null)
        return flattened
    }

    private data class Strings(
        val title: String,
        val sidebarRole: String,
        val sidebarTagline: String,
        val contact: String,
        val medicalInfo: String,
        val specialty: String,
        val patientInfo: String,
        val surgeryInfo: String,
        val fullName: String,
        val fileNumber: String,
        val dateOfBirth: String,
        val age: String,
        val years: String,
        val gender: String,
        val phone: String,
        val address: String,
        val date: String,
        val hospital: String,
        val surgeon: String,
        val assistants: String,
        val anesthesia: String,
        val diagnosis: String,
        val operatedSide: String,
        val procedure: String,
        val operativeSteps: String,
        val implants: String,
        val tableItem: String,
        val tableReference: String,
        val tableSize: String,
        val tableQuantity: String,
        val complications: String,
        val postOp: String,
        val none: String,
        val registrationNumber: String,
        val unnamedSurgeon: String,
        val signature: String,
        val stampPlaceholder: String,
        val verifyCaption: String,
        val footerBrand: String,
        val page: String
    ) {
        companion object {
            fun forLanguage(language: ProtocolLanguage): Strings = when (language) {
                ProtocolLanguage.FRENCH -> Strings(
                    title = "Compte-rendu opératoire",
                    sidebarRole = "Chirurgien Orthopédiste",
                    sidebarTagline = "Traumatologie & Chirurgie Orthopédique",
                    contact = "Contact",
                    medicalInfo = "Informations médicales",
                    specialty = "Spécialité",
                    patientInfo = "Informations patient",
                    surgeryInfo = "Informations chirurgicales",
                    fullName = "Nom complet",
                    fileNumber = "N° de dossier",
                    dateOfBirth = "Date de naissance",
                    age = "Âge",
                    years = "ans",
                    gender = "Sexe",
                    phone = "Téléphone",
                    address = "Adresse",
                    date = "Date",
                    hospital = "Établissement",
                    surgeon = "Chirurgien",
                    assistants = "Aides opératoires",
                    anesthesia = "Anesthésie",
                    diagnosis = "Diagnostic",
                    operatedSide = "Côté opéré",
                    procedure = "Intervention réalisée",
                    operativeSteps = "Technique opératoire",
                    implants = "Implants / Matériel",
                    tableItem = "Article",
                    tableReference = "Référence",
                    tableSize = "Taille",
                    tableQuantity = "Qté",
                    complications = "Complications",
                    postOp = "Consignes postopératoires",
                    none = "Aucune",
                    registrationNumber = "N° d'inscription",
                    unnamedSurgeon = "Chirurgien non renseigné",
                    signature = "Signature",
                    stampPlaceholder = "[CACHET]",
                    verifyCaption = "Scanner pour vérifier",
                    footerBrand = "Ortho Tips • Compte-rendu opératoire",
                    page = "Page"
                )
                ProtocolLanguage.ENGLISH -> Strings(
                    title = "Operative Report",
                    sidebarRole = "Orthopedic Surgeon",
                    sidebarTagline = "Traumatology & Orthopedic Surgery",
                    contact = "Contact",
                    medicalInfo = "Medical Information",
                    specialty = "Specialty",
                    patientInfo = "Patient Information",
                    surgeryInfo = "Surgery Information",
                    fullName = "Full name",
                    fileNumber = "File number",
                    dateOfBirth = "Date of birth",
                    age = "Age",
                    years = "yrs",
                    gender = "Gender",
                    phone = "Phone",
                    address = "Address",
                    date = "Date",
                    hospital = "Establishment",
                    surgeon = "Surgeon",
                    assistants = "Assistant surgeon",
                    anesthesia = "Anesthesia",
                    diagnosis = "Diagnosis",
                    operatedSide = "Operated side",
                    procedure = "Intervention performed",
                    operativeSteps = "Operative technique",
                    implants = "Implants / Materials",
                    tableItem = "Item",
                    tableReference = "Reference",
                    tableSize = "Size",
                    tableQuantity = "Qty",
                    complications = "Complications",
                    postOp = "Postoperative instructions",
                    none = "None",
                    registrationNumber = "Registration No.",
                    unnamedSurgeon = "Surgeon not set",
                    signature = "Signature",
                    stampPlaceholder = "[STAMP]",
                    verifyCaption = "Scan to verify",
                    footerBrand = "Ortho Tips • Operative Report",
                    page = "Page"
                )
            }
        }
    }
}
