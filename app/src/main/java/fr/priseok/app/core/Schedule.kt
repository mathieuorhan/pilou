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
 * Each medication has its own times, days, alert delay and contacts. Medications due at the
 * same moment are grouped into one [Dose]: one reminder, one big "taken" button (each medication
 * can also be confirmed on its own). A dose is identified by its "cycle", the epoch millis of its
 * trigger time, which is also the key of its history [Entry].
 */

/** How often a medication repeats. WEEKLY = on the chosen days of the week. */
enum class Frequency { DAILY, WEEKLY, MONTHLY }

/** Someone who gets a text when a dose is missed. */
data class Contact(val id: Long, val name: String = "", val phone: String = "")

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
    /** Time after each dose before the contacts get a text. Shown as a time of day in the editor. */
    val delayMinutes: Int = 120,
    /** Who gets a text if a dose is missed. Empty = no alert. */
    val contactIds: Set<Long> = emptySet(),
    /** Last day of a course of treatment, inclusive. Null = no end. */
    val endDate: LocalDate? = null,
    /** Reminders stopped for now, without deleting the medication. */
    val paused: Boolean = false,
    /** Epoch millis when this medication's schedule was last set. Earlier doses are ignored. */
    val activeSince: Long = 0L,
) {
    val alerts: Boolean get() = contactIds.isNotEmpty()

    /** Changes that make earlier doses no longer comparable: they restart the medication's history. */
    fun sameScheduleAs(o: Med): Boolean =
        times == o.times && frequency == o.frequency && daysOfWeek == o.daysOfWeek &&
            dayOfMonth == o.dayOfMonth && paused == o.paused

    /** Whether the schedule has a dose on [date] (end date included, start ignored). */
    fun takenOn(date: LocalDate): Boolean {
        if (paused) return false
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
    val contacts: List<Contact> = emptyList(),
) {
    val isConfigured: Boolean get() = meds.isNotEmpty()
    /** Whether any text may ever be sent, i.e. the SMS permission is worth asking for. */
    val sendsTexts: Boolean get() = meds.any { m -> m.contactIds.any { id -> contacts.any { it.id == id } } }
    fun contact(id: Long): Contact? = contacts.find { it.id == id }
}

/** One moment when one or more medications are due. */
data class Dose(val at: ZonedDateTime, val meds: List<Med>) {
    val cycle: Long get() = at.toInstant().toEpochMilli()
    val alerts: Boolean get() = meds.any { it.alerts }
}

/** What happened to one dose. Keyed by its cycle. */
data class Entry(
    val cycle: Long,
    /** The whole dose was confirmed at this time. */
    val takenAt: Long? = null,
    /** Medications confirmed one by one: med id -> time. */
    val medsTaken: Map<Long, Long> = emptyMap(),
    /** Texts sent: contact id -> time. */
    val sms: Map<Long, Long> = emptyMap(),
    /** Contacts whose text could not be sent. */
    val smsFailed: Set<Long> = emptySet(),
) {
    fun isTaken(medId: Long): Boolean = takenAt != null || medId in medsTaken

    /** Whether every medication of [d] is confirmed. */
    fun isTaken(d: Dose): Boolean = takenAt != null || d.meds.all { it.id in medsTaken }

    /** When the dose became fully confirmed, or null. */
    fun takenTime(d: Dose): Long? = takenAt ?: if (isTaken(d)) d.meds.maxOf { medsTaken.getValue(it.id) } else null
}

enum class CycleStatus { TAKEN, PENDING, MISSED }

/** One text to send: about the dose at [cycle], to [contactId]. */
data class Alert(val cycle: Long, val contactId: Long, val at: Long)

/** What the alarm layer must do now. All times are epoch millis. */
data class Plan(
    /** Null when no dose is left (every course has ended or is paused). */
    val nextReminder: Long?,
    /** The next time a text becomes due. Null if none is pending. */
    val nextAlertAt: Long?,
    /** Texts due now, including those that came due while the phone was off. */
    val sendNow: List<Alert>,
)

object Schedule {
    const val MINUTES_PER_DAY = 24 * 60
    const val MIN_DELAY = 15
    /** Validating up to this long before the reminder counts for the upcoming dose. */
    const val EARLY_WINDOW_MINUTES = 180L
    /** A missed deadline older than this (phone off for long) no longer triggers a late text. */
    const val LATE_SMS_LIMIT_HOURS = 12L
    private const val SEARCH_DAYS = 400L

    /** The alert must come before the medication's next dose of the day, at the latest. */
    fun maxDelayMinutes(m: Med): Int {
        if (m.times.size == 1 && m.frequency != Frequency.DAILY) return MINUTES_PER_DAY
        // Gaps between consecutive times, including from the last one to the first one the next day.
        val gaps = m.times.zipWithNext { a, b -> b - a } + (m.times.first() + MINUTES_PER_DAY - m.times.last())
        return (gaps.min() - MIN_DELAY).coerceAtLeast(MIN_DELAY)
    }

    fun clampedDelay(m: Med): Int = m.delayMinutes.coerceIn(MIN_DELAY, maxDelayMinutes(m))

    /**
     * The delay that makes the text go out at [alertMinute] (minute of the day), for a dose at [doseMinute].
     * An alert time at or before the dose means the next day.
     */
    fun delayForAlertTime(m: Med, doseMinute: Int, alertMinute: Int): Int {
        var d = Math.floorMod(alertMinute - doseMinute, MINUTES_PER_DAY)
        if (d == 0) d = MINUTES_PER_DAY
        return d.coerceIn(MIN_DELAY, maxDelayMinutes(m))
    }

    /** Minute of the day when the text goes out, and whether that is the day after the dose. */
    fun alertTime(doseMinute: Int, delayMinutes: Int): Pair<Int, Boolean> {
        val total = doseMinute + delayMinutes
        return (total % MINUTES_PER_DAY) to (total >= MINUTES_PER_DAY)
    }

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

    private fun lastDay(s: Settings): LocalDate? {
        val active = s.meds.filter { !it.paused }
        if (active.isEmpty()) return LocalDate.MIN
        return if (active.any { it.endDate == null }) null else active.mapNotNull { it.endDate }.maxOrNull()
    }

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

    /** When a dose counts as missed: its earliest medication deadline. */
    fun deadline(d: Dose): ZonedDateTime = d.at.plusMinutes(d.meds.minOf { clampedDelay(it) }.toLong())

    /** When [contactId] gets a text for [d], if [entry]'s confirmations leave anything of theirs open. */
    fun alertTime(d: Dose, contactId: Long, entry: Entry?): ZonedDateTime? =
        d.meds.filter { contactId in it.contactIds && entry?.isTaken(it.id) != true }
            .minOfOrNull { clampedDelay(it) }
            ?.let { d.at.plusMinutes(it.toLong()) }

    fun status(d: Dose, entry: Entry?, now: ZonedDateTime): CycleStatus = when {
        entry?.isTaken(d) == true -> CycleStatus.TAKEN
        now.isAfter(deadline(d)) -> CycleStatus.MISSED
        else -> CycleStatus.PENDING
    }

    private fun longestDelay(s: Settings): Long = (s.meds.maxOfOrNull { clampedDelay(it) } ?: 0).toLong()

    /**
     * The dose the "I took it" button applies to, in this order:
     * the oldest past dose still waiting (deadline not reached, not confirmed);
     * else the upcoming dose if it is less than [EARLY_WINDOW_MINUTES] away;
     * else the most recent dose. Null if there is none yet, or if no dose is left and the last was before today.
     */
    fun target(s: Settings, history: Map<Long, Entry>, now: ZonedDateTime): Dose? {
        if (!s.isConfigured) return null
        between(s, now.minusMinutes(longestDelay(s)), now)
            .firstOrNull { history[it.cycle]?.isTaken(it) != true && deadline(it).isAfter(now) }
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
        val nowMs = now.toInstant().toEpochMilli()
        var nextAlert: Long? = null
        val due = ArrayList<Alert>()
        // Every dose whose texts are still ahead, or came due less than LATE_SMS_LIMIT_HOURS ago.
        val from = now.minusMinutes(longestDelay(s)).minusHours(LATE_SMS_LIMIT_HOURS)
        for (d in between(s, from, now)) {
            val e = history[d.cycle]
            for (c in s.contacts) {
                if (e?.sms?.containsKey(c.id) == true) continue
                val at = alertTime(d, c.id, e) ?: continue
                val atMs = at.toInstant().toEpochMilli()
                if (atMs > nowMs) {
                    if (nextAlert == null || atMs < nextAlert) nextAlert = atMs
                } else if (Duration.between(at, now).toHours() < LATE_SMS_LIMIT_HOURS) {
                    due.add(Alert(d.cycle, c.id, atMs))
                }
            }
        }
        return Plan(next, nextAlert, due)
    }
}
