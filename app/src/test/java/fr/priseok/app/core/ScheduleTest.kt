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

/** One medication at 9h00 every day, as in version 1. */
private fun med(id: Long = 1, vararg times: Int = intArrayOf(9 * 60), f: Med.() -> Med = { this }) =
    Med(id = id, times = times.toList(), activeSince = since).f()

private fun settings(vararg meds: Med, delay: Int = 120) = Settings(meds.toList(), delay, "Camille", "0639980000")

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

    // ---- delay ----

    @Test fun deadlineIsRealDurationAcrossMidnight() {
        val s = settings(med(1, 22 * 60), delay = 180)
        assertEquals(at(2026, 3, 11, 1), Schedule.deadline(s, at(2026, 3, 10, 22)))
    }

    @Test fun delayStepsThroughTheOfferedValues() {
        assertEquals(150, Schedule.stepDelay(120, up = true))
        assertEquals(90, Schedule.stepDelay(120, up = false))
        assertEquals(240, Schedule.stepDelay(210, up = true)) // a version 1 value in between
        assertEquals(15, Schedule.stepDelay(15, up = false))
        assertEquals(24 * 60, Schedule.stepDelay(24 * 60, up = true))
    }

    // ---- plan: which alarms to set ----

    @Test fun beforeReminderOnlyNextReminderIsSet() {
        val p = Schedule.plan(daily, at(2026, 3, 10, 8), emptyMap())
        assertEquals(ms(at(2026, 3, 10, 9)), p.nextReminder)
        assertNull(p.deadlineAt)
        assertTrue(p.sendSmsNowFor.isEmpty())
    }

    @Test fun afterReminderDeadlineIsArmed() {
        val p = Schedule.plan(daily, at(2026, 3, 10, 9, 30), emptyMap())
        assertEquals(ms(at(2026, 3, 10, 9)), p.deadlineCycle)
        assertEquals(ms(at(2026, 3, 10, 11)), p.deadlineAt)
        assertEquals(ms(at(2026, 3, 11, 9)), p.nextReminder)
    }

    @Test fun confirmedDoseCancelsDeadline() {
        val c = ms(at(2026, 3, 10, 9))
        val p = Schedule.plan(daily, at(2026, 3, 10, 9, 30), mapOf(c to Entry(c, takenAt = c + 60_000)))
        assertNull(p.deadlineAt)
        assertTrue(p.sendSmsNowFor.isEmpty())
    }

    @Test fun phoneOffAtDeadlineSendsLateSms() {
        val p = Schedule.plan(daily, at(2026, 3, 10, 13), emptyMap())
        assertEquals(listOf(ms(at(2026, 3, 10, 9))), p.sendSmsNowFor)
    }

    @Test fun smsIsNeverSentTwice() {
        val c = ms(at(2026, 3, 10, 9))
        val p = Schedule.plan(daily, at(2026, 3, 10, 13), mapOf(c to Entry(c, smsAt = c + 1)))
        assertTrue(p.sendSmsNowFor.isEmpty())
    }

    @Test fun veryOldMissIsNotReported() {
        val s = settings(med(), delay = 15)
        val p = Schedule.plan(s, at(2026, 3, 10, 23), emptyMap()) // deadline 9h15, now 23h
        assertTrue(p.sendSmsNowFor.isEmpty())
    }

    @Test fun doseBeforeSetupIsIgnored() {
        // Set up at 10h for a 9h reminder: today's dose was never asked, so no SMS at 11h.
        val s = settings(med { copy(activeSince = ms(at(2026, 3, 10, 10))) })
        val p = Schedule.plan(s, at(2026, 3, 10, 10, 1), emptyMap())
        assertNull(p.deadlineAt)
        assertTrue(p.sendSmsNowFor.isEmpty())
    }

    @Test fun earliestOfSeveralOpenDeadlinesIsArmed() {
        val s = settings(med(1, 8 * 60), med(2, 9 * 60))
        val p = Schedule.plan(s, at(2026, 3, 10, 9, 30), emptyMap())
        assertEquals(ms(at(2026, 3, 10, 8)), p.deadlineCycle)
        assertEquals(ms(at(2026, 3, 10, 10)), p.deadlineAt)
    }

    @Test fun nextDeadlineIsArmedOnceTheFirstTextIsSent() {
        val s = settings(med(1, 8 * 60), med(2, 9 * 60))
        val c = ms(at(2026, 3, 10, 8))
        val p = Schedule.plan(s, at(2026, 3, 10, 10), mapOf(c to Entry(c, smsAt = ms(at(2026, 3, 10, 10)))))
        assertEquals(ms(at(2026, 3, 10, 9)), p.deadlineCycle)
    }

    @Test fun severalMissesWhilePhoneWasOffAreAllReported() {
        val s = settings(med(1, 8 * 60), med(2, 9 * 60))
        assertEquals(listOf(ms(at(2026, 3, 10, 8)), ms(at(2026, 3, 10, 9))), Schedule.plan(s, at(2026, 3, 10, 12), emptyMap()).sendSmsNowFor)
    }

    @Test fun medicationWithoutAlertNeverTexts() {
        val s = settings(med { copy(alert = false) })
        val p = Schedule.plan(s, at(2026, 3, 10, 9, 30), emptyMap())
        assertNull(p.deadlineAt)
        assertTrue(Schedule.plan(s, at(2026, 3, 10, 13), emptyMap()).sendSmsNowFor.isEmpty())
    }

    @Test fun doseAlertsIfOneOfItsMedicationsDoes() {
        val s = settings(med(1) { copy(alert = false) }, med(2))
        assertEquals(ms(at(2026, 3, 10, 9)), Schedule.plan(s, at(2026, 3, 10, 9, 30), emptyMap()).deadlineCycle)
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

    @Test fun statusOfACycle() {
        val c = ms(at(2026, 3, 10, 9))
        assertEquals(CycleStatus.PENDING, Schedule.status(daily, c, null, at(2026, 3, 10, 10)))
        assertEquals(CycleStatus.MISSED, Schedule.status(daily, c, null, at(2026, 3, 10, 12)))
        assertEquals(CycleStatus.TAKEN, Schedule.status(daily, c, Entry(c, takenAt = c), at(2026, 3, 10, 12)))
    }
}

class HomeTest {
    private fun at(d: Int, h: Int, mi: Int = 0) = at(2026, 3, d, h, mi)
    private val s = settings(med())

    @Test fun due() {
        val h = Home.state(s, emptyMap(), at(10, 9, 30))
        assertEquals(Phase.DUE, h.phase)
        assertEquals(at(10, 11), h.deadline)
        assertEquals(ms(at(10, 9)), h.confirmCycle)
    }

    @Test fun missedAfterSms() {
        val c = ms(at(10, 9))
        val h = Home.state(s, mapOf(c to Entry(c, smsAt = ms(at(10, 11)))), at(10, 12))
        assertEquals(Phase.MISSED, h.phase)
        assertEquals(at(10, 11), h.smsAt)
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
        val h = Home.state(course, emptyMap(), at(10, 16))
        assertNull(h.next)
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

    @Test fun dayIsMissedIfOneDoseWas() {
        val two = settings(med(1, 8 * 60), med(2, 20 * 60))
        val c8 = ms(at(9, 8))
        val doses = Schedule.dosesOn(two, LocalDate.of(2026, 3, 9), paris)
        assertEquals(CycleStatus.MISSED, Home.dayStatus(two, doses, mapOf(c8 to Entry(c8, takenAt = c8)), at(10, 12)))
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
