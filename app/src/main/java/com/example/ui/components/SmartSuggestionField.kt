package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.example.data.suggestion.SuggestionEngine
import com.example.R
import androidx.compose.ui.res.stringResource

/** A small banner card with a switch that turns smart suggestions/autofill on or off for a form. */
@Composable
fun SuggestionsToggleCard(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String = stringResource(R.string.smart_suggestions_default_subtitle)
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.smart_suggestions_title),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

/**
 * A text field that completes from what this surgeon has typed before.
 *
 * Changes from the original, each earning its place:
 *
 *  - **Quick picks on focus.** Tapping an empty field shows its three most-used
 *    values as chips. Most of these fields have a working vocabulary of three or
 *    four terms, so this removes the typing rather than speeding it up.
 *  - **Ranked, not filtered.** Matching runs through [SuggestionEngine], so a
 *    prefix beats a mid-word hit and a recent term beats a stale one. A typo
 *    still finds the value instead of returning nothing.
 *  - **Highlighted matches**, so the eye lands on why a row is offered.
 *  - **Long-press to forget.** A typo learned once used to sit in the list
 *    permanently; [onForget] removes it in place.
 *  - **[predictions]** are values that followed the related field's current value
 *    before. They lead the list under a "usually next" heading, because "what you
 *    did last time for this diagnosis" outranks raw frequency.
 *
 * For multi-value fields, matching and insertion work on the segment being typed,
 * so picking a suggestion completes that item rather than replacing the field.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SmartSuggestionField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    suggestions: List<String>,
    suggestionsEnabled: Boolean,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    isError: Boolean = false,
    errorText: String? = null,
    predictions: List<String> = emptyList(),
    onForget: ((String) -> Unit)? = null
) {
    var isFocused by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    // Long-press asks first: a suggestion vanishing on a stray press is worse
    // than one extra tap.
    var pendingForget by remember { mutableStateOf<String?>(null) }

    pendingForget?.let { candidate ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingForget = null },
            title = { Text(stringResource(R.string.suggestion_forget_title)) },
            text = { Text(stringResource(R.string.suggestion_forget_message, candidate)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    onForget?.invoke(candidate)
                    pendingForget = null
                }) { Text(stringResource(R.string.suggestion_forget_confirm)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pendingForget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    val activeSegment = remember(value) {
        value.substringAfterLast(',').substringAfterLast(';').trimStart()
    }
    val segmentPrefix = remember(value, activeSegment) {
        value.substring(0, value.length - activeSegment.length)
    }

    val ranked = remember(suggestions, predictions, activeSegment, suggestionsEnabled, isFocused) {
        if (!suggestionsEnabled || !isFocused) {
            emptyList()
        } else {
            rankFor(activeSegment, suggestions, predictions)
        }
    }

    // With nothing typed the list becomes quick picks instead of a dropdown:
    // three chips read faster than a stack of rows and cost one tap either way.
    val showQuickPicks = ranked.isNotEmpty() && activeSegment.isBlank()

    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = singleLine,
            isError = isError,
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (suggestionsEnabled) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                        )
                    }
                    FrenchVoiceInputButton { spokenText ->
                        onValueChange(appendDictatedText(value, spokenText))
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusEvent { focusState -> isFocused = focusState.isFocused }
        )

        if (isError && errorText != null) {
            Text(
                text = errorText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 16.dp, top = 2.dp)
            )
        }

        AnimatedVisibility(
            visible = showQuickPicks,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 6.dp)
            ) {
                ranked.take(4).forEach { item ->
                    AssistChip(
                        onClick = { onValueChange(segmentPrefix + item.value) },
                        label = { Text(item.value, maxLines = 1) },
                        leadingIcon = {
                            Icon(
                                imageVector = if (item.isPrediction) {
                                    Icons.Default.TrendingUp
                                } else {
                                    Icons.Default.History
                                },
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            leadingIconContentColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = ranked.isNotEmpty() && !showQuickPicks,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                tonalElevation = 3.dp,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
            ) {
                Column {
                    ranked.forEachIndexed { index, item ->
                        if (index == 0 && item.isPrediction) {
                            Text(
                                text = stringResource(R.string.suggestions_usually_next),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(
                                    start = 16.dp, top = 8.dp, bottom = 2.dp
                                )
                            )
                        }
                        SuggestionRow(
                            item = item,
                            onPick = { onValueChange(segmentPrefix + item.value) },
                            onForget = onForget?.let {
                                {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    pendingForget = item.value
                                }
                            }
                        )
                        if (index != ranked.lastIndex) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One row. 48dp minimum height so it is reliably tappable with gloved hands. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SuggestionRow(
    item: RankedSuggestion,
    onPick: () -> Unit,
    onForget: (() -> Unit)?
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .combinedClickable(onClick = onPick, onLongClick = onForget)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics {
                contentDescription = if (onForget != null) {
                    "${item.value}. Long press to forget this suggestion."
                } else {
                    item.value
                }
            }
    ) {
        Text(
            text = item.annotated(MaterialTheme.colorScheme.primary),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        if (item.isPrediction) {
            Icon(
                imageVector = Icons.Default.TrendingUp,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

private data class RankedSuggestion(
    val value: String,
    val highlight: IntRange?,
    val isPrediction: Boolean
) {
    fun annotated(accent: androidx.compose.ui.graphics.Color) = buildAnnotatedString {
        val range = highlight
        if (range == null || range.first < 0 || range.last >= value.length) {
            append(value)
            return@buildAnnotatedString
        }
        append(value.substring(0, range.first))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = accent)) {
            append(value.substring(range.first, range.last + 1))
        }
        append(value.substring(range.last + 1))
    }
}

/**
 * Predictions first, then ranked vocabulary. A prediction that is also a strong
 * text match would otherwise appear twice.
 */
private fun rankFor(
    query: String,
    suggestions: List<String>,
    predictions: List<String>
): List<RankedSuggestion> {
    val now = System.currentTimeMillis()
    val predictionSet = predictions.map(SuggestionEngine::normalize).toSet()

    val predicted = SuggestionEngine.rank(
        query = query,
        candidates = predictions.mapIndexed { index, value ->
            SuggestionEngine.Candidate(
                value = value,
                useCount = predictions.size - index,
                lastUsedAt = now,
                contextCount = predictions.size - index
            )
        },
        limit = 3,
        now = now
    ).map { RankedSuggestion(it.value, it.highlight, isPrediction = true) }

    val general = SuggestionEngine.rank(
        query = query,
        candidates = suggestions
            .filterNot { SuggestionEngine.normalize(it) in predictionSet }
            .mapIndexed { index, value ->
                // The repository already ordered these by familiarity, so position
                // stands in for the counts that were not carried across.
                SuggestionEngine.Candidate(
                    value = value,
                    useCount = (suggestions.size - index).coerceAtLeast(1),
                    lastUsedAt = now
                )
            },
        limit = 8 - predicted.size,
        now = now
    ).map { RankedSuggestion(it.value, it.highlight, isPrediction = false) }

    return predicted + general
}

private fun appendDictatedText(existing: String, dictated: String): String =
    if (existing.isBlank()) dictated.trim() else "$existing ${dictated.trim()}"

/**
 * A protocol/steps field that auto-formats free text into bullet points: typing a
 * period ends the current bullet and starts a new one on the next line (and pressing
 * Enter does the same), instead of the text staying one continuous paragraph.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BulletProtocolField(
    value: String,
    onValueChange: (String) -> Unit,
    suggestions: List<String> = emptyList(),
    suggestionsEnabled: Boolean = false,
    modifier: Modifier = Modifier,
    label: String = stringResource(R.string.pdf_option_operative_steps),
    placeholder: String = stringResource(R.string.protocol_steps_placeholder),
    predictions: List<String> = emptyList(),
    onForget: ((String) -> Unit)? = null
) {
    var fieldValue by remember {
        mutableStateOf(TextFieldValue(text = value, selection = TextRange(value.length)))
    }
    var isFocused by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current

    val currentLineStart = value.lastIndexOf('\n') + 1
    val currentLine = value.substring(currentLineStart)
    val activeSegment = currentLine.removePrefix("\u2022").trimStart()

    val ranked = remember(suggestions, predictions, activeSegment, suggestionsEnabled, isFocused) {
        if (!suggestionsEnabled || !isFocused || activeSegment.trim().length < 2) {
            emptyList()
        } else {
            rankFor(activeSegment.trim(), suggestions, predictions)
        }
    }

    // Sync from external changes only (e.g. loading an existing surgery record) — never
    // on every keystroke, or the cursor would jump to the end while mid-edit.
    LaunchedEffect(value) {
        if (value != fieldValue.text) {
            fieldValue = TextFieldValue(text = value, selection = TextRange(value.length))
        }
    }

    OutlinedTextField(
        value = fieldValue,
        onValueChange = { incoming ->
            val formatted = formatAsBulletPoints(fieldValue, incoming)
            fieldValue = formatted
            onValueChange(formatted.text)
        },
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        trailingIcon = {
            FrenchVoiceInputButton { spokenText ->
                val dictated = if (fieldValue.text.isBlank()) {
                    "\u2022 ${spokenText.trim()}"
                } else {
                    "${fieldValue.text.trimEnd()}\n\u2022 ${spokenText.trim()}"
                }
                onValueChange(dictated)
            }
        },
        minLines = 5,
        modifier = modifier
            .fillMaxWidth()
            .onFocusEvent { focusState -> isFocused = focusState.isFocused }
    )

    AnimatedVisibility(
        visible = ranked.isNotEmpty(),
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            tonalElevation = 3.dp,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
        ) {
            Column {
                ranked.forEachIndexed { index, item ->
                    SuggestionRow(
                        item = item,
                        onPick = {
                            val bulletPrefix =
                                if (currentLine.trimStart().startsWith("\u2022")) "\u2022 " else ""
                            val replacement =
                                value.substring(0, currentLineStart) + bulletPrefix + item.value
                            val selected = TextFieldValue(replacement, TextRange(replacement.length))
                            fieldValue = selected
                            onValueChange(selected.text)
                        },
                        onForget = onForget?.let {
                            {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                it(item.value)
                            }
                        }
                    )
                    if (index != ranked.lastIndex) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                        )
                    }
                }
            }
        }
    }
}

private fun formatAsBulletPoints(old: TextFieldValue, new: TextFieldValue): TextFieldValue {
    // Seed the very first bullet marker as soon as the surgeon starts typing.
    if (old.text.isEmpty() && new.text.isNotEmpty() && !new.text.startsWith("\u2022")) {
        val seeded = "\u2022 " + new.text
        return TextFieldValue(seeded, TextRange(seeded.length))
    }

    val isSingleCharacterInsert = new.text.length == old.text.length + 1 &&
        new.selection.start == new.text.length &&
        old.selection.start == old.text.length

    if (isSingleCharacterInsert) {
        when (new.text.last()) {
            '.' -> {
                val withoutDot = new.text.dropLast(1).trimEnd()
                val newText = "$withoutDot.\n\u2022 "
                return TextFieldValue(newText, TextRange(newText.length))
            }
            '\n' -> {
                val newText = new.text + "\u2022 "
                return TextFieldValue(newText, TextRange(newText.length))
            }
        }
    }
    return new
}
