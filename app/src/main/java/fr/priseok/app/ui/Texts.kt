package fr.priseok.app.ui

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import fr.priseok.app.R
import fr.priseok.app.core.Frequency
import fr.priseok.app.core.HomeState
import fr.priseok.app.core.Phase
import fr.priseok.app.core.Settings
import fr.priseok.app.core.Wording
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

    fun frequency(s: Settings): String {
        val at = time(s.hour, s.minute)
        return when (s.frequency) {
            Frequency.DAILY -> str(R.string.freq_daily, at)
            Frequency.WEEKLY -> str(R.string.freq_weekly, Wording.dayName(s.dayOfWeek, locale), at)
            Frequency.MONTHLY ->
                if (s.dayOfMonth == 1) str(R.string.freq_monthly_first, at) else str(R.string.freq_monthly, s.dayOfMonth, at)
        }
    }

    fun historyTitle(f: Frequency) = str(
        when (f) {
            Frequency.DAILY -> R.string.history_days
            Frequency.WEEKLY -> R.string.history_weeks
            Frequency.MONTHLY -> R.string.history_months
        }
    )

    fun home(h: HomeState, s: Settings, now: ZonedDateTime): HomeText {
        val next = str(R.string.next_reminder, whenText(h.next, now))
        val target = h.target?.let { time(it) } ?: ""
        return when (h.phase) {
            Phase.NOTHING_DUE -> HomeText(str(R.string.nothing_title), next, null)
            Phase.TAKEN -> HomeText(str(R.string.taken_title), str(R.string.taken_at, time(h.takenAt!!)), next)
            Phase.EARLY -> HomeText(str(R.string.early_title), str(R.string.early_line, target), str(R.string.early_hint))
            Phase.DUE -> HomeText(
                str(R.string.due_title), str(R.string.due_line, target),
                str(R.string.due_hint, contact(s.contactName), time(h.deadline!!)),
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
