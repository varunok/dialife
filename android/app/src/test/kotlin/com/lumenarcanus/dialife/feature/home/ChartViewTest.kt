package com.lumenarcanus.dialife.feature.home

import com.lumenarcanus.dialife.data.History
import com.lumenarcanus.dialife.data.Series
import com.lumenarcanus.dialife.data.Thresholds
import com.lumenarcanus.dialife.data.Treatment
import com.lumenarcanus.dialife.data.Untrusted
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartViewTest {
    private val now = 1_761_400_000_000L
    private val min = 60_000L

    private fun view(h: History) = chartView(h, emptyList(), Thresholds(), start = now - 3 * HOUR, end = now + FORECAST_SPAN, now = now)

    @Test
    fun keepsOnlyReadingsInTheWindow() {
        val h = History(0, now, sgv = Series(t = listOf(now - 4 * HOUR, now - 2 * HOUR, now), v = listOf(100, 120, 140)))
        assertEquals(listOf(1, 2), view(h).visible)
    }

    @Test
    fun glucometerChecksAndActivityAreNotBottomMarkers() {
        val dose = Treatment("d", now - 60 * min, insulin = 2.0)
        val check = Treatment("g", now - 50 * min, glucose = 150)
        val walk = Treatment("w", now - 40 * min, duration = 30)
        val v = view(History(0, now, treatments = listOf(dose, check, walk)))
        assertEquals(listOf(dose), v.treatments)
        assertEquals(listOf(150), v.meters.map { it.v })
        assertEquals(listOf(walk), v.activities)
    }

    @Test
    fun activityBegunBeforeTheWindowStillShows() {
        val walk = Treatment("w", now - 3 * HOUR - 20 * min, duration = 60)
        assertEquals(listOf(walk), view(History(0, now, treatments = listOf(walk))).activities)
    }

    @Test
    fun scaleGrowsWithTheHighestValueUpTo400() {
        assertEquals(300f, view(History(0, now, sgv = Series(t = listOf(now), v = listOf(200)))).hi)
        assertEquals(370f, view(History(0, now, mbg = Series(t = listOf(now), v = listOf(350)))).hi)
        assertEquals(400f, view(History(0, now, sgv = Series(t = listOf(now), v = listOf(395)))).hi)
    }

    @Test
    fun readingsAfterAShowerAreNotTrusted() {
        val shower = Untrusted(from = now - 50 * min, until = now - 10 * min)
        val old = Untrusted(from = now - 6 * HOUR, until = now - 5 * HOUR) // out of view
        val h = History(
            0,
            now,
            sgv = Series(t = listOf(now - 55 * min, now - 50 * min, now - 11 * min, now - 10 * min), v = listOf(120, 105, 100, 118)),
            untrusted = listOf(old, shower)
        )
        val v = view(h)
        assertEquals(listOf(shower), v.untrusted)
        assertTrue(v.trusted(0))
        assertFalse(v.trusted(1))
        assertFalse(v.trusted(2))
        assertTrue(v.trusted(3)) // [from, until)
    }
}
