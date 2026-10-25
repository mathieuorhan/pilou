package fr.priseok.app.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Locale-aware formatting of times and dates. Pure functions, unit-tested. Sentences live in strings.xml. */
object Wording {
    private fun isFrench(l: Locale) = l.language == "fr"

    /** "9h00" in French, "9:00 AM" or "9:00" in English depending on the phone's 12/24 h setting. */
    fun time(hour: Int, minute: Int, locale: Locale, is24h: Boolean): String = when {
        isFrench(locale) && is24h -> "%dh%02d".format(hour, minute)
        is24h -> "%d:%02d".format(hour, minute)
        else -> {
            val h = if (hour % 12 == 0) 12 else hour % 12
            "%d:%02d %s".format(h, minute, if (hour < 12) "AM" else "PM")
        }
    }

    fun time(t: ZonedDateTime, locale: Locale, is24h: Boolean) = time(t.hour, t.minute, locale, is24h)

    /** "lundi 3 novembre" / "Monday, November 3". */
    fun date(t: ZonedDateTime, locale: Locale): String = date(t.toLocalDate(), locale)

    fun date(d: LocalDate, locale: Locale): String =
        DateTimeFormatter.ofPattern(if (isFrench(locale)) "EEEE d MMMM" else "EEEE, MMMM d", locale).format(d)

    /** 0 = today, 1 = tomorrow, ... */
    fun daysBetween(now: ZonedDateTime, t: ZonedDateTime): Long =
        java.time.temporal.ChronoUnit.DAYS.between(now.toLocalDate(), t.toLocalDate())

    fun dayName(isoDay: Int, locale: Locale): String =
        DayOfWeek.of(isoDay).getDisplayName(TextStyle.FULL, locale)

    /** "Mon" / "lun". */
    fun dayShort(isoDay: Int, locale: Locale): String =
        DayOfWeek.of(isoDay).getDisplayName(TextStyle.SHORT, locale).trimEnd('.')

    fun dayLetter(isoDay: Int, locale: Locale): String =
        DayOfWeek.of(isoDay).getDisplayName(TextStyle.NARROW, locale).uppercase(locale)

    /** How far back the history pills reach, which decides how they are labelled. */
    enum class Scale { DAYS, WEEKS, MONTHS }

    /** Days within a week read as weekdays; a few weeks as dates; longer as months. */
    fun scale(first: LocalDate, last: LocalDate): Scale {
        val span = java.time.temporal.ChronoUnit.DAYS.between(first, last)
        return when {
            span < 7 -> Scale.DAYS
            span <= 62 -> Scale.WEEKS
            else -> Scale.MONTHS
        }
    }

    /** Short label under a history pill. */
    fun historyLabel(scale: Scale, d: LocalDate, locale: Locale): String = when (scale) {
        Scale.DAYS -> d.dayOfWeek.getDisplayName(TextStyle.SHORT, locale).trimEnd('.')
        Scale.WEEKS -> if (isFrench(locale)) "${d.dayOfMonth}/${d.monthValue}" else "${d.monthValue}/${d.dayOfMonth}"
        Scale.MONTHS -> d.month.getDisplayName(TextStyle.SHORT, locale).trimEnd('.')
    }

    /** Keeps a leading + and the digits. Null if it cannot be a phone number. */
    fun cleanPhone(raw: String): String? {
        val t = raw.trim()
        val digits = t.filter { it.isDigit() }
        if (digits.length < 6 || digits.length > 15) return null
        return (if (t.startsWith("+")) "+" else "") + digits
    }
}
