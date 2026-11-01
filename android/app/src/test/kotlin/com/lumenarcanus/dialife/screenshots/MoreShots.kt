package com.lumenarcanus.dialife.screenshots

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.lumenarcanus.dialife.core.ui.Units
import com.lumenarcanus.dialife.core.ui.theme.DiaLifeTheme
import com.lumenarcanus.dialife.data.AgpPoint
import com.lumenarcanus.dialife.data.DayTotal
import com.lumenarcanus.dialife.data.DoseSettings
import com.lumenarcanus.dialife.data.FoodCategory
import com.lumenarcanus.dialife.data.FoodTable
import com.lumenarcanus.dialife.data.PlannedDose
import com.lumenarcanus.dialife.data.Stats
import com.lumenarcanus.dialife.data.TableFood
import com.lumenarcanus.dialife.data.Zones
import com.lumenarcanus.dialife.feature.connect.ConnectFailure
import com.lumenarcanus.dialife.feature.connect.ConnectScreen
import com.lumenarcanus.dialife.feature.connect.ConnectUiState
import com.lumenarcanus.dialife.feature.entry.FoodPicker
import com.lumenarcanus.dialife.feature.home.sampleHomeUi
import com.lumenarcanus.dialife.feature.home.sampleMeter
import com.lumenarcanus.dialife.feature.planned.InjectSheet
import com.lumenarcanus.dialife.feature.stats.StatsLoad
import com.lumenarcanus.dialife.feature.stats.StatsScreen
import com.lumenarcanus.dialife.feature.stats.StatsUiState
import java.util.TimeZone
import kotlin.math.sin
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Screens the other shots do not show: statistics, connecting, the carb table, the planned dose's injection sheet. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "uk-w412dp-h1600dp-xxhdpi")
class MoreShots {
    @get:Rule val compose = createComposeRule()

    private val now = 1_761_400_000_000L

    @Before
    fun fixedZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("Etc/GMT+3"))
    }

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.setContent { DiaLifeTheme(dark = true) { content() } }
        compose.waitForIdle()
        captureScreenRoboImage("src/test/screenshots/$name.png")
    }

    private val stats = Stats(
        from = now - 14 * 86_400_000L, to = now, days = 14, count = 3900, coverage = 97.0, mean = 152.0, sd = 48.0, cv = 31.6, gmi = 7.0,
        zones = Zones(veryLow = 0.5, low = 3.0, inRange = 68.0, high = 22.0, veryHigh = 6.5), tir70 = 68.0, hypos = 4,
        agp = (0 until 96).map { i ->
            val m = 150 + (40 * sin(i / 15.0)).toInt()
            AgpPoint(i * 15, m - 60, m - 30, m, m + 35, m + 80, 14)
        },
        daily = (0 until 14).map {
            DayTotal(
                "2026-10-%02d".format(12 + it),
                basal = 12.0,
                rapid = 8.0 + it % 4,
                carbs =
                160.0 + 10 * (it % 3)
            )
        }
    )

    @Test
    fun statsUk() = shot("stats_dark_uk") {
        StatsScreen(StatsUiState(load = StatsLoad.Ready(stats), meter = sampleMeter(now)), onPick = {}, onRetry = {}, onBack = {})
    }

    @Test
    fun connectUk() = shot("connect_dark_uk") {
        ConnectScreen(ConnectUiState(), onScanned = {}, onScannerFailed = {}, onManual = { _, _ -> })
    }

    @Test
    fun connectFailedUk() = shot("connect_failed_dark_uk") {
        ConnectScreen(ConnectUiState(failure = ConnectFailure.Server, serverCode = 502), onScanned = {
        }, onScannerFailed = {}, onManual = { _, _ -> })
    }

    @Test
    fun foodPickerUk() = shot("food_picker_dark_uk") {
        val table = FoodTable(
            source = "uk",
            title = "Таблиця ХО",
            categories = listOf(FoodCategory("bread", mapOf("uk" to "Хліб")), FoodCategory("fruit", mapOf("uk" to "Фрукти"))),
            items = listOf(
                TableFood("b1", "bread", 15, mapOf("uk" to "Хліб чорний"), mapOf("uk" to "1 скибка")),
                TableFood("f1", "fruit", 12, mapOf("uk" to "Яблуко"), mapOf("uk" to "1 середнє")),
                TableFood("f2", "fruit", 20, mapOf("uk" to "Банан"), mapOf("uk" to "1 шт"))
            )
        )
        FoodPicker(listOf(table), "uk", onPick = { _, _ -> }, onDismiss = {})
    }

    @Test
    fun injectSheetUk() = shot("inject_sheet_dark_uk") {
        val p = PlannedDose("p", "m", 3.0, food = 2.25, corr = 1.0, carbs = 45, createdAt = now - 10 * 60_000L, by = "Тато")
        val recent = sampleHomeUi(now).history?.treatments.orEmpty()
        InjectSheet(p, DoseSettings(), now - 12 * 60_000L, recent, Units.MgDl, onDone = { _, _ -> }, onBack = {}, clock = { now })
    }
}
