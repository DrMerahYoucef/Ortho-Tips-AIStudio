package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.local.entity.ImageEntity

private enum class BlockKind { PARAGRAPH, BULLET, STEP, SUBHEADER }

private data class TextBlock(
    val kind: BlockKind,
    val text: String,
    val stepNumber: String? = null
)

/**
 * Reconstructs real paragraphs out of PDF-extracted text, where every original visual
 * line break was preserved as a "\n" even mid-sentence. Lines are merged until one ends
 * a sentence (., !, ?), a blank line is hit, or a bullet/numbered-step/subheading line
 * starts, so the result reads as flowing paragraphs instead of one fragment per line.
 */
private fun buildBlocks(content: String): List<TextBlock> {
    val blocks = mutableListOf<TextBlock>()
    val buffer = StringBuilder()

    fun flushParagraph() {
        val text = buffer.toString().trim()
        if (text.isNotEmpty()) {
            blocks.add(TextBlock(BlockKind.PARAGRAPH, text))
        }
        buffer.clear()
    }

    for (rawLine in content.lines()) {
        val line = rawLine.trim()
        when {
            line.isEmpty() -> flushParagraph()

            line.matches(Regex("^\\d+\\..*")) -> {
                flushParagraph()
                val stepNum = line.substringBefore(".").trim()
                val stepText = line.substringAfter(".").trim()
                blocks.add(TextBlock(BlockKind.STEP, stepText, stepNum))
            }

            line.startsWith("- ") || line.startsWith("\u2022 ") -> {
                flushParagraph()
                val bulletText = line.removePrefix("- ").removePrefix("\u2022 ").trim()
                blocks.add(TextBlock(BlockKind.BULLET, bulletText))
            }

            line.endsWith(":") && line.length <= 60 -> {
                flushParagraph()
                blocks.add(TextBlock(BlockKind.SUBHEADER, line))
            }

            else -> {
                if (buffer.isNotEmpty()) buffer.append(" ")
                buffer.append(line)
                if (line.endsWith(".") || line.endsWith("!") || line.endsWith("?")) {
                    flushParagraph()
                }
            }
        }
    }
    flushParagraph()
    return blocks
}

private val FIGURE_NUMBER_REGEX = Regex("""\d+-\d+""")

/** Distinct figure numbers (e.g. "13-1") mentioned in [text], in order of first appearance. */
private fun figureNumbersIn(text: String): List<String> =
    FIGURE_NUMBER_REGEX.findAll(text).map { it.value }.distinct().toList()

/** Builds highlighted text for [text], marking every case-insensitive match of [query]. */
private fun highlightedText(
    text: String,
    query: String,
    highlightColor: androidx.compose.ui.graphics.Color,
    highlightTextColor: androidx.compose.ui.graphics.Color
): AnnotatedString {
    if (query.isBlank()) return AnnotatedString(text)

    return buildAnnotatedString {
        var searchStart = 0
        var matchIndex = text.indexOf(query, searchStart, ignoreCase = true)
        if (matchIndex == -1) {
            append(text)
            return@buildAnnotatedString
        }
        while (matchIndex != -1) {
            append(text.substring(searchStart, matchIndex))
            withStyle(
                SpanStyle(
                    background = highlightColor,
                    color = highlightTextColor,
                    fontWeight = FontWeight.Bold
                )
            ) {
                append(text.substring(matchIndex, matchIndex + query.length))
            }
            searchStart = matchIndex + query.length
            matchIndex = text.indexOf(query, searchStart, ignoreCase = true)
        }
        append(text.substring(searchStart))
    }
}

@Composable
private fun InlineFigureCard(
    image: ImageEntity,
    onClick: (ImageEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick(image) }
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            ) {
                val model: Any? = if (!image.imagePath.isNullOrBlank()) {
                    image.imagePath
                } else null

                if (model != null) {
                    AsyncImage(
                        model = model,
                        contentDescription = image.title,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().height(180.dp)
                    )
                }
            }
            Row(
                modifier = Modifier.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.primary
                ) {
                    Text(
                        text = image.figureNumber,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = image.title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun OperativeText(
    content: String,
    modifier: Modifier = Modifier,
    images: List<ImageEntity> = emptyList(),
    searchQuery: String = "",
    onFigureClick: (ImageEntity) -> Unit = {}
) {
    val blocks = buildBlocks(content)
    val imagesByFigureNumber = remember(images) {
        images.associateBy { it.figureNumber }
    }
    val shownFigureIds = mutableSetOf<String>()

    val highlightColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    val highlightTextColor = MaterialTheme.colorScheme.onSurface

    Column(modifier = modifier.fillMaxWidth()) {
        blocks.forEach { block ->
            when (block.kind) {
                BlockKind.STEP -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = block.stepNumber ?: "",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    fontSize = 11.sp
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = highlightedText(block.text, searchQuery, highlightColor, highlightTextColor),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            lineHeight = 22.sp,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                BlockKind.BULLET -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(top = 8.dp)
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = highlightedText(block.text, searchQuery, highlightColor, highlightTextColor),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            lineHeight = 22.sp,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                BlockKind.SUBHEADER -> {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = highlightedText(block.text, searchQuery, highlightColor, highlightTextColor),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                }

                BlockKind.PARAGRAPH -> {
                    Text(
                        text = highlightedText(block.text, searchQuery, highlightColor, highlightTextColor),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = 22.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    )
                }
            }

            // Show the figure(s) referenced by this block, right under it, the first
            // time each one is mentioned in this section.
            figureNumbersIn(block.text).forEach { figNum ->
                val image = imagesByFigureNumber[figNum]
                if (image != null && shownFigureIds.add(image.id)) {
                    InlineFigureCard(
                        image = image,
                        onClick = onFigureClick,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                }
            }
        }
    }
}
