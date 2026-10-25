package fr.priseok.app

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Handles the three alarm-driven events: reminder, SMS deadline, and the notification button. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val cycle = intent.getLongExtra(Alarms.EXTRA_CYCLE, 0L)
        val now = System.currentTimeMillis()
        val store = Store(ctx)
        when (intent.action) {
            Alarms.ACTION_REMIND -> {
                // Skip the question if the dose was already confirmed early.
                if (store.history()[cycle]?.takenAt == null) Notifier.showReminder(ctx, cycle)
                // An alarm never fires early, but guard anyway so this cycle counts as "current".
                Alarms.reschedule(ctx, maxOf(now, cycle))
            }
            Alarms.ACTION_DEADLINE -> {
                Sms.sendAlert(ctx, cycle)
                Alarms.reschedule(ctx)
            }
            Alarms.ACTION_CONFIRM -> {
                if (cycle > 0) store.markTaken(cycle, now) // cycle -1 = test notification
                Notifier.cancelReminder(ctx)
                Alarms.reschedule(ctx)
            }
        }
    }
}

/** Learns whether the SMS actually left the phone (no credit, no network...). */
class SmsResultReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val test = intent.getBooleanExtra(Sms.EXTRA_TEST, false)
        val ok = resultCode == Activity.RESULT_OK
        if (test) {
            Notifier.info(
                ctx,
                if (ok) R.string.sms_test_sent_title else R.string.sms_test_failed_title,
                ctx.getString(if (ok) R.string.sms_test_sent_text else R.string.sms_test_failed_text),
            )
            return
        }
        val cycle = intent.getLongExtra(Alarms.EXTRA_CYCLE, 0L)
        if (!ok) {
            Store(ctx).update(cycle) { it.copy(smsFailed = true) }
            Notifier.info(ctx, R.string.sms_alert_failed_title, ctx.getString(R.string.sms_alert_failed_text))
        }
    }
}

/**
 * Alarms are wiped on reboot and can drift on clock changes: set them again.
 * This receiver is exported (system broadcasts need it), so it only reacts to these actions,
 * which Android lets only the system send.
 */
class SystemEventsReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action in SYSTEM_ACTIONS) Alarms.reschedule(ctx)
    }

    companion object {
        private val SYSTEM_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
        )
    }
}
