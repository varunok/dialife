package com.lumenarcanus.dialife.data

import kotlin.math.round
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Shapes of the server API (.local/notes/api-app.md); glucose is always mg/dL, time unix ms.
// Statistics, and sensor vs glucometer.

@Serializable
data class Zones(
    @SerialName("very_low") val veryLow: Double = 0.0,
    val low: Double = 0.0,
    @SerialName("in_range") val inRange: Double = 0.0,
    val high: Double = 0.0,
    @SerialName("very_high") val veryHigh: Double = 0.0
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
    val daily: List<DayTotal> = emptyList()
)

/** Insulin and carbs entered on one local day. */
@Serializable
data class DayTotal(val date: String, val basal: Double = 0.0, val rapid: Double = 0.0, val carbs: Double = 0.0)

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
    val lag: MeterLag = MeterLag()
)

/** How far the sensor runs behind the glucometer, minutes: the measured delay with its 95% interval, and the one used. */
@Serializable
data class MeterLag(
    val min: Int? = null,
    val lo: Int? = null,
    val hi: Int? = null,
    val n: Int = 0,
    val used: Int = 4,
    val source: String = "published"
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
    val used: Boolean = true
)

@Serializable
data class MeterModel(val a: Double, val b: Double, val offset: Double = 0.0, val sd: Double) {
    /** What the glucometer would most likely show for a sensor value, and the typical spread. */
    fun estimate(sensor: Int): Pair<Int, Int> {
        val sc = maxOf(sensor.toDouble(), 100.0)
        return round(sensor * (1 + b) + a + offset * sc).toInt() to round(sd * sc).toInt()
    }
}
