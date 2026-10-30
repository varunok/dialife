package com.lumenarcanus.dialife.feature.wait

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.core.ui.Units
import com.lumenarcanus.dialife.core.ui.formatClock
import com.lumenarcanus.dialife.core.ui.formatGlucose
import com.lumenarcanus.dialife.core.ui.theme.DiaTheme
import com.lumenarcanus.dialife.liveRepository
import java.util.Locale

/** The wait on home, with its own state and actions. */
@Composable
fun WaitSection() {
    val ctx = LocalContext.current
    val vm = viewModel { WaitViewModel(ctx.liveRepository) }
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    uiState.userMessage?.let { message ->
        LaunchedEffect(message) {
            Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
            vm.userMessageShown()
        }
    }
    WaitCards(uiState, vm::onAction)
}

/** Only draws: the countdown card and, apart from it, the warning. */
@Composable
fun WaitCards(uiState: WaitUiState, onAction: (WaitAction) -> Unit) {
    uiState.countdown?.let { c -> CountdownCard(c, uiState.units) { onAction(WaitAction.Eaten(c.doseId)) } }
    uiState.warning?.let { w -> WarningCard(w) { onAction(WaitAction.CloseWarning(w.doseId)) } }
}

/**
 * When to start eating: the server's suggestion by the sugar and a countdown.
 * Nothing here blocks or records; "already ate" only closes it, on every phone.
 */
@Composable
fun CountdownCard(ui: CountdownUiState, units: Units, onEaten: () -> Unit) {
    val c = DiaTheme.colors
    val ctx = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val w = ui.wait
    val (ink, fill) = when {
        ui.eatNow -> c.low to c.lowContainer
        ui.leftMs == 0L -> c.inRange to c.inRangeContainer
        else -> c.insulin to c.insulinContainer
    }
    val sgv = w.sgv?.let { formatGlucose(it, units, locale) }

    Column(
        Modifier.fillMaxWidth().background(fill, RoundedCornerShape(18.dp)).border(1.dp, ink, RoundedCornerShape(18.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Ring(ui, ink)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title(ui), color = ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle(ui, sgv), color = c.textMuted, fontSize = 13.sp)
            }
        }
        val facts = listOfNotNull(
            stringResource(R.string.wait_fact_dose, formatUnits(w.units, locale), formatClock(ctx, w.doseAt)),
            sgv?.let { stringResource(R.string.wait_fact_sugar, it) }
        )
        Text(facts.joinToString("  ·  "), color = c.textMuted, fontSize = 12.sp)
        Text(stringResource(R.string.wait_note), color = c.textFaint, fontSize = 11.5.sp)
        CardButton(stringResource(R.string.wait_eaten), onEaten)
    }
}

/** The dose covers more food than is entered, by over a unit's worth. */
@Composable
fun WarningCard(ui: WarningUiState, onClose: () -> Unit) {
    val c = DiaTheme.colors
    val ctx = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val dose = stringResource(R.string.wait_fact_dose, formatUnits(ui.units, locale), formatClock(ctx, ui.doseAt))
    Column(
        Modifier.fillMaxWidth().background(
            c.highContainer,
            RoundedCornerShape(18.dp)
        ).border(1.dp, c.high, RoundedCornerShape(18.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(stringResource(R.string.wait_more, ui.covers, ui.food), color = c.high, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.wait_more_check), color = c.textMuted, fontSize = 13.sp)
        Text(dose + "  ·  " + stringResource(R.string.wait_fact_food, ui.food), color = c.textMuted, fontSize = 12.sp)
        CardButton(stringResource(R.string.wait_close), onClose)
    }
}

/** The ring: what is left of the wait, "!" to eat now, "✓" once it is over. */
@Composable
private fun Ring(ui: CountdownUiState, ink: Color) {
    val track = DiaTheme.colors.line
    Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(56.dp)) {
            val w = 5.dp.toPx()
            val box = Size(size.width - w, size.height - w)
            val at = Offset(w / 2, w / 2)
            drawArc(track, 0f, 360f, false, at, box, style = Stroke(w))
            if (!ui.eatNow && ui.leftMs > 0) {
                drawArc(ink, -90f, 360f * ui.leftMs / ui.totalMs, false, at, box, style = Stroke(w, cap = StrokeCap.Round))
            }
        }
        val mark = when {
            ui.eatNow -> "!"
            ui.leftMs > 0 -> "${ui.leftMin}′"
            else -> "✓"
        }
        Text(mark, color = ink, fontSize = 17.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun title(ui: CountdownUiState): String = when {
    ui.eatNow -> stringResource(R.string.wait_eat_now)
    ui.leftMs == 0L -> stringResource(R.string.wait_go)
    else -> stringResource(R.string.wait_from, formatClock(LocalContext.current, ui.wait.eatFrom))
}

@Composable
private fun subtitle(ui: CountdownUiState, sgv: String?): String = when {
    ui.wait.reason == "low" -> stringResource(R.string.wait_why_low, sgv ?: "")
    ui.wait.reason == "falling" -> stringResource(R.string.wait_why_falling, sgv ?: "")
    ui.leftMs == 0L -> stringResource(R.string.wait_ago, ui.agoMin)
    ui.wait.reason == "high" -> stringResource(R.string.wait_why_high, ui.waitMin)
    ui.wait.reason == "unseen" -> stringResource(R.string.wait_why_unseen, ui.waitMin)
    else -> stringResource(R.string.wait_why_range, ui.waitMin)
}

@Composable
private fun CardButton(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(label, color = DiaTheme.colors.text, maxLines = 1)
    }
}

internal fun formatUnits(u: Double, locale: Locale): String = if (u % 1.0 == 0.0) u.toInt().toString() else String.format(locale, "%.1f", u)
