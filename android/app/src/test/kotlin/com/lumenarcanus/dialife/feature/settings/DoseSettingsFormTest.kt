package com.lumenarcanus.dialife.feature.settings

import com.lumenarcanus.dialife.core.ui.Units
import com.lumenarcanus.dialife.data.CorrectionRow
import com.lumenarcanus.dialife.data.DoseSettings
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DoseSettingsFormTest {
    private val dose = DoseSettings()
    private fun form(units: Units = Units.MgDl) = doseSettingsForm(dose, units, Locale.US)

    @Test
    fun defaultsHaveNoProblemsAndComeBackUnchanged() {
        assertTrue(form().problems(Units.MgDl).isEmpty())
        assertEquals(dose, form().toSettings(Units.MgDl))
    }

    @Test
    fun mmolIsTypedAndSavedAsMgDl() {
        val f = form(Units.Mmol).copy(rows = listOf("7,8" to "1"))
        assertTrue(f.problems(Units.Mmol).isEmpty())
        assertEquals(listOf(CorrectionRow(140, 1.0)), f.toSettings(Units.Mmol).table)
    }

    @Test
    fun tableRowOverTheRapidLimit() {
        val f = form().copy(maxRapid = "3")
        assertEquals(listOf(DoseProblem.TableOverMax(3.0)), f.problems(Units.MgDl))
    }

    @Test
    fun sameRowTwiceAndEmptyRow() {
        val f = form().copy(rows = listOf("140" to "1", "140" to "2", "" to ""))
        assertEquals(listOf(DoseProblem.Table, DoseProblem.Twice), f.problems(Units.MgDl))
    }

    @Test
    fun waitBoundsMustBeInOrder() {
        assertEquals(listOf(DoseProblem.Wait), form().copy(waitHighFrom = "70").problems(Units.MgDl))
        assertEquals(listOf(DoseProblem.Wait), form().copy(waitMin = "30", waitHighMin = "20").problems(Units.MgDl))
    }

    @Test
    fun rowsAreSavedInOrder() {
        val f = form().copy(rows = listOf("180" to "2", "140" to "1"))
        assertEquals(listOf(140, 180), f.toSettings(Units.MgDl).table.map { it.mg })
    }
}
