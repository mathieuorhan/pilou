package fr.priseok.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.selected
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
import fr.priseok.app.core.Contact
import fr.priseok.app.core.Frequency
import fr.priseok.app.core.Schedule
import fr.priseok.app.core.Med
import fr.priseok.app.core.Wording
import java.time.LocalDate

/** Edits one medication: name, times, days, end date, alert. Changes apply only on "Done". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MedEditor(
    med: Med,
    contacts: List<Contact>,
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
    // "Other" chosen for the alert delay: shows the fine-grained stepper.
    var customDelay by rememberSaveable { mutableStateOf(false) }
    var pickingDate by remember { mutableStateOf(false) }
    var askSave by remember { mutableStateOf(false) }
    val done = { onDone(d.copy(name = d.name.trim())) }
    val back = { if (d != med) askSave = true else onBack() }
    BackHandler(onBack = back)

    Column(
        modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) {
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
            val endText = Wording.date(end, tx.locale)
            val cd = stringResource(R.string.last_day_cd, endText)
            Text(
                stringResource(R.string.last_day, endText),
                style = MaterialTheme.typography.titleMedium, color = p.pine, textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(p.surface)
                    .border(1.dp, p.line, RoundedCornerShape(24.dp))
                    .clickable(role = Role.Button) { pickingDate = true }
                    .semantics { contentDescription = cd }
                    .padding(vertical = 16.dp, horizontal = 12.dp),
            )
            Spacer(Modifier.height(10.dp))
            // Shortcuts for a course starting today: the last day is today + n - 1.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(3, 7, 10).forEach { n ->
                    val last = today.plusDays(n - 1L)
                    SmallChip(pluralStringResource(R.plurals.for_days, n, n), end == last) { set { it.copy(endDate = last) } }
                }
                val month = today.plusMonths(1).minusDays(1)
                SmallChip(stringResource(R.string.for_month), end == month) { set { it.copy(endDate = month) } }
            }
        }

        // If it's missed: a switch, who to text (when there is a choice), and after how long.
        Section(stringResource(R.string.section_alert))
        if (contacts.isEmpty()) {
            Text(stringResource(R.string.alert_need_contact), style = MaterialTheme.typography.bodyMedium, color = p.pineSoft)
        } else {
            val single = contacts.singleOrNull()
            SwitchRow(
                label = if (single != null) stringResource(R.string.alert_switch_one, tx.contactName(single)) else stringResource(R.string.alert_switch),
                hint = stringResource(R.string.alert_switch_hint),
                checked = d.alerts,
            ) {
                set {
                    if (it.alerts) it.copy(contactIds = emptySet())
                    else {
                        // Back to whoever this medication alerted before, else the first contact.
                        val before = med.contactIds.filter { id -> contacts.any { c -> c.id == id } }.toSet()
                        it.copy(contactIds = before.ifEmpty { setOf(contacts.first().id) })
                    }
                }
            }
            if (d.alerts) {
                if (single == null) {
                    SubSection(stringResource(R.string.alert_who))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        contacts.forEach { c ->
                            val on = c.id in d.contactIds
                            SmallChip(tx.contactName(c), on, Role.Checkbox) {
                                // At least one contact stays chosen; the switch turns alerts off.
                                if (!on) set { it.copy(contactIds = it.contactIds + c.id) }
                                else if (d.contactIds.size > 1) set { it.copy(contactIds = it.contactIds - c.id) }
                            }
                        }
                    }
                }

                // The alert is a delay after each time, so it reads the same for every dose.
                val delay = Schedule.clampedDelay(d)
                val max = Schedule.maxDelayMinutes(d)
                val presets = listOf(30, 60, 120).filter { it <= max }
                val custom = customDelay || delay !in presets
                SubSection(stringResource(R.string.alert_after))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    presets.forEach { m ->
                        SmallChip(tx.delay(m), !custom && delay == m) { customDelay = false; set { it.copy(delayMinutes = m) } }
                    }
                    SmallChip(stringResource(R.string.delay_other), custom) { customDelay = true }
                }
                if (custom) {
                    Spacer(Modifier.height(12.dp))
                    fun step(by: Int) = set { it.copy(delayMinutes = (delay + by).coerceIn(Schedule.MIN_DELAY, max)) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RoundStep("−", stringResource(R.string.delay_less)) { step(-15) }
                        Text(
                            tx.delay(delay), style = MaterialTheme.typography.titleLarge, color = p.pine,
                            textAlign = TextAlign.Center, modifier = Modifier.width(120.dp),
                        )
                        RoundStep("+", stringResource(R.string.delay_more)) { step(15) }
                    }
                }

                Spacer(Modifier.height(14.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .border(1.dp, p.line, RoundedCornerShape(18.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    d.times.forEach { t ->
                        val (a, nextDay) = Schedule.alertTime(t, delay)
                        Text(
                            stringResource(if (nextDay) R.string.alert_line_next_day else R.string.alert_line, tx.time(t), tx.time(a)),
                            style = MaterialTheme.typography.bodyMedium, color = p.pineSoft,
                        )
                    }
                }
            }
        }

        // Pause
        Spacer(Modifier.height(30.dp))
        SwitchRow(stringResource(R.string.pause_switch), d.paused) { set { it.copy(paused = !it.paused) } }
        if (d.paused) {
            Text(stringResource(R.string.pause_hint), style = MaterialTheme.typography.bodyMedium, color = p.pineSoft)
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = done,
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

    if (pickingDate) {
        DateDialog(
            initial = d.endDate ?: today,
            firstAllowed = today,
            onPick = { date -> set { it.copy(endDate = date) }; pickingDate = false },
            onDismiss = { pickingDate = false },
        )
    }

    if (askSave) {
        UnsavedDialog(
            onSave = { askSave = false; done() },
            onDiscard = { askSave = false; onBack() },
            onKeep = { askSave = false },
            saveLabel = R.string.done,
        )
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

@Composable
private fun SmallChip(label: String, on: Boolean, role: Role = Role.RadioButton, onClick: () -> Unit) {
    val p = LocalPalette.current
    Text(
        label, style = MaterialTheme.typography.bodyMedium, color = if (on) p.onPine else p.pine,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (on) p.pine else p.surface)
            .border(1.dp, if (on) p.pine else p.line, CircleShape)
            .clickable(role = role, onClick = onClick)
            .semantics { selected = on }
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

@Composable
private fun SubSection(title: String) {
    Text(
        title, style = MaterialTheme.typography.bodyMedium, color = LocalPalette.current.pineSoft,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, hint: String? = null, onToggle: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(role = Role.Switch, onClick = onToggle)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = p.pine)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodyMedium, color = p.pineSoft)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked, onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedTrackColor = p.pine, checkedThumbColor = p.onPine,
                uncheckedTrackColor = p.surface, uncheckedBorderColor = p.line, uncheckedThumbColor = p.line,
            ),
        )
    }
}
