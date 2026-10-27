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
import com.lumenarcanus.dialife.data.AlarmView
import com.lumenarcanus.dialife.data.CarbEstimate
import com.lumenarcanus.dialife.data.CarbMine
import com.lumenarcanus.dialife.data.CarbSuggestion
import com.lumenarcanus.dialife.data.Caregiver
import com.lumenarcanus.dialife.data.Dose
import com.lumenarcanus.dialife.data.InsulinIntent
import com.lumenarcanus.dialife.data.Question
import com.lumenarcanus.dialife.data.Series
import com.lumenarcanus.dialife.data.Treatment
import com.lumenarcanus.dialife.ui.data.DataScreen
import com.lumenarcanus.dialife.ui.handling.HandlingScreen
import com.lumenarcanus.dialife.ui.home.ChartCard
import com.lumenarcanus.dialife.ui.home.HomeScreen
import com.lumenarcanus.dialife.ui.home.HomeUi
import com.lumenarcanus.dialife.ui.home.Units
import com.lumenarcanus.dialife.ui.home.sampleHomeUi
import com.lumenarcanus.dialife.ui.theme.DiaLifeTheme
import com.lumenarcanus.dialife.ui.theme.DiaTheme
import com.lumenarcanus.dialife.ui.treatment.FoodList
import com.lumenarcanus.dialife.ui.treatment.FoodRow
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone

/**
 * The pictures in the README (docs/screenshots), in English, on made-up data:
 * one phone screen for each thing DiaLife does that other apps do not.
 *   ./gradlew :app:recordRoborazziDebug --tests '*ReadmeShots*'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rGB-w412dp-h915dp-xxhdpi")
class ReadmeShots {
    @get:Rule val compose = createComposeRule()

    private val now = 1_761_400_000_000L
    private val min = 60_000L

    @Before
    fun fixedZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("Etc/GMT+3"))
    }

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.setContent { DiaLifeTheme(dark = true) { content() } }
        compose.onRoot().captureRoboImage("../../docs/screenshots/$name.png")
    }

    /** A calm morning: breakfast with its insulin, a glucometer check, no open question. */
    private fun day(): HomeUi {
        val ui = sampleHomeUi(now, dad = "Dad", mom = "Mom")
        val h = ui.history!!
        val t = h.sgv.t
        val breakfast = listOf(
            Treatment("r", t[276], insulin = 3.0, insulinType = "rapid", insulinName = "NovoRapid", enteredBy = "Mom"),
            Treatment("f", t[277], carbs = 40.0, enteredBy = "Mom"),
            Treatment("g", t[283], glucose = h.sgv.v[283] + 6, eventType = "BG Check", enteredBy = "Dad"),
        )
        return ui.copy(
            state = ui.state!!.copy(
                questions = emptyList(),
                forecastQuality = ui.state!!.forecastQuality?.copy(ignores = emptyList()),
                caregivers = listOf(Caregiver(8, "Dad"), Caregiver(9, "Mom")),
            ),
            history = h.copy(treatments = h.treatments + breakfast),
        )
    }

    /** The live day, the chart and what everyone logged. */
    @Test
    fun home() = shot("app_home") { HomeScreen(day(), deviceName = "Dad") }

    /** A low that Dad took on: the others are quiet, he is asked to check in. */
    @Test
    fun alarm() = shot("app_alarm") {
        val ui = day()
        val h = ui.history!!
        // the last hour slides down to 52 and turns back up
        val tail = listOf(118, 109, 99, 90, 81, 73, 66, 60, 55, 52, 52, 54, 56)
        val v = h.sgv.v.dropLast(tail.size) + tail
        val alarm = AlarmView(
            id = 1, kind = "low", level = "urgent", state = "handled", startedAt = now - 20 * min, sgv = 55,
            handledBy = "Dad", handledById = 8, handledAt = now - 12 * min, handledSgv = 52,
            checkinPending = true, checkinSentAt = now - min,
        )
        val st = ui.state!!.copy(bg = ui.state!!.bg!!.copy(sgv = 56, delta = 2), ar2 = null, alarms = listOf(alarm))
        HandlingScreen(
            // no glucometer check from the calm morning in the middle of a low
            ui.copy(state = st, myId = 8, history = h.copy(
                sgv = Series(h.sgv.t, v), mbg = Series(emptyList(), emptyList()),
                treatments = h.treatments.filter { it.glucose == null },
            )),
            alarmId = 1, units = Units.MgDl, onAct = { _, _ -> }, onSaveTreatment = {}, onBack = {},
        )
    }

    /** Mom has the insulin form open, and xDrip+ sent a dose right next to Dad's. */
    @Test
    fun doseGuard() = shot("app_dose_guard") {
        val ui = day()
        val double = Question(
            3, "double", "open", now - 25 * min, now - 21 * min, 0, 0,
            doses = listOf(Dose("x", now - 21 * min, 2.0, "xDrip+"), Dose("d", now - 25 * min, 2.0, "Dad")),
            insulin = "rapid", waiting = listOf("Dad", "Mom"), waitingIds = listOf(8, 9),
        )
        val st = ui.state!!.copy(
            intents = listOf(InsulinIntent(9, "Mom", "rapid", now - 2 * min, now + 8 * min)),
            questions = listOf(double),
        )
        HomeScreen(ui.copy(state = st, myId = 8), deviceName = "Dad")
    }

    /** A rise nobody logged food for: both parents are asked, and their answers differ. */
    @Test
    fun question() = shot("app_question") { HomeScreen(sampleHomeUi(now, dad = "Dad", mom = "Mom"), deviceName = "Dad") }

    /** A meal as a list: the family's own numbers first, then an estimate with a range. */
    @Test
    fun food() = shot("app_food") {
        val rows = mutableStateListOf(
            FoodRow(1, "Pasta", "180 g", "45", "mine", asked = true, models = true,
                suggestion = CarbSuggestion(CarbMine(45, "180 g", 9), CarbEstimate(48, 40, 56, 2))),
            FoodRow(2, "Bread", "2 slices", "", null, asked = true, models = true,
                suggestion = CarbSuggestion(estimate = CarbEstimate(24, 20, 30, 2))),
        )
        Column(
            Modifier.fillMaxSize().background(DiaTheme.colors.surface).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FoodList(rows, repo = null)
        }
    }

    /** Every reading and entry as a table. */
    @Test
    fun table() = shot("app_table") {
        val ui = day()
        DataScreen(
            ui.history, emptyList(), ui.state!!.thresholds, Units.MgDl, 3, now,
            loadHistory = { _, _ -> null }, onSaveTreatment = {}, onBack = {},
        )
    }

    @Test
    @Config(qualifiers = "en-rGB-w915dp-h412dp-land-xxhdpi")
    fun chart() = shot("app_chart") {
        val ui = day()
        ChartCard(
            ui.history, ui.state?.ar2, ui.state!!.thresholds, Units.MgDl, ui.rangeHours, ui.now, onRange = {},
            modifier = Modifier.fillMaxSize(), expanded = true, suspects = ui.state!!.questions,
        )
    }
}
