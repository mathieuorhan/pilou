package fr.priseok.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import fr.priseok.app.core.Schedule
import java.time.ZoneId

object Notifier {
    private const val CH_REMINDER = "rappel"
    private const val CH_INFO = "info"
    /** Notification id of the test reminder. Real reminders get one id per dose (see [reminderId]). */
    const val ID_REMINDER = 10
    private const val ID_INFO = 11

    fun reminderId(cycle: Long): Int = if (cycle < 0) ID_REMINDER else Alarms.confirmCode(cycle)

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        // Visible but silent: no sound, no vibration.
        nm.createNotificationChannel(
            NotificationChannel(CH_REMINDER, ctx.getString(R.string.channel_reminder), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = ctx.getString(R.string.channel_reminder_desc)
                setSound(null, null)
                enableVibration(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_INFO, ctx.getString(R.string.channel_info), NotificationManager.IMPORTANCE_LOW).apply {
                description = ctx.getString(R.string.channel_info_desc)
            }
        )
    }

    private fun allowed(ctx: Context) =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * The reminder for one dose, listing its medications by name.
     * [late] = the text already went out, but the dose can still be confirmed.
     */
    fun showReminder(ctx: Context, cycle: Long, late: Boolean = false) {
        if (!allowed(ctx)) return
        ensureChannels(ctx)
        val names = if (cycle < 0) "" else Store(ctx).settings().let { s ->
            Schedule.doseAt(s, cycle, ZoneId.systemDefault())?.meds.orEmpty()
                .map { it.name.trim() }.filter { it.isNotEmpty() }.joinToString(", ")
        }
        val hint = ctx.getString(if (late) R.string.notif_text_late else R.string.notif_text)
        val text = if (names.isEmpty()) hint else "$names\n$hint"
        val confirm = Alarms.intent(ctx, Alarms.ACTION_CONFIRM, cycle, Alarms.confirmCode(cycle))
        val n = NotificationCompat.Builder(ctx, CH_REMINDER)
            .setSmallIcon(R.drawable.ic_notif)
            .setColor(0xFFFF8EA1.toInt())
            .setContentTitle(ctx.getString(R.string.notif_question))
            .setContentText(names.ifEmpty { hint })
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(ctx))
            .addAction(R.drawable.ic_check, ctx.getString(R.string.confirm_button), confirm)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(reminderId(cycle), n)
    }

    fun cancelReminder(ctx: Context, cycle: Long) =
        ctx.getSystemService(NotificationManager::class.java).cancel(reminderId(cycle))

    fun info(ctx: Context, title: Int, text: String) {
        if (!allowed(ctx)) return
        ensureChannels(ctx)
        val n = NotificationCompat.Builder(ctx, CH_INFO)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(ctx.getString(title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(ID_INFO, n)
    }
}
