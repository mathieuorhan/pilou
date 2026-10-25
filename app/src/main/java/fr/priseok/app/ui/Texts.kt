package fr.priseok.app.ui

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import fr.priseok.app.R
import fr.priseok.app.core.Dose
import fr.priseok.app.core.Frequency
import fr.priseok.app.core.HomeState
import fr.priseok.app.core.Med
import fr.priseok.app.core.Phase
import fr.priseok.app.core.Settings
import fr.priseok.app.core.Wording
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.Locale

data class HomeText(val title: String, val line: String, val hint: String?)

/** Builds the sentences shown to the user, in the phone's language (English or French). */
class Texts(private val ctx: Context) {
    val locale: Locale = ctx.resources.configuration.locales[0]
    private val is24h = DateFormat.is24HourFormat(ctx)

    fun str(id: Int, vararg args: Any): String = ctx.getString(id, *args)

    fun time(t: ZonedDateTime) = Wording.time(t, locale, is24h)
    fun time(h: Int, m: Int) = Wording.time(h, m, locale, is24h)
    fun time(minuteOfDay: Int) = time(minuteOfDay / 60, minuteOfDay % 60)

    /** "today at 9:00 AM", "tomorrow at …", "Monday, November 3 at …". */
    fun whenText(t: ZonedDateTime, now: ZonedDateTime): String = when (Wording.daysBetween(now, t)) {
        0L -> str(R.string.when_today, time(t))
        1L -> str(R.string.when_tomorrow, time(t))
        else -> str(R.string.when_date, Wording.date(t, locale), time(t))
    }

    fun delay(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> str(R.string.delay_min, m)
            m == 0 -> str(R.string.delay_h, h)
            else -> str(R.string.delay_hm, h, m)
        }
    }

    fun contact(name: String) = name.trim().ifEmpty { str(R.string.your_contact) }
    private fun contactCap(name: String) = name.trim().ifEmpty { str(R.string.your_contact_cap) }

    fun medName(m: Med) = m.name.trim().ifEmpty { str(R.string.med_default) }

    fun doseNames(d: Dose) = d.meds.joinToString(", ") { medName(it) }

    /** "a, b and c". */
    fun list(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> str(R.string.list_and, items.dropLast(1).joinToString(", "), items.last())
    }

    /** "Every day at 8:00 AM and 8:00 PM", "Mon, Wed and Fri at 9:00 AM", "Day 15 of every month at …". */
    fun schedule(m: Med): String {
        val at = list(m.times.map { time(it) })
        return when {
            m.frequency == Frequency.DAILY || (m.frequency == Frequency.WEEKLY && m.daysOfWeek.size == 7) ->
                str(R.string.freq_daily, at)
            m.frequency == Frequency.WEEKLY && m.daysOfWeek.size == 1 ->
                str(R.string.freq_weekly, Wording.dayName(m.daysOfWeek.first(), locale), at)
            m.frequency == Frequency.WEEKLY ->
                str(R.string.freq_days, list(m.daysOfWeek.sorted().map { Wording.dayShort(it, locale) }), at)
            m.dayOfMonth == 1 -> str(R.string.freq_monthly_first, at)
            else -> str(R.string.freq_monthly, m.dayOfMonth, at)
        }
    }

    fun until(d: LocalDate) = str(R.string.until_date, Wording.date(d, locale))

    /** Under the app name: the schedule when there is one medication, else how many there are. */
    fun header(s: Settings): String =
        if (s.meds.size == 1) schedule(s.meds[0])
        else ctx.resources.getQuantityString(R.plurals.med_count, s.meds.size, s.meds.size)

    fun historyTitle(scale: Wording.Scale) = str(
        when (scale) {
            Wording.Scale.DAYS -> R.string.history_days
            Wording.Scale.WEEKS -> R.string.history_weeks
            Wording.Scale.MONTHS -> R.string.history_months
        }
    )

    fun home(h: HomeState, s: Settings, now: ZonedDateTime): HomeText {
        val next = h.next?.let { str(R.string.next_reminder, whenText(it.at, now)) } ?: str(R.string.none_left)
        val target = h.target?.let { time(it.at) } ?: ""
        val alerts = h.target?.alerts == true
        return when (h.phase) {
            Phase.NOTHING_DUE -> HomeText(str(R.string.nothing_title), next, null)
            Phase.TAKEN -> HomeText(str(R.string.taken_title), str(R.string.taken_at, time(h.takenAt!!)), next)
            Phase.EARLY -> HomeText(str(R.string.early_title), str(R.string.early_line, target), str(R.string.early_hint))
            Phase.DUE -> HomeText(
                str(R.string.due_title), str(R.string.due_line, target),
                if (alerts) str(R.string.due_hint, contact(s.contactName), time(h.deadline!!)) else str(R.string.due_hint_quiet),
            )
            Phase.MISSED -> HomeText(
                str(R.string.missed_title),
                when {
                    h.smsAt != null && h.smsFailed -> str(R.string.missed_sms_failed)
                    h.smsAt != null -> str(R.string.missed_sms_sent, contactCap(s.contactName), time(h.smsAt))
                    else -> str(R.string.missed_no_sms, target)
                },
                str(R.string.missed_hint),
            )
        }
    }
}

/** [Texts] for the current screen; rebuilt when the language or configuration changes. */
@Composable
fun rememberTexts(): Texts {
    val ctx = LocalContext.current
    val config = LocalConfiguration.current
    return remember(ctx, config) { Texts(ctx) }
}
