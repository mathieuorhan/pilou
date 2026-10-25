package fr.priseok.app

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat

object Sms {
    const val EXTRA_TEST = "test"

    fun canSend(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    private fun manager(ctx: Context): SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ctx.getSystemService(SmsManager::class.java)
        else @Suppress("DEPRECATION") SmsManager.getDefault()

    /** Sends the alert for [cycle] once. Records the attempt so it is never sent twice. */
    fun sendAlert(ctx: Context, cycle: Long) {
        val store = Store(ctx)
        val e = store.history()[cycle]
        if (e?.takenAt != null || e?.smsAt != null) return
        val ok = send(ctx, store.settings().phone, ctx.getString(R.string.sms_alert), cycle, test = false)
        store.update(cycle) { it.copy(smsAt = System.currentTimeMillis(), smsFailed = !ok) }
        if (ok) Notifier.showReminder(ctx, cycle, late = true)
    }

    fun sendTest(ctx: Context, phone: String): Boolean = send(ctx, phone, ctx.getString(R.string.sms_test), 0L, test = true)

    private fun send(ctx: Context, phone: String, text: String, cycle: Long, test: Boolean): Boolean {
        if (phone.isBlank()) {
            Notifier.info(ctx, R.string.sms_not_sent, ctx.getString(R.string.sms_no_number))
            return false
        }
        if (!canSend(ctx)) {
            Notifier.info(ctx, R.string.sms_not_sent, ctx.getString(R.string.sms_no_permission))
            return false
        }
        return try {
            val sent = PendingIntent.getBroadcast(
                ctx, if (test) 21 else 20,
                Intent(ctx, SmsResultReceiver::class.java)
                    .putExtra(Alarms.EXTRA_CYCLE, cycle)
                    .putExtra(EXTRA_TEST, test),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val sm = manager(ctx)
            // 70 characters always fit in one SMS, even with accents (UCS-2 encoding).
            if (text.length <= 70) sm.sendTextMessage(phone, null, text, sent, null)
            else {
                val parts = sm.divideMessage(text)
                sm.sendMultipartTextMessage(phone, null, parts, ArrayList(List(parts.size) { sent }), null)
            }
            true
        } catch (t: Throwable) {
            Notifier.info(ctx, R.string.sms_not_sent, ctx.getString(R.string.sms_refused, t.javaClass.simpleName))
            false
        }
    }
}
