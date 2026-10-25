package fr.priseok.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fr.priseok.app.Codec
import fr.priseok.app.R
import fr.priseok.app.core.Frequency
import fr.priseok.app.core.Med
import fr.priseok.app.core.Wording
import java.time.LocalDate

/** Edits one medication: name, times, days, end date, alert. Changes apply only on "Done". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MedEditor(
    med: Med,
    isNew: Boolean,
    canRemove: Boolean,
    today: LocalDate,
    onDone: (Med) -> Unit,
    onRemove: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    val tx = rememberTexts()
    // The draft, as JSON so it survives rotation.
    var json by rememberSaveable(med.id) { mutableStateOf(Codec.meds(listOf(med))) }
    val d = remember(json) { Codec.meds(json).first() }
    fun set(change: (Med) -> Med) { json = Codec.meds(listOf(change(d))) }
    // Index of the time being edited; -1 = adding one; null = no dialog.
    var editingTime by remember { mutableStateOf<Int?>(null) }

    Column(
        modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.back), tint = p.pine)
            }
            Spacer(Modifier.width(4.dp))
            Text(stringResource(if (isNew) R.string.med_new else R.string.med_title), style = MaterialTheme.typography.titleLarge, color = p.pine)
        }

        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = d.name, onValueChange = { v -> set { it.copy(name = v) } },
            label = { Text(stringResource(R.string.med_name)) },
            placeholder = { Text(stringResource(R.string.med_name_example), color = p.pineSoft) },
            singleLine = true, shape = RoundedCornerShape(18.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = p.pine, unfocusedBorderColor = p.line,
                focusedContainerColor = p.surface, unfocusedContainerColor = p.surface,
                focusedLabelColor = p.pine, cursorColor = p.pine,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        // At what time
        Section(stringResource(R.string.section_times))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            d.times.forEachIndexed { i, t ->
                TimeChip(
                    time = tx.time(t),
                    onClick = { editingTime = i },
                    onRemove = if (d.times.size > 1) ({ set { it.copy(times = it.times - t) } }) else null,
                )
            }
            AddChip(stringResource(R.string.add_time)) { editingTime = -1 }
        }

        // Which days
        Section(stringResource(R.string.section_days))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Frequency.DAILY to R.string.every_day, Frequency.WEEKLY to R.string.some_days, Frequency.MONTHLY to R.string.every_month)
                .forEach { (f, label) -> Toggle(stringResource(label), d.frequency == f, Modifier.weight(1f)) { set { it.copy(frequency = f) } } }
        }
        if (d.frequency == Frequency.WEEKLY) {
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                (1..7).forEach { day ->
                    val on = day in d.daysOfWeek
                    DayChip(Wording.dayLetter(day, tx.locale), Wording.dayName(day, tx.locale), on) {
                        // At least one day stays chosen.
                        if (!on) set { it.copy(daysOfWeek = it.daysOfWeek + day) }
                        else if (d.daysOfWeek.size > 1) set { it.copy(daysOfWeek = it.daysOfWeek - day) }
                    }
                }
            }
        }
        if (d.frequency == Frequency.MONTHLY) {
            Spacer(Modifier.height(14.dp))
            val dom = d.dayOfMonth
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.monthly_on), style = MaterialTheme.typography.bodyLarge, color = p.pine)
                Spacer(Modifier.width(10.dp))
                RoundStep("−", stringResource(R.string.previous_day)) { set { it.copy(dayOfMonth = if (dom <= 1) 31 else dom - 1) } }
                Text(
                    "$dom", style = MaterialTheme.typography.titleLarge, color = p.pine,
                    textAlign = TextAlign.Center, modifier = Modifier.width(48.dp),
                )
                RoundStep("+", stringResource(R.string.next_day)) { set { it.copy(dayOfMonth = if (dom >= 31) 1 else dom + 1) } }
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.monthly_of_month), style = MaterialTheme.typography.bodyLarge, color = p.pine)
            }
            if (dom > 28) {
                Text(
                    stringResource(R.string.monthly_short_hint),
                    style = MaterialTheme.typography.bodyMedium, color = p.pineSoft, modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        // Until when
        Section(stringResource(R.string.section_until))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Toggle(stringResource(R.string.no_end), d.endDate == null, Modifier.weight(1f)) { set { it.copy(endDate = null) } }
            Toggle(stringResource(R.string.until_toggle), d.endDate != null, Modifier.weight(1f)) {
                if (d.endDate == null) set { it.copy(endDate = today.plusDays(6)) } // a week by default
            }
        }
        d.endDate?.let { end ->
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoundStep("−", stringResource(R.string.previous_day)) {
                    if (end.isAfter(today)) set { it.copy(endDate = end.minusDays(1)) }
                }
                Text(
                    stringResource(R.string.last_day, Wording.date(end, tx.locale)),
                    style = MaterialTheme.typography.bodyLarge, color = p.pine,
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
                )
                RoundStep("+", stringResource(R.string.next_day)) { set { it.copy(endDate = end.plusDays(1)) } }
            }
        }

        // Alert
        Spacer(Modifier.height(30.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .clickable(role = Role.Switch) { set { it.copy(alert = !it.alert) } }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.alert_switch), style = MaterialTheme.typography.bodyLarge, color = p.pine, modifier = Modifier.weight(1f))
            Switch(
                checked = d.alert, onCheckedChange = null,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = p.pine, checkedThumbColor = p.onPine,
                    uncheckedTrackColor = p.surface, uncheckedBorderColor = p.line, uncheckedThumbColor = p.line,
                ),
            )
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { onDone(d.copy(name = d.name.trim())) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = p.pine, contentColor = p.onPine),
        ) { Text(stringResource(R.string.done), style = MaterialTheme.typography.labelLarge) }
        if (canRemove) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onRemove, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.remove_med), color = p.coral, style = MaterialTheme.typography.bodyLarge)
            }
        }
        Spacer(Modifier.height(32.dp))
    }

    editingTime?.let { i ->
        val start = if (i >= 0) d.times[i] else ((d.times.last() + 4 * 60) % (24 * 60))
        TimeDialog(
            initial = start,
            onPick = { t ->
                set { m ->
                    val others = if (i >= 0) m.times.filterIndexed { j, _ -> j != i } else m.times
                    m.copy(times = (others + t).distinct().sorted())
                }
                editingTime = null
            },
            onDismiss = { editingTime = null },
        )
    }
}

/** A time the medication is taken at: tap to change it, × to remove it. */
@Composable
private fun TimeChip(time: String, onClick: () -> Unit, onRemove: (() -> Unit)?) {
    val p = LocalPalette.current
    val cd = stringResource(R.string.time_cd, time)
    Row(
        Modifier
            .clip(CircleShape)
            .background(p.surface)
            .border(1.dp, p.line, CircleShape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            time, style = MaterialTheme.typography.titleLarge, color = p.pine,
            modifier = Modifier
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = cd }
                .padding(start = 20.dp, end = if (onRemove == null) 20.dp else 6.dp, top = 12.dp, bottom = 12.dp),
        )
        if (onRemove != null) {
            val rm = stringResource(R.string.remove_time_cd, time)
            Text(
                "×", style = MaterialTheme.typography.titleLarge, color = p.pineSoft,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onRemove)
                    .semantics { contentDescription = rm }
                    .padding(start = 8.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            )
        }
    }
}

@Composable
private fun AddChip(label: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    Text(
        "+  $label", style = MaterialTheme.typography.bodyLarge, color = p.pine,
        modifier = Modifier
            .clip(CircleShape)
            .border(1.dp, p.line, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
    )
}
