package fr.priseok.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

private val paris = ZoneId.of("Europe/Paris")
private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) = ZonedDateTime.of(LocalDateTime.of(y, mo, d, h, mi), paris)
private fun ms(z: ZonedDateTime) = z.toInstant().toEpochMilli()
private val since = ms(at(2026, 1, 1, 0))

private val camille = Contact(1L, "Camille", "0639980000")
private val sara = Contact(2L, "Sara", "0639980001")

/** One medication at 9h00 every day, alerting Camille after 2 h, as in version 1. */
private fun med(id: Long = 1, vararg times: Int = intArrayOf(9 * 60), f: Med.() -> Med = { this }) =
    Med(id = id, times = times.toList(), contactIds = setOf(1L), activeSince = since).f()

private fun settings(vararg meds: Med, contacts: List<Contact> = listOf(camille)) = Settings(meds.toList(), contacts)

private fun dose(s: Settings, z: ZonedDateTime) = Schedule.doseAt(s, ms(z), paris)!!

class ScheduleTest {
    private val daily = settings(med())

    // ---- next trigger ----

    @Test fun dailyNextIsLaterToday() =
        assertEquals(at(2026, 3, 10, 9), Schedule.after(daily, at(2026, 3, 10, 8))!!.at)

    @Test fun dailyNextIsTomorrowAfterTheTime() =
        assertEquals(at(2026, 3, 11, 9), Schedule.after(daily, at(2026, 3, 10, 9))!!.at)

    @Test fun weeklyLandsOnTheChosenDays() {
        val s = settings(med { copy(frequency = Frequency.WEEKLY, daysOfWeek = setOf(1, 5)) }) // Monday, Friday
        assertEquals(at(2026, 3, 13, 9), Schedule.after(s, at(2026, 3, 10, 12))!!.at) // from Tuesday
        assertEquals(at(2026, 3, 16, 9), Schedule.after(s, at(2026, 3, 13, 12))!!.at) // from Friday afternoon
    }

    @Test fun monthly31stFallsBackToLastDay() {
        val s = settings(med { copy(frequency = Frequency.MONTHLY, dayOfMonth = 31) })
        assertEquals(at(2026, 2, 28, 9), Schedule.after(s, at(2026, 2, 1, 12))!!.at)
        assertEquals(at(2026, 4, 30, 9), Schedule.after(s, at(2026, 4, 1, 12))!!.at)
        assertEquals(at(2028, 2, 29, 9), Schedule.after(s, at(2028, 2, 1, 12))!!.at) // leap year
    }

    @Test fun keepsWallClockTimeAcrossDst() {
        // Clocks go forward on 2026-03-29 in Paris: still 9h00 local the next day.
        val t = Schedule.after(daily, at(2026, 3, 28, 10))!!.at
        assertEquals(9, t.hour)
        assertEquals(29, t.dayOfMonth)
    }

    @Test fun reminderInsideDstGapIsMovedNotSkipped() {
        val s = settings(med(1, 2 * 60 + 30)) // 2h30 does not exist on 2026-03-29
        val t = Schedule.after(s, at(2026, 3, 29, 0))!!.at
        assertEquals(29, t.dayOfMonth)
        assertEquals(3, t.hour)
    }

    // ---- several medications ----

    @Test fun medicationsAtTheSameTimeShareOneDose() {
        val s = settings(med(1, 8 * 60), med(2, 8 * 60, 20 * 60))
        val doses = Schedule.dosesOn(s, LocalDate.of(2026, 3, 10), paris)
        assertEquals(listOf(at(2026, 3, 10, 8), at(2026, 3, 10, 20)), doses.map { it.at })
        assertEquals(listOf(1L, 2L), doses[0].meds.map { it.id })
        assertEquals(listOf(2L), doses[1].meds.map { it.id })
    }

    @Test fun nextDoseIsTheEarliestOfAllMedications() {
        val s = settings(med(1, 20 * 60), med(2, 12 * 60 + 30))
        assertEquals(at(2026, 3, 10, 12, 30), Schedule.after(s, at(2026, 3, 10, 9))!!.at)
        assertEquals(at(2026, 3, 10, 20), Schedule.after(s, at(2026, 3, 10, 13))!!.at)
    }

    @Test fun courseEndsAfterItsLastDay() {
        val s = settings(med { copy(endDate = LocalDate.of(2026, 3, 12)) })
        assertEquals(at(2026, 3, 12, 9), Schedule.after(s, at(2026, 3, 11, 10))!!.at)
        assertNull(Schedule.after(s, at(2026, 3, 12, 10)))
        assertNull(Schedule.plan(s, at(2026, 3, 12, 12), emptyMap()).nextReminder)
    }

    @Test fun pausedMedicationHasNoDose() {
        val s = settings(med(1, 8 * 60) { copy(paused = true) }, med(2, 20 * 60))
        assertEquals(listOf(at(2026, 3, 10, 20)), Schedule.dosesOn(s, LocalDate.of(2026, 3, 10), paris).map { it.at })
        assertNull(Schedule.after(settings(med { copy(paused = true) }), at(2026, 3, 10, 8)))
    }

    @Test fun newMedicationDoesNotCountBeforeItWasAdded() {
        val added = med(2, 8 * 60) { copy(activeSince = ms(at(2026, 3, 10, 10))) }
        val s = settings(med(), added)
        assertEquals(listOf(1L), Schedule.dosesOn(s, LocalDate.of(2026, 3, 10), paris).flatMap { d -> d.meds.map { it.id } })
        assertEquals(2, Schedule.dosesOn(s, LocalDate.of(2026, 3, 11), paris).size)
    }

    @Test fun doseAtFindsItsMedications() {
        val s = settings(med(1, 8 * 60), med(2, 8 * 60))
        assertEquals(2, Schedule.doseAt(s, ms(at(2026, 3, 10, 8)), paris)!!.meds.size)
        assertNull(Schedule.doseAt(s, ms(at(2026, 3, 10, 9)), paris))
    }

    // ---- alert time ----

    @Test fun deadlineIsRealDurationAcrossMidnight() {
        val s = settings(med(1, 22 * 60) { copy(delayMinutes = 180) })
        assertEquals(at(2026, 3, 11, 1), Schedule.deadline(dose(s, at(2026, 3, 10, 22))))
    }

    @Test fun alertChosenAsTimeOfDay() {
        val m = med()
        assertEquals(120, Schedule.delayForAlertTime(m, 9 * 60, 11 * 60))
        assertEquals(180, Schedule.delayForAlertTime(med(1, 22 * 60), 22 * 60, 1 * 60)) // next day
        assertEquals(60 to true, Schedule.alertTime(22 * 60, 180))
        assertEquals(15, Schedule.delayForAlertTime(m, 9 * 60, 9 * 60 + 5)) // at least 15 min
    }

    @Test fun alertComesBeforeTheNextDoseOfTheMedication() {
        assertEquals(24 * 60 - 15, Schedule.maxDelayMinutes(med()))
        assertEquals(24 * 60, Schedule.maxDelayMinutes(med { copy(frequency = Frequency.WEEKLY) }))
        assertEquals(4 * 60 + 15, Schedule.maxDelayMinutes(med(1, 8 * 60, 12 * 60 + 30, 20 * 60)))
        assertEquals(4 * 60 + 15, Schedule.delayForAlertTime(med(1, 8 * 60, 12 * 60 + 30), 8 * 60, 14 * 60))
    }

    @Test fun eachMedicationHasItsOwnAlertTime() {
        val s = settings(med(1, 8 * 60) { copy(delayMinutes = 60) }, med(2, 8 * 60) { copy(delayMinutes = 180, contactIds = setOf(2L)) },
            contacts = listOf(camille, sara))
        val d = dose(s, at(2026, 3, 10, 8))
        assertEquals(at(2026, 3, 10, 9), Schedule.deadline(d)) // missed from the earliest one
        assertEquals(at(2026, 3, 10, 9), Schedule.alertTime(d, 1L, null))
        assertEquals(at(2026, 3, 10, 11), Schedule.alertTime(d, 2L, null))
    }

    // ---- plan: which alarms to set ----

    @Test fun beforeReminderOnlyNextReminderIsSet() {
        val p = Schedule.plan(daily, at(2026, 3, 10, 8), emptyMap())
        assertEquals(ms(at(2026, 3, 10, 9)), p.nextReminder)
        assertNull(p.nextAlertAt)
        assertTrue(p.sendNow.isEmpty())
    }

    @Test fun afterReminderAlertIsArmed() {
        val p = Schedule.plan(daily, at(2026, 3, 10, 9, 30), emptyMap())
        assertEquals(ms(at(2026, 3, 10, 11)), p.nextAlertAt)
        assertEquals(ms(at(2026, 3, 11, 9)), p.nextReminder)
    }

    @Test fun confirmedDoseCancelsAlert() {
        val c = ms(at(2026, 3, 10, 9))
        val p = Schedule.plan(daily, at(2026, 3, 10, 9, 30), mapOf(c to Entry(c, takenAt = c + 60_000)))
        assertNull(p.nextAlertAt)
        assertTrue(p.sendNow.isEmpty())
    }

    @Test fun phoneOffAtDeadlineSendsLateSms() {
        val p = Schedule.plan(daily, at(2026, 3, 10, 13), emptyMap())
        assertEquals(listOf(ms(at(2026, 3, 10, 9)) to 1L), p.sendNow.map { it.cycle to it.contactId })
    }

    @Test fun smsIsNeverSentTwice() {
        val c = ms(at(2026, 3, 10, 9))
        val p = Schedule.plan(daily, at(2026, 3, 10, 13), mapOf(c to Entry(c, sms = mapOf(1L to c + 1))))
        assertTrue(p.sendNow.isEmpty())
    }

    @Test fun veryOldMissIsNotReported() {
        val s = settings(med { copy(delayMinutes = 15) })
        assertTrue(Schedule.plan(s, at(2026, 3, 10, 23), emptyMap()).sendNow.isEmpty()) // deadline 9h15, now 23h
    }

    @Test fun doseBeforeSetupIsIgnored() {
        // Set up at 10h for a 9h reminder: today's dose was never asked, so no SMS at 11h.
        val s = settings(med { copy(activeSince = ms(at(2026, 3, 10, 10))) })
        val p = Schedule.plan(s, at(2026, 3, 10, 10, 1), emptyMap())
        assertNull(p.nextAlertAt)
        assertTrue(p.sendNow.isEmpty())
    }

    @Test fun earliestOfSeveralOpenAlertsIsArmed() {
        val s = settings(med(1, 8 * 60), med(2, 9 * 60))
        assertEquals(ms(at(2026, 3, 10, 10)), Schedule.plan(s, at(2026, 3, 10, 9, 30), emptyMap()).nextAlertAt)
    }

    @Test fun nextAlertIsArmedOnceTheFirstTextIsSent() {
        val s = settings(med(1, 8 * 60), med(2, 9 * 60))
        val c = ms(at(2026, 3, 10, 8))
        val p = Schedule.plan(s, at(2026, 3, 10, 10), mapOf(c to Entry(c, sms = mapOf(1L to ms(at(2026, 3, 10, 10))))))
        assertEquals(ms(at(2026, 3, 10, 11)), p.nextAlertAt)
        assertTrue(p.sendNow.isEmpty())
    }

    @Test fun severalMissesWhilePhoneWasOffAreAllReported() {
        val s = settings(med(1, 8 * 60), med(2, 9 * 60))
        assertEquals(listOf(ms(at(2026, 3, 10, 8)), ms(at(2026, 3, 10, 9))), Schedule.plan(s, at(2026, 3, 10, 12), emptyMap()).sendNow.map { it.cycle })
    }

    @Test fun medicationWithoutContactNeverTexts() {
        val s = settings(med { copy(contactIds = emptySet()) })
        assertNull(Schedule.plan(s, at(2026, 3, 10, 9, 30), emptyMap()).nextAlertAt)
        assertTrue(Schedule.plan(s, at(2026, 3, 10, 13), emptyMap()).sendNow.isEmpty())
    }

    @Test fun eachContactIsTextedForTheirOwnMedications() {
        val s = settings(med(1, 8 * 60), med(2, 8 * 60) { copy(contactIds = setOf(2L)) }, contacts = listOf(camille, sara))
        val c = ms(at(2026, 3, 10, 8))
        assertEquals(listOf(1L, 2L), Schedule.plan(s, at(2026, 3, 10, 11), emptyMap()).sendNow.map { it.contactId })
        // Sara's medication was confirmed on its own: only Camille is texted.
        val p = Schedule.plan(s, at(2026, 3, 10, 11), mapOf(c to Entry(c, medsTaken = mapOf(2L to c))))
        assertEquals(listOf(1L), p.sendNow.map { it.contactId })
    }

    @Test fun removedContactIsNeverTexted() {
        val s = settings(med { copy(contactIds = setOf(2L)) })
        assertTrue(Schedule.plan(s, at(2026, 3, 10, 13), emptyMap()).sendNow.isEmpty())
    }

    // ---- which dose the button confirms ----

    @Test fun buttonTargetsUpcomingDoseWithinEarlyWindow() =
        assertEquals(at(2026, 3, 10, 9), Schedule.target(daily, emptyMap(), at(2026, 3, 10, 7))!!.at)

    @Test fun buttonTargetsCurrentDoseAfterReminder() =
        assertEquals(at(2026, 3, 10, 9), Schedule.target(daily, emptyMap(), at(2026, 3, 10, 15))!!.at)

    @Test fun nothingToConfirmRightAfterSetup() {
        val s = settings(med { copy(activeSince = ms(at(2026, 3, 10, 10))) })
        assertNull(Schedule.target(s, emptyMap(), at(2026, 3, 10, 12)))
    }

    @Test fun buttonKeepsAnOpenDoseBeforeTheNextOne() {
        // 8h not confirmed yet at 8h50: the button is for 8h, not for the 9h dose coming up.
        val s = settings(med(1, 8 * 60), med(2, 9 * 60))
        assertEquals(at(2026, 3, 10, 8), Schedule.target(s, emptyMap(), at(2026, 3, 10, 8, 50))!!.at)
        val c = ms(at(2026, 3, 10, 8))
        assertEquals(at(2026, 3, 10, 9), Schedule.target(s, mapOf(c to Entry(c, takenAt = c)), at(2026, 3, 10, 8, 50))!!.at)
    }

    @Test fun recentDaysListsLastDaysOldestFirst() {
        val r = Schedule.recentDays(daily, at(2026, 3, 10, 12), 7)
        assertEquals(7, r.size)
        assertEquals(LocalDate.of(2026, 3, 4), r.first().first)
        assertEquals(LocalDate.of(2026, 3, 10), r.last().first)
    }

    @Test fun recentDaysSkipsDaysWithoutDoses() {
        val s = settings(med { copy(frequency = Frequency.WEEKLY, daysOfWeek = setOf(2)) }) // Tuesdays
        val r = Schedule.recentDays(s, at(2026, 3, 10, 12), 3)
        assertEquals(listOf(LocalDate.of(2026, 2, 24), LocalDate.of(2026, 3, 3), LocalDate.of(2026, 3, 10)), r.map { it.first })
    }

    @Test fun statusOfADose() {
        val d = dose(daily, at(2026, 3, 10, 9))
        val c = d.cycle
        assertEquals(CycleStatus.PENDING, Schedule.status(d, null, at(2026, 3, 10, 10)))
        assertEquals(CycleStatus.MISSED, Schedule.status(d, null, at(2026, 3, 10, 12)))
        assertEquals(CycleStatus.TAKEN, Schedule.status(d, Entry(c, takenAt = c), at(2026, 3, 10, 12)))
    }

    @Test fun doseIsTakenOnceEveryMedicationIs() {
        val s = settings(med(1, 8 * 60), med(2, 8 * 60))
        val d = dose(s, at(2026, 3, 10, 8))
        val c = d.cycle
        assertEquals(false, Entry(c, medsTaken = mapOf(1L to c)).isTaken(d))
        assertEquals(true, Entry(c, medsTaken = mapOf(1L to c, 2L to c + 5)).isTaken(d))
        assertEquals(c + 5, Entry(c, medsTaken = mapOf(1L to c, 2L to c + 5)).takenTime(d))
    }
}

class HomeTest {
    private fun at(d: Int, h: Int, mi: Int = 0) = at(2026, 3, d, h, mi)
    private val s = settings(med())

    @Test fun due() {
        val h = Home.state(s, emptyMap(), at(10, 9, 30))
        assertEquals(Phase.DUE, h.phase)
        assertEquals(at(10, 11), h.alertAt)
        assertEquals(listOf(camille), h.alertContacts)
        assertEquals(ms(at(10, 9)), h.confirmCycle)
    }

    @Test fun missedAfterSms() {
        val c = ms(at(10, 9))
        val h = Home.state(s, mapOf(c to Entry(c, sms = mapOf(1L to ms(at(10, 11))))), at(10, 12))
        assertEquals(Phase.MISSED, h.phase)
        assertEquals(at(10, 11), h.smsAt)
        assertEquals(listOf(camille), h.smsContacts)
        assertEquals(c, h.confirmCycle)
    }

    @Test fun takenShowsNextReminder() {
        val c = ms(at(10, 9))
        val h = Home.state(s, mapOf(c to Entry(c, takenAt = ms(at(10, 9, 12)))), at(10, 10))
        assertEquals(Phase.TAKEN, h.phase)
        assertEquals(at(10, 9, 12), h.takenAt)
        assertEquals(at(11, 9), h.next!!.at)
        assertNull(h.confirmCycle)
    }

    @Test fun earlyConfirmationPointsToTheDayAfter() {
        val c = ms(at(10, 9))
        val h = Home.state(s, mapOf(c to Entry(c, takenAt = ms(at(10, 7)))), at(10, 7, 5))
        assertEquals(Phase.TAKEN, h.phase)
        assertEquals(at(11, 9), h.next!!.at)
    }

    @Test fun nothingLeftAfterTheLastDayOfACourse() {
        val course = settings(med { copy(endDate = LocalDate.of(2026, 3, 9)) })
        assertNull(Home.state(course, emptyMap(), at(10, 16)).next)
    }

    @Test fun todayListsEveryDoseWithItsOwnButton() {
        val two = settings(med(1, 8 * 60), med(2, 12 * 60, 20 * 60))
        val c8 = ms(at(10, 8))
        val now = at(10, 12, 30)
        val rows = Home.today(two, emptyMap(), now, bigButtonCycle = ms(at(10, 12)))
        assertEquals(3, rows.size)
        assertEquals(CycleStatus.MISSED, rows[0].status)
        assertTrue(rows[0].confirmable)          // the forgotten 8h dose can still be confirmed here
        assertEquals(false, rows[1].confirmable) // 12h is the big button's
        assertNull(rows[2].status)               // 20h is still to come
        val taken = Home.today(two, mapOf(c8 to Entry(c8, takenAt = c8)), now, ms(at(10, 12)))
        assertEquals(CycleStatus.TAKEN, taken[0].status)
        assertEquals(false, taken[0].confirmable)
    }

    @Test fun oneMedicationOfADoseCanBeConfirmedAlone() {
        val two = settings(med(1, 8 * 60), med(2, 8 * 60))
        val c = ms(at(10, 8))
        val rows = Home.today(two, mapOf(c to Entry(c, medsTaken = mapOf(2L to c))), at(10, 8, 30), null)
        assertEquals(setOf(2L), rows[0].medsTaken)
        assertEquals(CycleStatus.PENDING, rows[0].status)
    }

    @Test fun dayIsMissedIfOneDoseWas() {
        val two = settings(med(1, 8 * 60), med(2, 20 * 60))
        val c8 = ms(at(9, 8))
        val doses = Schedule.dosesOn(two, LocalDate.of(2026, 3, 9), paris)
        assertEquals(CycleStatus.MISSED, Home.dayStatus(doses, mapOf(c8 to Entry(c8, takenAt = c8)), at(10, 12)))
    }
}

class WordingTest {
    private val fr = Locale.FRENCH
    private val en = Locale.ENGLISH
    private val t = ZonedDateTime.of(LocalDateTime.of(2026, 11, 2, 21, 5), paris)
    private val d = t.toLocalDate()

    @Test fun timeFollowsLanguageAndClock() {
        assertEquals("21h05", Wording.time(t, fr, is24h = true))
        assertEquals("21:05", Wording.time(t, en, is24h = true))
        assertEquals("9:05 PM", Wording.time(t, en, is24h = false))
        assertEquals("12:00 AM", Wording.time(0, 0, en, is24h = false))
    }

    @Test fun dates() {
        assertEquals("lundi 2 novembre", Wording.date(t, fr))
        assertEquals("Monday, November 2", Wording.date(d, en))
    }

    @Test fun dayLetters() {
        assertEquals("L", Wording.dayLetter(1, fr))
        assertEquals("M", Wording.dayLetter(1, en))
        assertEquals("lun", Wording.dayShort(1, fr))
    }

    @Test fun historyLabels() {
        assertEquals("2/11", Wording.historyLabel(Wording.Scale.WEEKS, d, fr))
        assertEquals("11/2", Wording.historyLabel(Wording.Scale.WEEKS, d, en))
        assertEquals("lun", Wording.historyLabel(Wording.Scale.DAYS, d, fr))
        assertEquals("Mon", Wording.historyLabel(Wording.Scale.DAYS, d, en))
        assertEquals(Wording.Scale.DAYS, Wording.scale(d.minusDays(6), d))
        assertEquals(Wording.Scale.WEEKS, Wording.scale(d.minusDays(42), d))
        assertEquals(Wording.Scale.MONTHS, Wording.scale(d.minusDays(180), d))
    }

    @Test fun phoneCleaning() {
        assertEquals("0639981234", Wording.cleanPhone("06 39 98 12 34"))
        assertEquals("+33639981234", Wording.cleanPhone("+33 6.39.98.12.34"))
        assertNull(Wording.cleanPhone("12"))
    }
}
