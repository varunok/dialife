package com.lumenarcanus.dialife.ui.home

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.lumenarcanus.dialife.core.ui.Units
import com.lumenarcanus.dialife.core.ui.unitsOf

/** Nightscout trend names, as the server sends them. */
enum class Trend(val degrees: Float, val double: Boolean = false) {
    DoubleUp(-90f, true),
    SingleUp(-90f),
    FortyFiveUp(-45f),
    Flat(0f),
    FortyFiveDown(45f),
    SingleDown(90f),
    DoubleDown(90f, true),
    Unknown(0f)
}

enum class Range { Low, InRange, High }

data class HomeState(
    val sgv: Int,
    val deltaMgdl: Int?,
    val ageMin: Int,
    val trend: Trend,
    val units: Units,
    val low: Int,
    val high: Int,
    val forecast30: Int?,
    val forecastQuality: com.lumenarcanus.dialife.data.ForecastQuality? = null,
    val iob: com.lumenarcanus.dialife.data.Iob? = null,
    val iobQuality: com.lumenarcanus.dialife.data.ForecastQuality? = null,
    val phoneBattery: Int?,
    val uploaderName: String? = null, // the family's name for the xDrip+ phone, from the server
    val alarms: Int = 0,
    // percent over the last 24 h
    val timeInRange: Int? = null
) {
    val range: Range get() = when {
        sgv < low -> Range.Low
        sgv > high -> Range.High
        else -> Range.InRange
    }
}

/** What the hero and tiles show, or null while there is no reading yet. */
fun HomeUi.toHomeState(fallbackUnits: Units): HomeState? {
    val st = state ?: return null
    val bg = st.bg ?: return null
    val th = st.thresholds
    val inRange = history?.sgv?.v?.let { v -> if (v.isEmpty()) null else v.count { it in th.low..th.high } * 100 / v.size }
    return HomeState(
        sgv = bg.sgv,
        deltaMgdl = bg.delta,
        ageMin = ((now - bg.mills) / 60_000).toInt().coerceAtLeast(0),
        trend = Trend.entries.firstOrNull { it.name == bg.direction } ?: Trend.Unknown,
        units = st.displayUnits?.let(::unitsOf) ?: fallbackUnits,
        low = th.low,
        high = th.high,
        forecast30 = st.ar2?.predicted?.lastOrNull()?.sgv,
        forecastQuality = st.forecastQuality,
        iob = st.iob,
        iobQuality = st.iobQuality,
        phoneBattery = st.uploader?.battery,
        uploaderName = st.uploaderName,
        alarms = st.alarms.count { it.state != "cleared" },
        timeInRange = inRange
    )
}

/** Readings older than this are shown as stale: the chain is broken somewhere. */
const val STALE_MIN = 15

/** What Android still needs to allow before readings keep arriving with the app closed. */
data class BackgroundSetup(val notifications: Boolean = false, val battery: Boolean = false, val fullScreen: Boolean = false) {
    val needed get() = notifications || battery || fullScreen
}
