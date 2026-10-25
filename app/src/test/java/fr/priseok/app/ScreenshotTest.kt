package fr.priseok.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import fr.priseok.app.core.Entry
import fr.priseok.app.core.Frequency
import fr.priseok.app.core.Home
import fr.priseok.app.core.Settings
import fr.priseok.app.ui.HomeScreen
import fr.priseok.app.ui.LocalPalette
import fr.priseok.app.ui.Mascot
import fr.priseok.app.ui.Mood
import fr.priseok.app.ui.PermissionsCard
import fr.priseok.app.ui.PriseTheme
import fr.priseok.app.ui.SettingsScreen
import fr.priseok.app.ui.historyItems
import fr.priseok.app.ui.rememberTexts
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Renders each screen state, in English and French, to build/screenshots/<lang>/,
 * so the design can be reviewed without a phone. Also used for the store screenshots.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xxhdpi")
class ScreenshotTest(private val lang: String) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun langs() = listOf(arrayOf("en"), arrayOf("fr"))
    }

    @get:Rule val rule = createComposeRule()

    private val zone = ZoneId.of("Europe/Paris")
    private fun at(d: Int, h: Int, m: Int = 0) = ZonedDateTime.of(LocalDateTime.of(2026, 10, d, h, m), zone)
    private fun ms(z: ZonedDateTime) = z.toInstant().toEpochMilli()
    private val s = Settings(hour = 9, delayMinutes = 120, contactName = "Camille", phone = "0639980000",
        activeSince = ms(at(1, 0)))

    // a realistic week: one forgotten dose on Wednesday
    private val week: Map<Long, Entry> = (18..24).associate { d ->
        val c = ms(at(d, 9))
        c to if (d == 21) Entry(c, smsAt = ms(at(d, 11))) else Entry(c, takenAt = ms(at(d, 9, 7 + d % 5)))
    }

    private fun setLang(dark: Boolean = false) =
        RuntimeEnvironment.setQualifiers("+$lang" + if (dark) "-night" else "")

    private fun home(name: String, now: ZonedDateTime, history: Map<Long, Entry>, celebrate: Int = 0, perms: Boolean = false, dark: Boolean = false) {
        rule.mainClock.autoAdvance = false
        setLang(dark)
        rule.setContent {
            Frame {
                val tx = rememberTexts()
                val h = Home.state(s, history, now)
                HomeScreen(
                    phase = h.phase,
                    text = tx.home(h, s, now),
                    confirmCycle = h.confirmCycle,
                    frequencyText = tx.frequency(s),
                    historyTitle = tx.historyTitle(s.frequency),
                    history = historyItems(s, history, now, tx.locale),
                    celebrate = celebrate,
                    permissions = { if (perms) PermissionsCard(0) {} },
                    onConfirm = {}, onSettings = {},
                )
            }
        }
        rule.mainClock.advanceTimeBy(if (celebrate > 0) 260 else 1200)
        rule.onRoot().captureRoboImage("build/screenshots/$lang/$name.png")
    }

    @Composable
    private fun Frame(content: @Composable () -> Unit) {
        PriseTheme {
            Box(Modifier.fillMaxSize().background(LocalPalette.current.mist).padding(top = 24.dp)) { content() }
        }
    }

    @Test fun home1Due() = home("1_due", at(25, 9, 20), week, perms = true)

    @Test fun home2Taken() {
        val c = ms(at(25, 9))
        home("2_taken", at(25, 9, 21), week + (c to Entry(c, takenAt = ms(at(25, 9, 21)))), celebrate = 1)
    }

    @Test fun home3Missed() {
        val c = ms(at(25, 9))
        home("3_missed", at(25, 11, 30), week + (c to Entry(c, smsAt = ms(at(25, 11)))))
    }

    @Test fun home4Taken() {
        val c = ms(at(25, 9))
        home("4_taken_later", at(25, 16), week + (c to Entry(c, takenAt = ms(at(25, 8, 55)))))
    }

    @Test fun home5DarkDue() = home("5_dark_due", at(25, 9, 20), week, dark = true)

    private fun settings(name: String, initial: Settings, firstRun: Boolean) {
        setLang()
        rule.setContent {
            Frame {
                SettingsScreen(initial, firstRun, onSave = {}, onBack = if (firstRun) null else ({}), onTestReminder = {}, onTestSms = {})
            }
        }
        rule.onRoot().captureRoboImage("build/screenshots/$lang/$name.png")
    }

    @Test fun settings1FirstRun() = settings("6_welcome", Settings(), firstRun = true)

    @Test fun settings2Weekly() = settings(
        "7_settings_weekly",
        s.copy(frequency = Frequency.WEEKLY, dayOfWeek = 3, hour = 20, minute = 30, delayMinutes = 210), firstRun = false,
    )

    /** Pilou alone, celebrating, on a transparent background: the README picture. */
    @Test fun mascot() {
        if (lang != "en") return
        rule.mainClock.autoAdvance = false
        setLang()
        rule.setContent { PriseTheme { Mascot(Mood.HAPPY, celebrate = 1, modifier = Modifier.size(300.dp, 230.dp)) } }
        rule.mainClock.advanceTimeBy(300)
        rule.onRoot().captureRoboImage("build/screenshots/pilou.png")
    }
}
