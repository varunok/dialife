package com.lumenarcanus.dialife.feature.alarms

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.core.ui.BannerButton
import com.lumenarcanus.dialife.core.ui.formatGlucose
import com.lumenarcanus.dialife.data.AlarmView
import com.lumenarcanus.dialife.live.ActionReceiver
import com.lumenarcanus.dialife.live.AlarmText
import com.lumenarcanus.dialife.ui.theme.DiaTheme

/** A live episode on the home screen: what it is, who has it, and the one action that fits. */
@Composable
internal fun EpisodeBanner(a: AlarmView, ui: AlarmsUiState, onAction: (String, Long) -> Unit, onOpen: (Long) -> Unit) {
    val units = ui.units
    val c = DiaTheme.colors
    val ctx = LocalContext.current
    val myId = ui.myId
    val mine = a.handledById != 0L && a.handledById == myId
    val helpAsked = a.state == "handled" && !mine && a.helpAt != null && a.comingAt == null
    val (container, accent) = when {
        helpAsked -> c.highContainer to c.high
        a.state == "firing" && a.level == "urgent" -> c.urgentBackground to c.urgent
        a.state == "firing" -> (if (a.kind.endsWith("high")) c.highContainer else c.lowContainer) to
            (if (a.kind.endsWith("high")) c.high else c.low)
        else -> c.surface to c.text
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(container, RoundedCornerShape(18.dp))
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
            .padding(16.dp)
            .semantics(mergeDescendants = false) {},
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val title = when {
            a.state == "firing" -> AlarmText.title(ctx, a, units)
            helpAsked -> stringResource(R.string.help_title, a.handledBy.orEmpty())
            mine -> stringResource(R.string.you_handle, AlarmText.kind(ctx, a))
            else -> stringResource(R.string.someone_handles_kind, a.handledBy.orEmpty(), AlarmText.kind(ctx, a))
        }
        Text(title, color = accent, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        val details = if (a.state == "firing") AlarmText.reason(ctx, null, a) else AlarmText.progress(ctx, a, units)
        if (details.isNotBlank()) Text(details, color = c.textMuted, fontSize = 14.sp)
        when {
            a.comingById != 0L && a.comingById == myId -> Text(stringResource(R.string.you_are_coming), color = c.text, fontSize = 14.sp)
            !a.comingBy.isNullOrBlank() -> Text(stringResource(R.string.someone_coming, a.comingBy), color = c.text, fontSize = 14.sp)
        }
        a.quietUntil?.let {
            Text(stringResource(R.string.quiet_until_short, AlarmText.clock(ctx, it)), color = c.textMuted, fontSize = 14.sp)
        }
        // someone else has it: say exactly when this phone would ring again
        if (a.state == "handled" && !mine) {
            val rules = ui.episode ?: com.lumenarcanus.dialife.data.EpisodeRules()
            val locale = ctx.resources.configuration.locales[0]
            val reasons = buildList {
                if (a.handledSgv != null && (a.kind == "low" || a.kind == "high")) {
                    val limit = if (a.kind == "low") a.handledSgv - rules.worseBy else a.handledSgv + rules.worseBy
                    add(
                        stringResource(
                            if (a.kind ==
                                "low"
                            ) {
                                R.string.hd_wake_worse_low
                            } else {
                                R.string.hd_wake_worse_high
                            },
                            formatGlucose(limit, units, locale)
                        )
                    )
                }
                add(stringResource(R.string.wake_if_no_answer, a.handledBy.orEmpty()))
                if (a.level == "urgent" &&
                    a.handledAt != null
                ) {
                    add(
                        stringResource(
                            R.string.wake_if_ceiling,
                            AlarmText.clock(
                                ctx,
                                a.handledAt + rules.ceilingMin * 60_000L
                            )
                        )
                    )
                }
            }
            Text(stringResource(R.string.wake_if_title) + " " + reasons.joinToString("; "), color = c.textMuted, fontSize = 13.sp)
        }
        when {
            a.state == "firing" -> {
                BannerButton(stringResource(R.string.act_handle), accent, c.background) { onAction(ActionReceiver.HANDLE, a.id) }
                // "the receiver works": a stale or non-urgent alarm can be quieted for 2 h
                if (!a.urgentLow) {
                    BannerButton(stringResource(R.string.act_quiet), c.surfaceRaised, c.text) {
                        onAction(ActionReceiver.QUIET, a.id)
                    }
                }
            }
            mine -> {
                if (a.checkinPending) BannerButton(stringResource(R.string.checkin_title), c.high, c.background) { onOpen(a.id) }
                BannerButton(stringResource(R.string.hd_open), c.surfaceRaised, c.text) { onOpen(a.id) }
            }
            a.comingById != myId -> BannerButton(stringResource(R.string.act_coming), c.surfaceRaised, c.text) {
                onAction(ActionReceiver.COMING, a.id)
            }
        }
    }
}
