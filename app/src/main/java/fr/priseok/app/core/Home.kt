package fr.priseok.app.core

import java.time.Instant
import java.time.ZonedDateTime

/** Where the current dose stands, as shown on the home screen. */
enum class Phase { NOTHING_DUE, EARLY, DUE, TAKEN, MISSED }

/** What the home screen shows, as data. The words come from strings.xml (see ui/Texts.kt). */
data class HomeState(
    val phase: Phase,
    /** The dose this state is about. Null when nothing is due. */
    val target: ZonedDateTime?,
    val deadline: ZonedDateTime?,
    val takenAt: ZonedDateTime?,
    val smsAt: ZonedDateTime?,
    val smsFailed: Boolean,
    /** The next reminder worth announcing. */
    val next: ZonedDateTime,
    /** The dose the big button confirms. Null when there is nothing to confirm. */
    val confirmCycle: Long?,
)

object Home {
    fun state(s: Settings, history: Map<Long, Entry>, now: ZonedDateTime): HomeState {
        val next = Schedule.after(s, now)
        val target = Schedule.targetCycle(s, now)
            ?: return HomeState(Phase.NOTHING_DUE, null, null, null, null, false, next, null)

        val key = target.toInstant().toEpochMilli()
        val e = history[key]
        val deadline = Schedule.deadline(s, target)
        fun at(ms: Long?) = ms?.let { Instant.ofEpochMilli(it).atZone(now.zone) }
        val base = HomeState(Phase.DUE, target, deadline, at(e?.takenAt), at(e?.smsAt), e?.smsFailed == true, next, key)

        return when {
            e?.takenAt != null -> base.copy(
                phase = Phase.TAKEN,
                next = if (target.isAfter(now)) Schedule.after(s, target) else next,
                confirmCycle = null,
            )
            target.isAfter(now) -> base.copy(phase = Phase.EARLY)
            now.isBefore(deadline) -> base
            else -> base.copy(phase = Phase.MISSED)
        }
    }
}
