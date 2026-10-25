package fr.priseok.app.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Pure scheduling logic, no Android dependency, so it can be unit-tested on the JVM.
 *
 * Each medication has its own times and days. Medications due at the same moment are grouped
 * into one [Dose]: one reminder, one confirmation. A dose is identified by its "cycle",
 * the epoch millis of its trigger time, which is also the key of its history [Entry].
 */

/** How often a medication repeats. WEEKLY = on the chosen days of the week. */
enum class Frequency { DAILY, WEEKLY, MONTHLY }

data class Med(
    /** Stable identifier, kept across edits. */
    val id: Long,
    /** Free text, e.g. "Kardegic 75 mg". Empty = a generic name is shown. */
    val name: String = "",
    /** Minutes of the day, sorted, without duplicates. Never empty. */
    val times: List<Int> = listOf(9 * 60),
    val frequency: Frequency = Frequency.DAILY,
    /** ISO days of week, 1 = Monday ... 7 = Sunday. Used when frequency = WEEKLY. Never empty. */
    val daysOfWeek: Set<Int> = setOf(1),
    /** 1..31. Months shorter than this use their last day. Used when frequency = MONTHLY. */
    val dayOfMonth: Int = 1,
    /** Whether a missed dose of this medication texts the contact. */
    val alert: Boolean = true,
    /** Last day of a course of treatment, inclusive. Null = no end. */
    val endDate: LocalDate? = null,
    /** Epoch millis when this medication's schedule was last set. Earlier doses are ignored. */
    val activeSince: Long = 0L,
) {
    fun sameScheduleAs(o: Med): Boolean =
        times == o.times && frequency == o.frequency && daysOfWeek == o.daysOfWeek && dayOfMonth == o.dayOfMonth

    /** Whether the schedule has a dose on [date] (end date included, start ignored). */
    fun takenOn(date: LocalDate): Boolean {
        if (endDate != null && date.isAfter(endDate)) return false
        return when (frequency) {
            Frequency.DAILY -> true
            Frequency.WEEKLY -> date.dayOfWeek.value in daysOfWeek
            Frequency.MONTHLY -> date.dayOfMonth == minOf(dayOfMonth, date.lengthOfMonth())
        }
    }
}

data class Settings(
    val meds: List<Med> = emptyList(),
    /** Time after a dose before the text goes out. */
    val delayMinutes: Int = 120,
    val contactName: String = "",
    val phone: String = "",
) {
    val isConfigured: Boolean get() = meds.isNotEmpty()
    val needsPhone: Boolean get() = meds.any { it.alert }
}

/** One moment when one or more medications are due. */
data class Dose(val at: ZonedDateTime, val meds: List<Med>) {
    val cycle: Long get() = at.toInstant().toEpochMilli()
    val alerts: Boolean get() = meds.any { it.alert }
}

data class Entry(
    val cycle: Long,
    val takenAt: Long? = null,
    val smsAt: Long? = null,
    val smsFailed: Boolean = false,
)

enum class CycleStatus { TAKEN, PENDING, MISSED }

/** What the alarm layer must do now. All times are epoch millis. */
data class Plan(
    /** Null when no dose is left (every course has ended). */
    val nextReminder: Long?,
    val deadlineCycle: Long?,
    val deadlineAt: Long?,
    /** Deadlines that passed while the phone was off: send these texts right away. */
    val sendSmsNowFor: List<Long>,
)

object Schedule {
    const val MINUTES_PER_DAY = 24 * 60
    const val MIN_DELAY = 15
    const val MAX_DELAY = MINUTES_PER_DAY
    /** The delays offered in the settings, in minutes. */
    val DELAYS = listOf(15, 30, 45, 60, 90, 120, 150, 180, 240, 300, 360, 480, 600, 720, 960, 1200, 1440)
    /** Validating up to this long before the reminder counts for the upcoming dose. */
    const val EARLY_WINDOW_MINUTES = 180L
    /** A missed deadline older than this (phone off for long) no longer triggers a late text. */
    const val LATE_SMS_LIMIT_HOURS = 12L
    private const val SEARCH_DAYS = 400L

    fun clampedDelay(s: Settings): Int = s.delayMinutes.coerceIn(MIN_DELAY, MAX_DELAY)

    /** The next or previous offered delay, from any current value. */
    fun stepDelay(current: Int, up: Boolean): Int =
        if (up) DELAYS.firstOrNull { it > current } ?: DELAYS.last()
        else DELAYS.lastOrNull { it < current } ?: DELAYS.first()

    /** The doses on [date], in time order. */
    fun dosesOn(s: Settings, date: LocalDate, zone: ZoneId): List<Dose> {
        val byInstant = sortedMapOf<Long, MutableList<Med>>()
        val at = HashMap<Long, ZonedDateTime>()
        for (m in s.meds) {
            if (!m.takenOn(date)) continue
            for (t in m.times) {
                // ZonedDateTime.of moves a time that falls in a DST gap forward, and picks the
                // earlier offset in an overlap: the reminder fires once, at the expected wall-clock time.
                val z = ZonedDateTime.of(date, LocalTime.of(t / 60, t % 60), zone)
                val key = z.toInstant().toEpochMilli()
                if (key < m.activeSince) continue
                byInstant.getOrPut(key) { mutableListOf() }.add(m)
                at[key] = z
            }
        }
        return byInstant.map { (k, meds) -> Dose(at.getValue(k), meds) }
    }

    private fun firstDay(s: Settings, zone: ZoneId): LocalDate? =
        s.meds.minOfOrNull { it.activeSince }?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }

    private fun lastDay(s: Settings): LocalDate? =
        if (s.meds.any { it.endDate == null }) null else s.meds.mapNotNull { it.endDate }.maxOrNull()

    /** First dose strictly after [now], or null if none is left. */
    fun after(s: Settings, now: ZonedDateTime): Dose? {
        val end = lastDay(s)
        var d = now.toLocalDate()
        repeat(SEARCH_DAYS.toInt()) {
            if (end != null && d.isAfter(end)) return null
            dosesOn(s, d, now.zone).firstOrNull { it.at.isAfter(now) }?.let { return it }
            d = d.plusDays(1)
        }
        return null
    }

    /** Last dose at or before [now], or null if there is none since the setup. */
    fun atOrBefore(s: Settings, now: ZonedDateTime): Dose? {
        val start = firstDay(s, now.zone) ?: return null
        var d = now.toLocalDate()
        repeat(SEARCH_DAYS.toInt()) {
            if (d.isBefore(start)) return null
            dosesOn(s, d, now.zone).lastOrNull { !it.at.isAfter(now) }?.let { return it }
            d = d.minusDays(1)
        }
        return null
    }

    /** Doses with a trigger in [from, to], oldest first. */
    fun between(s: Settings, from: ZonedDateTime, to: ZonedDateTime): List<Dose> {
        val out = ArrayList<Dose>()
        var d = from.toLocalDate()
        while (!d.isAfter(to.toLocalDate())) {
            dosesOn(s, d, to.zone).filterTo(out) { !it.at.isBefore(from) && !it.at.isAfter(to) }
            d = d.plusDays(1)
        }
        return out
    }

    /** The dose at exactly [cycle], if the current schedule has one. */
    fun doseAt(s: Settings, cycle: Long, zone: ZoneId): Dose? {
        val t = Instant.ofEpochMilli(cycle).atZone(zone)
        return dosesOn(s, t.toLocalDate(), zone).firstOrNull { it.cycle == cycle }
    }

    /** The [n] most recent days with doses, up to [now], oldest first. Each day lists its past doses. */
    fun recentDays(s: Settings, now: ZonedDateTime, n: Int): List<Pair<LocalDate, List<Dose>>> {
        val start = firstDay(s, now.zone) ?: return emptyList()
        val out = ArrayList<Pair<LocalDate, List<Dose>>>()
        var d = now.toLocalDate()
        var guard = 0
        while (out.size < n && !d.isBefore(start) && guard++ < SEARCH_DAYS) {
            val past = dosesOn(s, d, now.zone).filter { !it.at.isAfter(now) }
            if (past.isNotEmpty()) out.add(d to past)
            d = d.minusDays(1)
        }
        return out.reversed()
    }

    fun deadline(s: Settings, trigger: ZonedDateTime): ZonedDateTime =
        trigger.plusMinutes(clampedDelay(s).toLong()) // instant-based: a real 2 h, even across DST

    fun deadline(s: Settings, cycle: Long, zone: ZoneId): ZonedDateTime =
        deadline(s, Instant.ofEpochMilli(cycle).atZone(zone))

    fun status(s: Settings, cycle: Long, entry: Entry?, now: ZonedDateTime): CycleStatus = when {
        entry?.takenAt != null -> CycleStatus.TAKEN
        now.isAfter(deadline(s, cycle, now.zone)) -> CycleStatus.MISSED
        else -> CycleStatus.PENDING
    }

    /**
     * The dose the "I took it" button applies to, in this order:
     * the oldest past dose still waiting (deadline not reached, not confirmed);
     * else the upcoming dose if it is less than [EARLY_WINDOW_MINUTES] away;
     * else the most recent dose. Null if there is none yet, or if no dose is left and the last was before today.
     */
    fun target(s: Settings, history: Map<Long, Entry>, now: ZonedDateTime): Dose? {
        if (!s.isConfigured) return null
        val window = now.minusMinutes(clampedDelay(s).toLong())
        between(s, window, now).firstOrNull { history[it.cycle]?.takenAt == null && deadline(s, it.at).isAfter(now) }
            ?.let { return it }
        val next = after(s, now)
        if (next != null && Duration.between(now, next.at).toMinutes() < EARLY_WINDOW_MINUTES) return next
        val last = atOrBefore(s, now)
        // Every course has ended: the last dose stays on screen for its day only.
        if (next == null && last != null && last.at.toLocalDate() != now.toLocalDate()) return null
        return last
    }

    fun plan(s: Settings, now: ZonedDateTime, history: Map<Long, Entry>): Plan {
        val next = after(s, now)?.cycle
        var deadlineCycle: Long? = null
        var deadlineAt: Long? = null
        val late = ArrayList<Long>()
        // Every dose whose deadline is still ahead, or passed less than LATE_SMS_LIMIT_HOURS ago.
        val from = now.minusMinutes(clampedDelay(s).toLong()).minusHours(LATE_SMS_LIMIT_HOURS)
        for (d in between(s, from, now)) {
            val e = history[d.cycle]
            if (!d.alerts || e?.takenAt != null || e?.smsAt != null) continue
            val dl = deadline(s, d.at)
            val dlMs = dl.toInstant().toEpochMilli()
            if (dl.isAfter(now)) {
                if (deadlineAt == null || dlMs < deadlineAt) { deadlineAt = dlMs; deadlineCycle = d.cycle }
            } else if (Duration.between(dl, now).toHours() < LATE_SMS_LIMIT_HOURS) {
                late.add(d.cycle)
            }
        }
        return Plan(next, deadlineCycle, deadlineAt, late)
    }
}
