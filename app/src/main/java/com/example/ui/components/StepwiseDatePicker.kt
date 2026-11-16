package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private enum class PickerStep { YEAR, MONTH, DAY }

/**
 * Date picker that starts on the year, then the month, then the day.
 *
 * For a date of birth this is far quicker than paging a calendar back thirty
 * years: three taps, each on a short list.
 */
@Composable
fun StepwiseDatePickerDialog(
    initialMillis: Long?,
    onDismiss: () -> Unit,
    onDateSelected: (Long) -> Unit,
    yearRange: IntRange = 1900..(Calendar.getInstance().get(Calendar.YEAR) + 1)
) {
    val locale = LocalConfiguration.current.locales[0]
    val start = remember(initialMillis) {
        Calendar.getInstance().apply { timeInMillis = initialMillis ?: System.currentTimeMillis() }
    }

    var step by remember { mutableStateOf(PickerStep.YEAR) }
    var year by remember { mutableIntStateOf(start.get(Calendar.YEAR)) }
    var month by remember { mutableIntStateOf(start.get(Calendar.MONTH)) }

    val monthNames = remember(locale) {
        val format = SimpleDateFormat("LLLL", locale)
        val calendar = Calendar.getInstance()
        (0..11).map { index ->
            calendar.set(Calendar.DAY_OF_MONTH, 1)
            calendar.set(Calendar.MONTH, index)
            format.format(calendar.time).replaceFirstChar { it.titlecase(locale) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = stringResource(
                        when (step) {
                            PickerStep.YEAR -> R.string.date_picker_choose_year
                            PickerStep.MONTH -> R.string.date_picker_choose_month
                            PickerStep.DAY -> R.string.date_picker_choose_day
                        }
                    ),
                    style = MaterialTheme.typography.titleMedium
                )
                // Breadcrumb: tapping a step goes back to it.
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (step != PickerStep.YEAR) {
                        Text(
                            text = year.toString(),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { step = PickerStep.YEAR }
                        )
                    }
                    if (step == PickerStep.DAY) {
                        Text("›", style = MaterialTheme.typography.labelLarge)
                        Text(
                            text = monthNames[month],
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { step = PickerStep.MONTH }
                        )
                    }
                }
            }
        },
        text = {
            when (step) {
                PickerStep.YEAR -> {
                    // Twelve years per page, swiped sideways. A single long grid
                    // meant scrolling through a hundred numbers to find a birth year.
                    val years = remember(yearRange) { yearRange.toList().reversed() }
                    val pages = remember(years) { years.chunked(12) }  // 3 columns x 4 rows
                    val startPage = remember(years) {
                        (years.indexOf(year).takeIf { it >= 0 } ?: 0) / 12
                    }
                    val pagerState = rememberPagerState(initialPage = startPage) { pages.size }
                    Column {
                        Text(
                            text = stringResource(R.string.date_picker_swipe_years),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.height(250.dp)
                        ) { page ->
                            Column {
                                // Same shape as the month page: three across, four down.
                                pages[page].chunked(3).forEach { row ->
                                    Row(modifier = Modifier.fillMaxWidth()) {
                                        row.forEach { value ->
                                            Box(modifier = Modifier.weight(1f)) {
                                                PickerCell(
                                                    label = value.toString(),
                                                    selected = value == year,
                                                    onClick = { year = value; step = PickerStep.MONTH }
                                                )
                                            }
                                        }
                                        repeat(3 - row.size) { Box(modifier = Modifier.weight(1f)) }
                                    }
                                }
                            }
                        }
                    }
                }

                PickerStep.MONTH -> LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.heightIn(max = 320.dp)
                ) {
                    items(monthNames) { name ->
                        val index = monthNames.indexOf(name)
                        PickerCell(
                            label = name.take(if (name.length > 9) 4 else name.length),
                            selected = index == month,
                            onClick = { month = index; step = PickerStep.DAY }
                        )
                    }
                }

                PickerStep.DAY -> {
                    val calendar = remember(year, month) {
                        Calendar.getInstance().apply {
                            set(year, month, 1, 12, 0, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                    }
                    val daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
                    val selectedDay = start.takeIf {
                        it.get(Calendar.YEAR) == year && it.get(Calendar.MONTH) == month
                    }?.get(Calendar.DAY_OF_MONTH)
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(7),
                        modifier = Modifier.heightIn(max = 320.dp)
                    ) {
                        items((1..daysInMonth).toList()) { day ->
                            PickerCell(
                                label = day.toString(),
                                selected = day == selectedDay,
                                onClick = {
                                    val picked = (calendar.clone() as Calendar).apply {
                                        set(Calendar.DAY_OF_MONTH, day)
                                    }
                                    onDateSelected(picked.timeInMillis)
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

@Composable
private fun PickerCell(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .padding(3.dp)
            .aspectRatio(1.4f)
            .background(
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
        )
    }
}

/** Formats a stored date for the read-only fields that open this picker. */
fun formatPickedDate(millis: Long?, locale: Locale): String =
    millis?.let { SimpleDateFormat("d MMM yyyy", locale).format(Date(it)) } ?: ""
