package com.lumenarcanus.dialife.feature.calibration

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
import com.lumenarcanus.dialife.core.ui.formatClock
import com.lumenarcanus.dialife.core.ui.formatGlucose
import com.lumenarcanus.dialife.core.ui.theme.DiaTheme
import com.lumenarcanus.dialife.data.CalibrationAdvice
import com.lumenarcanus.dialife.liveRepository
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** The calibration advice on home: the view model, and the glucometer entry to log a check. */
@Composable
fun CalibrationSection(onMeasure: () -> Unit) {
    val ctx = LocalContext.current
    val vm = viewModel { CalibrationViewModel(ctx.liveRepository, DefaultCalibrationRepository({ ctx.liveRepository.credentials })) }
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    uiState.userMessage?.let { message ->
        LaunchedEffect(message) {
            Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
            vm.userMessageShown()
        }
    }
    uiState.advice?.let { CalibrationCard(it, uiState.units, uiState.absorbMin, onMeasure = onMeasure, onClose = { vm.close(it.since) }) }
}

/**
 * The sensor keeps reading off from the glucometer. "Calibrate now with N" in a bright block when
 * the newest check may be entered in xDrip+; otherwise what the next check needs. Never a number
 * DiaLife worked out: only a check just taken.
 */
@Composable
fun CalibrationCard(a: CalibrationAdvice, units: Units, absorbMin: Int, onMeasure: () -> Unit, onClose: () -> Unit) {
    val c = DiaTheme.colors
    val ctx = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val unit = stringResource(if (units == Units.MgDl) R.string.unit_mgdl else R.string.unit_mmol)
    fun g(v: Int) = formatGlucose(v, units, locale)
    Column(
        Modifier.fillMaxWidth().background(c.highContainer, RoundedCornerShape(18.dp)).border(1.5.dp, c.high, RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val title = when {
            a.lowRead -> stringResource(R.string.calib_low_read, a.check?.mg?.let { g(it) } ?: "—", unit)
            a.side == "low" -> stringResource(R.string.calib_reads_low, g(abs(a.diffMg).roundToInt()), unit, pct(a.diffPct, locale))
            else -> stringResource(R.string.calib_reads_high, g(abs(a.diffMg).roundToInt()), unit, pct(a.diffPct, locale))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = c.high, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
            InfoButton("calibration")
        }
        Text(
            stringResource(if (a.lowRead) R.string.calib_low_read_text else R.string.calib_text),
            color = c.text,
            fontSize = 14.sp,
            lineHeight = 20.sp
        )
        val now = a.now
        if (now != null) {
            Column(
                Modifier.fillMaxWidth().background(
                    c.inRangeContainer,
                    RoundedCornerShape(14.dp)
                ).border(1.5.dp, c.inRange, RoundedCornerShape(14.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    stringResource(R.string.calib_now, g(now.mg), unit),
                    color = c.inRange,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(R.string.calib_now_how, g(now.mg), formatClock(ctx, now.until)),
                    color = c.text,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
            }
        } else {
            Text(stringResource(R.string.calib_next), color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                needs(a.wait, units, absorbMin, locale).forEach { need ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (need.met) "✓" else "•", color = if (need.met) c.inRange else c.high, fontSize = 14.sp)
                        Text(
                            stringResource(need.text, *need.args),
                            color = if (need.met) c.textMuted else c.text,
                            fontSize = 14.sp,
                            lineHeight = 20.sp
                        )
                    }
                }
            }
            BannerButton(stringResource(R.string.calib_measure), c.high, c.background, onMeasure)
        }
        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(R.string.calib_close), color = c.text, maxLines = 1)
        }
    }
}

/** One thing the check to calibrate with needs; [met] by the newest check already. */
private class Need(val text: Int, val args: Array<Any>, val met: Boolean)

/** What the check to calibrate with needs, from the server's reasons; with no check yet none is met. */
private fun needs(wait: List<String>, units: Units, absorbMin: Int, locale: Locale): List<Need> {
    val hours = if (absorbMin % 60 == 0) "${absorbMin / 60}" else String.format(locale, "%.1f", absorbMin / 60.0)
    val range = formatGlucose(90, units, locale) + "–" + formatGlucose(150, units, locale)
    val none = "no_check" in wait
    return buildList {
        add(Need(R.string.calib_need_fresh, arrayOf(), !none && "old" !in wait))
        add(Need(R.string.calib_need_range, arrayOf(range), !none && "range" !in wait))
        if ("no_trend" in wait) {
            add(Need(R.string.calib_need_numbers, arrayOf(), false))
        } else {
            add(Need(R.string.calib_need_steady, arrayOf(), !none && "moving" !in wait))
        }
        add(Need(R.string.calib_need_food, arrayOf(hours), !none && "food" !in wait && "insulin" !in wait))
        if ("shower" in wait) add(Need(R.string.calib_need_shower, arrayOf(), false))
    }
}

private fun pct(v: Double, locale: Locale) = (if (v < 0) "−" else "+") + String.format(locale, "%.0f%%", abs(v))
