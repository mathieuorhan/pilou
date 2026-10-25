package fr.priseok.app

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.telephony.SmsManager
import androidx.test.core.app.ApplicationProvider
import fr.priseok.app.core.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Runs the real receivers on a simulated Android, with fake SMS, alarms and notifications. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReceiversTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val store get() = Store(app)
    private val hour = 3_600_000L

    @Before fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.SEND_SMS, Manifest.permission.POST_NOTIFICATIONS)
        store.prefs.edit().clear().commit()
        // Active from now: the schedule's own past doses are ignored, so rescheduling never sends
        // a late SMS for them, whatever the time of day the tests run at.
        store.saveSettings(Settings(hour = 9, delayMinutes = 120, contactName = "Camille", phone = "+33639980000",
            activeSince = System.currentTimeMillis()))
    }

    private fun fire(action: String, cycle: Long) =
        ReminderReceiver().onReceive(app, Intent(action).putExtra(Alarms.EXTRA_CYCLE, cycle))

    private fun sms() = shadowOf(app.getSystemService(SmsManager::class.java))
    private fun lastSms() = sms().lastSentTextMessageParams

    @Test fun deadlineSendsTheSmsOnce() {
        val cycle = System.currentTimeMillis() - 3 * hour
        fire(Alarms.ACTION_DEADLINE, cycle)
        assertEquals("+33639980000", lastSms().destinationAddress)
        assertEquals("Warning! Your contact hasn't taken their medication.", lastSms().text)
        assertNotNull(store.history()[cycle]?.smsAt)

        sms().clearLastSentTextMessageParams()
        fire(Alarms.ACTION_DEADLINE, cycle)
        assertNull("no second SMS for the same dose", lastSms())
    }

    @Test fun notificationButtonPreventsTheSms() {
        val cycle = System.currentTimeMillis() - hour
        fire(Alarms.ACTION_CONFIRM, cycle)
        assertNotNull(store.history()[cycle]?.takenAt)
        fire(Alarms.ACTION_DEADLINE, cycle)
        assertNull(lastSms())
    }

    @Test fun reminderShowsSilentNotificationWithButton() {
        val cycle = System.currentTimeMillis()
        fire(Alarms.ACTION_REMIND, cycle)
        val nm = app.getSystemService(NotificationManager::class.java)
        val n = shadowOf(nm).getNotification(Notifier.ID_REMINDER)
        assertNotNull(n)
        assertEquals("Did you take your medication?", shadowOf(n).contentTitle)
        assertEquals("Yes, I took it", n.actions.single().title)
        val channel = nm.getNotificationChannel(n.channelId)
        assertNull("silent channel", channel.sound)
        assertTrue(!channel.shouldVibrate())
    }

    @Test fun reminderIsSkippedIfAlreadyTakenEarly() {
        val cycle = System.currentTimeMillis()
        store.markTaken(cycle, cycle - hour)
        fire(Alarms.ACTION_REMIND, cycle)
        assertNull(shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(Notifier.ID_REMINDER))
    }

    @Test fun rebootSetsTheNextReminder() {
        SystemEventsReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        val alarm = shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms
            .minByOrNull { it.triggerAtTime }
        assertNotNull(alarm)
        assertTrue(alarm!!.triggerAtTime > System.currentTimeMillis())
    }

    @Test fun otherActionsOnTheExportedReceiverAreIgnored() {
        SystemEventsReceiver().onReceive(app, Intent("com.example.SPOOF"))
        assertTrue(shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms.isEmpty())
    }

    @Test @Config(qualifiers = "fr")
    fun smsIsInThePhoneLanguage() {
        fire(Alarms.ACTION_DEADLINE, System.currentTimeMillis() - 3 * hour)
        assertEquals("Attention ! Ton contact n'a pas pris son médicament.", lastSms().text)
    }

    @Test fun noSmsWithoutPermission() {
        shadowOf(app).denyPermissions(Manifest.permission.SEND_SMS)
        fire(Alarms.ACTION_DEADLINE, System.currentTimeMillis() - 3 * hour)
        assertNull(lastSms())
    }
}

