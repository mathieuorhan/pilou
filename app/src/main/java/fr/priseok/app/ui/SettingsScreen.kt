package fr.priseok.app.ui

import android.app.Activity
import android.content.Intent
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fr.priseok.app.R
import fr.priseok.app.core.Frequency
import fr.priseok.app.core.Schedule
import fr.priseok.app.core.Settings
import fr.priseok.app.core.Wording
import androidx.compose.ui.res.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    initial: Settings,
    firstRun: Boolean,
    onSave: (Settings) -> Unit,
    onBack: (() -> Unit)?,
    onTestReminder: () -> Unit,
    onTestSms: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val tx = rememberTexts()
    var hour by rememberSaveable { mutableStateOf(initial.hour) }
    var minute by rememberSaveable { mutableStateOf(initial.minute) }
    var freq by rememberSaveable { mutableStateOf(initial.frequency) }
    var dow by rememberSaveable { mutableStateOf(initial.dayOfWeek) }
    var dom by rememberSaveable { mutableStateOf(initial.dayOfMonth) }
    var delay by rememberSaveable { mutableStateOf(initial.delayMinutes) }
    var name by rememberSaveable { mutableStateOf(initial.contactName) }
    var phone by rememberSaveable { mutableStateOf(initial.phone) }
    var phoneError by rememberSaveable { mutableStateOf<Int?>(null) }
    // Which clock is being edited: the reminder or the SMS alert. Null when no dialog is open.
    var editing by remember { mutableStateOf<Clock?>(null) }

    val maxDelay = Schedule.maxDelayMinutes(freq)
    if (delay > maxDelay) delay = maxDelay

    val pickContact = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val uri = res.data?.data
        if (res.resultCode == Activity.RESULT_OK && uri != null) {
            ctx.contentResolver.query(
                uri,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
                null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    phone = c.getString(0) ?: phone
                    if (name.isBlank()) name = (c.getString(1) ?: "").substringBefore(' ')
                    phoneError = null
                }
            }
        }
    }

    fun build(): Settings? {
        val clean = Wording.cleanPhone(phone)
        if (clean == null) {
            phoneError = R.string.phone_error
            return null
        }
        val draft = Settings(hour, minute, freq, dow, dom, delay, name.trim(), clean, initial.activeSince)
        val since = if (!initial.isConfigured || !draft.sameScheduleAs(initial)) System.currentTimeMillis() else initial.activeSince
        return draft.copy(activeSince = since)
    }

    Column(
        modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.back), tint = p.pine)
                }
                Spacer(Modifier.width(4.dp))
            }
            Text(stringResource(if (firstRun) R.string.welcome_title else R.string.settings), style = MaterialTheme.typography.titleLarge, color = p.pine)
        }
        if (firstRun) {
            Text(
                stringResource(R.string.welcome_text),
                style = MaterialTheme.typography.bodyLarge, color = p.pineSoft,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        val timeCd = stringResource(R.string.time_cd, tx.time(hour, minute))
        // When
        Section(stringResource(R.string.section_when))
        ClockBox(tx.time(hour, minute), timeCd) { editing = Clock.REMINDER }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Frequency.DAILY to R.string.every_day, Frequency.WEEKLY to R.string.every_week, Frequency.MONTHLY to R.string.every_month)
                .forEach { (f, label) -> Toggle(stringResource(label), freq == f, Modifier.weight(1f)) { freq = f } }
        }
        if (freq == Frequency.WEEKLY) {
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                (1..7).forEach { d ->
                    DayChip(Wording.dayLetter(d, tx.locale), Wording.dayName(d, tx.locale), dow == d) { dow = d }
                }
            }
        }
        if (freq == Frequency.MONTHLY) {
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.monthly_on), style = MaterialTheme.typography.bodyLarge, color = p.pine)
                Spacer(Modifier.width(10.dp))
                RoundStep("−", stringResource(R.string.previous_day)) { dom = if (dom <= 1) 31 else dom - 1 }
                Text(
                    "$dom", style = MaterialTheme.typography.titleLarge, color = p.pine,
                    textAlign = TextAlign.Center, modifier = Modifier.width(48.dp),
                )
                RoundStep("+", stringResource(R.string.next_day)) { dom = if (dom >= 31) 1 else dom + 1 }
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

        // Delay
        Section(stringResource(R.string.section_forget))
        // The alert is chosen as a time of day; the app keeps it as a delay after the reminder,
        // so moving the reminder moves the alert with it.
        val (alertMin, nextDay) = Schedule.alertTime(hour, minute, delay)
        val alertText = tx.time(alertMin / 60, alertMin % 60)
        ClockBox(alertText, stringResource(R.string.alert_cd, alertText)) { editing = Clock.ALERT }
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.alert_explain), style = MaterialTheme.typography.bodyLarge, color = p.pine)
        Text(
            stringResource(if (nextDay) R.string.alert_gap_next_day else R.string.alert_gap, tx.delay(delay)),
            style = MaterialTheme.typography.bodyMedium, color = p.pineSoft,
        )

        // Who
        Section(stringResource(R.string.section_who))
        val fieldColors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = p.pine, unfocusedBorderColor = p.line,
            focusedContainerColor = p.surface, unfocusedContainerColor = p.surface,
            focusedLabelColor = p.pine, cursorColor = p.pine,
        )
        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text(stringResource(R.string.first_name)) },
            singleLine = true, shape = RoundedCornerShape(18.dp), colors = fieldColors,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = phone, onValueChange = { phone = it; phoneError = null },
            label = { Text(stringResource(R.string.phone_number)) },
            singleLine = true, shape = RoundedCornerShape(18.dp), colors = fieldColors,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            isError = phoneError != null,
            supportingText = phoneError?.let { id -> { Text(stringResource(id)) } },
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = {
            pickContact.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
        }) {
            Icon(painterResource(R.drawable.ic_contact), contentDescription = null, tint = p.pine, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.pick_contact), color = p.pine, style = MaterialTheme.typography.bodyLarge)
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { build()?.let(onSave) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = p.pine, contentColor = p.onPine),
        ) { Text(stringResource(if (firstRun) R.string.start else R.string.save), style = MaterialTheme.typography.labelLarge) }

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = onTestReminder, modifier = Modifier.weight(1f), shape = CircleShape,
                border = BorderStroke(1.5.dp, p.line),
            ) { Text(stringResource(R.string.test_reminder), color = p.pine, textAlign = TextAlign.Center) }
            OutlinedButton(
                onClick = {
                    val clean = Wording.cleanPhone(phone)
                    if (clean == null) phoneError = R.string.phone_error_test else onTestSms(clean)
                },
                modifier = Modifier.weight(1f), shape = CircleShape, border = BorderStroke(1.5.dp, p.line),
            ) { Text(stringResource(R.string.test_sms), color = p.pine, textAlign = TextAlign.Center) }
        }
        Spacer(Modifier.height(32.dp))
    }

    editing?.let { clock ->
        val (aMin, _) = Schedule.alertTime(hour, minute, delay)
        val (h0, m0) = if (clock == Clock.REMINDER) hour to minute else aMin / 60 to aMin % 60
        val state = rememberTimePickerState(initialHour = h0, initialMinute = m0, is24Hour = android.text.format.DateFormat.is24HourFormat(ctx))
        AlertDialog(
            onDismissRequest = { editing = null },
            containerColor = p.mist,
            confirmButton = {
                TextButton(onClick = {
                    if (clock == Clock.REMINDER) {
                        hour = state.hour; minute = state.minute
                    } else {
                        delay = Schedule.delayForAlertTime(hour, minute, state.hour, state.minute, freq)
                    }
                    editing = null
                }) {
                    Text(stringResource(R.string.ok), color = p.pine)
                }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.cancel), color = p.pineSoft) } },
            text = {
                TimePicker(
                    state,
                    colors = TimePickerDefaults.colors(
                        clockDialColor = p.surface, selectorColor = p.pink,
                        timeSelectorSelectedContainerColor = p.butter,
                        timeSelectorSelectedContentColor = androidx.compose.ui.graphics.Color(0xFF17332D),
                        timeSelectorUnselectedContainerColor = p.surface,
                        timeSelectorUnselectedContentColor = p.pine,
                        clockDialSelectedContentColor = androidx.compose.ui.graphics.Color(0xFF17332D),
                        clockDialUnselectedContentColor = p.pine,
                    ),
                )
            },
        )
    }
}

private enum class Clock { REMINDER, ALERT }

/** A big tappable time, used for the reminder and for the SMS alert. */
@Composable
private fun ClockBox(time: String, description: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(p.surface)
            .border(1.dp, p.line, RoundedCornerShape(28.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 18.dp)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(time, style = MaterialTheme.typography.displayMedium, color = p.pine)
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(30.dp))
    Text(title, style = MaterialTheme.typography.titleMedium, color = LocalPalette.current.pine)
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun Toggle(label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
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
private fun DayChip(letter: String, full: String, on: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(if (on) p.pink else p.surface)
            .border(1.dp, if (on) p.pink else p.line, CircleShape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { selected = on; contentDescription = full },
        contentAlignment = Alignment.Center,
    ) {
        Text(letter, style = MaterialTheme.typography.titleMedium, color = androidx.compose.ui.graphics.Color(0xFF17332D).takeIf { on } ?: p.pine)
    }
}

@Composable
private fun RoundStep(sign: String, desc: String, onClick: () -> Unit) {
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
