package fr.priseok.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import fr.priseok.app.Alarms
import fr.priseok.app.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource

data class Need(val title: Int, val why: Int, val ask: () -> Unit)

fun hasPermission(ctx: Context, p: String) =
    ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

fun ignoresBattery(ctx: Context) =
    ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

/** Lists what is still missing for the reminders and the SMS to work. Hidden when all is granted. */
@SuppressLint("BatteryLife") // a medication alert is exactly the case this permission exists for
@Composable
fun PermissionsCard(refreshKey: Int, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    val askOne = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onChanged() }
    val openSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { onChanged() }

    @Suppress("UNUSED_EXPRESSION") refreshKey // re-evaluate after each change
    val needs = buildList {
        if (Build.VERSION.SDK_INT >= 33 && !hasPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)) {
            add(Need(R.string.perm_notif_title, R.string.perm_notif_why) {
                askOne.launch(Manifest.permission.POST_NOTIFICATIONS)
            })
        }
        if (!hasPermission(ctx, Manifest.permission.SEND_SMS)) {
            add(Need(R.string.perm_sms_title, R.string.perm_sms_why) {
                askOne.launch(Manifest.permission.SEND_SMS)
            })
        }
        if (!Alarms.canScheduleExact(ctx) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Need(R.string.perm_exact_title, R.string.perm_exact_why) {
                openSettings.launch(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")))
            })
        }
        if (!ignoresBattery(ctx)) {
            add(Need(R.string.perm_battery_title, R.string.perm_battery_why) {
                openSettings.launch(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                )
            })
        }
    }
    if (needs.isEmpty()) return

    Spacer(Modifier.height(28.dp))
    Column(
        Modifier
            .fillMaxWidth()
            .border(BorderStroke(1.5.dp, p.butter), RoundedCornerShape(24.dp))
            .padding(20.dp),
    ) {
        Text(
            pluralStringResource(R.plurals.perm_missing, needs.size, needs.size),
            style = MaterialTheme.typography.titleMedium, color = p.pine,
        )
        needs.forEach { n ->
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(n.title), style = MaterialTheme.typography.bodyLarge, color = p.pine)
                    Text(stringResource(n.why), style = MaterialTheme.typography.bodyMedium, color = p.pineSoft)
                }
                Spacer(Modifier.width(12.dp))
                OutlinedButton(onClick = n.ask, shape = CircleShape, border = BorderStroke(1.5.dp, p.pine)) {
                    Text(stringResource(R.string.perm_allow), color = p.pine)
                }
            }
        }
    }
}
