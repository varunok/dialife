package com.lumenarcanus.dialife.feature.home

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.core.ui.Units
import com.lumenarcanus.dialife.core.ui.formatGlucose
import com.lumenarcanus.dialife.core.ui.theme.DiaTheme
import com.lumenarcanus.dialife.data.Forecast
import com.lumenarcanus.dialife.data.History
import com.lumenarcanus.dialife.data.MeterModel
import com.lumenarcanus.dialife.data.PlannedDose
import com.lumenarcanus.dialife.data.Question
import com.lumenarcanus.dialife.data.Thresholds
import com.lumenarcanus.dialife.data.Treatment

@Composable
fun GlucoseChart(
    history: History?,
    forecast: Forecast?,
    thresholds: Thresholds,
    units: Units,
    rangeHours: Int,
    now: Long,
    window: ChartWindow? = null,
    onWindow: (ChartWindow) -> Unit = {},
    onEventPicked: (Treatment?) -> Unit = {},
    suspects: List<Question> = emptyList(),
    onSuspectPicked: (Question?) -> Unit = {},
    highlightFrom: Long? = null,
    // draw "≈ meter" from it; null: off
    estimate: MeterModel? = null,
    // not given yet: a dashed mark
    planned: List<PlannedDose> = emptyList(),
    // a tapped entry is told over the chart; false: the card under it does
    entryTooltip: Boolean = false,
    modifier: Modifier = Modifier
) {
    val c = DiaTheme.colors
    val measurer = rememberTextMeasurer()
    val haptics = LocalHapticFeedback.current
    val text = rememberChartText(units)

    val w = window?.at(now)
    val view = chartView(
        history,
        suspects,
        thresholds,
        start = w?.start ?: (now - rangeHours * HOUR),
        end = w?.end ?: (now + FORECAST_SPAN),
        now = now
    )
    val a11y = if (view.visible.isEmpty()) {
        stringResource(R.string.chart_a11y_empty, rangeHours)
    } else {
        stringResource(
            R.string.chart_a11y,
            rangeHours,
            formatGlucose(view.vs[view.visible.first()], units, text.locale),
            formatGlucose(view.vs[view.visible.last()], units, text.locale)
        )
    }

    var pick by remember(history, rangeHours) { mutableStateOf<Pick?>(null) }
    LaunchedEffect(pick) { onSuspectPicked((pick as? Pick.Suspect)?.question) }
    LaunchedEffect(pick) { onEventPicked((pick as? Pick.Event)?.treatment ?: (pick as? Pick.Meter)?.point?.treatment) }
    val current by rememberUpdatedState(view)

    Canvas(
        modifier
            .semantics { contentDescription = a11y }
            // same keys as `pick`, or taps write to a forgotten state
            .pointerInput(history, rangeHours) {
                chartGestures(view = { current }, pick = { pick }, onPick = { pick = it }, onWindow = onWindow, haptics = haptics)
            }
    ) {
        val s = ChartScale(size.width, size.height, this, view)
        highlightFrom?.let { drawHighlight(s, c, it) }
        drawThresholds(s, c, measurer, text)
        drawHourGrid(s, c, measurer, text)
        // everything time-based stays inside the plot when the window is pinched into the past
        clipRect(right = s.plotW, bottom = s.plotH + 1f) {
            drawForecast(s, c, forecast)
            drawNowLine(s, c)
            estimate?.let { drawMeterEstimate(s, c, it) }
            drawReadings(s, c)
            drawMarkers(s, c, measurer, text, pick)
            drawPlanned(s, c, measurer, text, planned)
            drawActivities(s, c, measurer, text, pick)
            drawSuspects(s, c, measurer, text, pick)
            drawMeters(s, c, pick)
        }
        drawSelection(s, c, measurer, text, pick, estimate, entryTooltip)
    }
}
