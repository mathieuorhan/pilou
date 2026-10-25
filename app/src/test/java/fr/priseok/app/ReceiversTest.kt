package fr.priseok.app

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.telephony.SmsManager
import androidx.test.core.app.ApplicationProvider
import fr.priseok.app.core.Contact
import fr.priseok.app.core.Med
import fr.priseok.app.core.Schedule
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
        save(activeSince = System.currentTimeMillis())
    }

    private val camille = Contact(1L, "Camille", "+33639980000")

    private fun save(activeSince: Long, vararg extra: Med) = store.saveSettings(Settings(
        listOf(Med(id = 1L, name = "Kardegic", contactIds = setOf(1L), activeSince = activeSince)) + extra,
        listOf(camille),
    ))

    /** Today's 9h00 dose, from a schedule that counts it (it may be in the past or future). */
    private fun todayDose(vararg extra: Med): Long {
        save(activeSince = 0L, *extra)
        val now = java.time.ZonedDateTime.now()
        return Schedule.dosesOn(store.settings(), now.toLocalDate(), now.zone).single().cycle
    }

    private fun fire(action: String, cycle: Long) =
        ReminderReceiver().onReceive(app, Intent(action).putExtra(Alarms.EXTRA_CYCLE, cycle))

    private fun sms() = shadowOf(app.getSystemService(SmsManager::class.java))
    private fun lastSms() = sms().lastSentTextMessageParams
    private fun notification(cycle: Long) =
        shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(Notifier.reminderId(cycle))

    @Test fun alertIsSentOnceAndNamesTheMedication() {
        val cycle = todayDose()
        Sms.sendAlert(app, cycle, 1L)
        assertEquals("+33639980000", lastSms().destinationAddress)
        assertEquals("Warning! Your contact hasn't taken: Kardegic.", lastSms().text)
        assertNotNull(store.history()[cycle]?.sms?.get(1L))

        sms().clearLastSentTextMessageParams()
        Sms.sendAlert(app, cycle, 1L)
        assertNull("no second SMS for the same dose", lastSms())
    }

    @Test fun unnamedMedicationGetsTheGenericText() {
        val cycle = todayDose()
        store.saveSettings(store.settings().let { st -> st.copy(meds = st.meds.map { it.copy(name = "") }) })
        Sms.sendAlert(app, cycle, 1L)
        assertEquals("Warning! Your contact hasn't taken their medication.", lastSms().text)
    }

    @Test fun notificationButtonPreventsTheSms() {
        val cycle = todayDose()
        fire(Alarms.ACTION_CONFIRM, cycle)
        assertNotNull(store.history()[cycle]?.takenAt)
        Sms.sendAlert(app, cycle, 1L)
        assertNull(lastSms())
    }

    @Test fun undoFromTheNotificationReopensTheDose() {
        val cycle = todayDose()
        fire(Alarms.ACTION_CONFIRM, cycle)
        assertEquals("Undo", notification(cycle)!!.actions.single().title)
        fire(Alarms.ACTION_UNDO, cycle)
        // Not taken any more (a text may go out right away if its time has passed).
        assertNull(store.history()[cycle]?.takenAt)
        assertEquals("Did you take your medication?", shadowOf(notification(cycle)).contentTitle)
    }

    @Test fun snoozeHidesTheReminderAndSetsAnAlarm() {
        val cycle = todayDose()
        fire(Alarms.ACTION_REMIND, cycle)
        fire(Alarms.ACTION_SNOOZE, cycle)
        assertNull(notification(cycle))
        val soon = System.currentTimeMillis() + Alarms.SNOOZE_MINUTES * 60_000
        assertTrue(shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms.any { Math.abs(it.triggerAtTime - soon) < 5_000 })
    }

    @Test fun reminderShowsSilentNotificationWithButtons() {
        val cycle = todayDose()
        fire(Alarms.ACTION_REMIND, cycle)
        val nm = app.getSystemService(NotificationManager::class.java)
        val n = notification(cycle)
        assertNotNull(n)
        assertEquals("Did you take your medication?", shadowOf(n).contentTitle)
        assertEquals(listOf("Yes, I took it", "In 15 min"), n.actions.map { it.title.toString() })
        val channel = nm.getNotificationChannel(n.channelId)
        assertNull("silent channel", channel.sound)
        assertTrue(!channel.shouldVibrate())
    }

    @Test fun reminderNamesTheMedicationsOfTheDose() {
        val cycle = todayDose(Med(id = 2L, name = "Levothyrox", activeSince = 0L))
        fire(Alarms.ACTION_REMIND, cycle)
        assertEquals("Kardegic, Levothyrox", shadowOf(notification(cycle)).contentText)
        // Once Kardegic is confirmed alone, only Levothyrox is left.
        store.markMedTaken(cycle, 1L, cycle)
        fire(Alarms.ACTION_REMIND, cycle)
        assertEquals("Levothyrox", shadowOf(notification(cycle)).contentText)
    }

    @Test fun reminderIsSkippedIfAlreadyTakenEarly() {
        val cycle = todayDose()
        store.markTaken(cycle, cycle - hour)
        fire(Alarms.ACTION_REMIND, cycle)
        assertNull(notification(cycle))
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
        Sms.sendAlert(app, todayDose(), 1L)
        assertEquals("Attention ! Ton contact n'a pas pris : Kardegic.", lastSms().text)
    }

    @Test fun noSmsWithoutPermission() {
        shadowOf(app).denyPermissions(Manifest.permission.SEND_SMS)
        Sms.sendAlert(app, todayDose(), 1L)
        assertNull(lastSms())
    }
}
