package fr.priseok.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import fr.priseok.app.core.Schedule
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Turns the pure [Schedule.plan] into real alarms.
 * Two alarms at most are ever pending: the next reminder, and the next time a text comes due.
 * When that alarm fires, rescheduling sends what is due and arms the next one.
 * Snoozed reminders get their own one-off alarm, one per dose.
 */
object Alarms {
    const val ACTION_REMIND = "fr.priseok.app.REMIND"
    const val ACTION_DEADLINE = "fr.priseok.app.DEADLINE"
    const val ACTION_CONFIRM = "fr.priseok.app.CONFIRM"
    const val ACTION_SNOOZE = "fr.priseok.app.SNOOZE"
    const val ACTION_UNDO = "fr.priseok.app.UNDO"
    const val EXTRA_CYCLE = "cycle"

    const val SNOOZE_MINUTES = 15L

    private const val RC_REMIND = 1
    private const val RC_DEADLINE = 2

    /** A request code per dose, so the buttons of two open reminders don't overwrite each other. */
    fun confirmCode(cycle: Long): Int = if (cycle < 0) 3 else 1000 + ((cycle / 60_000) % 1_000_000).toInt()

    /** Request codes for the other per-dose actions, in their own ranges. */
    fun snoozeCode(cycle: Long): Int = 2_000_000 + confirmCode(cycle)
    fun undoCode(cycle: Long): Int = 4_000_000 + confirmCode(cycle)

    fun canScheduleExact(ctx: Context): Boolean {
        val am = ctx.getSystemService(AlarmManager::class.java)
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
    }

    fun intent(ctx: Context, action: String, cycle: Long, rc: Int): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, rc,
            Intent(ctx, ReminderReceiver::class.java).setAction(action).putExtra(EXTRA_CYCLE, cycle),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Recompute and set every alarm, and send the texts that are due. Safe to call any number of times. */
    fun reschedule(ctx: Context, nowMillis: Long = System.currentTimeMillis()) {
        val store = Store(ctx)
        val s = store.settings()
        val am = ctx.getSystemService(AlarmManager::class.java)
        if (!s.isConfigured) {
            am.cancel(intent(ctx, ACTION_REMIND, 0, RC_REMIND))
            am.cancel(intent(ctx, ACTION_DEADLINE, 0, RC_DEADLINE))
            return
        }
        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), ZoneId.systemDefault())
        val plan = Schedule.plan(s, now, store.history())

        if (plan.nextReminder != null) {
            setExact(ctx, am, plan.nextReminder, intent(ctx, ACTION_REMIND, plan.nextReminder, RC_REMIND))
        } else {
            am.cancel(intent(ctx, ACTION_REMIND, 0, RC_REMIND))
        }
        if (plan.nextAlertAt != null) {
            setExact(ctx, am, plan.nextAlertAt, intent(ctx, ACTION_DEADLINE, 0, RC_DEADLINE))
        } else {
            am.cancel(intent(ctx, ACTION_DEADLINE, 0, RC_DEADLINE))
        }
        plan.sendNow.forEach { Sms.sendAlert(ctx, it.cycle, it.contactId) }
    }

    /** Shows the reminder of [cycle] again in [SNOOZE_MINUTES]. The text still goes out at its usual time. */
    fun snooze(ctx: Context, cycle: Long) {
        val at = System.currentTimeMillis() + SNOOZE_MINUTES * 60_000
        setExact(ctx, ctx.getSystemService(AlarmManager::class.java), at, intent(ctx, ACTION_REMIND, cycle, snoozeCode(cycle)))
    }

    private fun setExact(ctx: Context, am: AlarmManager, at: Long, pi: PendingIntent) {
        if (canScheduleExact(ctx)) {
            // Fires on time even in Doze (deep sleep).
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            // Fallback when the exact-alarm permission was revoked: may be a few minutes late.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }
}
