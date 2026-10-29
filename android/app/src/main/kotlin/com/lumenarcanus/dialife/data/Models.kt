package com.lumenarcanus.dialife.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Shapes of the server API (.local/notes/api-app.md). Glucose is always mg/dL, time unix ms.

@Serializable
data class Thresholds(
    @SerialName("bgLow") val urgentLow: Int = 55,
    @SerialName("bgTargetBottom") val low: Int = 70,
    @SerialName("bgTargetTop") val high: Int = 180,
    @SerialName("bgHigh") val urgentHigh: Int = 250,
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
    val interpolated: Boolean = false,
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
    val event: String? = null,
)

@Serializable
data class Uploader(val battery: Int? = null, val mills: Long = 0, val device: String? = null)

@Serializable
data class AlarmView(
    val id: Long,
    val kind: String, // low | high | predicted_low | predicted_high | stale
    val level: String, // warn | urgent
    val state: String = "firing", // firing | handled | cleared
    @SerialName("started_at") val startedAt: Long = 0,
    val sgv: Int? = null,
    val reason: String? = null,
    @SerialName("handled_by") val handledBy: String? = null,
    @SerialName("handled_by_id") val handledById: Long = 0,
    @SerialName("handled_at") val handledAt: Long? = null,
    @SerialName("handled_sgv") val handledSgv: Int? = null,
    @SerialName("quiet_until") val quietUntil: Long? = null,
    @SerialName("checkin_pending") val checkinPending: Boolean = false,
    @SerialName("checkin_sent_at") val checkinSentAt: Long? = null,
    @SerialName("next_checkin_at") val nextCheckinAt: Long? = null,
    @SerialName("help_at") val helpAt: Long? = null,
    @SerialName("coming_by") val comingBy: String? = null,
    @SerialName("coming_by_id") val comingById: Long = 0,
    @SerialName("coming_at") val comingAt: Long? = null,
) {
    val urgentLow get() = kind == "low" && level == "urgent"
}

@Serializable
data class AlarmSummary(
    @SerialName("duration_min") val durationMin: Int = 0,
    @SerialName("handled_by") val handledBy: String? = null,
    val manual: Boolean = false,
    @SerialName("resolved_by") val resolvedBy: String? = null,
)

/** SSE alarm/handled/checkin/help/coming/clear. */
@Serializable
data class AlarmEvent(
    val alarm: AlarmView,
    val reason: String? = null,
    @SerialName("was_handled_by") val wasHandledBy: String? = null,
    val by: String? = null,
    val summary: AlarmSummary? = null,
)

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
    val wait: Wait? = null, // after a rapid dose: when to start eating, a suggestion only
) {
    val mealRules get() = MealRules(mealGapMin, bolusBeforeMin, bolusAfterMin)
}

/** Rapid insulin still working, carbs still coming in, and what they make of the next hour. Alarms never use it. */
@Serializable
data class Iob(
    val insulin: Double = 0.0,
    val carbs: Int = 0,
    val isf: Int = 0,
    val forecast: List<IobPoint> = emptyList(), // +15, +30, +60 min; empty when the reading is old
)

@Serializable
data class IobPoint(val t: Long, val v: Int)

/** How the food form works out a rapid dose: shared by the family, set in settings. */
@Serializable
data class DoseSettings(
    val ratio: Int = 20, // 1 unit per this many grams of carbs
    val rule: String = "halfDown", // "nearest" | "halfDown" | "down" | "up"
    val step: Double = 1.0, // the pen's step: 1 or 0.5
    @SerialName("fresh_min") val freshMin: Int = 15, // a glucometer reading older than this: no correction
    @SerialName("max_rapid") val maxRapid: Double = 10.0,
    @SerialName("max_basal") val maxBasal: Double = 20.0,
    @SerialName("max_carbs") val maxCarbs: Int = 150,
    val table: List<CorrectionRow> = listOf(CorrectionRow(140, 1.0), CorrectionRow(180, 2.0), CorrectionRow(220, 3.0), CorrectionRow(260, 4.0), CorrectionRow(300, 5.0)),
    val isf: Int = 0, // mg/dL one rapid unit lowers; 0: from the correction table's steps
    @SerialName("dia_min") val diaMin: Int = 240, // how long rapid insulin works
    @SerialName("carb_min") val carbMin: Int = 180, // how long carbs take to come in
    @SerialName("updated_at") val updatedAt: Long = 0,
    @SerialName("updated_by") val updatedBy: String = "",
)

/** From [mg] mg/dL on the glucometer, add [u] units. */
@Serializable
data class CorrectionRow(val mg: Int, val u: Double)

/** A dose worked out for a meal and not given yet: nothing counts it as insulin. */
@Serializable
data class PlannedDose(
    val id: String,
    @SerialName("meal_id") val mealId: String,
    val dose: Double,
    val food: Double = 0.0, // the meal's part, before rounding
    val corr: Double = 0.0,
    val carbs: Int = 0,
    val meter: Int? = null,
    @SerialName("meter_at") val meterAt: Long? = null,
    @SerialName("created_at") val createdAt: Long,
    val by: String = "",
    @SerialName("device_id") val deviceId: Long = 0,
    val state: String = "planned",
)

/** "Dad is giving insulin": someone opened the insulin form; it ends when the form closes. */
@Serializable
data class InsulinIntent(
    @SerialName("device_id") val deviceId: Long,
    val person: String,
    val insulin: String = "rapid", // "rapid" | "basal"
    val since: Long,
    val until: Long,
)

@Serializable
data class IntentEvent(val intents: List<InsulinIntent> = emptyList())

/** The chart forecast's record on this child's recent readings, and what it cannot see now. */
@Serializable
data class ForecastQuality(
    val err80: Int = 0, // 80% of forecasts were within this many mg/dL
    @SerialName("hit_pct") val hitPct: Int = 0,
    val n: Int = 0,
    val ignores: List<ForecastBlind> = emptyList(),
)

@Serializable
data class ForecastBlind(val kind: String, val t: Long) // "food" | "insulin"

/** "Did you forget to log?": a rise without food or a fall without insulin. */
@Serializable
data class Question(
    val id: Long,
    val kind: String, // "rise" | "fall" | "bolus" | "double" (two doses too close, one from xDrip+)
    val state: String, // "open" | "answered" | "unknown" (a suspect mark) | "dismissed"
    val from: Long,
    val to: Long,
    @SerialName("sgv_from") val sgvFrom: Int,
    @SerialName("sgv_to") val sgvTo: Int,
    val round: Int = 1,
    val outcome: String? = null,
    @SerialName("treatment_id") val treatmentId: String? = null,
    val answers: List<QuestionAnswer> = emptyList(),
    @SerialName("planned_id") val plannedId: String? = null, // "planned": the dose nobody confirmed
    val dose: Double? = null, // "planned": its units
    val waiting: List<String> = emptyList(), // people yet to answer this round
    @SerialName("waiting_ids") val waitingIds: List<Long> = emptyList(),
    val remind: Boolean = false,
    val quiet: Boolean = false, // night: no sound
    val doses: List<Dose> = emptyList(), // "double": the two, the one "one injection" removes first
    val insulin: String? = null, // "double": "rapid" | "basal"
    @SerialName("watch_until") val watchUntil: Long? = null, // "double" answered "two": watch for a low until then
) {
    fun asks(deviceId: Long) = state == "open" && deviceId in waitingIds
    /** Two doses were given: everyone watches for a low until [watchUntil]. */
    val watch get() = kind == "double" && state == "answered" && outcome == "two"
    /** What the others said last round, when they disagreed: shown with the repeated question. */
    fun previous(): List<QuestionAnswer> = if (round > 1) answers.filter { it.round == round - 1 } else emptyList()
}

@Serializable
data class Dose(val id: String, val t: Long, val units: Double, val by: String = "")

@Serializable
data class QuestionAnswer(
    val person: String,
    val answer: String, // "food" | "food_unknown" | "insulin" | "nothing"
    val round: Int,
    val at: Long,
    @SerialName("treatment_id") val treatmentId: String? = null,
)

@Serializable
data class Caregiver(val id: Long, val name: String)

/** The server's episode rules, for "we wake everyone if…". */
@Serializable
data class EpisodeRules(
    @SerialName("worse_by") val worseBy: Int = 10,
    @SerialName("checkin_timeout_min") val checkinTimeoutMin: Int = 5,
    @SerialName("ceiling_min") val ceilingMin: Int = 45,
)

@Serializable
data class Series(
    val t: List<Long> = emptyList(),
    val v: List<Int> = emptyList(),
    val d: List<String> = emptyList(), // mbg only: where each check came from ("Roche 00:00:5E:…", "manual")
)

@Serializable
data class Treatment(
    val id: String,
    val t: Long,
    @SerialName("event_type") val eventType: String? = null,
    val insulin: Double? = null,
    val carbs: Double? = null,
    val notes: String? = null,
    @SerialName("entered_by") val enteredBy: String? = null,
    @SerialName("insulin_type") val insulinType: String? = null, // "rapid" | "basal"; null: rapid (older entries)
    @SerialName("insulin_name") val insulinName: String? = null, // the brand, e.g. "Lantus"
    val glucose: Int? = null, // a glucometer reading ("BG Check"), mg/dL
    val duration: Int? = null, // minutes of activity ("Exercise")
    val intensity: String? = null, // "light" | "moderate" | "vigorous"
    val pending: Boolean = false, // made on this phone, not on the server yet (never sent by the server)
    @SerialName("confirmed_new") val confirmedNew: String? = null, // saved after the warning about this other dose (sent as confirmedNew)
    @SerialName("food_items") val foodItems: List<FoodItem>? = null, // a meal logged as a list; carbs is their sum
    @SerialName("meal_id") val mealId: String? = null, // insulin: the meal it was given for
    @SerialName("insulin_role") val insulinRole: String? = null, // insulin: "meal" | "corr"
) {
    /** "борщ 18 ≈ · хліб 22": the meal's lines, "≈" on numbers taken from a suggestion. */
    fun foodText(): String? = foodItems?.takeIf { it.isNotEmpty() }?.joinToString(" · ") { f ->
        listOfNotNull(f.name.ifBlank { null }, f.carbs?.let { kotlin.math.round(it).toInt().toString() + if (f.suggested) " ≈" else "" }).joinToString(" ")
    }
}

/** One line of a meal: the food, its portion, its carbs and where the number came from. */
@Serializable
data class FoodItem(
    val name: String,
    val portion: String? = null,
    val carbs: Double? = null,
    val source: String? = null, // "mine" (the family's earlier entry) | "table" (a carb table) | "estimate" (models) | "manual"
) {
    val suggested get() = source == "mine" || source == "table" || source == "estimate"
}

/** POST /api/carbs/suggest: per item, the family's own number and the models' estimate. */
@Serializable
data class CarbSuggestions(val models: Boolean = false, val items: List<CarbSuggestion> = emptyList())

@Serializable
data class CarbSuggestion(
    val mine: CarbMine? = null,
    val estimate: CarbEstimate? = null,
    val table: List<CarbTableHit> = emptyList(),
    val similar: List<CarbTableHit> = emptyList(), // table foods that mean the same in other words
)

/** After a rapid dose, when to start eating (the server's suggestion, never a rule). */
@Serializable
data class Wait(
    @SerialName("dose_id") val doseId: String,
    @SerialName("dose_at") val doseAt: Long,
    val units: Double,
    @SerialName("eat_from") val eatFrom: Long,
    val reason: String, // "range" | "high" | "unseen": wait; "low" | "falling": eat now
    val sgv: Int? = null,
    @SerialName("covers_g") val coversG: Double = 0.0, // carbs the dose covers at the ratio
    @SerialName("food_g") val foodG: Double = 0.0, // carbs already logged after it
)

/** A portion from an imported carb table: carbs for it, or for the typed portion when scaled. */
@Serializable
data class CarbTableHit(
    val id: String,
    val carbs: Int,
    val base: Int = carbs,
    val scaled: Boolean = false,
    val per100: Double? = null, // counted from carbs per 100 g (a portion typed in grams)
    @SerialName("per100_src") val per100Src: String = "",
    val fiber100: Double? = null, // for information only, where the source gives them
    val avail100: Double? = null,
    val names: Map<String, String> = emptyMap(),
    val portions: Map<String, String> = emptyMap(),
    val source: String = "",
    val score: Float = 0f, // a look-alike: how close the names are
)

/** GET /api/foods: the carb tables to pick from and the family's most logged foods. */
@Serializable
data class FoodCatalog(val tables: List<FoodTable> = emptyList(), val frequent: List<FrequentFood> = emptyList())

@Serializable
data class FoodTable(
    val source: String,
    val title: String = "",
    val note: Map<String, String> = emptyMap(),
    val categories: List<FoodCategory> = emptyList(),
    val items: List<TableFood> = emptyList(),
)

@Serializable
data class FoodCategory(val id: String, val names: Map<String, String> = emptyMap())

@Serializable
data class TableFood(
    val id: String,
    val cat: String,
    val carbs: Int,
    val names: Map<String, String> = emptyMap(),
    val portions: Map<String, String> = emptyMap(),
)

@Serializable
data class FrequentFood(val name: String, val portion: String = "", val carbs: Int = 0, val times: Int = 1)

/** A table's text in the app's language: Ukrainian, Spanish, otherwise English. */
fun Map<String, String>.inLang(lang: String): String =
    (this[if (lang == "uk" || lang == "es") lang else "en"] ?: this["en"] ?: values.firstOrNull()).orEmpty()

@Serializable
data class CarbMine(
    val carbs: Int,
    val portion: String = "",
    val times: Int = 1,
    val like: String? = null, // found by meaning: the family's food it came from
    val score: Float = 0f,
)

@Serializable
data class CarbEstimate(val carbs: Int, val lo: Int, val hi: Int, val models: Int = 1)

@Serializable
data class Zones(
    @SerialName("very_low") val veryLow: Double = 0.0,
    val low: Double = 0.0,
    @SerialName("in_range") val inRange: Double = 0.0,
    val high: Double = 0.0,
    @SerialName("very_high") val veryHigh: Double = 0.0,
)

@Serializable
data class AgpPoint(val minute: Int, val p5: Int, val p25: Int, val p50: Int, val p75: Int, val p95: Int, val n: Int = 0)

/** GET /api/stats?days=N */
@Serializable
data class Stats(
    val from: Long,
    val to: Long,
    val days: Int,
    val count: Int = 0,
    val first: Long? = null,
    val coverage: Double = 0.0,
    val mean: Double = 0.0,
    val sd: Double = 0.0,
    val cv: Double = 0.0,
    val gmi: Double = 0.0,
    val zones: Zones = Zones(),
    @SerialName("tir_70_180") val tir70: Double = 0.0,
    val hypos: Int = 0,
    val agp: List<AgpPoint> = emptyList(),
    val thresholds: Thresholds = Thresholds(),
    val daily: List<DayTotal> = emptyList(),
)

/** Insulin and carbs entered on one local day. */
@Serializable
data class DayTotal(val date: String, val basal: Double = 0.0, val rapid: Double = 0.0, val carbs: Double = 0.0)

@Serializable
data class LogEvent(val t: Long, val type: String, val reason: String? = null, val by: String? = null, val sgv: Int? = null)

/** One episode of GET /api/alarms/log. */
@Serializable
data class LogEpisode(
    val id: Long,
    val kind: String,
    val level: String,
    val state: String = "cleared",
    @SerialName("started_at") val startedAt: Long = 0,
    val sgv: Int? = null,
    @SerialName("trigger_sgv") val triggerSgv: Int? = null,
    @SerialName("cleared_at") val clearedAt: Long? = null,
    @SerialName("handled_by") val handledBy: String? = null,
    @SerialName("resolved_by") val resolvedBy: String? = null,
    val manual: Boolean = false,
    val events: List<LogEvent> = emptyList(),
) {
    fun asView() = AlarmView(id = id, kind = kind, level = level, state = state, startedAt = startedAt, sgv = sgv)
}

@Serializable
data class AlarmLog(val episodes: List<LogEpisode> = emptyList())

@Serializable
data class History(
    val from: Long,
    val to: Long,
    val sgv: Series = Series(),
    val mbg: Series = Series(),
    val treatments: List<Treatment> = emptyList(),
)

/** Sensor vs glucometer, from GET /api/meter: all checks ever made. */
@Serializable
data class MeterReport(
    val ready: Boolean = false,
    val need: Int = 8,
    val used: Int = 0,
    val total: Int = 0,
    val sensors: Int = 0,
    @SerialName("bias_pct") val biasPct: Double = 0.0, // negative: the sensor reads lower than the meter
    val mard: Double = 0.0,
    val current: MeterCurrent? = null,
    val bands: List<MeterBand> = emptyList(),
    val model: MeterModel? = null,
    val pairs: List<MeterPair> = emptyList(),
    val starts: List<Long> = emptyList(),
)

@Serializable
data class MeterCurrent(val since: Long, val day: Int, val n: Int = 0, @SerialName("bias_pct") val biasPct: Double = 0.0)

@Serializable
data class MeterBand(val from: Int, val to: Int = 0, val unit: String, val bias: Double, val n: Int)

@Serializable
data class MeterPair(
    val t: Long,
    val meter: Int,
    val sensor: Double,
    @SerialName("sensor_no") val sensorNo: Int = 0,
    val used: Boolean = true,
)

@Serializable
data class MeterModel(val a: Double, val b: Double, val offset: Double = 0.0, val sd: Double) {
    /** What the glucometer would most likely show for a sensor value, and the typical spread. */
    fun estimate(sensor: Int): Pair<Int, Int> {
        val sc = maxOf(sensor.toDouble(), 100.0)
        return kotlin.math.round(sensor * (1 + b) + a + offset * sc).toInt() to kotlin.math.round(sd * sc).toInt()
    }
}

/** A browser asking to see the data, as the server describes it. */
@Serializable
data class WebLoginInfo(
    val id: String,
    val browser: String,
    val os: String,
    @SerialName("expires_at") val expiresAt: Long,
    @SerialName("max_minutes") val maxMinutes: Int = 90 * 24 * 60,
    val ip: String? = null, // where the browser asked from
    @SerialName("same_network") val sameNetwork: Boolean = false, // the same address this phone has now: at home, the family's own computer
)

/** A browser allowed in: who allowed it, until when, last seen. */
@Serializable
data class WebSession(
    val id: Long,
    val browser: String,
    val os: String,
    val by: String = "",
    @SerialName("created_at") val createdAt: Long,
    @SerialName("expires_at") val expiresAt: Long,
    @SerialName("last_seen_at") val lastSeenAt: Long? = null,
)
