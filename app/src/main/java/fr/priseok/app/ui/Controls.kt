package fr.priseok.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fr.priseok.app.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Small building blocks shared by the settings and the medication editor. */

private val Ink = Color(0xFF17332D) // text on the pink and butter fills, in both themes

@Composable
internal fun Section(title: String) {
    Spacer(Modifier.height(30.dp))
    Text(title, style = MaterialTheme.typography.titleMedium, color = LocalPalette.current.pine)
    Spacer(Modifier.height(12.dp))
}

@Composable
internal fun Toggle(label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        modifier
            .clip(CircleShape)
            .background(if (on) p.pine else p.surface)
            .border(1.dp, if (on) p.pine else p.line, CircleShape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { selected = on }
            .padding(vertical = 12.dp, horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, color = if (on) p.onPine else p.pine,
            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
        )
    }
}

@Composable
internal fun DayChip(letter: String, full: String, on: Boolean, role: Role = Role.Checkbox, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(if (on) p.pink else p.surface)
            .border(1.dp, if (on) p.pink else p.line, CircleShape)
            .clickable(role = role, onClick = onClick)
            .semantics { selected = on; contentDescription = full },
        contentAlignment = Alignment.Center,
    ) {
        Text(letter, style = MaterialTheme.typography.titleMedium, color = Ink.takeIf { on } ?: p.pine)
    }
}

@Composable
internal fun RoundStep(sign: String, desc: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier
            .size(42.dp)
            .clip(CircleShape)
            .border(1.dp, p.line, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) { Text(sign, style = MaterialTheme.typography.titleLarge, color = p.pine) }
}

/** The clock dialog, in Pilou's colors. Times are minutes of the day. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimeDialog(initial: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val state = rememberTimePickerState(
        initialHour = initial / 60, initialMinute = initial % 60,
        is24Hour = android.text.format.DateFormat.is24HourFormat(ctx),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = p.mist,
        confirmButton = {
            TextButton(onClick = { onPick(state.hour * 60 + state.minute) }) { Text(stringResource(R.string.ok), color = p.pine) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = p.pineSoft) } },
        text = {
            TimePicker(
                state,
                colors = TimePickerDefaults.colors(
                    clockDialColor = p.surface, selectorColor = p.pink,
                    timeSelectorSelectedContainerColor = p.butter,
                    timeSelectorSelectedContentColor = Ink,
                    timeSelectorUnselectedContainerColor = p.surface,
                    timeSelectorUnselectedContentColor = p.pine,
                    clockDialSelectedContentColor = Ink,
                    clockDialUnselectedContentColor = p.pine,
                ),
            )
        },
    )
}

/** The calendar dialog, in Pilou's colors. Days before [firstAllowed] can't be picked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateDialog(initial: LocalDate, firstAllowed: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    // The date picker works in UTC midnights.
    fun ms(d: LocalDate) = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val state = rememberDatePickerState(
        initialSelectedDateMillis = ms(initial),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= ms(firstAllowed)
            override fun isSelectableYear(year: Int) = year >= firstAllowed.year
        },
    )
    val colors = DatePickerDefaults.colors(
        containerColor = p.mist,
        selectedDayContainerColor = p.pine, selectedDayContentColor = p.onPine,
        todayDateBorderColor = p.pine, todayContentColor = p.pine,
        dayContentColor = p.pine, weekdayContentColor = p.pineSoft,
        titleContentColor = p.pineSoft, headlineContentColor = p.pine,
        navigationContentColor = p.pine, yearContentColor = p.pine,
        selectedYearContainerColor = p.pine, selectedYearContentColor = p.onPine,
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        colors = colors,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) } ?: onDismiss()
            }) { Text(stringResource(R.string.ok), color = p.pine) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = p.pineSoft) } },
    ) {
        DatePicker(state, colors = colors, showModeToggle = false)
    }
}
