package fr.priseok.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import fr.priseok.app.core.Frequency
import fr.priseok.app.core.Med
import fr.priseok.app.core.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Before fun setUp() { store.prefs.edit().clear().commit() }

    @Test fun version1SettingsBecomeOneMedication() {
        // What version 1.3 stored: one weekly reminder on Wednesday at 20h30.
        store.prefs.edit()
            .putInt("hour", 20).putInt("minute", 30).putString("frequency", "WEEKLY").putInt("dayOfWeek", 3)
            .putInt("dayOfMonth", 1).putInt("delayMinutes", 210).putString("contactName", "Camille")
            .putString("phone", "0639980000").putLong("activeSince", 1234L)
            .commit()
        val s = store.settings()
        assertEquals(
            listOf(Med(id = 1L, times = listOf(20 * 60 + 30), frequency = Frequency.WEEKLY, daysOfWeek = setOf(3), activeSince = 1234L)),
            s.meds,
        )
        assertEquals(210, s.delayMinutes)
        assertEquals("Camille", s.contactName)

        // Saving writes the new format and drops the old keys.
        store.saveSettings(s)
        assertFalse(store.prefs.contains("hour"))
        assertEquals(s, store.settings())
    }

    @Test fun freshInstallHasNoMedication() = assertEquals(emptyList<Med>(), store.settings().meds)

    @Test fun medicationsSurviveARoundTrip() {
        val meds = listOf(
            Med(1L, "Kardegic 75 mg", listOf(8 * 60, 20 * 60), activeSince = 5L),
            Med(2L, "Amoxicilline", listOf(12 * 60), Frequency.WEEKLY, setOf(1, 3, 5), alert = false, endDate = LocalDate.of(2026, 10, 31)),
            Med(3L, "", listOf(9 * 60), Frequency.MONTHLY, dayOfMonth = 15),
        )
        val s = Settings(meds, 90, "Sara", "+33639980000")
        store.saveSettings(s)
        assertEquals(s, store.settings())
        assertEquals(meds, Codec.meds(Codec.meds(meds)))
    }
}
