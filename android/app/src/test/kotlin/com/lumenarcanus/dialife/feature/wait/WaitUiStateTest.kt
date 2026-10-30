package com.lumenarcanus.dialife.feature.wait

import com.lumenarcanus.dialife.core.ui.Units
import com.lumenarcanus.dialife.data.Wait
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WaitUiStateTest {
    private val now = 1_761_400_000_000L
    private val dose = now - 10 * 60_000
    private val w = Wait("d", dose, 3.0, dose + 15 * 60_000, "range", 142, 60.0, 60.0)

    @Test
    fun countsDown() {
        val ui = CountdownUiState(w, now)
        assertEquals(5, ui.leftMin)
        assertEquals(15, ui.waitMin)
        assertFalse(ui.eatNow)
        assertEquals(0L, CountdownUiState(w, dose + 20 * 60_000).leftMs)
    }

    @Test
    fun theWarningOnlyForMoreThanAUnitOfFoodMissing() {
        assertNull("food enough", warningUiState(w, 20))
        assertNull("one unit short is not more", warningUiState(w.copy(foodG = 40.0), 20))
        assertEquals(WarningUiState("d", dose, 3.0, 60, 20), warningUiState(w.copy(foodG = 20.0), 20))
        assertNull("no food at all", warningUiState(w.copy(foodG = 0.0), 20))
        assertNull("closed", warningUiState(w.copy(foodG = 20.0, moreClosed = true), 20))
    }

    @Test
    fun closedOnAnyPhoneOrHere() {
        assertNull(waitUiState(w.copy(closed = true), 20, Units.MgDl, now).countdown)
        assertNull(waitUiState(w, 20, Units.MgDl, now, setOf("d:wait")).countdown)
        val short = w.copy(foodG = 20.0)
        assertNotNull(waitUiState(short, 20, Units.MgDl, now, setOf("d:wait")).warning)
        assertNull(waitUiState(short, 20, Units.MgDl, now, setOf("d:more")).warning)
        assertEquals(WaitUiState(units = Units.Mmol), waitUiState(null, 20, Units.Mmol, now))
    }

    @Test
    fun soundsOnlyWhileWaiting() {
        assertTrue(w.sounds())
        assertFalse(w.copy(closed = true).sounds())
        assertFalse(w.copy(reason = "low").sounds())
        assertFalse(w.copy(reason = "falling").sounds())
    }
}
