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
)

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
    val kind: String, // "rise" | "fall"
    val state: String, // "open" | "answered" | "unknown" (a suspect mark) | "dismissed"
    val from: Long,
    val to: Long,
    @SerialName("sgv_from") val sgvFrom: Int,
    @SerialName("sgv_to") val sgvTo: Int,
    val round: Int = 1,
    val outcome: String? = null,
    @SerialName("treatment_id") val treatmentId: String? = null,
    val answers: List<QuestionAnswer> = emptyList(),
    val waiting: List<String> = emptyList(), // people yet to answer this round
    @SerialName("waiting_ids") val waitingIds: List<Long> = emptyList(),
    val remind: Boolean = false,
    val quiet: Boolean = false, // night: no sound
) {
    fun asks(deviceId: Long) = state == "open" && deviceId in waitingIds
    /** What the others said last round, when they disagreed: shown with the repeated question. */
    fun previous(): List<QuestionAnswer> = if (round > 1) answers.filter { it.round == round - 1 } else emptyList()
}

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
)

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
