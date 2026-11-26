package com.lumenarcanus.dialife.feature.sensorless

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.core.info.InfoButton
import com.lumenarcanus.dialife.core.ui.BannerButton
import com.lumenarcanus.dialife.core.ui.Units
import com.lumenarcanus.dialife.core.ui.formatAmount
import com.lumenarcanus.dialife.core.ui.formatClock
import com.lumenarcanus.dialife.core.ui.formatGlucose
import com.lumenarcanus.dialife.core.ui.theme.DiaTheme
import com.lumenarcanus.dialife.data.CheckCard
import com.lumenarcanus.dialife.data.Iob
import com.lumenarcanus.dialife.data.Sensorless
import com.lumenarcanus.dialife.feature.entry.gapText
import com.lumenarcanus.dialife.liveRepository

/** The mode's cards on home, with their own state and actions; [onMeasure] opens the glucometer entry. */
@Composable
fun SensorlessSection(onMeasure: () -> Unit) {
    val ctx = LocalContext.current
    val vm = viewModel { SensorlessViewModel(ctx.liveRepository, DefaultSensorlessRepository({ ctx.liveRepository.credentials })) }
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    uiState.userMessage?.let { message ->
        LaunchedEffect(message) {
            Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
            vm.userMessageShown()
        }
    }
    SensorlessCards(uiState, onClose = vm::closeCard, onMeasure = onMeasure)
}

/** Only draws: a check out of range first, then the reminder to measure. */
@Composable
fun SensorlessCards(uiState: SensorlessUiState, onClose: (Long) -> Unit, onMeasure: () -> Unit) {
    uiState.card?.let { CheckCardView(it, uiState.units) { onClose(it.t) } }
    uiState.remind?.let { RemindCard(it, onMeasure) }
}

/** A glucometer check below the low or above the high threshold: bright, as no alarm will speak for it. */
@Composable
fun CheckCardView(card: CheckCard, units: Units, onClose: () -> Unit) {
    val c = DiaTheme.colors
    val ctx = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val low = card.kind == "low"
    val (ink, fill) = if (low) c.low to c.lowContainer else c.high to c.highContainer
    val value = formatGlucose(card.mg, units, locale)
    val unit = stringResource(if (units == Units.MgDl) R.string.unit_mgdl else R.string.unit_mmol)
    Column(
        Modifier.fillMaxWidth().background(fill, RoundedCornerShape(18.dp)).border(1.5.dp, ink, RoundedCornerShape(18.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            stringResource(if (low) R.string.sensorless_card_low else R.string.sensorless_card_high, value, unit),
            color = ink,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(stringResource(R.string.sensorless_card_text, formatClock(ctx, card.t)), color = c.text, fontSize = 14.sp)
        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(R.string.sensorless_card_close), color = c.text, maxLines = 1)
        }
    }
}

/** Long without a check: measure. Amber and bordered; it goes when a check is logged. */
@Composable
fun RemindCard(remind: Remind, onMeasure: () -> Unit) {
    val c = DiaTheme.colors
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxWidth().background(c.highContainer, RoundedCornerShape(18.dp)).border(1.5.dp, c.high, RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(stringResource(R.string.sensorless_remind_title), color = c.high, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text(
            remind.sinceCheckMs?.let { stringResource(R.string.sensorless_remind_text, gapText(ctx, it)) }
                ?: stringResource(R.string.sensorless_remind_none),
            color = c.text,
            fontSize = 14.sp
        )
        BannerButton(stringResource(R.string.sensorless_measure), c.high, c.background, onMeasure)
    }
}

/**
 * In place of the reading while the mode is on: calm, not the amber of missing
 * data. The newest glucometer check, since when the alarms are off, when the
 * reminder comes, and what insulin and carbs are still acting.
 */
@Composable
fun SensorlessHero(s: Sensorless, units: Units, now: Long, iob: Iob?) {
    val c = DiaTheme.colors
    val ctx = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val unit = stringResource(if (units == Units.MgDl) R.string.unit_mgdl else R.string.unit_mmol)
    Column(
        Modifier.fillMaxWidth().background(c.sensorlessContainer, RoundedCornerShape(20.dp))
            .border(1.dp, c.sensorless.copy(alpha = 0.6f), RoundedCornerShape(20.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.sensorless_title),
                color = c.sensorless,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f, fill = false)
            )
            InfoButton("no-sensor")
        }
        val last = s.lastCheck
        // no big number: an old check must not be read as the sugar now
        if (last != null) {
            Text(
                stringResource(
                    R.string.sensorless_last,
                    formatGlucose(last.mg, units, locale),
                    unit,
                    gapText(ctx, (now - last.t).coerceAtLeast(0))
                ),
                color = c.text,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
        } else {
            Text(stringResource(R.string.sensorless_last_none), color = c.text, fontSize = 14.sp)
        }
        Text(
            stringResource(R.string.sensorless_since, formatClock(ctx, s.since), s.by.orEmpty().ifBlank { "—" }),
            color = c.textMuted,
            fontSize = 13.sp
        )
        if (s.remindAt > 0 && !s.remindDue) {
            Text(stringResource(R.string.sensorless_next, formatClock(ctx, s.remindAt)), color = c.textMuted, fontSize = 13.sp)
        }
        iob?.let { OnBoard(it, locale, c.insulin, c.insulinContainer) }
    }
}

/** Insulin and carbs still acting, from what was logged: as under the reading. */
@Composable
private fun OnBoard(iob: Iob, locale: java.util.Locale, ink: Color, fill: Color) {
    val u = stringResource(R.string.unit_insulin)
    val parts = listOfNotNull(
        stringResource(R.string.iob_insulin, formatAmount(iob.insulin, locale), u).takeIf { iob.insulin >= 0.05 },
        stringResource(R.string.iob_carbs, iob.carbs).takeIf { iob.carbs > 0 }
    )
    if (parts.isEmpty()) return
    Text(
        parts.joinToString(" · "),
        color = ink,
        fontSize = 13.sp,
        modifier = Modifier.background(fill.copy(alpha = 0.5f), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 6.dp)
    )
}
