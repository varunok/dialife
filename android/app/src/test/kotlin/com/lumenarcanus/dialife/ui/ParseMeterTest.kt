package com.lumenarcanus.dialife.ui

import com.lumenarcanus.dialife.ui.home.Units
import com.lumenarcanus.dialife.ui.treatment.parseMeter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParseMeterTest {
    @Test
    fun mgdl() {
        assertEquals(144, parseMeter("144", Units.MgDl))
        assertNull(parseMeter("", Units.MgDl))
        assertNull(parseMeter("7", Units.MgDl)) // mmol typed on a mg/dL phone
        assertNull(parseMeter("1440", Units.MgDl))
    }

    @Test
    fun mmolWithEitherDecimalMark() {
        assertEquals(144, parseMeter("8", Units.Mmol))
        assertEquals(133, parseMeter("7,4", Units.Mmol))
        assertEquals(133, parseMeter("7.4", Units.Mmol))
        assertNull(parseMeter("144", Units.Mmol))
    }
}
