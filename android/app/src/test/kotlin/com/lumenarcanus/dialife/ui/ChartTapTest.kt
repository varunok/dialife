package com.lumenarcanus.dialife.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.lumenarcanus.dialife.data.FoodItem
import com.lumenarcanus.dialife.data.Treatment
import com.lumenarcanus.dialife.ui.home.ChartCard
import com.lumenarcanus.dialife.ui.home.Units
import com.lumenarcanus.dialife.ui.home.sampleHomeUi
import com.lumenarcanus.dialife.ui.theme.DiaLifeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Tapping the marks on the home-screen chart. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-w412dp-h915dp-xxhdpi")
class ChartTapTest {
    @get:Rule val compose = createComposeRule()

    /** A meal typed as one long name: its tooltip is wider than the chart and must wrap, not crash. */
    @Test
    fun longMealTooltip() {
        val ui = sampleHomeUi()
        val meal = Treatment(
            "m", ui.now - 30 * 60_000L, eventType = "Carb Correction", carbs = 25.0, enteredBy = "Мама",
            foodItems = listOf(FoodItem("Смузі зеленесмузі 205 грам, картопля пюре 83 грами", carbs = 25.0, source = "manual")),
        )
        compose.setContent {
            DiaLifeTheme(dark = true) {
                ChartCard(
                    ui.history!!.copy(treatments = listOf(meal)), null, ui.state!!.thresholds, Units.MgDl, ui.rangeHours, ui.now,
                    onRange = {}, modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        // tap along the food row, one tap per frame, so the finger lands on the mark and its tooltip
        // gets drawn (the mark's exact x depends on the window)
        val chart = compose.onNode(hasContentDescription("Glucose chart", substring = true))
        for (step in 0 until 40) {
            chart.performTouchInput {
                val plotW = width - 34.dp.toPx()
                click(Offset(plotW / 2 + step * plotW / 80, height - 24.dp.toPx() - 12.dp.toPx()))
            }
            compose.waitForIdle()
        }
    }
}
