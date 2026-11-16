package com.example.ui.screens.patients

import androidx.compose.material.icons.automirrored.filled.ArrowBack
import android.graphics.BitmapFactory
import android.graphics.PointF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.TextSnippet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.example.R
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.utils.AnnotationShape
import com.example.utils.NormalizedPoint
import com.example.viewmodel.AnnotationTool
import com.example.viewmodel.ImageAnnotationViewModel
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val ANNOTATION_COLOR = 0xFFFF3B30
private val HANDLE_COLOR = 0xFF4285F4 // matches the blue used by Google's own document scanner

/** Which corner-drag mode is active — they share the same 4-point overlay UI. */
private enum class QuadMode { NONE, CROP, OCR }

private fun nearestHandleIndex(points: List<Offset>, touch: Offset, maxDistancePx: Float): Int? {
    var bestIndex: Int? = null
    var bestDistance = Float.MAX_VALUE
    points.forEachIndexed { index, point ->
        val d = (point - touch).getDistance()
        if (d < bestDistance) {
            bestDistance = d
            bestIndex = index
        }
    }
    return if (bestDistance <= maxDistancePx) bestIndex else null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageAnnotationScreen(
    viewModel: ImageAnnotationViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val density = LocalDensity.current

    // The image's real pixel dimensions, read directly from the file (not from whatever the
    // display library decided to report) so the crop/OCR math never depends on async image-
    // loading timing. The container below is then sized to this exact aspect ratio, so its
    // measured bounds ARE the image's displayed bounds — no letterbox offset to account for,
    // and so no separate "fit rectangle" calculation that could ever drift out of sync with
    // what's on screen.
    var imageSize by remember(uiState.filePath, uiState.fileRevision) { mutableStateOf<Pair<Int, Int>?>(null) }

    LaunchedEffect(uiState.filePath, uiState.fileRevision) {
        val path = uiState.filePath ?: return@LaunchedEffect
        imageSize = withContext(Dispatchers.IO) {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, options)
            if (options.outWidth > 0 && options.outHeight > 0) options.outWidth to options.outHeight else null
        }
    }

    // Only decoded while a crop/OCR-region corner is actually being dragged — used solely to
    // draw the magnifier loupe below, from the real pixel data (not a scaled-down preview).
    var quadSourceBitmap by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }

    var canvasSizePx by remember { mutableStateOf(Offset(1f, 1f)) }
    var pendingTextPosition by remember { mutableStateOf<NormalizedPoint?>(null) }
    var textInput by remember { mutableStateOf("") }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var freehandPoints by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var showOcrPanel by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    var quadMode by remember { mutableStateOf(QuadMode.NONE) }
    var quadPoints by remember { mutableStateOf<List<Offset>?>(null) }
    var activeHandleIndex by remember { mutableStateOf<Int?>(null) }
    var showQuadOcrDialog by remember { mutableStateOf(false) }

    LaunchedEffect(quadMode, uiState.filePath, uiState.fileRevision) {
        quadSourceBitmap = if (quadMode != QuadMode.NONE) {
            val path = uiState.filePath
            if (path != null) {
                withContext(Dispatchers.IO) {
                    BitmapFactory.decodeFile(path)?.asImageBitmap()
                }
            } else null
        } else null
    }

    fun normalize(offset: Offset) = NormalizedPoint(
        x = (offset.x / canvasSizePx.x).coerceIn(0f, 1f),
        y = (offset.y / canvasSizePx.y).coerceIn(0f, 1f)
    )

    /** Canvas-space point → source-bitmap pixel space. Direct proportional scaling — the
     *  container is aspect-locked to the image, so there is no letterbox to correct for. */
    fun canvasToBitmap(point: Offset, imgW: Int, imgH: Int): PointF {
        val fracX = if (canvasSizePx.x > 0f) (point.x / canvasSizePx.x).coerceIn(0f, 1f) else 0f
        val fracY = if (canvasSizePx.y > 0f) (point.y / canvasSizePx.y).coerceIn(0f, 1f) else 0f
        return PointF(fracX * imgW, fracY * imgH)
    }

    fun fullFrameCorners(): List<Offset> {
        // A small inward inset so the initial handles land just shy of the screen's
        // physical edge — pinned exactly at (0,0) etc. left no room for an imprecise
        // finger to grab them without overshooting past the touchable area.
        val insetPx = with(density) { 28.dp.toPx() }
        val insetX = insetPx.coerceAtMost(canvasSizePx.x / 4f)
        val insetY = insetPx.coerceAtMost(canvasSizePx.y / 4f)
        return listOf(
            Offset(insetX, insetY),
            Offset(canvasSizePx.x - insetX, insetY),
            Offset(canvasSizePx.x - insetX, canvasSizePx.y - insetY),
            Offset(insetX, canvasSizePx.y - insetY)
        )
    }

    fun startQuadMode(mode: QuadMode) {
        if (quadMode == mode) {
            quadMode = QuadMode.NONE
            quadPoints = null
            return
        }
        quadMode = mode
        quadPoints = fullFrameCorners()
    }

    fun applyQuad() {
        val points = quadPoints
        val (imgW, imgH) = imageSize ?: (0 to 0)
        if (points != null && imgW > 0 && imgH > 0) {
            val bitmapCorners = points.map { canvasToBitmap(it, imgW, imgH) }
            when (quadMode) {
                QuadMode.CROP -> viewModel.cropQuad(context, bitmapCorners)
                QuadMode.OCR -> {
                    viewModel.scanQuadText(bitmapCorners)
                    showQuadOcrDialog = true
                }
                QuadMode.NONE -> {}
            }
        }
        quadMode = QuadMode.NONE
        quadPoints = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.annotate_title), maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    // Only the one action that finishes the job stays in the bar.
                    // Everything occasional moved to the menu, everything used
                    // while drawing moved to the toolbar at the bottom.
                    TextButton(onClick = { viewModel.saveAnnotations(); onNavigateBack() }) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.action_save))
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.annotate_more_actions))
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.annotate_scan_region)) },
                                leadingIcon = { Icon(Icons.Default.DocumentScanner, contentDescription = null) },
                                onClick = { showMenu = false; startQuadMode(QuadMode.OCR) }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.annotate_scan_all)) },
                                leadingIcon = { Icon(Icons.Default.TextSnippet, contentDescription = null) },
                                onClick = { showMenu = false; showOcrPanel = true }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.annotate_share)) },
                                leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    uiState.filePath?.let { path ->
                                        com.example.utils.ShareHelper.shareFile(
                                            context = context,
                                            file = java.io.File(path),
                                            mimeType = "image/jpeg",
                                            chooserTitle = context.getString(R.string.annotate_share)
                                        )
                                    }
                                }
                            )
                            if (uiState.hasBackup) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.annotate_restore_original)) },
                                    leadingIcon = { Icon(Icons.Default.Restore, contentDescription = null) },
                                    onClick = { showMenu = false; viewModel.restoreOriginal(context) }
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = {
            if (quadMode == QuadMode.NONE) {
                // Drawing tools on the left, image edits after a divider: the two
                // groups do different things and were mixed together before.
                Row(
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                ) {
                    ToolButton(Icons.Default.ArrowForward, stringResource(R.string.annotate_tool_arrow), uiState.activeTool == AnnotationTool.ARROW) {
                        viewModel.setActiveTool(AnnotationTool.ARROW)
                    }
                    ToolButton(Icons.Default.TextFields, stringResource(R.string.annotate_tool_text), uiState.activeTool == AnnotationTool.TEXT) {
                        viewModel.setActiveTool(AnnotationTool.TEXT)
                    }
                    ToolButton(Icons.Default.CropFree, stringResource(R.string.annotate_tool_rectangle), uiState.activeTool == AnnotationTool.RECTANGLE) {
                        viewModel.setActiveTool(AnnotationTool.RECTANGLE)
                    }
                    ToolButton(Icons.Default.Gesture, stringResource(R.string.annotate_tool_lasso), uiState.activeTool == AnnotationTool.FREEHAND) {
                        viewModel.setActiveTool(AnnotationTool.FREEHAND)
                    }
                    VerticalDivider(modifier = Modifier.height(24.dp))
                    ToolButton(Icons.Default.Crop, stringResource(R.string.annotate_crop), false) {
                        startQuadMode(QuadMode.CROP)
                    }
                    ToolButton(Icons.Default.RotateRight, stringResource(R.string.annotate_rotate), false) {
                        viewModel.rotate90()
                    }
                }
            } else {
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(
                        text = stringResource(R.string.annotate_drag_corners),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                    Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = { quadMode = QuadMode.NONE; quadPoints = null }) { Text(stringResource(R.string.action_cancel)) }
                        TextButton(onClick = { applyQuad() }, enabled = quadPoints != null) {
                            Text(stringResource(if (quadMode == QuadMode.OCR) R.string.annotate_scan_text else R.string.annotate_apply_crop))
                        }
                    }
                }
            }
        },
        modifier = modifier
    ) { paddingValues ->
        Box(
            modifier = Modifier.fillMaxSize().padding(paddingValues).background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            val (imgW, imgH) = imageSize ?: (0 to 0)
            if (uiState.isLoading || imgW <= 0 || imgH <= 0) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                return@Scaffold
            }

            // Aspect-locked to the actual image: this box's measured size IS the image's
            // displayed bounds, so every touch coordinate inside it maps directly (by simple
            // proportion) to a pixel in the source photo — no separate letterbox math needed.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .aspectRatio(imgW.toFloat() / imgH.toFloat())
                    .onGloballyPositioned { coordinates ->
                        canvasSizePx = Offset(coordinates.size.width.toFloat(), coordinates.size.height.toFloat())
                    }
                    .pointerInput(canvasSizePx, uiState.activeTool, quadMode) {
                        if (quadMode != QuadMode.NONE) {
                            detectDragGestures(
                                onDragStart = { start ->
                                    activeHandleIndex = quadPoints?.let { nearestHandleIndex(it, start, maxDistancePx = 96f) }
                                },
                                onDrag = { change, _ ->
                                    val idx = activeHandleIndex ?: return@detectDragGestures
                                    val current = quadPoints ?: return@detectDragGestures
                                    val clamped = Offset(
                                        change.position.x.coerceIn(0f, canvasSizePx.x),
                                        change.position.y.coerceIn(0f, canvasSizePx.y)
                                    )
                                    quadPoints = current.toMutableList().also { it[idx] = clamped }
                                },
                                onDragEnd = { activeHandleIndex = null }
                            )
                        } else {
                            when (uiState.activeTool) {
                                AnnotationTool.ARROW -> detectDragGestures(
                                    onDragStart = { start -> dragStart = start; freehandPoints = listOf(start) },
                                    onDrag = { change, _ -> freehandPoints = listOf(freehandPoints.first(), change.position) },
                                    onDragEnd = {
                                        val start = dragStart
                                        val end = freehandPoints.lastOrNull()
                                        if (start != null && end != null) {
                                            viewModel.addShape(
                                                AnnotationShape.Arrow(
                                                    id = java.util.UUID.randomUUID().toString(),
                                                    colorArgb = ANNOTATION_COLOR,
                                                    start = normalize(start),
                                                    end = normalize(end)
                                                )
                                            )
                                        }
                                        dragStart = null
                                        freehandPoints = emptyList()
                                    }
                                )
                                AnnotationTool.FREEHAND -> detectDragGestures(
                                    onDragStart = { freehandPoints = listOf(it) },
                                    onDrag = { change, _ -> freehandPoints = freehandPoints + change.position },
                                    onDragEnd = {
                                        if (freehandPoints.size > 1) {
                                            viewModel.addShape(
                                                AnnotationShape.Freehand(
                                                    id = java.util.UUID.randomUUID().toString(),
                                                    colorArgb = ANNOTATION_COLOR,
                                                    points = freehandPoints.map { normalize(it) }
                                                )
                                            )
                                        }
                                        freehandPoints = emptyList()
                                    }
                                )
                                AnnotationTool.RECTANGLE -> detectDragGestures(
                                    onDragStart = { dragStart = it; freehandPoints = listOf(it, it) },
                                    onDrag = { change, _ -> dragStart?.let { start -> freehandPoints = listOf(start, change.position) } },
                                    onDragEnd = {
                                        freehandPoints.let { pts ->
                                            if (pts.size == 2) {
                                                val (start, end) = pts
                                                viewModel.addShape(
                                                    AnnotationShape.Rectangle(
                                                        id = java.util.UUID.randomUUID().toString(),
                                                        colorArgb = ANNOTATION_COLOR,
                                                        topLeft = normalize(Offset(min(start.x, end.x), min(start.y, end.y))),
                                                        bottomRight = normalize(Offset(kotlin.math.max(start.x, end.x), kotlin.math.max(start.y, end.y)))
                                                    )
                                                )
                                            }
                                        }
                                        dragStart = null
                                        freehandPoints = emptyList()
                                    }
                                )
                                else -> {}
                            }
                        }
                    }
                    .pointerInput(uiState.activeTool, quadMode) {
                        if (quadMode == QuadMode.NONE) {
                            detectTapGestures { tapOffset ->
                                when (uiState.activeTool) {
                                    AnnotationTool.TEXT -> pendingTextPosition = normalize(tapOffset)
                                    else -> {}
                                }
                            }
                        }
                    }
            ) {
                AsyncImage(
                    model = uiState.filePath?.let { "file://$it?rev=${uiState.fileRevision}" },
                    contentDescription = stringResource(R.string.annot_patient_image),
                    contentScale = ContentScale.FillBounds, // safe: this box's aspect ratio already matches the image exactly
                    modifier = Modifier.fillMaxSize()
                )

                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height

                    uiState.shapes.forEach { shape ->
                        val color = Color(shape.colorArgb)
                        when (shape) {
                            is AnnotationShape.Arrow -> drawArrow(
                                Offset(shape.start.x * w, shape.start.y * h),
                                Offset(shape.end.x * w, shape.end.y * h),
                                color
                            )
                            is AnnotationShape.Rectangle -> drawRect(
                                color = color,
                                topLeft = Offset(shape.topLeft.x * w, shape.topLeft.y * h),
                                size = androidx.compose.ui.geometry.Size(
                                    (shape.bottomRight.x - shape.topLeft.x) * w,
                                    (shape.bottomRight.y - shape.topLeft.y) * h
                                ),
                                style = Stroke(width = 4f)
                            )
                            is AnnotationShape.Freehand -> {
                                val path = androidx.compose.ui.graphics.Path()
                                shape.points.forEachIndexed { index, point ->
                                    val p = Offset(point.x * w, point.y * h)
                                    if (index == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                                }
                                drawPath(path, color = color, style = Stroke(width = 4f))
                            }
                            is AnnotationShape.TextLabel -> {
                                drawContext.canvas.nativeCanvas.drawText(
                                    shape.text,
                                    shape.position.x * w,
                                    shape.position.y * h,
                                    android.graphics.Paint().apply {
                                        this.color = shape.colorArgb.toInt()
                                        textSize = 42f
                                        isFakeBoldText = true
                                    }
                                )
                            }
                        }
                    }

                    if (quadMode == QuadMode.NONE && freehandPoints.size > 1) {
                        when (uiState.activeTool) {
                            AnnotationTool.RECTANGLE -> {
                                val (start, end) = freehandPoints
                                drawRect(
                                    color = Color.White,
                                    topLeft = Offset(min(start.x, end.x), min(start.y, end.y)),
                                    size = androidx.compose.ui.geometry.Size(kotlin.math.abs(end.x - start.x), kotlin.math.abs(end.y - start.y)),
                                    style = Stroke(width = 3f)
                                )
                            }
                            else -> {
                                val path = androidx.compose.ui.graphics.Path()
                                freehandPoints.forEachIndexed { index, point ->
                                    if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
                                }
                                drawPath(path, color = Color(ANNOTATION_COLOR), style = Stroke(width = 4f))
                            }
                        }
                    }

                    quadPoints?.let { points ->
                        val path = androidx.compose.ui.graphics.Path().apply {
                            moveTo(points[0].x, points[0].y)
                            lineTo(points[1].x, points[1].y)
                            lineTo(points[2].x, points[2].y)
                            lineTo(points[3].x, points[3].y)
                            close()
                        }
                        drawPath(path, color = Color(HANDLE_COLOR), style = Stroke(width = 4f))
                        points.forEach { p ->
                            drawCircle(color = Color.Black, radius = 26f, center = p, alpha = 0.25f)
                            drawCircle(color = Color(HANDLE_COLOR), radius = 20f, center = p)
                            drawCircle(color = Color.White, radius = 20f, center = p, style = Stroke(width = 3f))
                        }
                    }

                    // Magnifier loupe: while dragging a corner, show a zoomed preview of the
                    // area under the finger, offset above it so the finger doesn't hide it —
                    // the same precision aid Google's own document scanner uses. Drawn in this
                    // SAME Canvas (same coordinate frame as the quad points themselves) using
                    // direct source/destination pixel rects — no separate positioned Box, no
                    // translation math that could drift out of alignment with the real touch point.
                    val bitmap = quadSourceBitmap
                    val activePoint = activeHandleIndex?.let { idx -> quadPoints?.getOrNull(idx) }
                    if (bitmap != null && activePoint != null && imgW > 0 && imgH > 0 && w > 0f && h > 0f) {
                        val loupeDiameterPx = with(density) { 120.dp.toPx() }
                        val zoom = 3f
                        val verticalOffsetPx = with(density) { 90.dp.toPx() }
                        val loupeRadius = loupeDiameterPx / 2f

                        // Where the loupe itself is centered on screen: above the finger.
                        val loupeCenterX = activePoint.x
                        val loupeCenterY = activePoint.y - verticalOffsetPx
                        val loupeTopLeftX = loupeCenterX - loupeRadius
                        val loupeTopLeftY = loupeCenterY - loupeRadius

                        // Which square of the SOURCE bitmap that loupe should show, centered on
                        // the actual touch point, converted from canvas pixels to bitmap pixels.
                        val bitmapPointX = (activePoint.x / w) * imgW
                        val bitmapPointY = (activePoint.y / h) * imgH
                        val bitmapPxPerCanvasPx = imgW / w
                        val srcSize = ((loupeDiameterPx / zoom) * bitmapPxPerCanvasPx)
                            .roundToInt()
                            .coerceIn(1, min(imgW, imgH))
                        val srcX = (bitmapPointX - srcSize / 2f).roundToInt().coerceIn(0, imgW - srcSize)
                        val srcY = (bitmapPointY - srcSize / 2f).roundToInt().coerceIn(0, imgH - srcSize)

                        clipPath(
                            androidx.compose.ui.graphics.Path().apply {
                                addOval(
                                    androidx.compose.ui.geometry.Rect(
                                        loupeTopLeftX, loupeTopLeftY,
                                        loupeTopLeftX + loupeDiameterPx, loupeTopLeftY + loupeDiameterPx
                                    )
                                )
                            }
                        ) {
                            drawRect(
                                color = Color.Black,
                                topLeft = Offset(loupeTopLeftX, loupeTopLeftY),
                                size = androidx.compose.ui.geometry.Size(loupeDiameterPx, loupeDiameterPx)
                            )
                            drawImage(
                                image = bitmap,
                                srcOffset = androidx.compose.ui.unit.IntOffset(srcX, srcY),
                                srcSize = androidx.compose.ui.unit.IntSize(srcSize, srcSize),
                                dstOffset = androidx.compose.ui.unit.IntOffset(loupeTopLeftX.roundToInt(), loupeTopLeftY.roundToInt()),
                                dstSize = androidx.compose.ui.unit.IntSize(loupeDiameterPx.roundToInt(), loupeDiameterPx.roundToInt())
                            )
                        }
                        drawCircle(color = Color.White, radius = loupeRadius, center = Offset(loupeCenterX, loupeCenterY), style = Stroke(width = 6f))
                        drawLine(Color(HANDLE_COLOR), Offset(loupeCenterX - 10f, loupeCenterY), Offset(loupeCenterX + 10f, loupeCenterY), strokeWidth = 2f)
                        drawLine(Color(HANDLE_COLOR), Offset(loupeCenterX, loupeCenterY - 10f), Offset(loupeCenterX, loupeCenterY + 10f), strokeWidth = 2f)
                    }
                }
            }
        }
    }

    pendingTextPosition?.let { position ->
        AlertDialog(
            onDismissRequest = { pendingTextPosition = null; textInput = "" },
            title = { Text(stringResource(R.string.annot_add_label)) },
            text = {
                OutlinedTextField(value = textInput, onValueChange = { textInput = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    if (textInput.isNotBlank()) {
                        viewModel.addShape(
                            AnnotationShape.TextLabel(
                                id = java.util.UUID.randomUUID().toString(),
                                colorArgb = ANNOTATION_COLOR,
                                position = position,
                                text = textInput.trim()
                            )
                        )
                    }
                    pendingTextPosition = null
                    textInput = ""
                }) { Text(stringResource(R.string.annot_add)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingTextPosition = null; textInput = "" }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    if (showOcrPanel) {
        AlertDialog(
            onDismissRequest = { showOcrPanel = false },
            title = { Text(stringResource(R.string.annot_ocr_whole)) },
            text = {
                Column {
                    if (uiState.isRunningOcr) {
                        CircularProgressIndicator(modifier = Modifier.padding(bottom = 8.dp))
                    }
                    SelectableOcrText(uiState.ocrText.ifBlank { stringResource(R.string.annotate_no_text) })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    clipboardManager.setText(AnnotatedString(uiState.ocrText))
                }) { Text(stringResource(R.string.annot_copy)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { viewModel.rerunOcr(context) }, enabled = !uiState.isRunningOcr) {
                        Text(stringResource(R.string.annot_rescan))
                    }
                    TextButton(onClick = { showOcrPanel = false }) { Text(stringResource(R.string.action_close)) }
                }
            }
        )
    }

    if (showQuadOcrDialog) {
        AlertDialog(
            onDismissRequest = { showQuadOcrDialog = false; viewModel.dismissQuadOcr() },
            title = { Text(stringResource(R.string.annot_ocr_region)) },
            text = {
                Column {
                    if (uiState.isRunningQuadOcr) {
                        CircularProgressIndicator(modifier = Modifier.padding(bottom = 8.dp))
                    }
                    SelectableOcrText(uiState.quadOcrText?.ifBlank { "No text detected in that selection." } ?: "Scanning…")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { clipboardManager.setText(AnnotatedString(uiState.quadOcrText ?: "")) },
                    enabled = !uiState.quadOcrText.isNullOrBlank()
                ) { Text(stringResource(R.string.annot_copy)) }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = { viewModel.keepQuadOcrText(); showQuadOcrDialog = false },
                        enabled = !uiState.quadOcrText.isNullOrBlank()
                    ) { Text(stringResource(R.string.annot_save_to_image)) }
                    TextButton(onClick = { showQuadOcrDialog = false; viewModel.dismissQuadOcr() }) { Text(stringResource(R.string.annot_close)) }
                }
            }
        )
    }
}

/**
 * OCR result text: explicitly colored (never left to inherit an ambient color that can end
 * up matching the dialog background) and wrapped in a [SelectionContainer] with an explicit,
 * clearly-visible selection highlight — so the surgeon can manually select a portion of the
 * scanned text without the "selection" being an invisible white-on-white smear.
 */
@Composable
private fun SelectableOcrText(text: String) {
    val customSelectionColors = TextSelectionColors(
        handleColor = MaterialTheme.colorScheme.primary,
        backgroundColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    )
    CompositionLocalProvider(LocalTextSelectionColors provides customSelectionColors) {
        SelectionContainer {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun ToolButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, isActive: Boolean, onClick: () -> Unit) {
    // The label under each icon: an unlabelled row of eight icons was the problem
    // this screen had in the first place.
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
            )
        ) {
            Icon(icon, contentDescription = label, tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawArrow(start: Offset, end: Offset, color: Color) {
    drawLine(color = color, start = start, end = end, strokeWidth = 5f)
    val angle = kotlin.math.atan2((end.y - start.y).toDouble(), (end.x - start.x).toDouble())
    val arrowLength = 24f
    val arrowAngle = Math.PI / 7
    val p1 = Offset(
        (end.x - arrowLength * kotlin.math.cos(angle - arrowAngle)).toFloat(),
        (end.y - arrowLength * kotlin.math.sin(angle - arrowAngle)).toFloat()
    )
    val p2 = Offset(
        (end.x - arrowLength * kotlin.math.cos(angle + arrowAngle)).toFloat(),
        (end.y - arrowLength * kotlin.math.sin(angle + arrowAngle)).toFloat()
    )
    drawLine(color = color, start = end, end = p1, strokeWidth = 5f)
    drawLine(color = color, start = end, end = p2, strokeWidth = 5f)
}
