package com.lumenarcanus.dialife.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Shapes of the server API (.local/notes/api-app.md); glucose is always mg/dL, time unix ms.
// What the server sends a phone: the state, readings, history and forecasts.

@Serializable
data class Thresholds(
    @SerialName("bgLow") val urgentLow: Int = 55,
    @SerialName("bgTargetBottom") val low: Int = 70,
    @SerialName("bgTargetTop") val high: Int = 180,
    @SerialName("bgHigh") val urgentHigh: Int = 250
)

@Serializable
data class Bg(
    val sgv: Int,
    val mills: Long,
    @SerialName("age_sec") val ageSec: Long = 0,
    val error: Boolean = false,
    val direction: String? = null,
    val delta: Int? = null,
    @SerialName("delta_exact") val deltaExact: Double? = null,
    val interpolated: Boolean = false
)

@Serializable
data class ForecastPoint(val t: Long, val sgv: Int)

@Serializable
data class ConePoint(val t: Long, val lo: Int, val hi: Int)

@Serializable
data class Forecast(
    val predicted: List<ForecastPoint> = emptyList(),
    val cone: List<ConePoint> = emptyList(),
    val level: String? = null,
    val event: String? = null
)

@Serializable
data class Uploader(val battery: Int? = null, val mills: Long = 0, val device: String? = null)

@Serializable
data class StateResponse(
    val now: Long,
    @SerialName("display_units") val displayUnits: String? = null,
    val thresholds: Thresholds = Thresholds(),
    val bg: Bg? = null,
    val ar2: Forecast? = null,
    val uploader: Uploader? = null,
    val alarms: List<AlarmView> = emptyList(),
    val episode: EpisodeRules? = null,
    val caregivers: List<Caregiver> = emptyList(),
    val questions: List<Question> = emptyList(), // open ones and the last day's suspect marks
    @SerialName("uploader_name") val uploaderName: String? = null, // what the family calls the xDrip+ phone (server config)
    @SerialName("forecast_quality") val forecastQuality: ForecastQuality? = null, // how often "in 30 min" came true lately
    val intents: List<InsulinIntent> = emptyList(), // parents with the insulin form open right now
    val dose: DoseSettings = DoseSettings(), // the family's dose calculation, the same on every phone
    val planned: List<PlannedDose> = emptyList(), // doses worked out for a meal, not given yet
    val iob: Iob? = null, // insulin still working and carbs still coming in; null: nothing
    @SerialName("iob_quality") val iobQuality: ForecastQuality? = null, // how often that forecast came true
    @SerialName("meal_gap_min") val mealGapMin: Int = MealRules().gapMin, // food entries this close are one meal
    @SerialName("bolus_before_min") val bolusBeforeMin: Int = MealRules().beforeMin, // rapid this long before a meal is for it
    @SerialName("bolus_after_min") val bolusAfterMin: Int = MealRules().afterMin, // and this long after
    // a rapid dose with food beside it: when to start eating, a suggestion only
    val wait: Wait? = null,
    // the sensor is not believed now (a shower): trust only the glucometer
    val untrusted: Untrusted? = null
) {
    val mealRules get() = MealRules(mealGapMin, bolusBeforeMin, bolusAfterMin)
}

/** Rapid insulin still working, carbs still coming in, and what they make of the next hour. Alarms never use it. */
@Serializable
data class Iob(
    val insulin: Double = 0.0,
    val carbs: Int = 0,
    val isf: Int = 0,
    // +15, +30, +60 min; empty when the reading is old
    val forecast: List<IobPoint> = emptyList()
)

@Serializable
data class IobPoint(val t: Long, val v: Int)

/** The chart forecast's record on this child's recent readings, and what it cannot see now. */
@Serializable
data class ForecastQuality(
    val err80: Int = 0, // 80% of forecasts were within this many mg/dL
    @SerialName("hit_pct") val hitPct: Int = 0,
    val n: Int = 0,
    val ignores: List<ForecastBlind> = emptyList(),
    val quiet: ForecastQuality? = null, // the same, scored apart for the quiet hours
    // and for the 3 hours after food
    @SerialName("after_food") val afterFood: ForecastQuality? = null
)

@Serializable
data class ForecastBlind(val kind: String, val t: Long) // "food" | "insulin"

@Serializable
data class Caregiver(val id: Long, val name: String)

/** The server's episode rules, for "we wake everyone if…". */
@Serializable
data class EpisodeRules(
    @SerialName("worse_by") val worseBy: Int = 10,
    @SerialName("checkin_timeout_min") val checkinTimeoutMin: Int = 5,
    @SerialName("ceiling_min") val ceilingMin: Int = 45
)

@Serializable
data class Series(
    val t: List<Long> = emptyList(),
    val v: List<Int> = emptyList(),
    // mbg only: where each check came from ("Roche 00:00:5E:…", "manual")
    val d: List<String> = emptyList()
)

@Serializable
data class History(
    val from: Long,
    val to: Long,
    val sgv: Series = Series(),
    val mbg: Series = Series(),
    val treatments: List<Treatment> = emptyList(),
    // when the sensor was not believed (showers), oldest first
    val untrusted: List<Untrusted> = emptyList()
)

/** A time the sensor is not to be believed, [from, until): a shower and half an hour after it. */
@Serializable
data class Untrusted(val from: Long, val until: Long, val reason: String = "shower") {
    operator fun contains(t: Long) = t in from until until
}

/** The window [now] falls in, if any; the state's own once it has passed is not one any more. */
fun StateResponse.untrustedAt(now: Long): Untrusted? = untrusted?.takeIf { now in it }
