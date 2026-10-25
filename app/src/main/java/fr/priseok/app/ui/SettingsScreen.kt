package fr.priseok.app.ui

import android.app.Activity
import android.content.Intent
import android.provider.ContactsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fr.priseok.app.Codec
import fr.priseok.app.R
import fr.priseok.app.core.Med
import fr.priseok.app.core.Schedule
import fr.priseok.app.core.Settings
import fr.priseok.app.core.Wording
import java.time.LocalDate

@Composable
fun SettingsScreen(
    initial: Settings,
    firstRun: Boolean,
    onSave: (Settings) -> Unit,
    onBack: (() -> Unit)?,
    onTestReminder: () -> Unit,
    onTestSms: (String) -> Unit,
    modifier: Modifier = Modifier,
    today: LocalDate = LocalDate.now(),
    /** Opens the editor of this medication right away (screenshots only). */
    openMed: Long? = null,
) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val tx = rememberTexts()
    // The draft list is kept as JSON so it survives rotation; the first run starts with one medication.
    var medsJson by rememberSaveable { mutableStateOf(Codec.meds(initial.meds.ifEmpty { listOf(Med(id = 1L)) })) }
    val meds = remember(medsJson) { Codec.meds(medsJson) }
    var delay by rememberSaveable { mutableStateOf(initial.delayMinutes) }
    var name by rememberSaveable { mutableStateOf(initial.contactName) }
    var phone by rememberSaveable { mutableStateOf(initial.phone) }
    var phoneError by rememberSaveable { mutableStateOf<Int?>(null) }
    // The medication being edited, by id. A new one gets an id no other has.
    var editing by rememberSaveable { mutableStateOf(openMed) }

    editing?.let { id ->
        val existing = meds.find { it.id == id }
        val close = { editing = null }
        BackHandler(onBack = close)
        MedEditor(
            med = existing ?: Med(id = id, times = listOf(meds.lastOrNull()?.times?.lastOrNull() ?: (9 * 60))),
            isNew = existing == null,
            canRemove = existing != null && meds.size > 1,
            today = today,
            onDone = { m ->
                medsJson = Codec.meds(if (existing == null) meds + m else meds.map { if (it.id == m.id) m else it })
                close()
            },
            onRemove = { medsJson = Codec.meds(meds.filter { it.id != id }); close() },
            onBack = close,
            modifier = modifier,
        )
        return
    }

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
        val needsPhone = meds.any { it.alert }
        if (clean == null && (needsPhone || phone.isNotBlank())) {
            phoneError = R.string.phone_error
            return null
        }
        val now = System.currentTimeMillis()
        // A medication whose times or days changed starts afresh: its earlier doses no longer count.
        val withStart = meds.map { m ->
            val before = initial.meds.find { it.id == m.id }
            m.copy(activeSince = if (before == null || !m.sameScheduleAs(before)) now else before.activeSince)
        }
        return Settings(withStart, delay, name.trim(), clean ?: "")
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

        // What
        Section(stringResource(R.string.section_meds))
        meds.forEach { m ->
            MedCard(m, tx) { editing = m.id }
            Spacer(Modifier.height(10.dp))
        }
        TextButton(onClick = { editing = (meds.maxOfOrNull { it.id } ?: 0L) + 1 }) {
            Text("+  " + stringResource(R.string.add_med), color = p.pine, style = MaterialTheme.typography.bodyLarge)
        }

        // If forgotten
        Section(stringResource(R.string.section_forget))
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundStep("−", stringResource(R.string.delay_less)) { delay = Schedule.stepDelay(delay, up = false) }
            Text(
                tx.delay(delay), style = MaterialTheme.typography.headlineMedium, color = p.pine,
                textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
            )
            RoundStep("+", stringResource(R.string.delay_more)) { delay = Schedule.stepDelay(delay, up = true) }
        }
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.delay_line, tx.delay(delay)), style = MaterialTheme.typography.bodyLarge, color = p.pine)
        if (meds.any { !it.alert }) {
            Text(stringResource(R.string.delay_only_alert), style = MaterialTheme.typography.bodyMedium, color = p.pineSoft)
        }

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
}

/** One medication in the list: name, when, until when, and whether it alerts. Tap to edit. */
@Composable
private fun MedCard(m: Med, tx: Texts, onClick: () -> Unit) {
    val p = LocalPalette.current
    val cd = stringResource(R.string.med_edit_cd, tx.medName(m))
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(p.surface)
            .border(1.dp, p.line, RoundedCornerShape(24.dp))
            .clickable(role = Role.Button, onClickLabel = cd, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp)
            .semantics(mergeDescendants = true) {},
    ) {
        Text(tx.medName(m), style = MaterialTheme.typography.titleMedium, color = p.pine)
        Text(tx.schedule(m), style = MaterialTheme.typography.bodyMedium, color = p.pineSoft)
        val extra = listOfNotNull(
            m.endDate?.let { tx.until(it) },
            stringResource(R.string.med_no_alert).takeIf { !m.alert },
        )
        if (extra.isNotEmpty()) {
            Text(extra.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = p.pineSoft)
        }
    }
}
