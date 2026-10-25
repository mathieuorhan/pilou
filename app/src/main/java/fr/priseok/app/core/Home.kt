package fr.priseok.app.core

import java.time.Instant
import java.time.ZonedDateTime

/** Where the current dose stands, as shown on the home screen. */
enum class Phase { NOTHING_DUE, EARLY, DUE, TAKEN, MISSED }

/** What the home screen shows, as data. The words come from strings.xml (see ui/Texts.kt). */
data class HomeState(
    val phase: Phase,
    /** The dose this state is about. Null when nothing is due. */
    val target: Dose?,
    val deadline: ZonedDateTime?,
    val takenAt: ZonedDateTime?,
    val smsAt: ZonedDateTime?,
    val smsFailed: Boolean,
    /** The next reminder worth announcing. Null when no dose is left. */
    val next: Dose?,
    /** The dose the big button confirms. Null when there is nothing to confirm. */
    val confirmCycle: Long?,
)

/** One line of the "today" list. */
data class DoseRow(
    val dose: Dose,
    /** Null for a dose still to come. */
    val status: CycleStatus?,
    val takenAt: ZonedDateTime?,
    val smsAt: ZonedDateTime?,
    /** Whether the line offers its own "taken" button. */
    val confirmable: Boolean,
)

object Home {
    fun state(s: Settings, history: Map<Long, Entry>, now: ZonedDateTime): HomeState {
        val next = Schedule.after(s, now)
        val target = Schedule.target(s, history, now)
            ?: return HomeState(Phase.NOTHING_DUE, null, null, null, null, false, next, null)

        val e = history[target.cycle]
        val deadline = Schedule.deadline(s, target.at)
        fun at(ms: Long?) = ms?.let { Instant.ofEpochMilli(it).atZone(now.zone) }
        val base = HomeState(Phase.DUE, target, deadline, at(e?.takenAt), at(e?.smsAt), e?.smsFailed == true, next, target.cycle)

        return when {
            e?.takenAt != null -> base.copy(
                phase = Phase.TAKEN,
                next = if (target.at.isAfter(now)) Schedule.after(s, target.at) else next,
                confirmCycle = null,
            )
            target.at.isAfter(now) -> base.copy(phase = Phase.EARLY)
            now.isBefore(deadline) -> base
            else -> base.copy(phase = Phase.MISSED)
        }
    }

    /** Today's doses. Past ones not yet confirmed get their own button, except the one the big button handles. */
    fun today(s: Settings, history: Map<Long, Entry>, now: ZonedDateTime, bigButtonCycle: Long?): List<DoseRow> {
        fun at(ms: Long?) = ms?.let { Instant.ofEpochMilli(it).atZone(now.zone) }
        return Schedule.dosesOn(s, now.toLocalDate(), now.zone).map { d ->
            val e = history[d.cycle]
            val past = !d.at.isAfter(now)
            val status = when {
                e?.takenAt != null -> CycleStatus.TAKEN
                past -> Schedule.status(s, d.cycle, e, now)
                else -> null
            }
            DoseRow(
                d, status, at(e?.takenAt), at(e?.smsAt),
                confirmable = past && e?.takenAt == null && d.cycle != bigButtonCycle,
            )
        }
    }

    /** Aggregates a day: missed if any dose was missed, pending if one is still open, else taken. */
    fun dayStatus(s: Settings, doses: List<Dose>, history: Map<Long, Entry>, now: ZonedDateTime): CycleStatus {
        val all = doses.map { Schedule.status(s, it.cycle, history[it.cycle], now) }
        return when {
            CycleStatus.MISSED in all -> CycleStatus.MISSED
            CycleStatus.PENDING in all -> CycleStatus.PENDING
            else -> CycleStatus.TAKEN
        }
    }
}
