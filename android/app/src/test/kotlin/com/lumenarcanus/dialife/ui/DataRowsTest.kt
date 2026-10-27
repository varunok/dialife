package com.lumenarcanus.dialife.ui

import com.lumenarcanus.dialife.data.History
import com.lumenarcanus.dialife.data.Series
import com.lumenarcanus.dialife.data.Treatment
import com.lumenarcanus.dialife.ui.data.EntryKind
import com.lumenarcanus.dialife.ui.data.entriesOf
import com.lumenarcanus.dialife.ui.data.rowsOf
import org.junit.Assert.assertEquals
import org.junit.Test

class DataRowsTest {
    private val m = 60_000L
    private val t0 = 1_761_399_900_000L // on a 15-minute boundary in UTC
    // readings every 5 min for an hour, an entry at +7 min (between readings) and a meal bolus at +31
    private val h = History(
        t0, t0 + 60 * m,
        sgv = Series((0..12).map { t0 + it * 5 * m }, (0..12).map { 100 + it * 5 }),
        treatments = listOf(
            Treatment("a", t0 + 7 * m, carbs = 20.0),
            Treatment("b", t0 + 31 * m, insulin = 2.0, carbs = 30.0),
        ),
    )

    @Test
    fun everyReadingKeepsItsEntries() {
        val es = entriesOf(h, emptyList(), t0, t0 + 60 * m)
        assertEquals(listOf(EntryKind.Carbs, EntryKind.Rapid, EntryKind.Carbs), es.map { it.kind })
        val rows = rowsOf(h, es, t0, t0 + 60 * m, 5)
        assertEquals(13, rows.size)
        assertEquals(t0 + 60 * m, rows.first().t) // newest first
        // +7 belongs to the +10 reading, +31 to +35
        assertEquals(1, rows.single { it.t == t0 + 10 * m }.entries.size)
        assertEquals(2, rows.single { it.t == t0 + 35 * m }.entries.size)
    }

    @Test
    fun longerStepIsIntervalsOnTheClock() {
        val es = entriesOf(h, emptyList(), t0, t0 + 60 * m)
        val rows = rowsOf(h, es, t0, t0 + 60 * m, 15, java.util.TimeZone.getTimeZone("UTC"))
        assertEquals(listOf(60, 45, 30, 15, 0).map { t0 + it * m }, rows.map { it.t })
        val first = rows.last() // 00–15: readings 100, 105, 110 and the entry at +7
        assertEquals(105, first.sgv)
        assertEquals(100, first.min)
        assertEquals(110, first.max)
        assertEquals(10, first.delta)
        assertEquals(t0 + 15 * m, first.end)
        assertEquals(1, first.entries.size)
        assertEquals(2, rows.single { it.t == t0 + 30 * m }.entries.size) // the meal bolus at +31
        assertEquals(3, rows.sumOf { it.entries.size })
    }
}
