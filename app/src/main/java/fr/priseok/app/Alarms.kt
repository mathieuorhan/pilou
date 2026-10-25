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
 * Two alarms at most are ever pending: the next reminder, and the SMS deadline of the current dose.
 */
object Alarms {
    const val ACTION_REMIND = "fr.priseok.app.REMIND"
    const val ACTION_DEADLINE = "fr.priseok.app.DEADLINE"
    const val ACTION_CONFIRM = "fr.priseok.app.CONFIRM"
    const val EXTRA_CYCLE = "cycle"

    private const val RC_REMIND = 1
    private const val RC_DEADLINE = 2
    const val RC_CONFIRM = 3

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

    /** Recompute and set every alarm. Safe to call any number of times. */
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

        setExact(ctx, am, plan.nextReminder, intent(ctx, ACTION_REMIND, plan.nextReminder, RC_REMIND))
        if (plan.deadlineAt != null && plan.deadlineCycle != null) {
            setExact(ctx, am, plan.deadlineAt, intent(ctx, ACTION_DEADLINE, plan.deadlineCycle, RC_DEADLINE))
        } else {
            am.cancel(intent(ctx, ACTION_DEADLINE, 0, RC_DEADLINE))
        }
        plan.sendSmsNowFor?.let { Sms.sendAlert(ctx, it) }
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
