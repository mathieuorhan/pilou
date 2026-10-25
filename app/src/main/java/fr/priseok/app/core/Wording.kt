package fr.priseok.app.core

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** French wording shared by the screens. Pure functions, unit-tested. */
object Wording {
    private val FR = Locale.FRENCH

    fun time(t: ZonedDateTime): String = "%dh%02d".format(t.hour, t.minute)
    fun time(h: Int, m: Int): String = "%dh%02d".format(h, m)

    /** "aujourd'hui à 9h00", "demain à 9h00", "lundi 3 novembre à 9h00". */
    fun whenText(t: ZonedDateTime, now: ZonedDateTime): String {
        val days = java.time.temporal.ChronoUnit.DAYS.between(now.toLocalDate(), t.toLocalDate())
        val day = when (days) {
            0L -> "aujourd'hui"
            1L -> "demain"
            else -> DateTimeFormatter.ofPattern("EEEE d MMMM", FR).format(t)
        }
        return "$day à ${time(t)}"
    }

    fun delay(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> "$m min"
            m == 0 -> "$h h"
            else -> "$h h %02d".format(m)
        }
    }

    fun contact(name: String): String = name.trim().ifEmpty { "ton contact" }

    /** Capitalised, for the start of a sentence. */
    fun contactCap(name: String): String = contact(name).replaceFirstChar { it.uppercase(FR) }

    fun frequency(s: Settings): String = when (s.frequency) {
        Frequency.DAILY -> "Chaque jour à ${time(s.hour, s.minute)}"
        Frequency.WEEKLY -> "Chaque ${dayName(s.dayOfWeek)} à ${time(s.hour, s.minute)}"
        Frequency.MONTHLY -> "Le ${if (s.dayOfMonth == 1) "1er" else s.dayOfMonth.toString()} de chaque mois à ${time(s.hour, s.minute)}"
    }

    fun dayName(isoDay: Int): String =
        java.time.DayOfWeek.of(isoDay).getDisplayName(TextStyle.FULL, FR)

    /** Short label under a history pill. */
    fun historyLabel(f: Frequency, t: ZonedDateTime): String = when (f) {
        Frequency.DAILY -> t.dayOfWeek.getDisplayName(TextStyle.SHORT, FR).trimEnd('.')
        Frequency.WEEKLY -> "%d/%d".format(t.dayOfMonth, t.monthValue)
        Frequency.MONTHLY -> t.month.getDisplayName(TextStyle.SHORT, FR).trimEnd('.')
    }

    fun historyTitle(f: Frequency): String = when (f) {
        Frequency.DAILY -> "Les derniers jours"
        Frequency.WEEKLY -> "Les dernières semaines"
        Frequency.MONTHLY -> "Les derniers mois"
    }

    /** Keeps a leading + and the digits. Null if it cannot be a phone number. */
    fun cleanPhone(raw: String): String? {
        val t = raw.trim()
        val digits = t.filter { it.isDigit() }
        if (digits.length < 6 || digits.length > 15) return null
        return (if (t.startsWith("+")) "+" else "") + digits
    }
}
