package com.lumenarcanus.dialife.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.lumenarcanus.dialife.data.CarbEstimate
import com.lumenarcanus.dialife.data.CarbMine
import com.lumenarcanus.dialife.data.CarbSuggestion
import com.lumenarcanus.dialife.data.InsulinIntent
import com.lumenarcanus.dialife.data.Treatment
import com.lumenarcanus.dialife.ui.home.ChartCard
import com.lumenarcanus.dialife.ui.home.HomeScreen
import com.lumenarcanus.dialife.ui.home.Units
import com.lumenarcanus.dialife.ui.home.sampleHomeUi
import com.lumenarcanus.dialife.ui.theme.DiaLifeTheme
import com.lumenarcanus.dialife.ui.theme.DiaTheme
import com.lumenarcanus.dialife.ui.treatment.DoseLevel
import com.lumenarcanus.dialife.ui.treatment.FoodList
import com.lumenarcanus.dialife.ui.treatment.FoodRow
import com.lumenarcanus.dialife.ui.treatment.FoodTotal
import com.lumenarcanus.dialife.ui.treatment.IntentWarning
import com.lumenarcanus.dialife.ui.treatment.NearDose
import com.lumenarcanus.dialife.ui.treatment.RecentDoseNote
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone

/**
 * The pictures in the README (docs/screenshots), in English, on made-up data.
 *   ./gradlew :app:recordRoborazziDebug --tests '*ReadmeShots*'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-w412dp-h915dp-xxhdpi")
class ReadmeShots {
    @get:Rule val compose = createComposeRule()

    private val now = 1_761_400_000_000L

    @Before
    fun fixedZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("Etc/GMT+3"))
    }

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.setContent { DiaLifeTheme(dark = true) { content() } }
        compose.onRoot().captureRoboImage("../../docs/screenshots/$name.png")
    }

    @Test
    fun home() = shot("app_home") { HomeScreen(sampleHomeUi(now, dad = "Dad", mom = "Mom"), deviceName = "Dad") }

    @Test
    @Config(qualifiers = "en-w915dp-h412dp-land-xxhdpi")
    fun chart() = shot("app_chart") {
        val ui = sampleHomeUi(now, dad = "Dad", mom = "Mom")
        ChartCard(
            ui.history, ui.state?.ar2, ui.state!!.thresholds, Units.MgDl, ui.rangeHours, ui.now, onRange = {},
            modifier = Modifier.fillMaxSize(), expanded = true, suspects = ui.state!!.questions,
        )
    }

    @Test
    @Config(qualifiers = "en-w412dp-h1330dp-xxhdpi")
    fun food() = shot("app_food") {
        val rows = mutableStateListOf(
            FoodRow(1, "Pasta", "180 g", "45", "mine", asked = true, models = true,
                suggestion = CarbSuggestion(CarbMine(45, "180 g", 9), CarbEstimate(48, 40, 56, 2))),
            FoodRow(2, "Bread", "2 slices", "", null, asked = true, models = true,
                suggestion = CarbSuggestion(estimate = CarbEstimate(24, 20, 30, 2))),
            FoodRow(3, "Grandma's pie", "1 piece", "35", "manual", asked = true, models = true),
        )
        Column(
            Modifier.fillMaxSize().background(DiaTheme.colors.surface).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FoodList(rows, repo = null)
            FoodTotal(rows)
        }
    }

    @Test
    @Config(qualifiers = "en-w412dp-h300dp-xxhdpi")
    fun doseGuard() = shot("app_dose_guard") {
        Column(
            Modifier.fillMaxSize().background(DiaTheme.colors.surface).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IntentWarning(listOf(InsulinIntent(9, "Mom", "rapid", now - 120_000, now + 480_000)), now)
            RecentDoseNote(
                NearDose(Treatment("p", now - 70 * 60_000L, insulin = 2.0, insulinType = "rapid", enteredBy = "Mom"), 70 * 60_000L, DoseLevel.Recent),
                null,
            )
        }
    }
}
