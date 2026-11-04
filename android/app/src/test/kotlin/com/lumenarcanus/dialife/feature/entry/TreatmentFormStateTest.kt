package com.lumenarcanus.dialife.feature.entry

import com.lumenarcanus.dialife.core.ui.Units
import com.lumenarcanus.dialife.data.DoseSettings
import com.lumenarcanus.dialife.data.InsulinType
import com.lumenarcanus.dialife.data.MealRules
import com.lumenarcanus.dialife.data.Treatment
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TreatmentFormStateTest {
    private val now = 1_761_400_000_000L
    private val min = 60_000L
    private val dose = DoseSettings(maxRapid = 10.0)

    private fun form(
        kind: TreatmentKind = TreatmentKind.Insulin,
        editing: Treatment? = null,
        recent: List<Treatment> = emptyList(),
        units: Units = Units.MgDl
    ) = TreatmentFormState(kindOf(editing, kind), editing, recent, units, now, Locale.US, basalTimes = emptyList())

    @Test
    fun anEditedEntryOpensAsItsKind() {
        assertEquals(TreatmentKind.Meter, kindOf(Treatment("g", now, glucose = 120), TreatmentKind.Insulin))
        assertEquals(TreatmentKind.Activity, kindOf(Treatment("a", now, duration = 30), TreatmentKind.Insulin))
        assertEquals(TreatmentKind.Note, kindOf(Treatment("n", now, notes = "x"), TreatmentKind.Insulin))
        assertEquals(TreatmentKind.Food, kindOf(null, TreatmentKind.Food))
    }

    @Test
    fun aDoseOverTheLimitNeedsItsOwnConfirm() {
        val f = form()
        assertFalse(f.amountOk(dose))
        f.changeInsulin(12.0)
        assertFalse(f.amountOk(dose))
        f.okBig = true
        assertTrue(f.amountOk(dose))
        f.changeInsulin(13.0) // a new amount: the confirm was for the old one
        assertFalse(f.amountOk(dose))
    }

    @Test
    fun rapidNearAMealIsForTheMealUntilTapped() {
        val meal = Treatment("m", now - 5 * min, carbs = 40.0)
        val f = form(recent = listOf(meal))
        f.changeInsulin(3.0)
        assertEquals("meal", f.build(null, MealRules()).insulinRole)
        f.insRole = "corr"
        assertEquals("corr", f.build(null, MealRules()).insulinRole)
        assertEquals("corr", form().role(MealRules()))
    }

    @Test
    fun editingInsulinKeepsTheMealHalf() {
        val bolus = Treatment("b", now - 10 * min, insulin = 3.0, carbs = 45.0, insulinType = "rapid", insulinName = "NovoRapid")
        val f = form(editing = bolus)
        f.changeInsulin(4.0)
        val t = f.build(null, MealRules()) { null }
        assertEquals("b", t.id)
        assertEquals(4.0, t.insulin!!, 0.0)
        assertEquals(45.0, t.carbs!!, 0.0)
        assertEquals("NovoRapid", t.insulinName)
        assertEquals(bolus.t, t.t)
    }

    @Test
    fun aNewBasalStartsFromTheLastOne() {
        val f = form(recent = listOf(Treatment("l", now - 24 * 60 * min, insulin = 14.0, insulinType = "basal")))
        f.insType = InsulinType.Basal
        f.startFromLastBasal()
        assertEquals(14.0, f.insulin, 0.0)
    }

    @Test
    fun meterIsTypedInThePhonesUnits() {
        val f = form(kind = TreatmentKind.Meter, units = Units.Mmol)
        f.meter = "7,8"
        assertTrue(f.amountOk(dose))
        assertEquals(140, f.build(null, MealRules()).glucose)
    }

    @Test
    fun aShowerIsItsOwnEntryWithItsLength() {
        val f = form(kind = TreatmentKind.Activity)
        assertFalse(f.shower) // sport first, as before
        f.shower = true
        assertEquals(10.0, f.showerMinutes, 0.0)
        f.showerMinutes = 15.0
        assertTrue(f.amountOk(dose))
        val t = f.build(null, MealRules())
        assertEquals(Treatment.SHOWER, t.eventType)
        assertEquals(15, t.duration)
        assertEquals(null, t.intensity)
        assertTrue(t.isShower)
        // sport again: an ordinary activity, its own minutes and intensity
        f.shower = false
        val sport = f.build(null, MealRules())
        assertEquals(null, sport.eventType)
        assertEquals(30, sport.duration)
        assertEquals("moderate", sport.intensity)
    }

    @Test
    fun anEditedShowerOpensOnItsTab() {
        val shower = Treatment("s", now - 20 * min, eventType = Treatment.SHOWER, duration = 20)
        val f = form(editing = shower)
        assertEquals(TreatmentKind.Activity, f.kind)
        assertTrue(f.shower)
        assertEquals(20.0, f.showerMinutes, 0.0)
        assertEquals("s", f.build(null, MealRules()).id)
    }
}
