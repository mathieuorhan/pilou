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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import kotlin.math.roundToInt

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
    var hour by rememberSaveable { mutableStateOf(initial.hour) }
    var minute by rememberSaveable { mutableStateOf(initial.minute) }
    var freq by rememberSaveable { mutableStateOf(initial.frequency) }
    var dow by rememberSaveable { mutableStateOf(initial.dayOfWeek) }
    var dom by rememberSaveable { mutableStateOf(initial.dayOfMonth) }
    var delay by rememberSaveable { mutableStateOf(initial.delayMinutes) }
    var name by rememberSaveable { mutableStateOf(initial.contactName) }
    var phone by rememberSaveable { mutableStateOf(initial.phone) }
    var phoneError by rememberSaveable { mutableStateOf<String?>(null) }
    var showTime by remember { mutableStateOf(false) }

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
            phoneError = "Ajoute un numéro valide pour que l'alerte puisse partir."
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
                    Icon(painterResource(R.drawable.ic_back), contentDescription = "Retour", tint = p.pine)
                }
                Spacer(Modifier.width(4.dp))
            }
            Text(if (firstRun) "Bienvenue !" else "Réglages", style = MaterialTheme.typography.titleLarge, color = p.pine)
        }
        if (firstRun) {
            Text(
                "Trois réglages et c'est parti. Je te poserai la question à l'heure choisie, sans bruit.",
                style = MaterialTheme.typography.bodyLarge, color = p.pineSoft,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        // When
        Section("Quand ?")
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(p.surface)
                .border(1.dp, p.line, RoundedCornerShape(28.dp))
                .clickable(role = Role.Button) { showTime = true }
                .padding(vertical = 18.dp)
                .semantics { contentDescription = "Heure du rappel : ${Wording.time(hour, minute)}. Toucher pour changer." },
            contentAlignment = Alignment.Center,
        ) {
            Text("%02d:%02d".format(hour, minute), style = MaterialTheme.typography.displayMedium, color = p.pine)
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Frequency.DAILY to "Chaque jour", Frequency.WEEKLY to "Chaque semaine", Frequency.MONTHLY to "Chaque mois")
                .forEach { (f, label) -> Toggle(label, freq == f, Modifier.weight(1f)) { freq = f } }
        }
        if (freq == Frequency.WEEKLY) {
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("L", "M", "M", "J", "V", "S", "D").forEachIndexed { i, l ->
                    DayChip(l, Wording.dayName(i + 1), dow == i + 1) { dow = i + 1 }
                }
            }
        }
        if (freq == Frequency.MONTHLY) {
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Le", style = MaterialTheme.typography.bodyLarge, color = p.pine)
                Spacer(Modifier.width(10.dp))
                RoundStep("−", "Jour précédent") { dom = if (dom <= 1) 31 else dom - 1 }
                Text(
                    "$dom", style = MaterialTheme.typography.titleLarge, color = p.pine,
                    textAlign = TextAlign.Center, modifier = Modifier.width(48.dp),
                )
                RoundStep("+", "Jour suivant") { dom = if (dom >= 31) 1 else dom + 1 }
                Spacer(Modifier.width(10.dp))
                Text("du mois", style = MaterialTheme.typography.bodyLarge, color = p.pine)
            }
            if (dom > 28) {
                Text(
                    "Les mois plus courts, ce sera le dernier jour.",
                    style = MaterialTheme.typography.bodyMedium, color = p.pineSoft, modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        // Delay
        Section("Si j'oublie ?")
        Text(
            "Un SMS part au bout de ${Wording.delay(delay)} sans réponse.",
            style = MaterialTheme.typography.bodyLarge, color = p.pine,
        )
        Slider(
            value = delay.toFloat(),
            onValueChange = { delay = ((it / 15f).roundToInt() * 15).coerceIn(15, maxDelay) },
            valueRange = 15f..maxDelay.toFloat(),
            steps = (maxDelay - 15) / 15 - 1,
            colors = SliderDefaults.colors(
                thumbColor = p.pine, activeTrackColor = p.pink, inactiveTrackColor = p.line,
                activeTickColor = androidx.compose.ui.graphics.Color.Transparent,
                inactiveTickColor = androidx.compose.ui.graphics.Color.Transparent,
            ),
        )
        Row(Modifier.fillMaxWidth()) {
            Text("15 min", style = MaterialTheme.typography.labelMedium, color = p.pineSoft, modifier = Modifier.weight(1f))
            Text(Wording.delay(maxDelay), style = MaterialTheme.typography.labelMedium, color = p.pineSoft)
        }

        // Who
        Section("Qui prévenir ?")
        val fieldColors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = p.pine, unfocusedBorderColor = p.line,
            focusedContainerColor = p.surface, unfocusedContainerColor = p.surface,
            focusedLabelColor = p.pine, cursorColor = p.pine,
        )
        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text("Prénom (facultatif)") },
            singleLine = true, shape = RoundedCornerShape(18.dp), colors = fieldColors,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = phone, onValueChange = { phone = it; phoneError = null },
            label = { Text("Numéro de téléphone") },
            singleLine = true, shape = RoundedCornerShape(18.dp), colors = fieldColors,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            isError = phoneError != null,
            supportingText = phoneError?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = {
            pickContact.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
        }) {
            Icon(painterResource(R.drawable.ic_contact), contentDescription = null, tint = p.pine, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Choisir dans mes contacts", color = p.pine, style = MaterialTheme.typography.bodyLarge)
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { build()?.let(onSave) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = p.pine, contentColor = p.onPine),
        ) { Text(if (firstRun) "C'est parti" else "Enregistrer", style = MaterialTheme.typography.labelLarge) }

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = onTestReminder, modifier = Modifier.weight(1f), shape = CircleShape,
                border = BorderStroke(1.5.dp, p.line),
            ) { Text("Tester le rappel", color = p.pine, textAlign = TextAlign.Center) }
            OutlinedButton(
                onClick = {
                    val clean = Wording.cleanPhone(phone)
                    if (clean == null) phoneError = "Ajoute un numéro valide pour tester." else onTestSms(clean)
                },
                modifier = Modifier.weight(1f), shape = CircleShape, border = BorderStroke(1.5.dp, p.line),
            ) { Text("Tester le SMS", color = p.pine, textAlign = TextAlign.Center) }
        }
        Spacer(Modifier.height(32.dp))
    }

    if (showTime) {
        val state = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { showTime = false },
            containerColor = p.mist,
            confirmButton = {
                TextButton(onClick = { hour = state.hour; minute = state.minute; showTime = false }) {
                    Text("Valider", color = p.pine)
                }
            },
            dismissButton = { TextButton(onClick = { showTime = false }) { Text("Annuler", color = p.pineSoft) } },
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
