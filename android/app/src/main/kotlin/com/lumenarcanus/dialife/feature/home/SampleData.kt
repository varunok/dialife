package com.lumenarcanus.dialife.feature.home

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.lumenarcanus.dialife.data.Bg
import com.lumenarcanus.dialife.data.ConePoint
import com.lumenarcanus.dialife.data.Forecast
import com.lumenarcanus.dialife.data.ForecastBlind
import com.lumenarcanus.dialife.data.ForecastPoint
import com.lumenarcanus.dialife.data.ForecastQuality
import com.lumenarcanus.dialife.data.History
import com.lumenarcanus.dialife.data.Iob
import com.lumenarcanus.dialife.data.IobPoint
import com.lumenarcanus.dialife.data.MeterBand
import com.lumenarcanus.dialife.data.MeterCurrent
import com.lumenarcanus.dialife.data.MeterLag
import com.lumenarcanus.dialife.data.MeterModel
import com.lumenarcanus.dialife.data.MeterPair
import com.lumenarcanus.dialife.data.MeterReport
import com.lumenarcanus.dialife.data.Question
import com.lumenarcanus.dialife.data.QuestionAnswer
import com.lumenarcanus.dialife.data.Series
import com.lumenarcanus.dialife.data.StateResponse
import com.lumenarcanus.dialife.data.Thresholds
import com.lumenarcanus.dialife.data.Treatment
import com.lumenarcanus.dialife.data.Uploader
import kotlin.math.sin

// An evening like the mockups, for previews.
fun sampleHomeUi(now: Long = 1_761_400_000_000L, dad: String = "Тато", mom: String = "Мама"): HomeUi {
    val t = (0 until 288).map { now - (287 - it) * 300_000L }
    val v = t.indices.map { i -> (150 + 70 * sin(i / 22.0) + 20 * sin(i / 5.0)).toInt() }
    val bg = Bg(sgv = v.last(), mills = t.last(), direction = "Flat", delta = v.last() - v[v.size - 2])
    val ar2 = Forecast(
        predicted = (1..6).map { ForecastPoint(now + it * 300_000L, bg.sgv + it) },
        cone = (1..6).map { ConePoint(now + it * 300_000L, bg.sgv - it * 4, bg.sgv + it * 5) }
    )
    val treatments = listOf(
        Treatment("d", now - 3 * 3_600_000L - 300_000L, insulin = 18.0, insulinType = "basal", insulinName = "Lantus"),
        Treatment("a", now - 3 * 3_600_000L, insulin = 2.5, insulinType = "rapid", insulinName = "NovoRapid"),
        Treatment("b", now - 3 * 3_600_000L + 600_000L, carbs = 45.0),
        Treatment("m", t[235], glucose = v[235] + 14, eventType = "BG Check", enteredBy = mom),
        Treatment("x", t[262], duration = 40, intensity = "moderate", eventType = "Exercise", enteredBy = dad)
    )
    val questions = listOf(
        Question(
            1,
            "rise",
            "unknown",
            t[218],
            t[224],
            v[218],
            v[224]
        ),
        Question(
            2, "rise", "open", t[276], t[283], v[276], v[283], round = 2,
            answers = listOf(
                QuestionAnswer(dad, "nothing", 1, t[284]),
                QuestionAnswer(mom, "food_unknown", 1, t[285])
            ),
            waiting = listOf(dad, mom), waitingIds = listOf(8, 9)
        )
    )
    return HomeUi(
        state = StateResponse(
            now, "mg/dl", Thresholds(55, 80, 250, 300), bg, ar2, Uploader(battery = 69), questions = questions,
            forecastQuality = ForecastQuality(
                19,
                74,
                560,
                listOf(ForecastBlind("food", t[270])),
                quiet = ForecastQuality(12, 95, 600),
                afterFood = ForecastQuality(23, 87, 414)
            ),
            iob = Iob(
                1.8,
                25,
                40,
                listOf(
                    IobPoint(t[287] + 15 * 60_000L, 176),
                    IobPoint(
                        t[287] + 30 * 60_000L,
                        168
                    ),
                    IobPoint(t[287] + 60 * 60_000L, 150)
                )
            ),
            iobQuality = ForecastQuality(18, 71, 1900)
        ),
        myId = 8,
        history = History(t.first(), now, Series(t, v), mbg = Series(listOf(t[270]), listOf(v[270] - 9)), treatments = treatments),
        now = now
    )
}

/** The current sensor, calibrated two days ago: it reads ~7% low since, with the "≈ meter" line, for previews. */
fun sampleMeter(now: Long = 1_761_400_000_000L): MeterReport {
    val day = 86_400_000L
    val start = now - 5 * day
    val cal = now - 2 * day
    val pairs = listOf(
        // before the calibration: ~25% low
        (4.5 to (88 to 117)), (3.8 to (142 to 190)), (3.1 to (120 to 158)), (2.4 to (176 to 230)),
        // since: ~7% low
        (1.8 to (98 to 104)), (1.3 to (205 to 222)), (0.9 to (155 to 168)), (0.6 to (232 to 255)), (0.2 to (130 to 138))
    ).map { (d, sm) ->
        val t = now - (d * day).toLong()
        MeterPair(t, sm.second, sm.first.toDouble(), 1, used = true, beforeCal = t < cal)
    }
    return MeterReport(
        ready = true, used = 5, total = 5, sensors = 1, biasPct = -7.0, mard = 7.4,
        current = MeterCurrent(start, 6, 5, -7.0, calibratedAt = cal),
        bands = listOf(
            MeterBand(0, 100, "mgdl", -6.0, 1),
            MeterBand(100, 200, "pct", -6.5, 2),
            MeterBand(200, 0, "pct", -8.5, 2)
        ),
        model = MeterModel(a = 0.0, b = 0.0, offset = 0.075, sd = 0.07, since = cal),
        pairs = pairs, starts = listOf(now - 15 * day, start), calibrations = listOf(cal),
        lag = MeterLag(min = 9, lo = 1, hi = 16, n = 66, used = 4, source = "published")
    )
}
