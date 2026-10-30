package com.lumenarcanus.dialife.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MetersTest {
    private fun min(m: Int) = m * 60_000L
    private fun check(at: Int, mg: Int) = Treatment(id = "c$at", t = min(at), glucose = mg)

    @Test
    fun aMeterReadingFromXdripCounts() {
        val mbg = Series(listOf(min(100)), listOf(182))
        assertEquals(MeterReading(min(100), 182), Meters.latest(emptyList(), mbg, to = min(105)))
    }

    @Test
    fun theNewestOfBothSourcesWins() {
        val mbg = Series(listOf(min(90), min(110)), listOf(150, 182))
        assertEquals(182, Meters.latest(listOf(check(100, 140)), mbg, to = min(115))?.mg)
        assertEquals(140, Meters.latest(listOf(check(120, 140)), mbg, to = min(125))?.mg)
    }

    @Test
    fun outsideTheWindowIsLeftOut() {
        val mbg = Series(listOf(min(130)), listOf(182))
        // a reading after "now" does not count yet, one before "from" no longer does
        assertEquals(140, Meters.latest(listOf(check(100, 140)), mbg, to = min(120))?.mg)
        assertNull(Meters.latest(listOf(check(100, 140)), mbg, to = min(120), from = min(105)))
    }

    @Test
    fun emptyOrZeroIsNoReading() {
        assertNull(Meters.latest(listOf(Treatment(id = "i", t = min(100), insulin = 2.0)), Series(listOf(min(100)), listOf(0)), to = min(120)))
    }
}
