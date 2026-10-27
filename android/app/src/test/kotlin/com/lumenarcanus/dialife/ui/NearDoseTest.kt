package com.lumenarcanus.dialife.ui

import com.lumenarcanus.dialife.data.InsulinType
import com.lumenarcanus.dialife.data.Treatment
import com.lumenarcanus.dialife.ui.treatment.DoseLevel
import com.lumenarcanus.dialife.ui.treatment.nearDose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NearDoseTest {
    private val now = 1_800_000_000_000L
    private fun min(m: Int) = m * 60_000L
    private fun rapid(id: String, ago: Long, u: Double = 2.0) = Treatment(id, now - ago, insulin = u, insulinType = "rapid")
    private fun basal(id: String, ago: Long) = Treatment(id, now - ago, insulin = 10.0, insulinType = "basal")

    @Test
    fun rapidWindows() {
        assertEquals(DoseLevel.Same, nearDose(listOf(rapid("a", min(8))), InsulinType.Rapid, now, null)?.level)
        assertEquals(DoseLevel.Recent, nearDose(listOf(rapid("a", min(40))), InsulinType.Rapid, now, null)?.level)
        assertNull(nearDose(listOf(rapid("a", min(200))), InsulinType.Rapid, now, null)) // past 3 h
        // the nearest counts, before or after a back-dated entry
        val near = nearDose(listOf(rapid("a", min(90)), rapid("b", min(10))), InsulinType.Rapid, now - min(25), null)
        assertEquals("b", near?.dose?.id)
        assertEquals(DoseLevel.Same, near?.level)
    }

    @Test
    fun typesAndBasal() {
        // long-acting next to rapid: no conflict
        assertNull(nearDose(listOf(basal("a", min(5))), InsulinType.Rapid, now, null))
        assertEquals(DoseLevel.Same, nearDose(listOf(basal("a", min(9 * 60))), InsulinType.Basal, now, null)?.level)
        assertNull(nearDose(listOf(basal("a", min(20 * 60))), InsulinType.Basal, now, null))
        // the entry being edited is not its own neighbour; food alone is no dose
        assertNull(nearDose(listOf(rapid("a", min(5)), Treatment("f", now, carbs = 30.0)), InsulinType.Rapid, now, "a"))
    }
}
