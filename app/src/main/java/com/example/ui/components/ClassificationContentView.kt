package com.example.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.data.classification.ClassificationDocument
import com.example.data.local.model.DocParagraph
import com.example.data.local.model.ParagraphStyles

/**
 * Shows Word-style classification content: headings of any depth, nested
 * bullet/numbered/lettered lists, notes, free text, figures and tables. Used by
 * the classification viewer and by the editor's preview, so what is written is
 * exactly what is read. Unknown styles (from a newer app) show as text.
 */
@Composable
fun ClassificationContentView(
    paragraphs: List<DocParagraph>,
    loadImage: (String) -> ImageBitmap?,
    modifier: Modifier = Modifier
) {
    val markers = remember(paragraphs) { ClassificationDocument.listMarkers(paragraphs) }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        paragraphs.forEachIndexed { index, paragraph ->
            ContentParagraph(paragraph, markers.getOrNull(index), loadImage)
        }
    }
}

@Composable
private fun ContentParagraph(paragraph: DocParagraph, marker: String?, loadImage: (String) -> ImageBitmap?) {
    val start = paragraphIndent(paragraph)
    when (paragraph.style) {
        ParagraphStyles.IMAGE -> {
            val path = paragraph.image ?: return
            val bitmap = remember(path) { loadImage(path) } ?: return
            Image(
                bitmap = bitmap,
                contentDescription = paragraph.text.ifBlank { null },
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .padding(vertical = 6.dp)
                    .clip(RoundedCornerShape(12.dp))
            )
        }
        ParagraphStyles.TABLE -> ContentTable(paragraph.rows)
        ParagraphStyles.NOTE -> Row(
            modifier = Modifier
                .padding(start = start, top = 4.dp, bottom = 4.dp)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                .padding(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(18.dp)
                    .background(MaterialTheme.colorScheme.tertiary, RoundedCornerShape(2.dp))
            )
            Text(
                text = paragraph.text,
                style = paragraphTextStyle(paragraph),
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        else -> Row(modifier = Modifier.padding(start = start, top = headingSpace(paragraph))) {
            if (marker != null) {
                Text(
                    text = marker,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.widthIn(min = 24.dp)
                )
            }
            Text(text = paragraph.text, style = paragraphTextStyle(paragraph))
        }
    }
}

@Composable
private fun ContentTable(rows: List<List<String>>) {
    if (rows.isEmpty()) return
    Column(
        modifier = Modifier
            .padding(vertical = 4.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .horizontalScroll(rememberScrollState())
    ) {
        rows.forEachIndexed { r, row ->
            Row(
                modifier = Modifier.background(
                    if (r == 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                )
            ) {
                row.forEach { cell ->
                    Text(
                        text = cell,
                        style = if (r == 0) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall,
                        fontWeight = if (r == 0) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier
                            .width(150.dp)
                            .padding(horizontal = 8.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

/** The text style of a line, shared with the editor so both look the same. */
@Composable
fun paragraphTextStyle(paragraph: DocParagraph): TextStyle {
    val typography = MaterialTheme.typography
    val colors = MaterialTheme.colorScheme
    return when (paragraph.style) {
        ParagraphStyles.TITLE -> typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
        ParagraphStyles.HEADING -> when (paragraph.level) {
            1 -> typography.titleLarge.copy(fontWeight = FontWeight.Bold, color = colors.primary)
            2 -> typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            3 -> typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = colors.primary)
            else -> typography.labelLarge.copy(fontWeight = FontWeight.Bold, color = colors.onSurfaceVariant)
        }
        ParagraphStyles.NOTE -> typography.bodyMedium.copy(fontStyle = FontStyle.Italic)
        else -> typography.bodyMedium.copy(color = colors.onSurface)
    }
}

/** Left indent: list and text levels; headings deeper than a classification step in too. */
fun paragraphIndent(paragraph: DocParagraph): Dp = when (paragraph.style) {
    ParagraphStyles.HEADING -> (((paragraph.level - 3).coerceAtLeast(0)) * 12).dp
    ParagraphStyles.TITLE, ParagraphStyles.IMAGE, ParagraphStyles.TABLE -> 0.dp
    else -> (paragraph.level.coerceAtMost(ClassificationDocument.MAX_INDENT) * 18).dp
}

private fun headingSpace(paragraph: DocParagraph): Dp =
    if (paragraph.style == ParagraphStyles.HEADING) 6.dp else 0.dp
