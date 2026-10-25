package fr.priseok.app

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import fr.priseok.app.core.Schedule
import java.time.ZoneId

object Sms {
    const val EXTRA_TEST = "test"
    const val EXTRA_CONTACT = "contact"

    fun canSend(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    private fun manager(ctx: Context): SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ctx.getSystemService(SmsManager::class.java)
        else @Suppress("DEPRECATION") SmsManager.getDefault()

    /**
     * Texts [contactId] about the dose at [cycle], once, if a medication of theirs in it is still
     * unconfirmed. Names those medications when they have names.
     */
    fun sendAlert(ctx: Context, cycle: Long, contactId: Long) {
        val store = Store(ctx)
        val s = store.settings()
        val contact = s.contact(contactId) ?: return
        val e = store.history()[cycle]
        if (e?.sms?.containsKey(contactId) == true) return
        val dose = Schedule.doseAt(s, cycle, ZoneId.systemDefault()) ?: return
        val open = dose.meds.filter { contactId in it.contactIds && e?.isTaken(it.id) != true }
        if (open.isEmpty()) return
        val names = open.map { it.name.trim() }.filter { it.isNotEmpty() }.joinToString(", ")
        val text = if (names.isEmpty()) ctx.getString(R.string.sms_alert) else ctx.getString(R.string.sms_alert_named, names)
        // Recorded before sending, so a crash can never lead to a second text.
        store.recordSms(cycle, contactId, System.currentTimeMillis())
        val ok = send(ctx, contact.phone, text, cycle, contactId, test = false)
        if (!ok) store.recordSmsFailed(cycle, contactId)
        else Notifier.showReminder(ctx, cycle, late = true)
    }

    fun sendTest(ctx: Context, phone: String): Boolean = send(ctx, phone, ctx.getString(R.string.sms_test), 0L, 0L, test = true)

    /** One request code per dose and contact, so texts sent together each report their own result. */
    private fun resultCode(cycle: Long, contactId: Long, test: Boolean): Int =
        if (test) 21 else 30_000_000 + (Alarms.confirmCode(cycle) % 1_000_000) * 10 + (contactId % 10).toInt()

    private fun send(ctx: Context, phone: String, text: String, cycle: Long, contactId: Long, test: Boolean): Boolean {
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
                ctx, resultCode(cycle, contactId, test),
                Intent(ctx, SmsResultReceiver::class.java)
                    .putExtra(Alarms.EXTRA_CYCLE, cycle)
                    .putExtra(EXTRA_CONTACT, contactId)
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
