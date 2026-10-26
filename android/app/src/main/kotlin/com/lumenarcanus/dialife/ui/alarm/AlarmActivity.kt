package com.lumenarcanus.dialife.ui.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.alarmController
import com.lumenarcanus.dialife.data.AlarmView
import com.lumenarcanus.dialife.live.ActionReceiver
import com.lumenarcanus.dialife.live.AlarmText
import com.lumenarcanus.dialife.liveRepository
import com.lumenarcanus.dialife.ui.home.unitsOf
import com.lumenarcanus.dialife.ui.theme.DiaLifeTheme
import com.lumenarcanus.dialife.ui.theme.DiaTheme
import com.lumenarcanus.dialife.ui.theme.GlucoseHero
import kotlinx.coroutines.launch

/**
 * Full screen over the lock screen: the sensor alarm (red) or a help request
 * (orange, so it is never mistaken for the sensor). It closes itself when the
 * episode no longer needs this phone: someone took it, it ended, help is coming.
 */
class AlarmActivity : AppCompatActivity() {
    private val close = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getLongExtra(EXTRA_ID, 0) == alarmId) finish()
        }
    }
    private var alarmId = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        alarmId = intent.getLongExtra(EXTRA_ID, 0)
        val help = intent.getBooleanExtra(EXTRA_HELP, false)
        registerReceiver(close, IntentFilter(ACTION_CLOSE), RECEIVER_NOT_EXPORTED)
        setContent {
            DiaLifeTheme(dark = true) {
                val live by liveRepository.live.collectAsStateWithLifecycle()
                val alarm = live.state?.alarms?.firstOrNull { it.id == alarmId }
                val stillMine = when {
                    alarm == null -> live.state == null // not loaded yet: keep showing
                    help -> alarm.helpAt != null && alarm.comingAt == null
                    else -> alarm.state == "firing"
                }
                LaunchedEffect(stillMine) { if (!stillMine) finish() }
                alarm?.let {
                    AlarmScreen(
                        alarm = it,
                        help = help,
                        state = live.state,
                        current = live.state?.bg?.sgv,
                        units = unitsOf(live.state?.displayUnits),
                        onAct = { action -> alarmController.act(action, alarmId) },
                        onDone = ::finish,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate() // another alarm took the screen
    }

    override fun onDestroy() {
        unregisterReceiver(close)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ID = "alarm_id"
        const val EXTRA_HELP = "help"
        const val ACTION_CLOSE = "com.lumenarcanus.dialife.CLOSE_ALARM"

        fun intent(context: Context, alarmId: Long, help: Boolean): Intent =
            Intent(context, AlarmActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
                .putExtra(EXTRA_ID, alarmId)
                .putExtra(EXTRA_HELP, help)
    }
}

@Composable
private fun AlarmScreen(
    alarm: AlarmView,
    help: Boolean,
    state: com.lumenarcanus.dialife.data.StateResponse?,
    current: Int?,
    units: com.lumenarcanus.dialife.ui.home.Units,
    onAct: suspend (String) -> Result<Unit>,
    onDone: () -> Unit,
) {
    val c = DiaTheme.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val accent = if (help) c.high else c.urgent
    val background = if (help) Color(0xFF241605) else c.urgentBackground
    val locale = ctx.resources.configuration.locales[0]

    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    fun act(action: String) {
        if (busy) return
        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.Confirm)
        busy = true
        failed = false
        scope.launch {
            onAct(action).onSuccess {
                // took it: open "you are handling" (after unlock)
                if (action == ActionReceiver.HANDLE || action == ActionReceiver.QUIET) {
                    ctx.startActivity(
                        android.content.Intent(ctx, com.lumenarcanus.dialife.MainActivity::class.java)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            .putExtra(com.lumenarcanus.dialife.MainActivity.EXTRA_HANDLING, alarm.id),
                    )
                }
                onDone()
            }.onFailure { failed = true }
            busy = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(background)
            .safeDrawingPadding()
            .padding(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // the details scroll; the buttons below never do, whatever the font size
        Column(
            Modifier.weight(1f).verticalScroll(androidx.compose.foundation.rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
        Text(
            if (help) stringResource(R.string.help_title, alarm.handledBy.orEmpty()) else AlarmText.kind(ctx, alarm),
            color = accent, fontSize = 26.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
        )
        val sgv = current ?: alarm.sgv
        if (sgv != null && alarm.kind != "stale") {
            // the number in a ring of the alarm's colour: readable from across the room
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(Modifier.size(250.dp), contentAlignment = Alignment.Center) {
                    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                        drawCircle(accent.copy(alpha = 0.18f), radius = size.minDimension / 2)
                        drawCircle(accent, radius = size.minDimension / 2 - 5.dp.toPx(), style = androidx.compose.ui.graphics.drawscope.Stroke(8.dp.toPx()))
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        // fits the ring even with the largest system font
                        androidx.compose.foundation.text.BasicText(
                            com.lumenarcanus.dialife.ui.home.formatGlucose(sgv, units, locale),
                            style = GlucoseHero.copy(fontSize = 92.sp, lineHeight = 92.sp, color = c.text, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                            maxLines = 1,
                            autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 40.sp, maxFontSize = 92.sp, stepSize = 4.sp),
                            modifier = Modifier.width(190.dp),
                        )
                        val bg = state?.bg
                        Text(
                            listOfNotNull(
                                com.lumenarcanus.dialife.live.Notifications.arrowOf(bg?.direction).ifBlank { null },
                                bg?.delta?.let { com.lumenarcanus.dialife.ui.home.formatDelta(it, units, locale) },
                                stringResource(if (units == com.lumenarcanus.dialife.ui.home.Units.MgDl) R.string.unit_mgdl else R.string.unit_mmol),
                            ).joinToString("  "),
                            color = c.textMuted, fontSize = 18.sp, maxLines = 1,
                            modifier = Modifier.width(170.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            state?.ar2?.predicted?.lastOrNull()?.let {
                Text(stringResource(R.string.forecast_30, com.lumenarcanus.dialife.ui.home.formatGlucose(it.sgv, units, locale)), color = c.textMuted, fontSize = 16.sp, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
        Text(
            if (help) {
                listOfNotNull(
                    alarm.handledAt?.let { stringResource(R.string.help_handling_since, alarm.handledBy.orEmpty(), AlarmText.clock(ctx, it)) },
                    AlarmText.progress(ctx, alarm, units).takeIf { it.isNotBlank() },
                ).joinToString(" · ")
            } else {
                AlarmText.reason(ctx, null, alarm)
            },
            color = c.text, fontSize = 17.sp,
        )
        // who else is woken right now
        if (!help) {
            val names = state?.caregivers.orEmpty().map { it.name }
            if (names.isNotEmpty()) {
                Text(stringResource(R.string.al_notified, names.joinToString(", ")), color = c.textMuted, fontSize = 15.sp)
            }
        }
        if (help) {
            val live by ctx.liveRepository.live.collectAsStateWithLifecycle()
            val from = (alarm.handledAt ?: alarm.startedAt) - 15 * 60_000L
            val now = System.currentTimeMillis()
            com.lumenarcanus.dialife.ui.home.GlucoseChart(
                history = live.history, forecast = state?.ar2, thresholds = state?.thresholds ?: com.lumenarcanus.dialife.data.Thresholds(), units = units,
                rangeHours = 3, now = now,
                window = com.lumenarcanus.dialife.ui.home.ChartWindow(from, maxOf(now + 30 * 60_000L, from + 3_600_000L), followsNow = true),
                modifier = Modifier.fillMaxWidth().height(170.dp),
            )
        }
        }
        if (failed) {
            Text(stringResource(R.string.action_failed_body), color = c.high, fontSize = 15.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        TextButton(
            onClick = {
                ctx.startActivity(
                    android.content.Intent(ctx, com.lumenarcanus.dialife.MainActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.al_open_chart), color = c.textMuted, fontSize = 15.sp) }
        if (help) {
            BigButton(stringResource(R.string.act_coming), accent, c.background, busy) { act(ActionReceiver.COMING) }
        } else {
            BigButton(stringResource(R.string.act_handle), accent, Color.White, busy) { act(ActionReceiver.HANDLE) }
            if (!alarm.urgentLow) {
                OutlinedButton(
                    onClick = { act(ActionReceiver.QUIET) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(18.dp),
                ) { Text(stringResource(R.string.act_quiet), color = c.text, fontSize = 16.sp) }
            }
        }
    }
}

@Composable
private fun BigButton(text: String, container: Color, content: Color, busy: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth().height(72.dp),
        shape = RoundedCornerShape(22.dp),
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
    ) {
        Text(text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
}
