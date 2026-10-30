package com.lumenarcanus.dialife.feature.home

import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.core.ui.Units
import com.lumenarcanus.dialife.core.ui.formatAmount
import com.lumenarcanus.dialife.core.ui.formatClock
import com.lumenarcanus.dialife.core.ui.formatDelta
import com.lumenarcanus.dialife.core.ui.formatGlucose
import com.lumenarcanus.dialife.core.ui.theme.DiaTheme
import com.lumenarcanus.dialife.core.ui.theme.GlucoseHero
import com.lumenarcanus.dialife.data.ForecastQuality
import java.util.Locale

@Composable
internal fun Hero(state: HomeState, locale: Locale) {
    val c = DiaTheme.colors
    val stateColor = when (state.range) {
        Range.Low -> c.low
        Range.InRange -> c.inRange
        Range.High -> c.high
    }
    val units = if (state.units == Units.MgDl) stringResource(R.string.unit_mgdl) else stringResource(R.string.unit_mmol)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // trend badge centred on the number
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.weight(1f)) {
                // weighted, so the units label is measured first and the number shrinks to what is left
                // a new reading slides in: up when it rose, down when it fell
                androidx.compose.animation.AnimatedContent(
                    targetState = state.sgv,
                    transitionSpec = {
                        val up = targetState > initialState
                        (
                            androidx.compose.animation.slideInVertically { h -> if (up) h / 2 else -h / 2 } +
                                androidx.compose.animation.fadeIn()
                            )
                            .togetherWith(
                                androidx.compose.animation.slideOutVertically { h -> if (up) -h / 2 else h / 2 } +
                                    androidx.compose.animation.fadeOut()
                            )
                    },
                    modifier = Modifier.weight(1f, fill = false),
                    label = "sgv"
                ) { sgv ->
                    BasicText(
                        formatGlucose(sgv, state.units, locale),
                        style = GlucoseHero.copy(
                            color = if (state.ageMin >= STALE_MIN) c.textFaint else c.text,
                            // an old number must not be read as the current one
                            textDecoration = if (state.ageMin >= STALE_MIN) {
                                androidx.compose.ui.text.style.TextDecoration.LineThrough
                            } else {
                                null
                            }
                        ),
                        maxLines = 1,
                        autoSize = TextAutoSize.StepBased(minFontSize = 56.sp, maxFontSize = GlucoseHero.fontSize, stepSize = 4.sp)
                    )
                }
                Spacer(Modifier.size(8.dp))
                Text(
                    units,
                    color = c.textMuted,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }
            TrendBadge(state.trend, stateColor)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            state.deltaMgdl?.let {
                Text(
                    stringResource(R.string.delta_per_5, formatDelta(it, state.units, locale)),
                    color = c.text,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
                Dot()
            }
            val stale = state.ageMin >= STALE_MIN
            Text(
                pluralStringResource(R.plurals.minutes_ago, state.ageMin, state.ageMin),
                color = if (stale) c.high else c.textMuted,
                fontSize = 15.sp,
                fontWeight = if (stale) FontWeight.SemiBold else FontWeight.Normal
            )
        }
        // wraps onto a second line rather than squeezing a chip into a column of words
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val bounds = formatGlucose(state.low, state.units, locale) + "–" + formatGlucose(state.high, state.units, locale)
            val label = when (state.range) {
                Range.InRange -> stringResource(R.string.state_in_range, bounds)
                Range.Low -> stringResource(R.string.state_low, formatGlucose(state.low, state.units, locale))
                Range.High -> stringResource(R.string.state_high, formatGlucose(state.high, state.units, locale))
            }
            // an old reading says nothing about the range now
            if (state.ageMin < STALE_MIN) {
                Chip(
                    label,
                    background = if (state.range == Range.InRange) {
                        c.inRangeContainer
                    } else if (state.range == Range.Low) {
                        c.lowContainer
                    } else {
                        c.highContainer
                    },
                    color = stateColor
                )
            }
            state.forecast30?.let {
                val q = state.forecastQuality?.takeIf { it.n >= 50 } // a record of fewer forecasts says little
                val text = if (q != null) {
                    stringResource(
                        R.string.forecast_30_err,
                        formatGlucose(it, state.units, locale),
                        formatGlucose(q.err80, state.units, locale)
                    )
                } else {
                    stringResource(R.string.forecast_30, formatGlucose(it, state.units, locale))
                }
                Chip(text, background = c.surface, color = c.textMuted)
            }
        }
        ForecastNotes(state, locale)
    }
}

/**
 * Under the reading: how often the "in 30 min" came true (apart for the quiet
 * hours and after food), the insulin and carbs still acting, and what the
 * trend cannot see (food or rapid insulin of the last two hours).
 */
@Composable
private fun ForecastNotes(state: HomeState, locale: Locale) {
    val c = DiaTheme.colors
    // how often it came true, apart for the quiet hours and after food: one number hides the second
    state.forecastQuality?.takeIf { state.forecast30 != null && (it.quiet != null || it.afterFood != null) }?.let { q ->
        val few = stringResource(R.string.forecast_few)
        val pct = { f: ForecastQuality? -> f?.takeIf { it.n >= 50 }?.let { "${it.hitPct}%" } ?: few }
        Text(stringResource(R.string.forecast_hits, pct(q.quiet), pct(q.afterFood)), color = c.textFaint, fontSize = 12.5.sp)
    }
    // insulin and carbs still acting, from what was logged
    state.iob?.let { iob ->
        val u = stringResource(R.string.unit_insulin)
        val parts = listOfNotNull(
            stringResource(R.string.iob_insulin, formatAmount(iob.insulin, locale), u).takeIf {
                iob.insulin >=
                    0.05
            },
            stringResource(R.string.iob_carbs, iob.carbs).takeIf { iob.carbs > 0 }
        )
        // its forecast is not shown until, tuned to this child, it beats the trend after food
        if (parts.isNotEmpty()) {
            Text(
                parts.joinToString(" · "),
                color = c.insulin,
                fontSize = 13.sp,
                modifier = Modifier.background(
                    c.insulinContainer.copy(alpha = 0.5f),
                    RoundedCornerShape(10.dp)
                ).padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
    }
    // what the trend cannot see: food or rapid insulin in the last two hours
    state.forecastQuality?.ignores?.maxByOrNull { it.t }?.let { b ->
        val ctx = androidx.compose.ui.platform.LocalContext.current
        Text(
            stringResource(
                if (b.kind == "food") {
                    R.string.forecast_blind_food
                } else {
                    R.string.forecast_blind_insulin
                },
                formatClock(ctx, b.t)
            ),
            color = c.textFaint,
            fontSize = 13.sp
        )
    }
}

@Composable
internal fun TrendBadge(trend: Trend, color: Color) {
    val c = DiaTheme.colors
    val description = stringResource(
        when {
            trend.degrees < 0 -> R.string.trend_up
            trend.degrees > 0 -> R.string.trend_down
            else -> R.string.trend_flat
        }
    )
    Box(
        Modifier
            .size(72.dp)
            .background(c.inRangeContainer.copy(alpha = if (c.isDark) 1f else 0.9f), CircleShape)
            .border(1.dp, color.copy(alpha = 0.25f), CircleShape)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(34.dp).rotate(trend.degrees)) {
            val w = size.width
            val stroke = w * 0.09f
            fun arrow(dx: Float) {
                drawLine(color, Offset(w * 0.12f + dx, w / 2), Offset(w * 0.82f + dx, w / 2), stroke, StrokeCap.Round)
                drawLine(color, Offset(w * 0.56f + dx, w * 0.24f), Offset(w * 0.82f + dx, w / 2), stroke, StrokeCap.Round)
                drawLine(color, Offset(w * 0.56f + dx, w * 0.76f), Offset(w * 0.82f + dx, w / 2), stroke, StrokeCap.Round)
            }
            if (trend.double) {
                arrow(-w * 0.1f)
                arrow(w * 0.1f)
            } else if (trend != Trend.Unknown) {
                arrow(0f)
            }
        }
    }
}
