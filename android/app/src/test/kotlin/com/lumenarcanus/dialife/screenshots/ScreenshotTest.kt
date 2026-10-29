package com.lumenarcanus.dialife.screenshots

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.lumenarcanus.dialife.data.AlarmView
import com.lumenarcanus.dialife.data.Caregiver
import com.lumenarcanus.dialife.data.Credentials
import com.lumenarcanus.dialife.ui.handling.HandlingScreen
import com.lumenarcanus.dialife.ui.home.BackgroundSetup
import com.lumenarcanus.dialife.ui.home.HomeScreen
import com.lumenarcanus.dialife.ui.home.Units
import com.lumenarcanus.dialife.ui.home.sampleHomeUi
import com.lumenarcanus.dialife.ui.settings.SettingsScreen
import com.lumenarcanus.dialife.ui.theme.DiaLifeTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone

/**
 * The main screens as pictures, compared with the ones in src/test/screenshots.
 *   ./gradlew :app:verifyRoborazziDebug   — fails when a screen changed
 *   ./gradlew :app:recordRoborazziDebug   — accept the change as the new reference
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val now = 1_761_400_000_000L // a fixed evening, so the pictures do not change by themselves
    private val creds = Credentials("https://dia.example.com", "dl_test", "Тато", "caregiver", "mg/dl", deviceId = 8)

    @Before
    fun fixedZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("Etc/GMT+3"))
    }

    private fun shot(name: String, dark: Boolean, content: @Composable () -> Unit) {
        compose.setContent { DiaLifeTheme(dark = dark) { content() } }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test
    @Config(qualifiers = "+uk")
    fun homeDarkUk() = shot("home_dark_uk", dark = true) { HomeScreen(sampleHomeUi(now), deviceName = "Тато") }

    // tall enough to show the entry buttons under the chart
    @Test
    @Config(qualifiers = "uk-w412dp-h915dp-xxhdpi")
    fun foodOtherPortionUk() = shot("food_other_portion_dark_uk", dark = true) {
        val hit = com.lumenarcanus.dialife.data.CarbTableHit(
            "fruit-frutillas-1-taza", 15, names = mapOf("uk" to "Полуниця"), portions = mapOf("uk" to "1 чашка (250 мл)"),
        )
        val row = com.lumenarcanus.dialife.ui.treatment.newFoodRow("Полуниця", portion = "100 грам").copy(
            asked = true, models = true,
            suggestion = com.lumenarcanus.dialife.data.CarbSuggestion(table = listOf(hit), estimate = com.lumenarcanus.dialife.data.CarbEstimate(8, 7, 9, 1)),
        )
        androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            com.lumenarcanus.dialife.ui.treatment.FoodSuggestions(row, "uk") {}
        }
    }

    @Test
    @Config(qualifiers = "uk-w412dp-h2000dp-xxhdpi")
    fun homeButtonsUk() = shot("home_buttons_dark_uk", dark = true) { HomeScreen(sampleHomeUi(now), deviceName = "Тато") }

    @Test
    @Config(qualifiers = "es-w412dp-h2000dp-xxhdpi")
    fun homeButtonsEs() = shot("home_buttons_dark_es", dark = true) { HomeScreen(sampleHomeUi(now), deviceName = "Papá") }

    @Test
    @Config(qualifiers = "uk-w915dp-h412dp-land-xxhdpi")
    fun chartFullScreenUk() = shot("chart_full_dark_uk", dark = true) {
        val ui = sampleHomeUi(now)
        com.lumenarcanus.dialife.ui.home.ChartCard(
            ui.history, ui.state?.ar2, ui.state!!.thresholds, Units.MgDl, ui.rangeHours, ui.now, onRange = {},
            modifier = androidx.compose.ui.Modifier.fillMaxSize(), expanded = true,
            suspects = ui.state!!.questions,
        )
    }

    @Test
    @Config(qualifiers = "uk-w915dp-h412dp-land-xxhdpi")
    fun chartMeterLineUk() = shot("chart_meter_dark_uk", dark = true) {
        val ui = sampleHomeUi(now)
        com.lumenarcanus.dialife.ui.home.ChartCard(
            ui.history, ui.state?.ar2, ui.state!!.thresholds, Units.MgDl, ui.rangeHours, ui.now, onRange = {},
            modifier = androidx.compose.ui.Modifier.fillMaxSize(), expanded = true,
            meter = com.lumenarcanus.dialife.ui.home.sampleMeter(now), meterLine = true,
        )
    }

    @Test
    @Config(qualifiers = "+uk")
    fun meterCardUk() = shot("meter_card_dark_uk", dark = true) {
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.padding(16.dp)) {
            com.lumenarcanus.dialife.ui.stats.MeterCard(com.lumenarcanus.dialife.ui.home.sampleMeter(now), Units.MgDl)
        }
    }

    @Test
    @Config(qualifiers = "+uk")
    fun dataListUk() = shot("data_list_dark_uk", dark = true) {
        val ui = sampleHomeUi(now)
        com.lumenarcanus.dialife.ui.data.DataScreen(
            ui.history, ui.state!!.questions, ui.state!!.thresholds, Units.MgDl, 3, now,
            loadHistory = { _, _ -> null }, onSaveTreatment = {}, onBack = {},
        )
    }

    @Test
    @Config(qualifiers = "+es")
    fun homeDarkEs() = shot("home_dark_es", dark = true) { HomeScreen(sampleHomeUi(now), deviceName = "Papá") }

    @Test
    @Config(qualifiers = "+uk")
    fun webLoginUk() = shot("web_login_uk", dark = true) {
        androidx.compose.foundation.layout.Column(
            androidx.compose.ui.Modifier.padding(20.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp),
        ) {
            com.lumenarcanus.dialife.ui.web.Approve(com.lumenarcanus.dialife.data.WebLoginInfo("x", "Firefox", "Linux", now + 120_000, ip = "198.51.100.9"), now = now) {}
            com.lumenarcanus.dialife.ui.web.Sessions(
                listOf(com.lumenarcanus.dialife.data.WebSession(1, "Chrome", "Windows", "Тато", now - 86_400_000, now + 6 * 86_400_000, now - 600_000)),
                now = now,
            ) {}
        }
    }

    @Test
    @Config(qualifiers = "+en")
    fun homeLightEnMmol() = shot("home_light_en_mmol", dark = false) {
        val ui = sampleHomeUi(now)
        HomeScreen(ui.copy(state = ui.state?.copy(displayUnits = "mmol")), deviceName = "Dad")
    }

    @Test
    @Config(qualifiers = "+uk")
    fun homeStaleUk() = shot("home_stale_uk", dark = true) {
        HomeScreen(sampleHomeUi(now).copy(now = now + 23 * 60_000L), deviceName = "Тато")
    }

    @Test
    @Config(qualifiers = "+uk")
    fun handlingUk() = shot("handling_uk", dark = true) {
        val ui = sampleHomeUi(now)
        val taken = now - 12 * 60_000L
        val alarm = AlarmView(
            id = 1, kind = "low", level = "urgent", state = "handled", startedAt = taken, sgv = 56,
            handledBy = "Тато", handledById = 8, handledAt = taken, handledSgv = 52,
            checkinPending = true, checkinSentAt = now - 60_000L,
        )
        val st = ui.state!!.copy(
            bg = ui.state.bg!!.copy(sgv = 56),
            alarms = listOf(alarm),
            caregivers = listOf(Caregiver(8, "Тато"), Caregiver(9, "Мама")),
        )
        HandlingScreen(ui.copy(state = st, myId = 8), alarmId = 1, units = Units.MgDl, onAct = { _, _ -> }, onSaveTreatment = {}, onBack = {})
    }

    @Test
    @Config(qualifiers = "+uk")
    fun settingsUk() = shot("settings_uk", dark = true) {
        SettingsScreen(
            creds = creds, state = sampleHomeUi(now).state, setup = BackgroundSetup(battery = true),
            onFixNotifications = {}, onFixBattery = {}, onFixFullScreen = {}, onTestSound = {}, onDisconnect = {}, onBack = {},
        )
    }

    private fun meal(now: Long) = com.lumenarcanus.dialife.data.Treatment(id = "m1", t = now - 25 * 60_000L, carbs = 45.0,
        foodItems = listOf(com.lumenarcanus.dialife.data.FoodItem("Борщ", "тарілка", 18.0, "mine"), com.lumenarcanus.dialife.data.FoodItem("Хліб чорний", "2 скибки", 22.0, "table"), com.lumenarcanus.dialife.data.FoodItem("Салат", null, 5.0, "manual")))

    @Test
    @Config(qualifiers = "+uk")
    fun mealUk() = shot("meal_dark_uk", dark = true) {
        val t = sampleHomeUi(now).history?.treatments.orEmpty() +
            com.lumenarcanus.dialife.data.Treatment(id = "g1", t = now - 6 * 60_000L, glucose = 186)
        com.lumenarcanus.dialife.ui.meal.MealScreen(
            repo = null, treatments = t, dose = com.lumenarcanus.dialife.data.DoseSettings(), planned = emptyList(), units = Units.MgDl,
            editing = meal(now).copy(foodItems = meal(now).foodItems!! + com.lumenarcanus.dialife.data.FoodItem("Гречка з котлетою", null, null, null)),
            initialTime = null, onSave = {}, onSaved = {}, onClose = {},
        )
    }

    @Test
    @Config(qualifiers = "+uk")
    fun plannedUk() = shot("planned_dark_uk", dark = true) {
        androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)) {
            val p = com.lumenarcanus.dialife.data.PlannedDose(id = "p1", mealId = "m1", dose = 4.0, food = 2.25, corr = 2.0, carbs = 45, meter = 186, meterAt = now - 30 * 60_000L, createdAt = now - 10 * 60_000L, by = "Мама")
            com.lumenarcanus.dialife.ui.meal.PlannedCard(p, com.lumenarcanus.dialife.data.DoseSettings(), now, listOf(meal(now)), null, Units.MgDl, {}, {})
            com.lumenarcanus.dialife.ui.meal.PlannedCard(p.copy(id = "p2", createdAt = now - 65 * 60_000L), com.lumenarcanus.dialife.data.DoseSettings(), now, listOf(meal(now)),
                com.lumenarcanus.dialife.data.Question(id = 9, kind = "planned", state = "open", from = now - 25 * 60_000L, to = now, sgvFrom = 0, sgvTo = 0, plannedId = "p2", dose = 4.0), Units.MgDl, {}, {})
            com.lumenarcanus.dialife.ui.meal.EntryDetail(meal(now), listOf(meal(now),
                com.lumenarcanus.dialife.data.Treatment(id = "i1", t = now - 30 * 60_000L, insulin = 2.0, insulinType = "rapid", insulinName = "Rapilog One", enteredBy = "Тато")),
                com.lumenarcanus.dialife.data.DoseSettings(), Units.MgDl)
        }
    }

    @Test
    @Config(qualifiers = "+uk")
    fun doseSettingsUk() = shot("dose_settings_dark_uk", dark = true) {
        com.lumenarcanus.dialife.ui.settings.DoseSettingsScreen(com.lumenarcanus.dialife.data.DoseSettings(updatedBy = "Тато"), Units.MgDl, onSave = { Result.success(it) }, onBack = {})
    }
}
