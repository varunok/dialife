package com.lumenarcanus.dialife.feature.sensorless

import com.lumenarcanus.dialife.core.ui.Units
import com.lumenarcanus.dialife.data.CheckCard
import com.lumenarcanus.dialife.data.MeterCheck
import com.lumenarcanus.dialife.data.Sensorless
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SensorlessUiStateTest {
    private val now = 1_800_000_000_000L
    private val since = now - 5 * 3_600_000L

    @Test
    fun offShowsNothing() {
        val ui = sensorlessUiState(null, Units.MgDl, now)
        assertNull(ui.card)
        assertNull(ui.remind)
    }

    @Test
    fun aCardGoesOnceClosedHere() {
        val s = Sensorless(since, card = CheckCard(now - 60_000L, 62, "low"))
        assertEquals(62, sensorlessUiState(s, Units.MgDl, now).card?.mg)
        assertNull(sensorlessUiState(s, Units.MgDl, now, closedHere = setOf(now - 60_000L)).card)
    }

    @Test
    fun theReminderSaysHowLongSinceTheCheck() {
        val last = MeterCheck(now - 3 * 3_600_000L, 140)
        assertNull(sensorlessUiState(Sensorless(since, lastCheck = last), Units.MgDl, now).remind)
        val due = sensorlessUiState(Sensorless(since, lastCheck = last, remindDue = true), Units.MgDl, now)
        assertEquals(3 * 3_600_000L, due.remind?.sinceCheckMs)
        assertNull(sensorlessUiState(Sensorless(since, remindDue = true), Units.MgDl, now).remind?.sinceCheckMs)
    }
}
