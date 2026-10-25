package fr.priseok.app.core

import java.time.Instant
import java.time.ZonedDateTime

/** Where the current dose stands, as shown on the home screen. */
enum class Phase { NOTHING_DUE, EARLY, DUE, TAKEN, MISSED }

data class HomeState(
    val phase: Phase,
    val title: String,
    val line: String,
    val hint: String?,
    /** The dose the big button confirms. Null when there is nothing to confirm. */
    val confirmCycle: Long?,
)

object Home {
    fun state(s: Settings, history: Map<Long, Entry>, now: ZonedDateTime): HomeState {
        val next = Schedule.after(s, now)
        val nextText = Wording.whenText(next, now)
        val who = Wording.contact(s.contactName)
        val target = Schedule.targetCycle(s, now)
            ?: return HomeState(Phase.NOTHING_DUE, "Rien à prendre pour l'instant", "Prochain rappel $nextText.", null, null)

        val key = target.toInstant().toEpochMilli()
        val e = history[key]
        val deadline = Schedule.deadline(s, target)

        e?.takenAt?.let { at ->
            val atTime = Instant.ofEpochMilli(at).atZone(now.zone)
            val upcoming = if (target.isAfter(now)) Schedule.after(s, target) else next
            return HomeState(
                Phase.TAKEN, "C'est fait, bravo !",
                "Pris à ${Wording.time(atTime)}.",
                "Prochain rappel ${Wording.whenText(upcoming, now)}.",
                null,
            )
        }
        if (target.isAfter(now)) {
            return HomeState(
                Phase.EARLY, "C'est bientôt l'heure",
                "Rappel prévu à ${Wording.time(target)}.",
                "Tu peux déjà valider si c'est pris.",
                key,
            )
        }
        if (now.isBefore(deadline)) {
            return HomeState(
                Phase.DUE, "C'est l'heure !",
                "Ton médicament de ${Wording.time(target)} t'attend.",
                "Sans réponse, $who reçoit un SMS à ${Wording.time(deadline)}.",
                key,
            )
        }
        val line = when {
            e?.smsAt != null && e.smsFailed -> "Le SMS d'alerte n'a pas pu partir."
            e?.smsAt != null -> "${Wording.contactCap(s.contactName)} a reçu l'alerte par SMS à ${Wording.time(Instant.ofEpochMilli(e.smsAt).atZone(now.zone))}."
            else -> "Le délai de ${Wording.time(target)} est passé."
        }
        return HomeState(Phase.MISSED, "Oups, c'est passé", line, "Tu peux quand même valider.", key)
    }
}
