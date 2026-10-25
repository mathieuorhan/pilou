package fr.priseok.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import fr.priseok.app.core.Entry
import fr.priseok.app.core.Frequency
import fr.priseok.app.core.Home
import fr.priseok.app.core.Med
import fr.priseok.app.core.Settings
import fr.priseok.app.ui.Texts
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** The sentences of the home screen, in both languages, from the real string resources. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TextsTest {
    private val zone = ZoneId.of("Europe/Paris")
    private fun at(d: Int, h: Int, m: Int = 0) = ZonedDateTime.of(LocalDateTime.of(2026, 3, d, h, m), zone)
    private fun ms(z: ZonedDateTime) = z.toInstant().toEpochMilli()
    private val med = Med(id = 1L, activeSince = ms(at(1, 0)))
    private val s = Settings(listOf(med), delayMinutes = 120, contactName = "Camille", phone = "0639980000")
    private fun tx() = Texts(ApplicationProvider.getApplicationContext<Application>())
    private val missed = ms(at(10, 9)).let { c -> mapOf(c to Entry(c, smsAt = ms(at(10, 11)))) }

    @Test fun english() {
        val t = tx()
        val due = t.home(Home.state(s, emptyMap(), at(10, 9, 30)), s, at(10, 9, 30))
        assertEquals("It's time!", due.title)
        assertEquals("No answer and Camille gets a text at 11:00 AM.", due.hint)
        assertEquals("Camille got the alert by text at 11:00 AM.", t.home(Home.state(s, missed, at(10, 12)), s, at(10, 12)).line)
        assertEquals("Every day at 9:00 AM", t.header(s))
        assertEquals("Day 15 of every month at 9:00 AM", t.schedule(med.copy(frequency = Frequency.MONTHLY, dayOfMonth = 15)))
        assertEquals("Every day at 8:00 AM, 12:30 PM and 8:00 PM", t.schedule(med.copy(times = listOf(480, 750, 1200))))
        assertEquals("Every Mon, Wed and Fri at 9:00 AM", t.schedule(med.copy(frequency = Frequency.WEEKLY, daysOfWeek = setOf(5, 1, 3))))
        assertEquals("Every Wednesday at 9:00 AM", t.schedule(med.copy(frequency = Frequency.WEEKLY, daysOfWeek = setOf(3))))
        assertEquals("2 medications", t.header(s.copy(meds = listOf(med, med.copy(id = 2L)))))
        assertEquals("Medication", t.medName(med))
        assertEquals("No dose planned for now.", t.home(Home.state(s.copy(meds = listOf(med.copy(endDate = java.time.LocalDate.of(2026, 3, 9)))), emptyMap(), at(10, 12)), s, at(10, 12)).line)
        assertEquals("tomorrow at 9:00 AM", t.whenText(at(11, 9), at(10, 12)))
        assertEquals("3 h 30 min", t.delay(210))
    }

    @Test @Config(qualifiers = "fr")
    fun french() {
        val t = tx()
        val due = t.home(Home.state(s, emptyMap(), at(10, 9, 30)), s, at(10, 9, 30))
        assertEquals("C'est l'heure !", due.title)
        assertEquals("Sans réponse, Camille reçoit un SMS à 11h00.", due.hint)
        assertEquals("Camille a reçu l'alerte par SMS à 11h00.", t.home(Home.state(s, missed, at(10, 12)), s, at(10, 12)).line)
        assertEquals("Chaque jour à 9h00", t.header(s))
        assertEquals("Le 1er de chaque mois à 9h00", t.schedule(med.copy(frequency = Frequency.MONTHLY, dayOfMonth = 1)))
        assertEquals("Chaque jour à 8h00 et 20h00", t.schedule(med.copy(times = listOf(480, 1200))))
        assertEquals("Chaque lun, mer et ven à 9h00", t.schedule(med.copy(frequency = Frequency.WEEKLY, daysOfWeek = setOf(1, 3, 5))))
        assertEquals("Jusqu'au samedi 31 octobre", t.until(java.time.LocalDate.of(2026, 10, 31)))
        val fresh = s.copy(meds = listOf(med.copy(activeSince = ms(at(10, 10))))) // set up after today's reminder
        assertEquals("Prochain rappel demain à 9h00.", t.home(Home.state(fresh, emptyMap(), at(10, 16)), fresh, at(10, 16)).line)
    }

    @Test fun doseWithoutAlertSaysSo() {
        val quiet = s.copy(meds = listOf(med.copy(alert = false)))
        assertEquals("No alert for this one: tap when it's done.", tx().home(Home.state(quiet, emptyMap(), at(10, 9, 30)), quiet, at(10, 9, 30)).hint)
    }

    @Test fun missingNameFallsBack() {
        val t = tx()
        val h = t.home(Home.state(s.copy(contactName = ""), emptyMap(), at(10, 9, 30)), s.copy(contactName = ""), at(10, 9, 30))
        assertEquals("No answer and your contact gets a text at 11:00 AM.", h.hint)
    }
}
