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
    val untrusted: Untrusted? = null,
    // the "no sensor" mode is on for the family: no alarms, the glucometer only
    val sensorless: Sensorless? = null,
    @SerialName("sensorless_settings") val sensorlessSettings: SensorlessSettings = SensorlessSettings(),
    // carbs were given for a low: measure again with the glucometer 15 minutes on
    val recheck: Recheck? = null,
    // the sensor keeps reading off from the glucometer: calibrate, with a fresh check only
    val calibration: CalibrationAdvice? = null,
    // the app's newest version on the family's server: offered when this one's code is lower
    val app: AppRelease? = null
) {
    val mealRules get() = MealRules(mealGapMin, bolusBeforeMin, bolusAfterMin)
}

/** Carbs logged on a low: "waiting" for 15 minutes, then "due"; "still_low" once a check after them is under 70. */
@Serializable
data class Recheck(
    val id: Long,
    @SerialName("carbs_t") val carbsT: Long,
    val carbs: Double,
    @SerialName("due_at") val dueAt: Long,
    val state: String,
    @SerialName("check_t") val checkT: Long = 0,
    @SerialName("check_mg") val checkMg: Int = 0
)

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
    val untrusted: List<Untrusted> = emptyList(),
    // when the "no sensor" mode was on, oldest first
    val sensorless: List<SensorlessPeriod> = emptyList()
)

/** A time the sensor is not to be believed, [from, until): a shower and half an hour after it. */
@Serializable
data class Untrusted(val from: Long, val until: Long, val reason: String = "shower") {
    operator fun contains(t: Long) = t in from until until
}

/** The window [now] falls in, if any; the state's own once it has passed is not one any more. */
fun StateResponse.untrustedAt(now: Long): Untrusted? = untrusted?.takeIf { now in it }

/** The "no sensor" mode while it is on: since when, the newest glucometer check, the reminder, a card. */
@Serializable
data class Sensorless(
    val since: Long,
    val by: String? = null,
    // the newest check, also from before the mode
    @SerialName("last_check") val lastCheck: MeterCheck? = null,
    // when the reminder to measure is due; 0: off
    @SerialName("remind_at") val remindAt: Long = 0,
    @SerialName("remind_due") val remindDue: Boolean = false,
    // the newest check is out of range and nobody closed it
    val card: CheckCard? = null
)

@Serializable
data class MeterCheck(val t: Long, val mg: Int)

/** A check out of range in the mode: "low" or "high". */
@Serializable
data class CheckCard(val t: Long, val mg: Int, val kind: String)

/** The family's: how long without a check before every phone is reminded; 0: never. */
@Serializable
data class SensorlessSettings(@SerialName("remind_min") val remindMin: Int = 180, @SerialName("updated_by") val updatedBy: String? = null)

/** A time the mode was on, [from, until); until 0 while it lasts. */
@Serializable
data class SensorlessPeriod(
    val from: Long,
    val until: Long = 0,
    val by: String? = null,
    @SerialName("ended_by") val endedBy: String? = null,
    // "manual" | "sensor"
    val end: String? = null
) {
    /** Its end on a chart drawn at [now]. */
    fun end(now: Long) = if (until > 0) until else now
}

/** The server's calibration advice: which way the sensor is off, by how much, and whether the newest check may be entered now. */
@Serializable
data class CalibrationAdvice(
    val side: String, // "low": the sensor reads lower than the glucometer; "high"
    val since: Long,
    @SerialName("diff_mg") val diffMg: Double = 0.0,
    @SerialName("diff_pct") val diffPct: Double = 0.0,
    @SerialName("low_read") val lowRead: Boolean = false,
    val now: CalibrateNow? = null,
    val wait: List<String> = emptyList(), // "no_check" | "old" | "range" | "moving" | "no_trend" | "food" | "insulin" | "shower"
    val check: CalibrationCheck? = null
)

/** Enter [mg] in xDrip+ as a calibration before [until]. */
@Serializable
data class CalibrateNow(val mg: Int, val t: Long, val until: Long)

@Serializable
data class CalibrationCheck(val t: Long, val mg: Int)

/** An APK `dl app-push` put on the server: fetched from /api/app/apk, checked against [sha256]. */
@Serializable
data class AppRelease(val name: String, val code: Int, val sha256: String, val size: Long, val notes: String = "")
