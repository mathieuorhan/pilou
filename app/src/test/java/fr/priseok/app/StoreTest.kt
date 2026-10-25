package fr.priseok.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import fr.priseok.app.core.Contact
import fr.priseok.app.core.Entry
import fr.priseok.app.core.Frequency
import fr.priseok.app.core.Med
import fr.priseok.app.core.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StoreTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val store get() = Store(app)
    private val camille = Contact(1L, "Camille", "0639980000")

    @Before fun setUp() { store.prefs.edit().clear().commit() }

    @Test fun version1SettingsBecomeOneMedication() {
        // What version 1.3 stored: one weekly reminder on Wednesday at 20h30, text after 3 h 30.
        store.prefs.edit()
            .putInt("hour", 20).putInt("minute", 30).putString("frequency", "WEEKLY").putInt("dayOfWeek", 3)
            .putInt("dayOfMonth", 1).putInt("delayMinutes", 210).putString("contactName", "Camille")
            .putString("phone", "0639980000").putLong("activeSince", 1234L)
            .commit()
        val s = store.settings()
        assertEquals(
            listOf(Med(id = 1L, times = listOf(20 * 60 + 30), frequency = Frequency.WEEKLY, daysOfWeek = setOf(3),
                delayMinutes = 210, contactIds = setOf(1L), activeSince = 1234L)),
            s.meds,
        )
        assertEquals(listOf(camille), s.contacts)

        // Saving writes the new format and drops the old keys.
        store.saveSettings(s)
        assertFalse(store.prefs.contains("hour"))
        assertFalse(store.prefs.contains("phone"))
        assertEquals(s, store.settings())
    }

    @Test fun version2SettingsKeepTheirAlertSwitch() {
        // Version 2: medications with an alert switch, one global delay and contact.
        store.prefs.edit()
            .putString("meds", """[{"id":1,"n":"Kardegic","t":[480],"f":"DAILY","w":[1],"d":1,"a":true,"s":5},""" +
                """{"id":2,"n":"Vitamine D","t":[1200],"f":"DAILY","w":[1],"d":1,"a":false,"s":5}]""")
            .putInt("delayMinutes", 90).putString("contactName", "Camille").putString("phone", "0639980000")
            .putString("history", """[{"c":100,"t":110},{"c":200,"s":300,"f":true}]""")
            .commit()
        val s = store.settings()
        assertEquals(listOf(setOf(1L), emptySet<Long>()), s.meds.map { it.contactIds })
        assertEquals(listOf(90, 90), s.meds.map { it.delayMinutes })
        assertEquals(listOf(camille), s.contacts)
        val h = store.history()
        assertEquals(110L, h.getValue(100).takenAt)
        assertEquals(mapOf(1L to 300L), h.getValue(200).sms)
        assertEquals(setOf(1L), h.getValue(200).smsFailed)
    }

    @Test fun version2WithoutContactAlertsNobody() {
        store.prefs.edit().putString("meds", """[{"id":1,"n":"","t":[540],"f":"DAILY","w":[1],"d":1,"a":true,"s":5}]""").commit()
        val s = store.settings()
        assertEquals(emptyList<Contact>(), s.contacts)
        assertEquals(emptySet<Long>(), s.meds.single().contactIds)
    }

    @Test fun freshInstallHasNoMedication() = assertEquals(Settings(), store.settings())

    @Test fun settingsSurviveARoundTrip() {
        val meds = listOf(
            Med(1L, "Kardegic 75 mg", listOf(8 * 60, 20 * 60), contactIds = setOf(1L, 2L), activeSince = 5L),
            Med(2L, "Amoxicilline", listOf(12 * 60), Frequency.WEEKLY, setOf(1, 3, 5), delayMinutes = 45,
                endDate = LocalDate.of(2026, 10, 31), paused = true),
            Med(3L, "", listOf(9 * 60), Frequency.MONTHLY, dayOfMonth = 15),
        )
        val s = Settings(meds, listOf(camille, Contact(2L, "", "+33639980001")))
        store.saveSettings(s)
        assertEquals(s, store.settings())
        assertEquals(meds, Codec.meds(Codec.meds(meds)))
    }

    @Test fun historyKeepsEachMedicationAndText() {
        store.markMedTaken(100, 2L, 150)
        store.recordSms(100, 1L, 160)
        store.recordSmsFailed(100, 1L)
        assertEquals(Entry(100, medsTaken = mapOf(2L to 150L), sms = mapOf(1L to 160L), smsFailed = setOf(1L)), store.history()[100])
    }

    @Test fun undoPutsTheEntryBack() {
        store.saveUndo(100)
        store.markTaken(100, 150)
        store.undo(100)
        assertNull(store.history()[100])

        store.markMedTaken(200, 1L, 210)
        val before = store.history()[200]
        store.saveUndo(200)
        store.markTaken(200, 250)
        store.undo(200)
        assertEquals(before, store.history()[200])
    }
}
