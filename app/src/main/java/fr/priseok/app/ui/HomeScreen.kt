package fr.priseok.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fr.priseok.app.R
import fr.priseok.app.core.CycleStatus
import fr.priseok.app.core.Phase
import fr.priseok.app.core.Wording
import androidx.compose.ui.res.stringResource
import java.time.ZonedDateTime

data class HistoryItem(val label: String, val status: CycleStatus, val time: ZonedDateTime)

@Composable
fun HomeScreen(
    phase: Phase,
    text: HomeText,
    confirmCycle: Long?,
    frequencyText: String,
    historyTitle: String,
    history: List<HistoryItem>,
    celebrate: Int,
    permissions: @Composable () -> Unit,
    onConfirm: (Long) -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    val mood = when (phase) {
        Phase.NOTHING_DUE -> Mood.SLEEPY
        Phase.EARLY, Phase.DUE -> Mood.WAITING
        Phase.TAKEN -> Mood.HAPPY
        Phase.MISSED -> Mood.WORRIED
    }
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, color = p.pine)
                Text(frequencyText, style = MaterialTheme.typography.bodyMedium, color = p.pineSoft)
            }
            IconButton(onClick = onSettings) {
                Icon(painterResource(R.drawable.ic_settings), contentDescription = stringResource(R.string.settings), tint = p.pine)
            }
        }

        Mascot(
            mood = mood,
            celebrate = celebrate,
            modifier = Modifier.fillMaxWidth().height(250.dp),
            onTap = confirmCycle?.let { c -> { onConfirm(c) } },
        )

        AnimatedContent(
            targetState = text,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            contentKey = { phase },
            label = "status",
        ) { s ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(s.title, style = MaterialTheme.typography.headlineMedium, color = p.pine, textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Text(s.line, style = MaterialTheme.typography.bodyLarge, color = p.pine, textAlign = TextAlign.Center)
                s.hint?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = p.pineSoft, textAlign = TextAlign.Center)
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        val target = confirmCycle
        if (target != null) {
            Button(
                onClick = { onConfirm(target) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = p.pine, contentColor = p.onPine),
            ) {
                Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.confirm_button), style = MaterialTheme.typography.labelLarge)
            }
        }

        permissions()

        if (history.isNotEmpty()) {
            Spacer(Modifier.height(32.dp))
            Text(
                historyTitle, style = MaterialTheme.typography.titleMedium, color = p.pine,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                history.forEach { HistoryPill(it) }
                repeat(7 - history.size) { Spacer(Modifier.width(34.dp)) }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                LegendDot(p.leaf); Text(" " + stringResource(R.string.status_taken), style = MaterialTheme.typography.labelMedium, color = p.pineSoft)
                Spacer(Modifier.width(14.dp))
                LegendDot(p.coral); Text(" " + stringResource(R.string.status_missed), style = MaterialTheme.typography.labelMedium, color = p.pineSoft)
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun HistoryPill(item: HistoryItem) {
    val p = LocalPalette.current
    val what = stringResource(
        when (item.status) {
            CycleStatus.TAKEN -> R.string.status_taken
            CycleStatus.MISSED -> R.string.status_missed
            CycleStatus.PENDING -> R.string.status_pending
        }
    )
    val desc = stringResource(R.string.history_item, item.label, what)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(34.dp).semantics { contentDescription = desc },
    ) {
        Canvas(Modifier.size(width = 16.dp, height = 34.dp)) {
            val r = CornerRadius(size.width / 2, size.width / 2)
            when (item.status) {
                CycleStatus.TAKEN -> drawRoundRect(p.leaf, cornerRadius = r)
                CycleStatus.MISSED -> drawRoundRect(p.coral, cornerRadius = r)
                CycleStatus.PENDING -> {
                    // half-filled capsule: the dose is open
                    drawRoundRect(p.butter, Offset(0f, size.height / 2), Size(size.width, size.height / 2), r)
                    drawRoundRect(p.pine, cornerRadius = r, style = Stroke(2.dp.toPx()))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(item.label, style = MaterialTheme.typography.labelMedium, color = p.pineSoft)
    }
}

@Composable
private fun LegendDot(c: androidx.compose.ui.graphics.Color) {
    Box(Modifier.size(width = 8.dp, height = 14.dp)) {
        Canvas(Modifier.fillMaxSize()) { drawRoundRect(c, cornerRadius = CornerRadius(size.width / 2)) }
    }
}

/** Builds the history row from the schedule. */
fun historyItems(
    s: fr.priseok.app.core.Settings,
    history: Map<Long, fr.priseok.app.core.Entry>,
    now: ZonedDateTime,
    locale: java.util.Locale,
): List<HistoryItem> =
    fr.priseok.app.core.Schedule.recent(s, now, 7).map { t ->
        val key = t.toInstant().toEpochMilli()
        HistoryItem(
            Wording.historyLabel(s.frequency, t, locale),
            fr.priseok.app.core.Schedule.status(s, key, history[key], now),
            t,
        )
    }
