package com.lumenarcanus.dialife.feature.home

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.lumenarcanus.dialife.data.Bg
import com.lumenarcanus.dialife.data.ConePoint
import com.lumenarcanus.dialife.data.Forecast
import com.lumenarcanus.dialife.data.ForecastPoint
import com.lumenarcanus.dialife.data.History
import com.lumenarcanus.dialife.data.Question
import com.lumenarcanus.dialife.data.Series
import com.lumenarcanus.dialife.data.StateResponse
import com.lumenarcanus.dialife.data.Thresholds
import com.lumenarcanus.dialife.data.Treatment
import com.lumenarcanus.dialife.data.Uploader

// An evening like the mockups, for previews.
fun sampleHomeUi(now: Long = 1_761_400_000_000L, dad: String = "Тато", mom: String = "Мама"): HomeUi {
    val t = (0 until 288).map { now - (287 - it) * 300_000L }
    val v = t.indices.map { i -> (150 + 70 * kotlin.math.sin(i / 22.0) + 20 * kotlin.math.sin(i / 5.0)).toInt() }
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
        com.lumenarcanus.dialife.data.Question(
            1,
            "rise",
            "unknown",
            t[218],
            t[224],
            v[218],
            v[224]
        ),
        com.lumenarcanus.dialife.data.Question(
            2, "rise", "open", t[276], t[283], v[276], v[283], round = 2,
            answers = listOf(
                com.lumenarcanus.dialife.data.QuestionAnswer(dad, "nothing", 1, t[284]),
                com.lumenarcanus.dialife.data.QuestionAnswer(mom, "food_unknown", 1, t[285])
            ),
            waiting = listOf(dad, mom), waitingIds = listOf(8, 9)
        )
    )
    return HomeUi(
        state = StateResponse(
            now, "mg/dl", Thresholds(55, 80, 250, 300), bg, ar2, Uploader(battery = 69), questions = questions,
            forecastQuality = com.lumenarcanus.dialife.data.ForecastQuality(
                19,
                74,
                560,
                listOf(com.lumenarcanus.dialife.data.ForecastBlind("food", t[270])),
                quiet = com.lumenarcanus.dialife.data.ForecastQuality(12, 95, 600),
                afterFood = com.lumenarcanus.dialife.data.ForecastQuality(23, 87, 414)
            ),
            iob = com.lumenarcanus.dialife.data.Iob(
                1.8,
                25,
                40,
                listOf(
                    com.lumenarcanus.dialife.data.IobPoint(t[287] + 15 * 60_000L, 176),
                    com.lumenarcanus.dialife.data.IobPoint(
                        t[287] + 30 * 60_000L,
                        168
                    ),
                    com.lumenarcanus.dialife.data.IobPoint(t[287] + 60 * 60_000L, 150)
                )
            ),
            iobQuality = com.lumenarcanus.dialife.data.ForecastQuality(18, 71, 1900)
        ),
        myId = 8,
        history = History(t.first(), now, Series(t, v), mbg = Series(listOf(t[270]), listOf(v[270] - 9)), treatments = treatments),
        now = now
    )
}

/** A sensor that reads ~7% low, with enough checks for the "≈ meter" line, for previews. */
fun sampleMeter(now: Long = 1_761_400_000_000L): com.lumenarcanus.dialife.data.MeterReport {
    val day = 86_400_000L
    val starts = listOf(now - 25 * day, now - 15 * day, now - 5 * day)
    val pairs = listOf(
        88 to 96, 142 to 150, 205 to 228, 176 to 185, 260 to 291, 120 to 131, 98 to 104, 232 to 255,
        70 to 72, 155 to 170, 199 to 214, 240 to 268, 130 to 136, 110 to 121, 285 to 316,
        96 to 101, 168 to 172, 212 to 222, 144 to 150
    ).mapIndexed { i, (s, m) ->
        val k = if (i < 8) {
            1
        } else if (i < 15) {
            2
        } else {
            3
        }
        com.lumenarcanus.dialife.data.MeterPair(now - (24 - i) * day, m, s.toDouble(), k, used = i != 4)
    }
    return com.lumenarcanus.dialife.data.MeterReport(
        ready = true, used = 18, total = 19, sensors = 3, biasPct = -7.0, mard = 9.4,
        current = com.lumenarcanus.dialife.data.MeterCurrent(starts[2], 5, 4, -3.0),
        bands = listOf(
            com.lumenarcanus.dialife.data.MeterBand(0, 100, "mgdl", -4.0, 5),
            com.lumenarcanus.dialife.data.MeterBand(100, 200, "pct", -6.0, 8),
            com.lumenarcanus.dialife.data.MeterBand(200, 0, "pct", -11.0, 5)
        ),
        model = com.lumenarcanus.dialife.data.MeterModel(a = 2.0, b = 0.07, offset = -0.02, sd = 0.09),
        pairs = pairs, starts = starts
    )
}
