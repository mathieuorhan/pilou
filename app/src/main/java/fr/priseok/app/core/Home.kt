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
    val takenAt: ZonedDateTime?,
    /** Who will get a text if the dose stays unconfirmed, and when the first one goes out. */
    val alertContacts: List<Contact>,
    val alertAt: ZonedDateTime?,
    /** Who already got a text for this dose, and when the first one went out. */
    val smsContacts: List<Contact>,
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
    /** Medications of this dose already confirmed, by id. */
    val medsTaken: Set<Long>,
    /** Whether the line offers its own "taken" button for the whole dose. */
    val confirmable: Boolean,
)

object Home {
    fun state(s: Settings, history: Map<Long, Entry>, now: ZonedDateTime): HomeState {
        val next = Schedule.after(s, now)
        val target = Schedule.target(s, history, now)
            ?: return HomeState(Phase.NOTHING_DUE, null, null, emptyList(), null, emptyList(), null, false, next, null)

        val e = history[target.cycle]
        fun at(ms: Long?) = ms?.let { Instant.ofEpochMilli(it).atZone(now.zone) }
        val pending = s.contacts.mapNotNull { c ->
            if (e?.sms?.containsKey(c.id) == true) null else Schedule.alertTime(target, c.id, e)?.let { c to it }
        }
        val sent = s.contacts.filter { e?.sms?.containsKey(it.id) == true }
        val base = HomeState(
            phase = Phase.DUE, target = target, takenAt = at(e?.takenTime(target)),
            alertContacts = pending.map { it.first }, alertAt = pending.minOfOrNull { it.second },
            smsContacts = sent, smsAt = at(sent.minOfOrNull { e!!.sms.getValue(it.id) }),
            smsFailed = e?.smsFailed?.isNotEmpty() == true, next = next, confirmCycle = target.cycle,
        )

        return when {
            e?.isTaken(target) == true -> base.copy(
                phase = Phase.TAKEN,
                next = if (target.at.isAfter(now)) Schedule.after(s, target.at) else next,
                confirmCycle = null,
            )
            target.at.isAfter(now) -> base.copy(phase = Phase.EARLY)
            now.isBefore(Schedule.deadline(target)) -> base
            else -> base.copy(phase = Phase.MISSED)
        }
    }

    /** Today's doses. Past ones not yet confirmed get their own button, except the one the big button handles. */
    fun today(s: Settings, history: Map<Long, Entry>, now: ZonedDateTime, bigButtonCycle: Long?): List<DoseRow> {
        fun at(ms: Long?) = ms?.let { Instant.ofEpochMilli(it).atZone(now.zone) }
        return Schedule.dosesOn(s, now.toLocalDate(), now.zone).map { d ->
            val e = history[d.cycle]
            val past = !d.at.isAfter(now)
            val taken = e?.isTaken(d) == true
            DoseRow(
                dose = d,
                status = when {
                    taken -> CycleStatus.TAKEN
                    past -> Schedule.status(d, e, now)
                    else -> null
                },
                takenAt = at(e?.takenTime(d)),
                smsAt = at(e?.sms?.values?.minOrNull()),
                medsTaken = d.meds.filter { e?.isTaken(it.id) == true }.map { it.id }.toSet(),
                confirmable = past && !taken && d.cycle != bigButtonCycle,
            )
        }
    }

    /** Aggregates a day: missed if any dose was missed, pending if one is still open, else taken. */
    fun dayStatus(doses: List<Dose>, history: Map<Long, Entry>, now: ZonedDateTime): CycleStatus {
        val all = doses.map { Schedule.status(it, history[it.cycle], now) }
        return when {
            CycleStatus.MISSED in all -> CycleStatus.MISSED
            CycleStatus.PENDING in all -> CycleStatus.PENDING
            else -> CycleStatus.TAKEN
        }
    }
}
