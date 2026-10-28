package com.lumenarcanus.dialife.data

import kotlin.math.floor
import kotlin.math.round

/**
 * The rapid dose for a meal, worked out the way clinics' handouts do it: the
 * meal's part and the correction are added first, the total rounded once.
 * Only ever shown; nothing here records insulin.
 */
object DoseCalc {
    private const val EPS = 1e-9

    /** [x] rounded to the pen's [step] by [rule]. */
    fun round(x: Double, rule: String, step: Double): Double {
        val q = x / step
        val f = floor(q + EPS)
        val r = q - f
        val n = when (rule) {
            "down" -> f
            "up" -> if (r > EPS) f + 1 else f
            "nearest" -> if (r >= 0.5 - EPS) f + 1 else f
            else -> if (r > 0.5 + EPS) f + 1 else f // "halfDown": exactly half goes down
        }
        return round(n * step * 10) / 10
    }

    /** The table's units for a glucometer reading: the highest row it reaches, else 0. */
    fun correction(mg: Int, table: List<CorrectionRow>): Double =
        table.filter { it.mg > 0 }.sortedByDescending { it.mg }.firstOrNull { mg >= it.mg }?.u ?: 0.0

    enum class Level { Ok, Unusual, High, Absurd }

    /** Over the limit: a separate confirm; over twice the limit: a mistake; over 1.5× the usual: a note. */
    fun level(dose: Double, max: Double, usual: Double?): Level = when {
        dose > max * 2 -> Level.Absurd
        dose > max -> Level.High
        usual != null && usual > 0 && dose > usual * 1.5 -> Level.Unusual
        else -> Level.Ok
    }

    /** Something to see before injecting; [bad] ones are red and need a tap to go on. */
    sealed class Alert(val bad: Boolean) {
        data class MeterOld(val ageMin: Int, val freshMin: Int) : Alert(true)
        data class NoMeter(val freshMin: Int) : Alert(true)
        data class Low(val mg: Int) : Alert(true)
        data class VeryHigh(val mg: Int, val maxCorr: Double) : Alert(true)
        data class RecentRapid(val units: Double, val at: Long) : Alert(false)
        data class GivenLess(val given: Double, val dose: Double) : Alert(false)
        data class GivenMore(val given: Double, val dose: Double) : Alert(true)
        data class GivenSame(val given: Double) : Alert(false)
        data class CarbsHigh(val carbs: Int, val max: Int) : Alert(true)
        data class CarbsUnusual(val carbs: Int, val usual: Int) : Alert(false)
        data class DoseAbsurd(val dose: Double, val max: Double) : Alert(true)
        data class DoseHigh(val dose: Double, val max: Double) : Alert(true)
        data class DoseUnusual(val dose: Double, val usual: Double) : Alert(false)
    }

    data class Meal(
        val carbs: Int, // the whole meal, earlier lines of it included
        val food: Double, // carbs / ratio, before rounding
        val meter: Int?,
        val meterAgeMin: Int?,
        val fresh: Boolean,
        val corr: Double,
        val raw: Double,
        val dose: Double, // rounded
        val given: Double, // rapid already given for this meal
        val toGive: Double, // what is still to give (dose - given)
        val level: Level, // of toGive
        val alerts: List<Alert>,
    ) {
        /** May go in as a planned dose: something to give, and not a mistake. */
        val canPlan get() = toGive > 0 && carbs > 0 && level != Level.Absurd
        /** Saving asks first: a red card or a note that matters. */
        val asks get() = alerts.any { it.bad || it is Alert.RecentRapid || it is Alert.DoseUnusual || it is Alert.CarbsUnusual }
    }

    /**
     * @param meterAt when [meter] (a glucometer reading, mg/dL) was taken
     * @param given rapid already given for this meal
     * @param recentRapid another rapid dose within 3 h, not this meal's: (units, when)
     * @param usualDose the largest rapid dose of the last 30 days
     * @param usualCarbs the largest meal of the last 30 days
     */
    fun meal(
        s: DoseSettings,
        carbs: Int,
        meter: Int?,
        meterAt: Long?,
        now: Long,
        given: Double = 0.0,
        recentRapid: Pair<Double, Long>? = null,
        usualDose: Double? = null,
        usualCarbs: Int? = null,
    ): Meal {
        val food = carbs.toDouble() / s.ratio.coerceAtLeast(1)
        val age = meterAt?.let { ((now - it) / 60_000L).toInt().coerceAtLeast(0) }
        val fresh = meter != null && age != null && age <= s.freshMin
        val corr = if (fresh) correction(meter!!, s.table) else 0.0
        val raw = food + corr
        val dose = round(raw, s.rule, s.step)
        val toGive = round(dose - given, "nearest", 0.1)
        val level = level(toGive, s.maxRapid, usualDose)
        val alerts = buildList {
            if (carbs > s.maxCarbs) add(Alert.CarbsHigh(carbs, s.maxCarbs))
            else if (usualCarbs != null && usualCarbs > 0 && carbs > usualCarbs * 1.5) add(Alert.CarbsUnusual(carbs, usualCarbs))
            when (level) {
                Level.Absurd -> add(Alert.DoseAbsurd(toGive, s.maxRapid))
                Level.High -> add(Alert.DoseHigh(toGive, s.maxRapid))
                Level.Unusual -> add(Alert.DoseUnusual(toGive, usualDose ?: 0.0))
                Level.Ok -> {}
            }
            when {
                meter == null || age == null -> add(Alert.NoMeter(s.freshMin))
                !fresh -> add(Alert.MeterOld(age, s.freshMin))
                meter < 70 -> add(Alert.Low(meter))
                meter > 350 -> add(Alert.VeryHigh(meter, s.table.maxOfOrNull { it.u } ?: 0.0))
            }
            recentRapid?.let { add(Alert.RecentRapid(it.first, it.second)) }
            if (given > 0) when {
                toGive > 0 -> add(Alert.GivenLess(given, dose))
                toGive < 0 -> add(Alert.GivenMore(given, dose))
                else -> add(Alert.GivenSame(given))
            }
        }
        return Meal(carbs, food, meter, age, fresh, corr, raw, dose, given, toGive, level, alerts)
    }
}
