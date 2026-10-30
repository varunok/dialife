package com.lumenarcanus.dialife.navigation

import android.os.Bundle
import com.lumenarcanus.dialife.data.FoodItem
import com.lumenarcanus.dialife.data.Treatment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** An older entry reaches the food form whole, whatever its notes and dishes hold. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TreatmentNavTypeTest {
    private val tr = Treatment(
        id = "a/b?c=1&d",
        t = 1_761_400_000_000L,
        carbs = 45.0,
        notes = "Борщ & хліб — 100%",
        foodItems = listOf(FoodItem("Хліб чорний", "2 скибки", 22.0, "table"))
    )

    @Test
    fun throughTheRoute() {
        assertEquals(tr, TreatmentNavType.parseValue(TreatmentNavType.serializeAsValue(tr)))
        assertNull(TreatmentNavType.parseValue(TreatmentNavType.serializeAsValue(null)))
    }

    @Test
    fun throughTheBundle() {
        val b = Bundle()
        TreatmentNavType.put(b, "entry", tr)
        assertEquals(tr, TreatmentNavType[b, "entry"])
    }
}
