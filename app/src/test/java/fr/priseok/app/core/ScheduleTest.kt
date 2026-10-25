package fr.priseok.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class ScheduleTest {
    private val paris = ZoneId.of("Europe/Paris")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) =
        ZonedDateTime.of(LocalDateTime.of(y, mo, d, h, mi), paris)
    private fun ms(z: ZonedDateTime) = z.toInstant().toEpochMilli()

    private val daily = Settings(hour = 9, minute = 0, delayMinutes = 120, phone = "0639980000",
        activeSince = ms(at(2026, 1, 1, 0)))

    // ---- next trigger ----

    @Test fun dailyNextIsLaterToday() =
        assertEquals(at(2026, 3, 10, 9), Schedule.after(daily, at(2026, 3, 10, 8)))

    @Test fun dailyNextIsTomorrowAfterTheTime() =
        assertEquals(at(2026, 3, 11, 9), Schedule.after(daily, at(2026, 3, 10, 9)))

    @Test fun weeklyLandsOnTheChosenDay() {
        val s = daily.copy(frequency = Frequency.WEEKLY, dayOfWeek = 5) // Friday
        assertEquals(at(2026, 3, 13, 9), Schedule.after(s, at(2026, 3, 10, 12))) // from Tuesday
    }

    @Test fun monthly31stFallsBackToLastDay() {
        val s = daily.copy(frequency = Frequency.MONTHLY, dayOfMonth = 31)
        assertEquals(at(2026, 2, 28, 9), Schedule.after(s, at(2026, 2, 1, 12)))
        assertEquals(at(2026, 4, 30, 9), Schedule.after(s, at(2026, 4, 1, 12)))
        assertEquals(at(2028, 2, 29, 9), Schedule.after(s, at(2028, 2, 1, 12))) // leap year
    }

    @Test fun keepsWallClockTimeAcrossDst() {
        // Clocks go forward on 2026-03-29 in Paris: still 9h00 local the next day.
        val t = Schedule.after(daily, at(2026, 3, 28, 10))
        assertEquals(9, t.hour)
        assertEquals(29, t.dayOfMonth)
    }

    @Test fun reminderInsideDstGapIsMovedNotSkipped() {
        val s = daily.copy(hour = 2, minute = 30) // 2h30 does not exist on 2026-03-29
        val t = Schedule.after(s, at(2026, 3, 29, 0))
        assertEquals(29, t.dayOfMonth)
        assertEquals(3, t.hour)
    }

    // ---- delay ----

    @Test fun dailyDelayStaysBelow24h() {
        assertEquals(24 * 60 - 15, Schedule.clampedDelay(daily.copy(delayMinutes = 24 * 60)))
        assertEquals(24 * 60, Schedule.clampedDelay(daily.copy(frequency = Frequency.WEEKLY, delayMinutes = 24 * 60)))
    }

    @Test fun deadlineIsRealDurationAcrossMidnight() {
        val s = daily.copy(hour = 22, delayMinutes = 180)
        assertEquals(at(2026, 3, 11, 1), Schedule.deadline(s, at(2026, 3, 10, 22)))
    }

    // ---- plan: which alarms to set ----

    @Test fun beforeReminderOnlyNextReminderIsSet() {
        val p = Schedule.plan(daily, at(2026, 3, 10, 8), emptyMap())
        assertEquals(ms(at(2026, 3, 10, 9)), p.nextReminder)
        assertNull(p.deadlineAt)
        assertNull(p.sendSmsNowFor)
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
        assertNull(p.sendSmsNowFor)
    }

    @Test fun phoneOffAtDeadlineSendsLateSms() {
        val p = Schedule.plan(daily, at(2026, 3, 10, 13), emptyMap())
        assertEquals(ms(at(2026, 3, 10, 9)), p.sendSmsNowFor)
    }

    @Test fun smsIsNeverSentTwice() {
        val c = ms(at(2026, 3, 10, 9))
        val p = Schedule.plan(daily, at(2026, 3, 10, 13), mapOf(c to Entry(c, smsAt = c + 1)))
        assertNull(p.sendSmsNowFor)
    }

    @Test fun veryOldMissIsNotReported() {
        val s = daily.copy(delayMinutes = 15)
        val p = Schedule.plan(s, at(2026, 3, 10, 23), emptyMap()) // deadline 9h15, now 23h
        assertNull(p.sendSmsNowFor)
    }

    @Test fun doseBeforeSetupIsIgnored() {
        // Set up at 10h for a 9h reminder: today's dose was never asked, so no SMS at 11h.
        val s = daily.copy(activeSince = ms(at(2026, 3, 10, 10)))
        val p = Schedule.plan(s, at(2026, 3, 10, 10, 1), emptyMap())
        assertNull(p.deadlineAt)
        assertNull(p.sendSmsNowFor)
    }

    // ---- which dose the button confirms ----

    @Test fun buttonTargetsUpcomingDoseWithinEarlyWindow() =
        assertEquals(at(2026, 3, 10, 9), Schedule.targetCycle(daily, at(2026, 3, 10, 7)))

    @Test fun buttonTargetsCurrentDoseAfterReminder() =
        assertEquals(at(2026, 3, 10, 9), Schedule.targetCycle(daily, at(2026, 3, 10, 15)))

    @Test fun nothingToConfirmRightAfterSetup() {
        val s = daily.copy(activeSince = ms(at(2026, 3, 10, 10)))
        assertNull(Schedule.targetCycle(s, at(2026, 3, 10, 12)))
    }

    @Test fun recentListsLastCyclesOldestFirst() {
        val r = Schedule.recent(daily, at(2026, 3, 10, 12), 7)
        assertEquals(7, r.size)
        assertEquals(at(2026, 3, 4, 9), r.first())
        assertEquals(at(2026, 3, 10, 9), r.last())
    }

    @Test fun statusOfACycle() {
        val c = ms(at(2026, 3, 10, 9))
        assertEquals(CycleStatus.PENDING, Schedule.status(daily, c, null, at(2026, 3, 10, 10)))
        assertEquals(CycleStatus.MISSED, Schedule.status(daily, c, null, at(2026, 3, 10, 12)))
        assertEquals(CycleStatus.TAKEN, Schedule.status(daily, c, Entry(c, takenAt = c), at(2026, 3, 10, 12)))
    }
}

class HomeTest {
    private val paris = ZoneId.of("Europe/Paris")
    private fun at(d: Int, h: Int, mi: Int = 0) = ZonedDateTime.of(LocalDateTime.of(2026, 3, d, h, mi), paris)
    private val s = Settings(hour = 9, delayMinutes = 120, contactName = "Camille", phone = "0639980000",
        activeSince = at(1, 0).toInstant().toEpochMilli())

    @Test fun due() {
        val h = Home.state(s, emptyMap(), at(10, 9, 30))
        assertEquals(Phase.DUE, h.phase)
        assertTrue(h.hint!!.contains("Camille reçoit un SMS à 11h00"))
    }

    @Test fun missedAfterSms() {
        val c = at(10, 9).toInstant().toEpochMilli()
        val h = Home.state(s, mapOf(c to Entry(c, smsAt = at(10, 11).toInstant().toEpochMilli())), at(10, 12))
        assertEquals(Phase.MISSED, h.phase)
        assertEquals("Camille a reçu l'alerte par SMS à 11h00.", h.line)
        assertEquals(c, h.confirmCycle)
    }

    @Test fun takenShowsNextReminder() {
        val c = at(10, 9).toInstant().toEpochMilli()
        val h = Home.state(s, mapOf(c to Entry(c, takenAt = at(10, 9, 12).toInstant().toEpochMilli())), at(10, 10))
        assertEquals(Phase.TAKEN, h.phase)
        assertEquals("Pris à 9h12.", h.line)
        assertEquals("Prochain rappel demain à 9h00.", h.hint)
    }

    @Test fun earlyConfirmationPointsToTheDayAfter() {
        val c = at(10, 9).toInstant().toEpochMilli()
        val h = Home.state(s, mapOf(c to Entry(c, takenAt = at(10, 7).toInstant().toEpochMilli())), at(10, 7, 5))
        assertEquals(Phase.TAKEN, h.phase)
        assertEquals("Prochain rappel demain à 9h00.", h.hint)
    }
}

class WordingTest {
    @Test fun phoneCleaning() {
        assertEquals("0639981234", Wording.cleanPhone("06 39 98 12 34"))
        assertEquals("+33639981234", Wording.cleanPhone("+33 6.39.98.12.34"))
        assertNull(Wording.cleanPhone("12"))
    }

    @Test fun delays() {
        assertEquals("45 min", Wording.delay(45))
        assertEquals("2 h", Wording.delay(120))
        assertEquals("3 h 30", Wording.delay(210))
    }
}
