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
 * A "cycle" is one expected dose. It is identified by the epoch millis of its trigger time.
 */

enum class Frequency { DAILY, WEEKLY, MONTHLY }

data class Settings(
    val hour: Int = 9,
    val minute: Int = 0,
    val frequency: Frequency = Frequency.DAILY,
    /** ISO day of week, 1 = Monday ... 7 = Sunday. Used when frequency = WEEKLY. */
    val dayOfWeek: Int = 1,
    /** 1..31. Months shorter than this use their last day. Used when frequency = MONTHLY. */
    val dayOfMonth: Int = 1,
    /** Time after the reminder before the SMS goes out. */
    val delayMinutes: Int = 120,
    val contactName: String = "",
    val phone: String = "",
    /** Epoch millis when the schedule was last set. Cycles before it are ignored. 0 = never configured. */
    val activeSince: Long = 0L,
) {
    val isConfigured: Boolean get() = activeSince > 0L

    fun sameScheduleAs(o: Settings): Boolean =
        hour == o.hour && minute == o.minute && frequency == o.frequency &&
            dayOfWeek == o.dayOfWeek && dayOfMonth == o.dayOfMonth
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
    val nextReminder: Long,
    val deadlineCycle: Long?,
    val deadlineAt: Long?,
    /** A deadline that passed while the phone was off: send the SMS right away. */
    val sendSmsNowFor: Long?,
)

object Schedule {
    const val MINUTES_PER_DAY = 24 * 60
    /** Validating up to this long before the reminder counts for the upcoming dose. */
    const val EARLY_WINDOW_MINUTES = 180L
    /** A missed deadline older than this (phone off for long) no longer triggers a late SMS. */
    const val LATE_SMS_LIMIT_HOURS = 12L
    private const val SEARCH_DAYS = 400L

    /** The delay must stay shorter than the gap between two doses. */
    fun maxDelayMinutes(f: Frequency): Int =
        if (f == Frequency.DAILY) MINUTES_PER_DAY - 15 else MINUTES_PER_DAY

    /**
     * The delay that makes the SMS go out at [alertHour]:[alertMinute], for a reminder at [hour]:[minute].
     * An alert time at or before the reminder means the next day.
     */
    fun delayForAlertTime(hour: Int, minute: Int, alertHour: Int, alertMinute: Int, f: Frequency): Int {
        var d = Math.floorMod((alertHour * 60 + alertMinute) - (hour * 60 + minute), MINUTES_PER_DAY)
        if (d == 0) d = MINUTES_PER_DAY
        return d.coerceIn(15, maxDelayMinutes(f))
    }

    /** Minute of the day when the SMS goes out, and whether that is the day after the reminder. */
    fun alertTime(hour: Int, minute: Int, delayMinutes: Int): Pair<Int, Boolean> {
        val total = hour * 60 + minute + delayMinutes
        return (total % MINUTES_PER_DAY) to (total >= MINUTES_PER_DAY)
    }

    fun clampedDelay(s: Settings): Int = s.delayMinutes.coerceIn(15, maxDelayMinutes(s.frequency))

    /** The trigger on [date], or null if the schedule has no dose that day. */
    fun occurrenceOn(s: Settings, date: LocalDate, zone: ZoneId): ZonedDateTime? {
        val matches = when (s.frequency) {
            Frequency.DAILY -> true
            Frequency.WEEKLY -> date.dayOfWeek.value == s.dayOfWeek
            Frequency.MONTHLY -> date.dayOfMonth == minOf(s.dayOfMonth, date.lengthOfMonth())
        }
        if (!matches) return null
        // ZonedDateTime.of moves a time that falls in a DST gap forward, and picks the
        // earlier offset in an overlap: the reminder fires once, at the expected wall-clock time.
        return ZonedDateTime.of(date, LocalTime.of(s.hour, s.minute), zone)
    }

    /** First trigger strictly after [now]. */
    fun after(s: Settings, now: ZonedDateTime): ZonedDateTime {
        var d = now.toLocalDate()
        repeat(SEARCH_DAYS.toInt()) {
            occurrenceOn(s, d, now.zone)?.let { if (it.isAfter(now)) return it }
            d = d.plusDays(1)
        }
        error("No occurrence found")
    }

    /** Last trigger at or before [now]. */
    fun atOrBefore(s: Settings, now: ZonedDateTime): ZonedDateTime? {
        var d = now.toLocalDate()
        repeat(SEARCH_DAYS.toInt()) {
            occurrenceOn(s, d, now.zone)?.let { if (!it.isAfter(now)) return it }
            d = d.minusDays(1)
        }
        return null
    }

    /** The [n] most recent triggers at or before [now] since the schedule was set, oldest first. */
    fun recent(s: Settings, now: ZonedDateTime, n: Int): List<ZonedDateTime> {
        val out = ArrayList<ZonedDateTime>()
        var cursor: ZonedDateTime = now
        while (out.size < n) {
            val t = atOrBefore(s, cursor) ?: break
            if (t.toInstant().toEpochMilli() < s.activeSince) break
            out.add(t)
            cursor = t.minusNanos(1)
        }
        return out.reversed()
    }

    fun deadline(s: Settings, trigger: ZonedDateTime): ZonedDateTime =
        trigger.plusMinutes(clampedDelay(s).toLong()) // instant-based: a real 2 h, even across DST

    fun deadline(s: Settings, cycle: Long, zone: ZoneId): ZonedDateTime =
        deadline(s, Instant.ofEpochMilli(cycle).atZone(zone))

    /**
     * The dose the "I took it" button applies to: the upcoming one if it is less than
     * [EARLY_WINDOW_MINUTES] away, otherwise the most recent one. Null if there is none yet.
     */
    fun targetCycle(s: Settings, now: ZonedDateTime): ZonedDateTime? {
        if (!s.isConfigured) return null
        val next = after(s, now)
        if (Duration.between(now, next).toMinutes() < EARLY_WINDOW_MINUTES) return next
        val last = atOrBefore(s, now) ?: return null
        return if (last.toInstant().toEpochMilli() >= s.activeSince) last else null
    }

    fun status(s: Settings, cycle: Long, entry: Entry?, now: ZonedDateTime): CycleStatus = when {
        entry?.takenAt != null -> CycleStatus.TAKEN
        now.isAfter(deadline(s, cycle, now.zone)) -> CycleStatus.MISSED
        else -> CycleStatus.PENDING
    }

    fun plan(s: Settings, now: ZonedDateTime, history: Map<Long, Entry>): Plan {
        val next = after(s, now).toInstant().toEpochMilli()
        val last = atOrBefore(s, now)
        if (last != null) {
            val key = last.toInstant().toEpochMilli()
            val e = history[key]
            if (key >= s.activeSince && e?.takenAt == null && e?.smsAt == null) {
                val dl = deadline(s, last)
                if (dl.isAfter(now)) {
                    return Plan(next, key, dl.toInstant().toEpochMilli(), null)
                }
                if (Duration.between(dl, now).toHours() < LATE_SMS_LIMIT_HOURS) {
                    return Plan(next, null, null, key)
                }
            }
        }
        return Plan(next, null, null, null)
    }
}
