package com.lumenarcanus.dialife.feature.calibration

import com.lumenarcanus.dialife.core.ui.Units
import com.lumenarcanus.dialife.data.CalibrateNow
import com.lumenarcanus.dialife.data.CalibrationAdvice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalibrationUiStateTest {
    private val min = 60_000L
    private val a = CalibrationAdvice(
        side = "low",
        since = 5L,
        diffMg = -31.0,
        diffPct = -28.0,
        now = CalibrateNow(
            108,
            100 * min,
            105 * min
        )
    )

    @Test
    fun calibrateNowOnlyWhileTheCheckIsFresh() {
        assertEquals(108, calibrationUiState(a, Units.MgDl, 180, 104 * min, null).advice?.now?.mg)
        val later = calibrationUiState(a, Units.MgDl, 180, 105 * min, null).advice
        assertNull(later?.now)
        assertEquals(listOf("old"), later?.wait)
    }

    @Test
    fun closedHereForItsStretch() {
        assertNull(calibrationUiState(a, Units.MgDl, 180, 101 * min, 5L).advice)
        assertEquals(a, calibrationUiState(a, Units.MgDl, 180, 101 * min, 4L).advice)
    }
}
