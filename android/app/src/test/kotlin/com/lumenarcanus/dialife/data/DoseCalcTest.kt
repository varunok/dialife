package com.lumenarcanus.dialife.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DoseCalcTest {
    private val s = DoseSettings()
    private val now = 1_800_000_000_000L
    private fun ago(min: Int) = now - min * 60_000L

    @Test
    fun roundingRules() {
        val cases = listOf(
            Triple("nearest", 1.0, listOf(1.5 to 2.0, 2.4 to 2.0, 2.5 to 3.0)),
            Triple("halfDown", 1.0, listOf(1.5 to 1.0, 1.55 to 2.0, 2.6 to 3.0)),
            Triple("down", 1.0, listOf(2.9 to 2.0, 2.0 to 2.0)),
            Triple("up", 1.0, listOf(2.1 to 3.0, 2.0 to 2.0)),
            Triple("nearest", 0.5, listOf(2.2 to 2.0, 2.25 to 2.5, 2.7 to 2.5, 2.8 to 3.0)),
            Triple("halfDown", 0.5, listOf(2.25 to 2.0, 2.3 to 2.5, 2.75 to 2.5)),
            Triple("down", 0.5, listOf(2.4 to 2.0, 2.9 to 2.5)),
            Triple("up", 0.5, listOf(2.1 to 2.5, 2.6 to 3.0)),
        )
        for ((rule, step, pairs) in cases) for ((x, want) in pairs) assertEquals("$rule $step $x", want, DoseCalc.round(x, rule, step), 1e-9)
    }

    @Test
    fun theDoctorsExample() {
        // 1 unit per 20 g, exactly half down: 30 g is 1 unit, 31 g is 2
        assertEquals(1.0, DoseCalc.meal(s, 30, null, null, now).dose, 0.0)
        assertEquals(2.0, DoseCalc.meal(s, 31, null, null, now).dose, 0.0)
    }

    @Test
    fun correctionTable() {
        assertEquals(0.0, DoseCalc.correction(139, s.table), 0.0)
        assertEquals(1.0, DoseCalc.correction(140, s.table), 0.0)
        assertEquals(2.0, DoseCalc.correction(219, s.table), 0.0)
        assertEquals(5.0, DoseCalc.correction(420, s.table), 0.0)
    }

    @Test
    fun theTotalIsRoundedOnce() {
        // 45 g = 2.25 + 2 for 186 = 4.25 → 4
        val m = DoseCalc.meal(s, 45, 186, ago(6), now)
        assertEquals(2.25, m.food, 1e-9)
        assertEquals(2.0, m.corr, 0.0)
        assertEquals(4.0, m.dose, 0.0)
        assertTrue(m.alerts.isEmpty())
    }

    @Test
    fun anOldMeterReadingGivesNoCorrection() {
        val m = DoseCalc.meal(s, 45, 186, ago(42), now)
        assertEquals(0.0, m.corr, 0.0)
        assertTrue(m.alerts.any { it is DoseCalc.Alert.MeterOld && it.ageMin == 42 })
        assertTrue(DoseCalc.meal(s, 45, null, null, now).alerts.any { it is DoseCalc.Alert.NoMeter })
    }

    @Test
    fun lowAndVeryHigh() {
        assertTrue(DoseCalc.meal(s, 45, 64, ago(4), now).alerts.any { it is DoseCalc.Alert.Low })
        val high = DoseCalc.meal(s, 45, 372, ago(3), now)
        assertTrue(high.alerts.any { it is DoseCalc.Alert.VeryHigh })
        assertEquals(5.0, high.corr, 0.0)
    }

    @Test
    fun alreadyGiven() {
        val m = DoseCalc.meal(s, 65, 186, ago(5), now, given = 2.0)
        assertEquals(5.0, m.dose, 0.0) // 3.25 + 2 = 5.25 → 5
        assertEquals(3.0, m.toGive, 0.0)
        assertTrue(m.alerts.any { it is DoseCalc.Alert.GivenLess })
        assertTrue(DoseCalc.meal(s, 20, 100, ago(5), now, given = 3.0).alerts.any { it is DoseCalc.Alert.GivenMore })
    }

    @Test
    fun limits() {
        // 250 g: 12.5 + 0 → 12, over 10: a separate confirm
        val high = DoseCalc.meal(s, 250, 100, ago(5), now)
        assertEquals(DoseCalc.Level.High, high.level)
        assertTrue(high.canPlan && high.asks)
        assertTrue(high.alerts.any { it is DoseCalc.Alert.CarbsHigh })
        // over twice the limit: a mistake, never a planned dose
        val absurd = DoseCalc.meal(s, 450, 100, ago(5), now)
        assertEquals(DoseCalc.Level.Absurd, absurd.level)
        assertFalse(absurd.canPlan)
        // more than usual: a note
        val usual = DoseCalc.meal(s, 120, 100, ago(5), now, usualDose = 3.0, usualCarbs = 70)
        assertTrue(usual.alerts.any { it is DoseCalc.Alert.DoseUnusual } && usual.alerts.any { it is DoseCalc.Alert.CarbsUnusual })
    }
}
