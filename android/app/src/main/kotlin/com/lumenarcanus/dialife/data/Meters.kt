package com.lumenarcanus.dialife.data

/** A glucometer reading, mg/dL. */
data class MeterReading(val t: Long, val mg: Int)

/**
 * Glucometer readings come two ways: a "BG Check" typed in DiaLife (a
 * treatment) and the checks xDrip+ uploads (a Bluetooth meter, a calibration),
 * the history's mbg. The server leaves out an mbg that repeats a BG Check.
 */
object Meters {

    /** Every reading of both, oldest first. */
    fun all(treatments: List<Treatment>, mbg: Series): List<MeterReading> {
        val typed = treatments.mapNotNull { t -> t.glucose?.takeIf { it > 0 }?.let { MeterReading(t.t, it) } }
        val uploaded = mbg.t.indices.mapNotNull { i -> mbg.v.getOrNull(i)?.takeIf { it > 0 }?.let { MeterReading(mbg.t[i], it) } }
        return (typed + uploaded).sortedBy { it.t }
    }

    /** The newest reading taken in [from]..[to]. */
    fun latest(treatments: List<Treatment>, mbg: Series, to: Long, from: Long = Long.MIN_VALUE): MeterReading? =
        all(treatments, mbg).lastOrNull { it.t in from..to }
}
