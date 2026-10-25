package fr.priseok.app

import android.Manifest
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import fr.priseok.app.core.Home
import fr.priseok.app.core.Settings
import fr.priseok.app.ui.HomeScreen
import fr.priseok.app.ui.LocalPalette
import fr.priseok.app.ui.PermissionsCard
import fr.priseok.app.ui.PriseTheme
import fr.priseok.app.ui.SettingsScreen
import fr.priseok.app.ui.hasPermission
import fr.priseok.app.ui.historyItems
import fr.priseok.app.ui.rememberTexts
import kotlinx.coroutines.delay
import java.time.ZonedDateTime

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Notifier.ensureChannels(this)
        Alarms.reschedule(this)
        setContent { PriseTheme { App() } }
    }
}

@Composable
private fun App() {
    val ctx = LocalContext.current
    val store = remember { Store(ctx) }
    var tick by remember { mutableIntStateOf(0) }
    var celebrate by remember { mutableIntStateOf(0) }

    // Refresh on any stored change (e.g. the notification button), on resume, and every 30 s.
    DisposableEffect(store) {
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> tick++ }
        store.prefs.registerOnSharedPreferenceChangeListener(l)
        onDispose { store.prefs.unregisterOnSharedPreferenceChangeListener(l) }
    }
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
    LaunchedEffect(Unit) { while (true) { delay(30_000); tick++ } }

    @Suppress("UNUSED_EXPRESSION") tick
    val settings = store.settings()
    var screen by rememberSaveable { mutableStateOf(if (settings.isConfigured) "home" else "settings") }

    // Test actions may need a permission first; run them once it is granted.
    var pendingTest by remember { mutableStateOf<(() -> Unit)?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) pendingTest?.invoke()
        else Toast.makeText(ctx, R.string.toast_denied, Toast.LENGTH_LONG).show()
        pendingTest = null
        tick++
    }
    fun withPermission(perm: String, action: () -> Unit) {
        if (hasPermission(ctx, perm)) action() else { pendingTest = action; ask.launch(perm) }
    }

    Box(Modifier.fillMaxSize().background(LocalPalette.current.mist).safeDrawingPadding()) {
        if (screen == "settings") {
            val back: (() -> Unit)? = if (settings.isConfigured) ({ screen = "home" }) else null
            BackHandler(enabled = back != null) { back?.invoke() }
            SettingsScreen(
                initial = settings,
                firstRun = !settings.isConfigured,
                onSave = { s: Settings ->
                    store.saveSettings(s)
                    Alarms.reschedule(ctx)
                    screen = "home"
                },
                onBack = back,
                onTestReminder = {
                    val run = { Notifier.showReminder(ctx, cycle = -1L) }
                    if (Build.VERSION.SDK_INT >= 33) withPermission(Manifest.permission.POST_NOTIFICATIONS, run) else run()
                },
                onTestSms = { phone ->
                    withPermission(Manifest.permission.SEND_SMS) {
                        if (Sms.sendTest(ctx, phone)) Toast.makeText(ctx, ctx.getString(R.string.toast_test_sms, phone), Toast.LENGTH_SHORT).show()
                    }
                },
            )
        } else {
            val now = ZonedDateTime.now()
            val history = store.history()
            val tx = rememberTexts()
            val home = Home.state(settings, history, now)
            HomeScreen(
                phase = home.phase,
                text = tx.home(home, settings, now),
                confirmCycle = home.confirmCycle,
                frequencyText = tx.frequency(settings),
                historyTitle = tx.historyTitle(settings.frequency),
                history = historyItems(settings, history, now, tx.locale),
                celebrate = celebrate,
                permissions = { PermissionsCard(refreshKey = tick, onChanged = { tick++; Alarms.reschedule(ctx) }) },
                onConfirm = { cycle ->
                    store.markTaken(cycle, System.currentTimeMillis())
                    Notifier.cancelReminder(ctx)
                    Alarms.reschedule(ctx)
                    celebrate++
                },
                onSettings = { screen = "settings" },
            )
        }
    }
}
