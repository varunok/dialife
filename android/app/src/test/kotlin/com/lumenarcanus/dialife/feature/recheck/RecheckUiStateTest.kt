package com.lumenarcanus.dialife.feature.recheck

import com.lumenarcanus.dialife.core.ui.Units
import com.lumenarcanus.dialife.data.Recheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecheckUiStateTest {
    private val min = 60_000L
    private val r = Recheck(id = 7, carbsT = 100 * min, carbs = 15.0, dueAt = 115 * min, state = "waiting")

    @Test
    fun waitingTurnsDueOnTimeWithoutNewData() {
        assertFalse(recheckUiState(r, Units.MgDl, 114 * min, emptySet()).due)
        assertTrue(recheckUiState(r, Units.MgDl, 115 * min, emptySet()).due)
    }

    @Test
    fun closedHereIsGoneUntilTheServerSaysSoToo() {
        assertNull(recheckUiState(r, Units.MgDl, 120 * min, setOf(7L)).recheck)
        assertEquals(r, recheckUiState(r, Units.MgDl, 120 * min, setOf(8L)).recheck)
    }

    @Test
    fun stillLowIsNotDue() {
        val s = recheckUiState(r.copy(state = "still_low", checkT = 117 * min, checkMg = 64), Units.MgDl, 118 * min, emptySet())
        assertFalse(s.due)
        assertEquals(64, s.recheck?.checkMg)
    }
}
