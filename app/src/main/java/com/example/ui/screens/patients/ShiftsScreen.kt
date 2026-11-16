package com.example.ui.screens.patients

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextButton
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.repository.ShiftRepository
import com.example.viewmodel.ShiftsViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Month calendar where the surgeon taps the days they are on call. Tapping toggles
 * a day immediately, so several shifts are entered in a few seconds and there is
 * nothing to save.
 *
 * Content only: it lives inside the Agenda tab, which owns the app bar.
 */
@Composable
fun ShiftsContent(
    viewModel: ShiftsViewModel,
    modifier: Modifier = Modifier
) {
    val locale = LocalConfiguration.current.locales[0]
    val month by viewModel.month.collectAsStateWithLifecycle()
    val shifts by viewModel.shiftsThisMonth.collectAsStateWithLifecycle()
    val today = remember { ShiftRepository.today() }

    val calendar = remember(month) {
        Calendar.getInstance().apply {
            set(month.year, month.month, 1, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }
    val monthLabel = remember(month, locale) {
        SimpleDateFormat("LLLL yyyy", locale).format(calendar.time)
            .replaceFirstChar { it.titlecase(locale) }
    }
    val daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
    val firstWeekday = calendar.get(Calendar.DAY_OF_WEEK) // 1 = Sunday
    // Weekend days are a personal setting (Friday and Saturday by default here),
    // kept on the phone and editable right under the calendar.
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences(UI_PREFS, android.content.Context.MODE_PRIVATE) }
    var weekendDays by remember {
        mutableStateOf(
            prefs.getString(KEY_WEEKEND, null)
                ?.split(',')?.mapNotNull { it.toIntOrNull() }?.toSet()
                ?: setOf(Calendar.FRIDAY, Calendar.SATURDAY)
        )
    }
    fun toggleWeekend(day: Int) {
        weekendDays = if (day in weekendDays) weekendDays - day else weekendDays + day
        prefs.edit().putString(KEY_WEEKEND, weekendDays.joinToString(",")).apply()
    }
    var editingWeekend by rememberSaveable { mutableStateOf(false) }

    // The week starts on the day after the weekend: with a Friday–Saturday weekend
    // the calendar begins on Sunday, with Saturday–Sunday on Monday. Without a
    // weekend it follows the language's usual first day.
    val weekStart = remember(weekendDays) { weekStartAfter(weekendDays, calendar.firstDayOfWeek) }
    val leadingBlanks = ((firstWeekday - weekStart) + 7) % 7

    // Calendar.DAY_OF_WEEK values in display order, e.g. SUNDAY..SATURDAY.
    val weekOrder = remember(weekStart) { (0..6).map { ((weekStart - 1 + it) % 7) + 1 } }
    val weekdayNames = remember(locale, weekOrder) { weekOrder.map { dayName(it, "EEEEE", locale).uppercase(locale) } }


    // The first shift after today, when it falls in the month on screen.
    val nextShift = remember(shifts, today) { shifts.filter { it > today }.minOrNull() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        ) {
            IconButton(onClick = { viewModel.step(-1) }) {
                Icon(Icons.Default.ChevronLeft, contentDescription = stringResource(R.string.shifts_previous_month))
            }
            Text(text = monthLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            IconButton(onClick = { viewModel.step(1) }) {
                Icon(Icons.Default.ChevronRight, contentDescription = stringResource(R.string.shifts_next_month))
            }
        }

        Text(
            text = stringResource(R.string.shifts_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Row(modifier = Modifier.fillMaxWidth()) {
            weekdayNames.forEachIndexed { index, name ->
                val weekend = weekOrder[index] in weekendDays
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (weekend) FontWeight.Bold else FontWeight.Normal,
                    color = if (weekend) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }

        val cells = remember(month, daysInMonth, leadingBlanks) {
            List(leadingBlanks) { 0 } + (1..daysInMonth).toList()
        }
        // Plain rows, not a lazy grid: 42 fixed cells gain nothing from laziness,
        // and this lets the whole tab scroll on short screens.
        cells.chunked(7).forEach { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                week.forEach { day ->
                    Box(modifier = Modifier.weight(1f)) {
                        if (day == 0) {
                            Box(modifier = Modifier.aspectRatio(1f))
                        } else {
                            val dayCalendar = (calendar.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, day) }
                            val date = ShiftRepository.format(dayCalendar.timeInMillis)
                            DayCell(
                                day = day,
                                isShift = date in shifts,
                                isToday = date == today,
                                isNextShift = date == nextShift,
                                isWeekend = dayCalendar.get(Calendar.DAY_OF_WEEK) in weekendDays,
                                onClick = { viewModel.toggle(date, date !in shifts) }
                            )
                        }
                    }
                }
                repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
            }
        }

        Text(
            text = stringResource(R.string.shifts_count_this_month, shifts.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp)
        )
        nextShift?.let { next ->
            val label = remember(next, locale) {
                runCatching {
                    val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(next)!!
                    SimpleDateFormat("EEEE d MMMM", locale).format(parsed).replaceFirstChar { it.titlecase(locale) }
                }.getOrDefault(next)
            }
            Text(
                text = stringResource(R.string.shifts_next_shift, label),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        // Legend, so the colours explain themselves.
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LegendItem(stringResource(R.string.shifts_legend_shift)) {
                    Modifier.background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                }
                LegendItem(stringResource(R.string.shifts_legend_next)) {
                    Modifier.background(MaterialTheme.colorScheme.tertiary, RoundedCornerShape(4.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LegendItem(stringResource(R.string.widget_today_label)) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(4.dp))
                }
                LegendItem(stringResource(R.string.shifts_legend_weekend)) {
                    Modifier.background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(4.dp))
                }
            }
        }

        // Weekend days, adjustable in place.
        val weekendLabel = remember(weekendDays, weekOrder, locale) {
            weekOrder.filter { it in weekendDays }.joinToString(", ") { dayName(it, "EEE", locale) }
        }
        TextButton(
            onClick = { editingWeekend = !editingWeekend },
            modifier = Modifier.padding(top = 8.dp)
        ) {
            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.shifts_weekend_days, weekendLabel.ifBlank { "—" }))
        }
        AnimatedVisibility(visible = editingWeekend) {
            Column {
                Text(
                    text = stringResource(R.string.shifts_weekend_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, bottom = 6.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                    weekOrder.forEach { day ->
                        FilterChip(
                            selected = day in weekendDays,
                            onClick = { toggleWeekend(day) },
                            label = {
                                Text(
                                    dayName(day, "EEEEE", locale).uppercase(locale),
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            },
                            modifier = Modifier
                                .weight(1f)
                                .semantics { contentDescription = dayName(day, "EEEE", locale) }
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DayCell(
    day: Int,
    isShift: Boolean,
    isToday: Boolean,
    isNextShift: Boolean,
    isWeekend: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(10.dp)
    val colors = MaterialTheme.colorScheme
    val (background, content) = when {
        isNextShift -> colors.tertiary to colors.onTertiary
        isShift -> colors.primary to colors.onPrimary
        isWeekend -> colors.secondaryContainer to colors.onSecondaryContainer
        else -> colors.surfaceVariant.copy(alpha = 0.4f) to colors.onSurface
    }
    val todayLabel = stringResource(R.string.widget_today_label)
    val nextLabel = stringResource(R.string.shifts_legend_next)
    val weekendLabel = stringResource(R.string.shifts_legend_weekend)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .padding(3.dp)
            .aspectRatio(1f)
            .clip(shape)
            .background(background)
            // Today is a ring, so it still shows on a shift or a weekend day.
            .then(if (isToday) Modifier.border(2.5.dp, colors.onSurface, shape) else Modifier)
            // A toggle for TalkBack ("on call, checked"), with the ripple clipped to the cell.
            .toggleable(value = isShift, role = Role.Checkbox, onValueChange = { onClick() })
            .semantics {
                stateDescription = listOfNotNull(
                    todayLabel.takeIf { isToday },
                    nextLabel.takeIf { isNextShift },
                    weekendLabel.takeIf { isWeekend }
                ).joinToString(", ")
            }
    ) {
        Text(
            text = day.toString(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isShift || isToday) FontWeight.Bold else FontWeight.Normal,
            color = content
        )
    }
}

@Composable
private fun LegendItem(label: String, swatch: @Composable () -> Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(14.dp).then(swatch()))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * First day after the weekend. Scans from [fallback] for a weekend day followed by a
 * working day, so a split weekend still gives a sensible start.
 */
private fun weekStartAfter(weekend: Set<Int>, fallback: Int): Int {
    if (weekend.isEmpty() || weekend.size == 7) return fallback
    for (offset in 0..6) {
        val day = ((fallback - 1 + offset) % 7) + 1
        val next = day % 7 + 1
        if (day in weekend && next !in weekend) return next
    }
    return fallback
}

private fun dayName(dayOfWeek: Int, pattern: String, locale: Locale): String {
    val cursor = Calendar.getInstance().apply { set(Calendar.DAY_OF_WEEK, dayOfWeek) }
    return SimpleDateFormat(pattern, locale).format(cursor.time)
}

private const val UI_PREFS = "ui_state"
private const val KEY_WEEKEND = "weekend_days"
