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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fr.priseok.app.Codec
import fr.priseok.app.R
import fr.priseok.app.core.Contact
import fr.priseok.app.core.Med
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
    val tx = rememberTexts()
    // Drafts are kept as JSON so they survive rotation. The first run starts with one medication.
    val startMeds = remember(initial) { initial.meds.ifEmpty { listOf(Med(id = 1L)) } }
    var medsJson by rememberSaveable { mutableStateOf(Codec.meds(startMeds)) }
    var contactsJson by rememberSaveable { mutableStateOf(Codec.contacts(initial.contacts)) }
    val meds = remember(medsJson) { Codec.meds(medsJson) }
    val contacts = remember(contactsJson) { Codec.contacts(contactsJson) }
    // The medication or contact being edited, by id. A new one gets an id no other has.
    var editingMed by rememberSaveable { mutableStateOf(openMed) }
    var editingContact by rememberSaveable { mutableStateOf<Long?>(null) }
    var askSave by rememberSaveable { mutableStateOf(false) }

    val dirty = meds != startMeds || contacts != initial.contacts

    fun build(): Settings {
        val now = System.currentTimeMillis()
        val ids = contacts.map { it.id }.toSet()
        // A medication whose times, days or pause changed starts afresh: its earlier doses no longer count.
        val withStart = meds.map { m ->
            val before = initial.meds.find { it.id == m.id }
            m.copy(
                contactIds = m.contactIds intersect ids,
                activeSince = if (before == null || !m.sameScheduleAs(before)) now else before.activeSince,
            )
        }
        return Settings(withStart, contacts)
    }

    editingMed?.let { id ->
        val existing = meds.find { it.id == id }
        val close = { editingMed = null }
        MedEditor(
            med = existing ?: Med(
                id = id,
                times = listOf(meds.lastOrNull()?.times?.lastOrNull() ?: (9 * 60)),
                contactIds = contacts.map { it.id }.toSet(), // a new medication alerts everyone by default
            ),
            contacts = contacts,
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

    val back: (() -> Unit)? = onBack?.let { b -> { if (dirty) askSave = true else b() } }
    BackHandler(enabled = back != null) { back?.invoke() }

    Column(
        modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (back != null) {
                IconButton(onClick = back) {
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
            Card(
                title = tx.medName(m), lines = listOf(tx.schedule(m), tx.medDetails(m, Settings(meds, contacts))),
                clickLabel = stringResource(R.string.med_edit_cd, tx.medName(m)),
            ) { editingMed = m.id }
            Spacer(Modifier.height(10.dp))
        }
        TextButton(onClick = { editingMed = (meds.maxOfOrNull { it.id } ?: 0L) + 1 }) {
            Text("+  " + stringResource(R.string.add_med), color = p.pine, style = MaterialTheme.typography.bodyLarge)
        }

        // Who
        Section(stringResource(R.string.section_who))
        if (contacts.isEmpty()) {
            Text(stringResource(R.string.contacts_none), style = MaterialTheme.typography.bodyMedium, color = p.pineSoft)
            Spacer(Modifier.height(6.dp))
        }
        contacts.forEach { c ->
            Card(
                title = tx.contactName(c), lines = listOfNotNull(c.phone.takeIf { c.name.isNotBlank() }),
                clickLabel = stringResource(R.string.contact_edit_cd, tx.contactName(c)),
            ) { editingContact = c.id }
            Spacer(Modifier.height(10.dp))
        }
        TextButton(onClick = { editingContact = (contacts.maxOfOrNull { it.id } ?: 0L) + 1 }) {
            Text("+  " + stringResource(R.string.add_contact), color = p.pine, style = MaterialTheme.typography.bodyLarge)
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { onSave(build()) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = p.pine, contentColor = p.onPine),
        ) { Text(stringResource(if (firstRun) R.string.start else R.string.save), style = MaterialTheme.typography.labelLarge) }

        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onTestReminder, modifier = Modifier.fillMaxWidth(), shape = CircleShape,
            border = BorderStroke(1.5.dp, p.line),
        ) { Text(stringResource(R.string.test_reminder), color = p.pine, textAlign = TextAlign.Center) }
        Spacer(Modifier.height(32.dp))
    }

    editingContact?.let { id ->
        val existing = contacts.find { it.id == id }
        ContactDialog(
            contact = existing ?: Contact(id),
            isNew = existing == null,
            onDone = { c ->
                if (existing == null) {
                    // The first contact alerts every medication: that is what most people set it up for.
                    if (contacts.isEmpty()) medsJson = Codec.meds(meds.map { it.copy(contactIds = it.contactIds + c.id) })
                    contactsJson = Codec.contacts(contacts + c)
                } else {
                    contactsJson = Codec.contacts(contacts.map { if (it.id == c.id) c else it })
                }
                editingContact = null
            },
            onRemove = if (existing == null) null else ({
                contactsJson = Codec.contacts(contacts.filter { it.id != id })
                medsJson = Codec.meds(meds.map { it.copy(contactIds = it.contactIds - id) })
                editingContact = null
            }),
            onTestSms = onTestSms,
            onDismiss = { editingContact = null },
        )
    }

    if (askSave) {
        UnsavedDialog(
            onSave = { askSave = false; onSave(build()) },
            onDiscard = { askSave = false; onBack?.invoke() },
            onKeep = { askSave = false },
        )
    }
}

/** A medication or a contact in a list: tap to edit. */
@Composable
private fun Card(title: String, lines: List<String>, clickLabel: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(p.surface)
            .border(1.dp, p.line, RoundedCornerShape(24.dp))
            .clickable(role = Role.Button, onClickLabel = clickLabel, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp)
            .semantics(mergeDescendants = true) {},
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = p.pine)
        lines.filter { it.isNotBlank() }.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = p.pineSoft) }
    }
}

/** Name and number of one contact, with a test text and removal. */
@Composable
private fun ContactDialog(
    contact: Contact,
    isNew: Boolean,
    onDone: (Contact) -> Unit,
    onRemove: (() -> Unit)?,
    onTestSms: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    var name by rememberSaveable(contact.id) { mutableStateOf(contact.name) }
    var phone by rememberSaveable(contact.id) { mutableStateOf(contact.phone) }
    var phoneError by rememberSaveable(contact.id) { mutableStateOf<Int?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
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
    val colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = p.pine, unfocusedBorderColor = p.line,
        focusedContainerColor = p.surface, unfocusedContainerColor = p.surface,
        focusedLabelColor = p.pine, cursorColor = p.pine,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = p.mist,
        title = { Text(stringResource(if (isNew) R.string.contact_new else R.string.contact_title), color = p.pine) },
        text = {
            Column {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text(stringResource(R.string.first_name)) },
                    singleLine = true, shape = RoundedCornerShape(18.dp), colors = colors,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = phone, onValueChange = { phone = it; phoneError = null },
                    label = { Text(stringResource(R.string.phone_number)) },
                    singleLine = true, shape = RoundedCornerShape(18.dp), colors = colors,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    isError = phoneError != null,
                    supportingText = phoneError?.let { id -> { Text(stringResource(id)) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = {
                    pick.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
                }) {
                    Icon(painterResource(R.drawable.ic_contact), contentDescription = null, tint = p.pine, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.pick_contact), color = p.pine)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = {
                        val clean = Wording.cleanPhone(phone)
                        if (clean == null) phoneError = R.string.phone_error_test else onTestSms(clean)
                    }) { Text(stringResource(R.string.test_sms), color = p.pine) }
                    if (onRemove != null) {
                        TextButton(onClick = onRemove) { Text(stringResource(R.string.remove), color = p.coral) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val clean = Wording.cleanPhone(phone)
                if (clean == null) phoneError = R.string.phone_error else onDone(Contact(contact.id, name.trim(), clean))
            }) { Text(stringResource(R.string.ok), color = p.pine) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = p.pineSoft) } },
    )
}

/** Leaving with changes: save them, drop them, or stay. */
@Composable
internal fun UnsavedDialog(onSave: () -> Unit, onDiscard: () -> Unit, onKeep: () -> Unit, saveLabel: Int = R.string.save) {
    val p = LocalPalette.current
    AlertDialog(
        onDismissRequest = onKeep,
        containerColor = p.mist,
        title = { Text(stringResource(R.string.unsaved_title), color = p.pine) },
        confirmButton = { TextButton(onClick = onSave) { Text(stringResource(saveLabel), color = p.pine) } },
        dismissButton = {
            Row {
                TextButton(onClick = onKeep) { Text(stringResource(R.string.keep_editing), color = p.pineSoft) }
                TextButton(onClick = onDiscard) { Text(stringResource(R.string.discard), color = p.coral) }
            }
        },
    )
}
