package com.lumenarcanus.dialife.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.togetherWith
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.data.AlarmView
import com.lumenarcanus.dialife.data.Bg
import com.lumenarcanus.dialife.live.ActionReceiver
import com.lumenarcanus.dialife.live.AlarmText
import androidx.compose.ui.platform.LocalContext
import com.lumenarcanus.dialife.data.ConePoint
import com.lumenarcanus.dialife.data.Forecast
import com.lumenarcanus.dialife.data.ForecastPoint
import com.lumenarcanus.dialife.data.History
import com.lumenarcanus.dialife.data.Series
import com.lumenarcanus.dialife.data.StateResponse
import com.lumenarcanus.dialife.data.Thresholds
import com.lumenarcanus.dialife.data.Treatment
import com.lumenarcanus.dialife.data.Question
import com.lumenarcanus.dialife.ui.treatment.TreatmentKind
import com.lumenarcanus.dialife.ui.treatment.TreatmentSheet
import androidx.compose.material3.AlertDialog
import com.lumenarcanus.dialife.data.Uploader
import com.lumenarcanus.dialife.ui.theme.DataLabel
import com.lumenarcanus.dialife.ui.theme.DiaLifeTheme
import com.lumenarcanus.dialife.ui.theme.DiaTheme
import com.lumenarcanus.dialife.ui.theme.GlucoseHero
import java.util.Locale

enum class Units { MgDl, Mmol }

/** Nightscout trend names, as the server sends them. */
enum class Trend(val degrees: Float, val double: Boolean = false) {
    DoubleUp(-90f, true), SingleUp(-90f), FortyFiveUp(-45f), Flat(0f),
    FortyFiveDown(45f), SingleDown(90f), DoubleDown(90f, true), Unknown(0f),
}

enum class Range { Low, InRange, High }

data class HomeState(
    val sgv: Int,
    val deltaMgdl: Int?,
    val ageMin: Int,
    val trend: Trend,
    val units: Units,
    val low: Int,
    val high: Int,
    val forecast30: Int?,
    val forecastQuality: com.lumenarcanus.dialife.data.ForecastQuality? = null,
    val iob: com.lumenarcanus.dialife.data.Iob? = null,
    val iobQuality: com.lumenarcanus.dialife.data.ForecastQuality? = null,
    val phoneBattery: Int?,
    val uploaderName: String? = null, // the family's name for the xDrip+ phone, from the server
    val alarms: Int = 0,
    val timeInRange: Int? = null, // percent over the last 24 h
) {
    val range: Range get() = when {
        sgv < low -> Range.Low
        sgv > high -> Range.High
        else -> Range.InRange
    }
}

// An evening like the mockups, for previews.
fun sampleHomeUi(now: Long = 1_761_400_000_000L, dad: String = "Тато", mom: String = "Мама"): HomeUi {
    val t = (0 until 288).map { now - (287 - it) * 300_000L }
    val v = t.indices.map { i -> (150 + 70 * kotlin.math.sin(i / 22.0) + 20 * kotlin.math.sin(i / 5.0)).toInt() }
    val bg = Bg(sgv = v.last(), mills = t.last(), direction = "Flat", delta = v.last() - v[v.size - 2])
    val ar2 = Forecast(
        predicted = (1..6).map { ForecastPoint(now + it * 300_000L, bg.sgv + it) },
        cone = (1..6).map { ConePoint(now + it * 300_000L, bg.sgv - it * 4, bg.sgv + it * 5) },
    )
    val treatments = listOf(
        Treatment("d", now - 3 * 3_600_000L - 300_000L, insulin = 18.0, insulinType = "basal", insulinName = "Lantus"),
        Treatment("a", now - 3 * 3_600_000L, insulin = 2.5, insulinType = "rapid", insulinName = "NovoRapid"),
        Treatment("b", now - 3 * 3_600_000L + 600_000L, carbs = 45.0),
        Treatment("m", t[235], glucose = v[235] + 14, eventType = "BG Check", enteredBy = mom),
        Treatment("x", t[262], duration = 40, intensity = "moderate", eventType = "Exercise", enteredBy = dad),
    )
    val questions = listOf(
        com.lumenarcanus.dialife.data.Question(
            1, "rise", "unknown", t[218], t[224], v[218], v[224],
        ),
        com.lumenarcanus.dialife.data.Question(
            2, "rise", "open", t[276], t[283], v[276], v[283], round = 2,
            answers = listOf(
                com.lumenarcanus.dialife.data.QuestionAnswer(dad, "nothing", 1, t[284]),
                com.lumenarcanus.dialife.data.QuestionAnswer(mom, "food_unknown", 1, t[285]),
            ),
            waiting = listOf(dad, mom), waitingIds = listOf(8, 9),
        ),
    )
    return HomeUi(
        state = StateResponse(now, "mg/dl", Thresholds(55, 80, 250, 300), bg, ar2, Uploader(battery = 69), questions = questions,
            forecastQuality = com.lumenarcanus.dialife.data.ForecastQuality(19, 74, 560, listOf(com.lumenarcanus.dialife.data.ForecastBlind("food", t[270]))),
            iob = com.lumenarcanus.dialife.data.Iob(1.8, 25, 40, listOf(com.lumenarcanus.dialife.data.IobPoint(t[287] + 15 * 60_000L, 176), com.lumenarcanus.dialife.data.IobPoint(t[287] + 30 * 60_000L, 168), com.lumenarcanus.dialife.data.IobPoint(t[287] + 60 * 60_000L, 150))),
            iobQuality = com.lumenarcanus.dialife.data.ForecastQuality(18, 71, 1900)),
        myId = 8,
        history = History(t.first(), now, Series(t, v), mbg = Series(listOf(t[270]), listOf(v[270] - 9)), treatments = treatments),
        now = now,
    )
}

/** The units to show: this phone's own choice (settings), else what the server keeps for it. */
fun unitsOf(displayUnits: String?): Units =
    com.lumenarcanus.dialife.data.Prefs.units ?: if (displayUnits?.startsWith("mmol") == true) Units.Mmol else Units.MgDl

/** What the hero and tiles show, or null while there is no reading yet. */
fun HomeUi.toHomeState(fallbackUnits: Units): HomeState? {
    val st = state ?: return null
    val bg = st.bg ?: return null
    val th = st.thresholds
    val inRange = history?.sgv?.v?.let { v -> if (v.isEmpty()) null else v.count { it in th.low..th.high } * 100 / v.size }
    return HomeState(
        sgv = bg.sgv,
        deltaMgdl = bg.delta,
        ageMin = ((now - bg.mills) / 60_000).toInt().coerceAtLeast(0),
        trend = Trend.entries.firstOrNull { it.name == bg.direction } ?: Trend.Unknown,
        units = st.displayUnits?.let(::unitsOf) ?: fallbackUnits,
        low = th.low,
        high = th.high,
        forecast30 = st.ar2?.predicted?.lastOrNull()?.sgv,
        forecastQuality = st.forecastQuality,
        iob = st.iob,
        iobQuality = st.iobQuality,
        phoneBattery = st.uploader?.battery,
        uploaderName = st.uploaderName,
        alarms = st.alarms.count { it.state != "cleared" },
        timeInRange = inRange,
    )
}

/** Readings older than this are shown as stale: the chain is broken somewhere. */
const val STALE_MIN = 15

/** mg/dL → the text shown for the chosen units, with the locale's decimal separator. */
fun formatGlucose(mgdl: Int, units: Units, locale: Locale): String = when (units) {
    Units.MgDl -> mgdl.toString()
    Units.Mmol -> String.format(locale, "%.1f", mgdl / 18.0)
}

fun formatDelta(mgdl: Int, units: Units, locale: Locale): String {
    val sign = if (mgdl > 0) "+" else if (mgdl < 0) "−" else "±"
    val abs = kotlin.math.abs(mgdl)
    return sign + when (units) {
        Units.MgDl -> abs.toString()
        Units.Mmol -> String.format(locale, "%.1f", abs / 18.0)
    }
}

@Composable
fun HomeScreen(
    ui: HomeUi,
    fallbackUnits: Units = Units.MgDl,
    deviceName: String = "",
    setup: BackgroundSetup = BackgroundSetup(),
    onAllowNotifications: () -> Unit = {},
    onAllowBackground: () -> Unit = {},
    onAllowFullScreen: () -> Unit = {},
    onAlarmAction: (action: String, alarmId: Long) -> Unit = { _, _ -> },
    onSaveTreatment: (Treatment) -> Unit = {},
    onDeleteTreatment: (String) -> Unit = {},
    onOpenStats: () -> Unit = {},
    onOpenLog: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenHandling: (Long) -> Unit = {},
    onRetry: () -> Unit = {},
    onRange: (Int) -> Unit = {},
    onDisconnect: () -> Unit = {},
    onAnswerQuestion: (id: Long, answer: String, treatmentId: String?) -> Unit = { _, _, _ -> },
    onDismissQuestion: (Long) -> Unit = {},
    onOpenData: () -> Unit = {},
    onOpenWebLogin: () -> Unit = {},
    onOpenMeal: (editing: Treatment?, question: Question?) -> Unit = { _, _ -> }, // the food screen
    onPlanned: (com.lumenarcanus.dialife.ui.meal.PlannedAction) -> Unit = {}, // given, changed or not given
) {
    val c = DiaTheme.colors
    val locale = LocalConfiguration.current.locales[0]
    val state = ui.toHomeState(fallbackUnits)
    val thresholds = ui.state?.thresholds ?: Thresholds()
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    // the add/edit sheet: which tab it opened on, and what it edits (null: a new entry)
    var sheet by remember { mutableStateOf<Pair<TreatmentKind, Treatment?>?>(null) }
    var confirmDelete by remember { mutableStateOf<Treatment?>(null) }
    var fullChart by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var sheetFor by remember { mutableStateOf<Question?>(null) } // the sheet answers this question
    // food has a screen of its own
    val food = sheet?.let { (kind, editing) ->
        editing?.let { (it.carbs ?: 0.0) > 0 && (it.insulin ?: 0.0) <= 0 && it.glucose == null && it.duration == null } ?: (kind == TreatmentKind.Food)
    } == true
    androidx.compose.runtime.LaunchedEffect(food) {
        if (food) {
            onOpenMeal(sheet?.second, sheetFor)
            sheet = null; sheetFor = null
        }
    }
    sheet?.takeIf { !food }?.let { (kind, editing) ->
        TreatmentSheet(
            kind, editing,
            onSave = {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                onSaveTreatment(it)
                sheetFor?.let { q -> onAnswerQuestion(q.id, if (q.kind == "rise") "food" else "insulin", it.id) }
                sheet = null; sheetFor = null
            },
            onDismiss = { sheet = null; sheetFor = null },
            initialTime = sheetFor?.from,
            recent = ui.history?.treatments.orEmpty(),
            units = state?.units ?: fallbackUnits,
            onFood = { onOpenMeal(null, sheetFor); sheet = null; sheetFor = null },
            dose = ui.state?.dose ?: com.lumenarcanus.dialife.data.DoseSettings(),
        )
    }
    confirmDelete?.let { tr ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.tr_delete_title)) },
            confirmButton = { TextButton(onClick = { onDeleteTreatment(tr.id); confirmDelete = null }) { Text(stringResource(R.string.tr_delete), color = c.low) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.tr_cancel)) } },
        )
    }
    if (fullChart) {
        FullScreenChart(onClose = { fullChart = false }) {
            ChartCard(
                history = ui.history,
                forecast = ui.state?.ar2,
                thresholds = thresholds,
                units = state?.units ?: fallbackUnits,
                rangeHours = ui.rangeHours,
                now = ui.now,
                onRange = onRange,
                modifier = Modifier.fillMaxSize(),
                onEdit = { sheet = TreatmentKind.Insulin to it },
                onDelete = { confirmDelete = it },
                highlightFrom = ui.state?.alarms?.filter { it.state != "cleared" }?.minOfOrNull { it.startedAt },
                expanded = true,
                onExpand = { fullChart = false },
                meter = ui.meter,
                suspects = ui.state?.questions.orEmpty(),
                onDismissSuspect = onDismissQuestion,
                dose = ui.state?.dose ?: com.lumenarcanus.dialife.data.DoseSettings(),
                planned = ui.state?.planned.orEmpty(),
            )
        }
        return
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        val status = when {
            ui.offline -> R.string.status_offline
            state == null || state.ageMin >= STALE_MIN -> R.string.status_stale
            else -> R.string.status_live
        }
        Header(deviceName, status, onOpenStats, onOpenLog, onOpenSettings, onOpenWebLogin)
        if (state != null) {
            Hero(state, locale)
        } else {
            Empty(loading = ui.state == null && !ui.offline)
        }
        // banners come and go without making the page jump
        androidx.compose.animation.AnimatedVisibility(ui.offline) { OfflineBanner(ui, onRetry) }
        androidx.compose.animation.AnimatedVisibility(!ui.offline && state != null && state.ageMin >= STALE_MIN) {
            state?.let { StaleBanner(ui, it) }
        }
        Column(Modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            ui.state?.alarms.orEmpty().forEach { a ->
                androidx.compose.runtime.key(a.id) { EpisodeBanner(a, ui, state?.units ?: fallbackUnits, onAlarmAction, onOpenHandling) }
            }
        }
        // someone else has the insulin form open: talk before reaching for a pen too
        ui.state?.intents.orEmpty().filter { it.deviceId != ui.myId && it.until > ui.now }.takeIf { it.isNotEmpty() }?.let {
            com.lumenarcanus.dialife.ui.treatment.IntentWarning(it, ui.now)
        }
        ui.state?.questions.orEmpty().filter { it.watch && (it.watchUntil ?: 0) > ui.now }.forEach { q ->
            androidx.compose.runtime.key("w", q.id) { WatchCard(q) }
        }
        // doses worked out for a meal: given, changed or not given, on every phone
        ui.state?.planned.orEmpty().filter { it.state == "planned" }.forEach { p ->
            androidx.compose.runtime.key("p", p.id) {
                com.lumenarcanus.dialife.ui.meal.PlannedCard(
                    p, ui.state?.dose ?: com.lumenarcanus.dialife.data.DoseSettings(), ui.now, ui.history?.treatments.orEmpty(),
                    question = ui.state?.questions.orEmpty().firstOrNull { it.kind == "planned" && it.plannedId == p.id && it.state == "open" },
                    units = state?.units ?: fallbackUnits,
                    onPlanned = onPlanned,
                    onSaveTreatment = onSaveTreatment,
                )
            }
        }
        ui.state?.questions.orEmpty().filter { it.state == "open" && it.kind != "planned" }.forEach { q ->
            androidx.compose.runtime.key("q", q.id) {
                QuestionCard(
                    q, ui.myId, ui.history?.treatments.orEmpty(), state?.units ?: fallbackUnits,
                    onAnswer = { answer, tr -> onAnswerQuestion(q.id, answer, tr) },
                    onLog = { sheetFor = q; sheet = (if (q.kind == "rise") TreatmentKind.Food else TreatmentKind.Insulin) to null },
                )
            }
        }
        if (setup.needed) SetupCard(setup, onAllowNotifications, onAllowBackground, onAllowFullScreen)
        ChartCard(
            history = ui.history,
            forecast = ui.state?.ar2,
            thresholds = thresholds,
            units = state?.units ?: fallbackUnits,
            rangeHours = ui.rangeHours,
            now = ui.now,
            onRange = onRange,
            modifier = Modifier.fillMaxWidth(),
            onEdit = { sheet = TreatmentKind.Insulin to it },
            onDelete = { confirmDelete = it },
            highlightFrom = ui.state?.alarms?.filter { it.state != "cleared" }?.minOfOrNull { it.startedAt },
            onExpand = { fullChart = true },
            meter = ui.meter,
            suspects = ui.state?.questions.orEmpty(),
            onDismissSuspect = onDismissQuestion,
            dose = ui.state?.dose ?: com.lumenarcanus.dialife.data.DoseSettings(),
            planned = ui.state?.planned.orEmpty(),
            onOpenTable = onOpenData,
        )
        state?.let { Tiles(it, onOpenStats, onOpenLog) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionButton(stringResource(R.string.add_insulin), c.insulinContainer, c.insulin, Modifier.weight(1f)) { sheet = TreatmentKind.Insulin to null }
            ActionButton(stringResource(R.string.add_food), c.carbsContainer, c.carbs, Modifier.weight(1f)) { sheet = TreatmentKind.Food to null }
            ActionButton(stringResource(R.string.add_meter), c.surfaceRaised, c.text, Modifier.weight(1f)) { sheet = TreatmentKind.Meter to null }
        }
    }
}

/**
 * "Did you forget to log?" Everyone answers; whoever has answered sees who is
 * still to; when the answers differ, the card shows what each said and asks again.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun QuestionCard(
    q: Question,
    myId: Long,
    treatments: List<Treatment>,
    units: Units,
    onAnswer: (answer: String, treatmentId: String?) -> Unit,
    onLog: () -> Unit,
) {
    val c = DiaTheme.colors
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    fun g(v: Int) = formatGlucose(v, units, locale)
    val rise = q.kind == "rise"
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.highContainer, RoundedCornerShape(22.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(com.lumenarcanus.dialife.live.QuestionNotifier.questionTitle(ctx, q), color = c.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text(
            com.lumenarcanus.dialife.live.QuestionNotifier.questionBody(ctx, q, units),
            color = c.text, fontSize = 14.sp,
        )
        q.previous().takeIf { it.isNotEmpty() }?.let { prev ->
            Text(
                stringResource(R.string.q_again, prev.joinToString(", ") { ctx.getString(R.string.q_said, it.person, com.lumenarcanus.dialife.live.QuestionNotifier.answerText(ctx, it.answer)) }),
                color = c.high, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            )
        }
        val double = q.kind == "double"
        // an entry someone made for it: the others only confirm it
        val entry = q.treatmentId?.takeIf { !double }?.let { id -> treatments.firstOrNull { it.id == id } }
        entry?.let { tr ->
            val what = listOfNotNull(
                tr.carbs?.takeIf { it > 0 }?.let { com.lumenarcanus.dialife.ui.treatment.formatAmount(it, locale) + " " + stringResource(R.string.unit_carbs) },
                tr.insulin?.takeIf { it > 0 }?.let { com.lumenarcanus.dialife.ui.treatment.formatAmount(it, locale) + " " + stringResource(R.string.unit_insulin) },
            ).joinToString(" · ")
            Text(stringResource(R.string.q_entered, what, com.lumenarcanus.dialife.live.AlarmText.clock(ctx, tr.t), tr.enteredBy.orEmpty()), color = c.textMuted, fontSize = 13.sp)
        }
        if (q.asks(myId)) {
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (double) {
                    Button(onClick = { onAnswer("one", null) }) { Text(stringResource(R.string.q_btn_one)) }
                    androidx.compose.material3.OutlinedButton(onClick = { onAnswer("two", null) }) { Text(stringResource(R.string.q_btn_two), color = c.text) }
                    return@FlowRow
                }
                if (entry != null) {
                    Button(onClick = { onAnswer(if (rise) "food" else "insulin", entry.id) }) { Text(stringResource(R.string.q_btn_yes)) }
                } else {
                    Button(onClick = onLog) { Text(stringResource(if (rise) R.string.q_btn_food else R.string.q_btn_insulin)) }
                }
                if (rise) androidx.compose.material3.OutlinedButton(onClick = { onAnswer("food_unknown", null) }) { Text(stringResource(R.string.q_btn_food_unknown), color = c.text) }
                // asleep she eats nothing: the rise is not food, and no more questions about it for a while
                if (rise) androidx.compose.material3.OutlinedButton(onClick = { onAnswer("asleep", null) }) { Text(stringResource(R.string.q_btn_asleep), color = c.text) }
                androidx.compose.material3.OutlinedButton(onClick = { onAnswer("nothing", null) }) { Text(stringResource(if (q.kind == "bolus") R.string.q_btn_no_insulin else R.string.q_btn_nothing), color = c.text) }
            }
        } else if (q.waiting.isNotEmpty()) {
            Text(stringResource(R.string.q_waiting, q.waiting.joinToString(", ")), color = c.textMuted, fontSize = 13.sp)
        }
    }
}

/** Two doses were given: everyone watches for a low until the insulin wears off. */
@Composable
private fun WatchCard(q: Question) {
    val c = DiaTheme.colors
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(
        Modifier.fillMaxWidth().background(c.lowContainer, RoundedCornerShape(22.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(R.string.watch_title), color = c.low, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text(com.lumenarcanus.dialife.live.QuestionNotifier.watchBody(ctx, q), color = c.text, fontSize = 14.sp)
    }
}

/**
 * The chart alone, sideways and edge to edge: on a small phone the card is too
 * small to read a night. Turns to landscape (either side) and back on close.
 */
@Composable
private fun FullScreenChart(onClose: () -> Unit, content: @Composable () -> Unit) {
    val c = DiaTheme.colors
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    androidx.compose.runtime.DisposableEffect(activity) {
        val window = activity?.window
        val bars = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, it.decorView) }
        activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        bars?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        onDispose {
            activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            bars?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }
    androidx.activity.compose.BackHandler(onBack = onClose)
    Box(
        Modifier
            .fillMaxSize()
            .background(c.background)
            .displayCutoutPadding()
            .padding(8.dp),
    ) { content() }
}

@Composable
private fun Header(deviceName: String, status: Int, onOpenStats: () -> Unit, onOpenLog: () -> Unit, onOpenSettings: () -> Unit, onOpenWebLogin: () -> Unit) {
    val c = DiaTheme.colors
    val live = status == R.string.status_live
    val offline = status == R.string.status_offline
    val blue = if (c.isDark) Color(0xFF7FB2FF) else Color(0xFF1F5FCC)
    val blueBg = if (c.isDark) Color(0xFF0E1C33) else Color(0xFFE3EDFF)
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium, color = c.text)
        Spacer(Modifier.size(10.dp))
        Row(
            Modifier
                .background(if (live) c.inRangeContainer else if (offline) blueBg else c.highContainer, CircleShape)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(6.dp).background(if (live) c.inRange else if (offline) blue else c.high, CircleShape))
            Text(
                stringResource(status),
                color = if (live) c.onInRangeContainer else if (offline) blue else c.high, fontSize = 12.sp, fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.weight(1f))
        Box {
            TextButton(onClick = { menu = true }) {
                Text(deviceName.ifBlank { stringResource(R.string.action_settings) }, color = c.textMuted, fontSize = 13.sp)
            }
            // A6 brings the real settings screen; until then: who this phone is, and a way out
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (deviceName.isNotBlank()) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.connected_as, deviceName)) }, onClick = {}, enabled = false)
                }
                DropdownMenuItem(text = { Text(stringResource(R.string.stats_title)) }, onClick = { menu = false; onOpenStats() })
                DropdownMenuItem(text = { Text(stringResource(R.string.log_title)) }, onClick = { menu = false; onOpenLog() })
                DropdownMenuItem(text = { Text(stringResource(R.string.web_title)) }, onClick = { menu = false; onOpenWebLogin() })
                DropdownMenuItem(text = { Text(stringResource(R.string.action_settings)) }, onClick = { menu = false; onOpenSettings() })
            }
        }
    }
}

@Composable
private fun Hero(state: HomeState, locale: Locale) {
    val c = DiaTheme.colors
    val stateColor = when (state.range) {
        Range.Low -> c.low
        Range.InRange -> c.inRange
        Range.High -> c.high
    }
    val units = if (state.units == Units.MgDl) stringResource(R.string.unit_mgdl) else stringResource(R.string.unit_mmol)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { // trend badge centred on the number
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.weight(1f)) {
                // weighted, so the units label is measured first and the number shrinks to what is left
                // a new reading slides in: up when it rose, down when it fell
                androidx.compose.animation.AnimatedContent(
                    targetState = state.sgv,
                    transitionSpec = {
                        val up = targetState > initialState
                        (androidx.compose.animation.slideInVertically { h -> if (up) h / 2 else -h / 2 } + androidx.compose.animation.fadeIn())
                            .togetherWith(androidx.compose.animation.slideOutVertically { h -> if (up) -h / 2 else h / 2 } + androidx.compose.animation.fadeOut())
                    },
                    modifier = Modifier.weight(1f, fill = false),
                    label = "sgv",
                ) { sgv ->
                BasicText(
                    formatGlucose(sgv, state.units, locale),
                    style = GlucoseHero.copy(
                        color = if (state.ageMin >= STALE_MIN) c.textFaint else c.text,
                        // an old number must not be read as the current one
                        textDecoration = if (state.ageMin >= STALE_MIN) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                    ),
                    maxLines = 1,
                    autoSize = TextAutoSize.StepBased(minFontSize = 56.sp, maxFontSize = GlucoseHero.fontSize, stepSize = 4.sp),
                )
                }
                Spacer(Modifier.size(8.dp))
                Text(
                    units, color = c.textMuted, fontSize = 17.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, softWrap = false, modifier = Modifier.padding(bottom = 16.dp),
                )
            }
            TrendBadge(state.trend, stateColor)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            state.deltaMgdl?.let {
                Text(
                    stringResource(R.string.delta_per_5, formatDelta(it, state.units, locale)),
                    color = c.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                )
                Dot()
            }
            val stale = state.ageMin >= STALE_MIN
            Text(
                pluralStringResource(R.plurals.minutes_ago, state.ageMin, state.ageMin),
                color = if (stale) c.high else c.textMuted, fontSize = 15.sp,
                fontWeight = if (stale) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
        // wraps onto a second line rather than squeezing a chip into a column of words
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val bounds = formatGlucose(state.low, state.units, locale) + "–" + formatGlucose(state.high, state.units, locale)
            val label = when (state.range) {
                Range.InRange -> stringResource(R.string.state_in_range, bounds)
                Range.Low -> stringResource(R.string.state_low, formatGlucose(state.low, state.units, locale))
                Range.High -> stringResource(R.string.state_high, formatGlucose(state.high, state.units, locale))
            }
            // an old reading says nothing about the range now
            if (state.ageMin < STALE_MIN) Chip(label, background = if (state.range == Range.InRange) c.inRangeContainer else if (state.range == Range.Low) c.lowContainer else c.highContainer, color = stateColor)
            state.forecast30?.let {
                val q = state.forecastQuality?.takeIf { it.n >= 50 } // a record of fewer forecasts says little
                val text = if (q != null) stringResource(R.string.forecast_30_q, formatGlucose(it, state.units, locale), formatGlucose(q.err80, state.units, locale), q.hitPct)
                else stringResource(R.string.forecast_30, formatGlucose(it, state.units, locale))
                Chip(text, background = c.surface, color = c.textMuted)
            }
        }
        // the forecast that knows the food and insulin logged; alarms do not use it
        state.iob?.let { iob ->
            val u = stringResource(R.string.unit_insulin)
            val parts = listOfNotNull(
                stringResource(R.string.iob_insulin, com.lumenarcanus.dialife.ui.treatment.formatAmount(iob.insulin, locale), u).takeIf { iob.insulin >= 0.05 },
                stringResource(R.string.iob_carbs, iob.carbs).takeIf { iob.carbs > 0 },
            )
            val f30 = iob.forecast.getOrNull(1)?.v
            val q = state.iobQuality?.takeIf { it.n >= 50 }
            val fc = f30?.let { v ->
                if (q != null) stringResource(R.string.iob_forecast_q, formatGlucose(v, state.units, locale), q.hitPct) else stringResource(R.string.iob_forecast, formatGlucose(v, state.units, locale))
            }
            if (parts.isNotEmpty() || fc != null) Text(
                (parts + listOfNotNull(fc)).joinToString(" · "),
                color = c.insulin, fontSize = 13.sp,
                modifier = Modifier.background(c.insulinContainer.copy(alpha = 0.5f), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        // what the trend cannot see: food or rapid insulin in the last two hours
        state.forecastQuality?.ignores?.maxByOrNull { it.t }?.let { b ->
            val ctx = androidx.compose.ui.platform.LocalContext.current
            Text(
                stringResource(if (b.kind == "food") R.string.forecast_blind_food else R.string.forecast_blind_insulin, com.lumenarcanus.dialife.live.AlarmText.clock(ctx, b.t)),
                color = c.textFaint, fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun TrendBadge(trend: Trend, color: Color) {
    val c = DiaTheme.colors
    val description = stringResource(
        when {
            trend.degrees < 0 -> R.string.trend_up
            trend.degrees > 0 -> R.string.trend_down
            else -> R.string.trend_flat
        },
    )
    Box(
        Modifier
            .size(72.dp)
            .background(c.inRangeContainer.copy(alpha = if (c.isDark) 1f else 0.9f), CircleShape)
            .border(1.dp, color.copy(alpha = 0.25f), CircleShape)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(34.dp).rotate(trend.degrees)) {
            val w = size.width
            val stroke = w * 0.09f
            fun arrow(dx: Float) {
                drawLine(color, Offset(w * 0.12f + dx, w / 2), Offset(w * 0.82f + dx, w / 2), stroke, StrokeCap.Round)
                drawLine(color, Offset(w * 0.56f + dx, w * 0.24f), Offset(w * 0.82f + dx, w / 2), stroke, StrokeCap.Round)
                drawLine(color, Offset(w * 0.56f + dx, w * 0.76f), Offset(w * 0.82f + dx, w / 2), stroke, StrokeCap.Round)
            }
            if (trend.double) {
                arrow(-w * 0.1f); arrow(w * 0.1f)
            } else if (trend != Trend.Unknown) {
                arrow(0f)
            }
        }
    }
}

@Composable
private fun Tiles(state: HomeState, onOpenStats: () -> Unit, onOpenLog: () -> Unit) {
    val tiles = listOf(
        Triple(state.uploaderName ?: stringResource(R.string.tile_uploader_phone), state.phoneBattery?.let { "$it%" } ?: "—", null),
        Triple(stringResource(R.string.tile_time_in_range), state.timeInRange?.let { "$it%" } ?: "—", onOpenStats),
        Triple(
            stringResource(R.string.tile_alarms),
            if (state.alarms == 0) stringResource(R.string.tile_alarms_quiet) else pluralStringResource(R.plurals.tile_alarms_active, state.alarms, state.alarms),
            onOpenLog,
        ),
    )
    // three tiles in a row cut words in half with a large system font: then one per line
    if (androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            tiles.forEach { (label, value, open) -> WideTile(label, value, open) }
        }
        return
    }
    // equal heights even when one label wraps
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
        tiles.forEach { (label, value, open) ->
            Tile(label, value, Modifier.weight(1f).let { m -> if (open != null) m.clickable(onClick = open) else m })
        }
    }
}

@Composable
private fun WideTile(label: String, value: String, onClick: (() -> Unit)?) {
    val c = DiaTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.surface, RoundedCornerShape(18.dp))
            .border(1.dp, c.line, RoundedCornerShape(18.dp))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = c.textMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Tile(label: String, value: String, modifier: Modifier) {
    val c = DiaTheme.colors
    Column(
        modifier
            .fillMaxHeight()
            .background(c.surface, RoundedCornerShape(18.dp))
            .border(1.dp, c.line, RoundedCornerShape(18.dp))
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label, color = c.textMuted, fontSize = 12.sp)
        Spacer(Modifier.weight(1f))
        Text(value, color = c.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** No reading for a while: the chain is broken somewhere, and here is where to look. */
@Composable
private fun StaleBanner(ui: HomeUi, state: HomeState) {
    val c = DiaTheme.colors
    val ctx = LocalContext.current
    val bg = ui.state?.bg ?: return
    Column(
        Modifier.fillMaxWidth().background(c.highContainer, RoundedCornerShape(18.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(pluralStringResource(R.plurals.stale_title, state.ageMin, state.ageMin), color = c.high, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.stale_last, AlarmText.clock(ctx, bg.mills)), color = c.text, fontSize = 14.sp)
        ui.state.uploader?.let { u ->
            if (u.mills > 0) Text(stringResource(R.string.stale_phone, AlarmText.uploader(ctx, ui.state), AlarmText.clock(ctx, u.mills), u.battery?.let { "$it%" } ?: "—"), color = c.textMuted, fontSize = 14.sp)
        }
        Text(stringResource(R.string.stale_check), color = c.textMuted, fontSize = 14.sp)
    }
}

/** This phone cannot reach the server: its own problem, so a different colour from "no data". */
@Composable
private fun OfflineBanner(ui: HomeUi, onRetry: () -> Unit) {
    val c = DiaTheme.colors
    val blue = if (c.isDark) Color(0xFF7FB2FF) else Color(0xFF1F5FCC)
    val blueBg = if (c.isDark) Color(0xFF0E1C33) else Color(0xFFE3EDFF)
    Column(
        Modifier.fillMaxWidth().background(blueBg, RoundedCornerShape(18.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(R.string.conn_lost_title), color = blue, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.offline_body), color = c.text, fontSize = 14.sp)
        Text(stringResource(R.string.offline_check), color = c.textMuted, fontSize = 14.sp)
        BannerButton(stringResource(R.string.offline_retry), blue, c.background, onRetry)
    }
}

/** What Android still needs to allow before readings keep arriving with the app closed. */
data class BackgroundSetup(val notifications: Boolean = false, val battery: Boolean = false, val fullScreen: Boolean = false) {
    val needed get() = notifications || battery || fullScreen
}

/** A live episode on the home screen: what it is, who has it, and the one action that fits. */
@Composable
private fun EpisodeBanner(a: AlarmView, ui: HomeUi, units: Units, onAction: (String, Long) -> Unit, onOpen: (Long) -> Unit) {
    val c = DiaTheme.colors
    val ctx = LocalContext.current
    val myId = ui.myId
    val mine = a.handledById != 0L && a.handledById == myId
    val helpAsked = a.state == "handled" && !mine && a.helpAt != null && a.comingAt == null
    val (container, accent) = when {
        helpAsked -> c.highContainer to c.high
        a.state == "firing" && a.level == "urgent" -> c.urgentBackground to c.urgent
        a.state == "firing" -> (if (a.kind.endsWith("high")) c.highContainer else c.lowContainer) to (if (a.kind.endsWith("high")) c.high else c.low)
        else -> c.surface to c.text
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(container, RoundedCornerShape(18.dp))
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
            .padding(16.dp)
            .semantics(mergeDescendants = false) {},
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val title = when {
            a.state == "firing" -> AlarmText.title(ctx, a, units)
            helpAsked -> stringResource(R.string.help_title, a.handledBy.orEmpty())
            mine -> stringResource(R.string.you_handle, AlarmText.kind(ctx, a))
            else -> stringResource(R.string.someone_handles_kind, a.handledBy.orEmpty(), AlarmText.kind(ctx, a))
        }
        Text(title, color = accent, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        val details = if (a.state == "firing") AlarmText.reason(ctx, null, a) else AlarmText.progress(ctx, a, units)
        if (details.isNotBlank()) Text(details, color = c.textMuted, fontSize = 14.sp)
        when {
            a.comingById != 0L && a.comingById == myId -> Text(stringResource(R.string.you_are_coming), color = c.text, fontSize = 14.sp)
            !a.comingBy.isNullOrBlank() -> Text(stringResource(R.string.someone_coming, a.comingBy), color = c.text, fontSize = 14.sp)
        }
        a.quietUntil?.let { Text(stringResource(R.string.quiet_until_short, AlarmText.clock(ctx, it)), color = c.textMuted, fontSize = 14.sp) }
        // someone else has it: say exactly when this phone would ring again
        if (a.state == "handled" && !mine) {
            val rules = ui.state?.episode ?: com.lumenarcanus.dialife.data.EpisodeRules()
            val locale = ctx.resources.configuration.locales[0]
            val reasons = buildList {
                if (a.handledSgv != null && (a.kind == "low" || a.kind == "high")) {
                    val limit = if (a.kind == "low") a.handledSgv - rules.worseBy else a.handledSgv + rules.worseBy
                    add(stringResource(if (a.kind == "low") R.string.hd_wake_worse_low else R.string.hd_wake_worse_high, formatGlucose(limit, units, locale)))
                }
                add(stringResource(R.string.wake_if_no_answer, a.handledBy.orEmpty()))
                if (a.level == "urgent" && a.handledAt != null) add(stringResource(R.string.wake_if_ceiling, AlarmText.clock(ctx, a.handledAt + rules.ceilingMin * 60_000L)))
            }
            Text(stringResource(R.string.wake_if_title) + " " + reasons.joinToString("; "), color = c.textMuted, fontSize = 13.sp)
        }
        when {
            a.state == "firing" -> {
                BannerButton(stringResource(R.string.act_handle), accent, c.background) { onAction(ActionReceiver.HANDLE, a.id) }
                // "the receiver works": a stale or non-urgent alarm can be quieted for 2 h
                if (!a.urgentLow) BannerButton(stringResource(R.string.act_quiet), c.surfaceRaised, c.text) { onAction(ActionReceiver.QUIET, a.id) }
            }
            mine -> {
                if (a.checkinPending) BannerButton(stringResource(R.string.checkin_title), c.high, c.background) { onOpen(a.id) }
                BannerButton(stringResource(R.string.hd_open), c.surfaceRaised, c.text) { onOpen(a.id) }
            }
            a.comingById != myId -> BannerButton(stringResource(R.string.act_coming), c.surfaceRaised, c.text) { onAction(ActionReceiver.COMING, a.id) }
        }
    }
}

@Composable
private fun BannerButton(text: String, container: Color, content: Color, onClick: () -> Unit) {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    Button(
        onClick = { haptics.performHapticFeedback(HapticFeedbackType.Confirm); onClick() },
        modifier = Modifier.fillMaxWidth().height(48.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
    ) {
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SetupCard(setup: BackgroundSetup, onNotifications: () -> Unit, onBattery: () -> Unit, onFullScreen: () -> Unit) {
    val c = DiaTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.highContainer, RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.bg_card_title), color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.bg_card_body), color = c.textMuted, fontSize = 14.sp)
        if (setup.notifications) SetupButton(stringResource(R.string.bg_card_notifications), onNotifications)
        if (setup.battery) SetupButton(stringResource(R.string.bg_card_battery), onBattery)
        if (setup.fullScreen) SetupButton(stringResource(R.string.bg_card_fullscreen), onFullScreen)
    }
}

@Composable
private fun SetupButton(text: String, onClick: () -> Unit) {
    val c = DiaTheme.colors
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = c.high, contentColor = c.background),
    ) {
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Empty(loading: Boolean) {
    val c = DiaTheme.colors
    Column(
        Modifier.fillMaxWidth().height(180.dp).padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Text(
            stringResource(if (loading) R.string.home_loading else R.string.home_no_data),
            color = c.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
        )
        if (!loading) Text(stringResource(R.string.home_no_data_hint), color = c.textMuted, fontSize = 15.sp)
    }
}

@Composable
private fun Chip(text: String, background: Color, color: Color) {
    Text(
        text,
        color = color,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.background(background, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun Dot() {
    Box(Modifier.size(3.dp).background(DiaTheme.colors.textFaint, CircleShape))
}

@Composable
private fun ActionButton(text: String, background: Color, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.height(56.dp),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = background, contentColor = color),
        contentPadding = PaddingValues(0.dp),
    ) {
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Preview(name = "Dark · uk", locale = "uk", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES, widthDp = 412, heightDp = 915)
@Composable
private fun PreviewDarkUk() = DiaLifeTheme(dark = true) { HomeScreen(sampleHomeUi()) }

@Preview(name = "Light · en · mmol", locale = "en", widthDp = 412, heightDp = 915)
@Composable
private fun PreviewLightEn() = DiaLifeTheme(dark = false) { HomeScreen(sampleHomeUi().let { it.copy(state = it.state?.copy(displayUnits = "mmol")) }) }

/** A sensor that reads ~7% low, with enough checks for the "≈ meter" line, for previews. */
fun sampleMeter(now: Long = 1_761_400_000_000L): com.lumenarcanus.dialife.data.MeterReport {
    val day = 86_400_000L
    val starts = listOf(now - 25 * day, now - 15 * day, now - 5 * day)
    val pairs = listOf(
        88 to 96, 142 to 150, 205 to 228, 176 to 185, 260 to 291, 120 to 131, 98 to 104, 232 to 255,
        70 to 72, 155 to 170, 199 to 214, 240 to 268, 130 to 136, 110 to 121, 285 to 316,
        96 to 101, 168 to 172, 212 to 222, 144 to 150,
    ).mapIndexed { i, (s, m) ->
        val k = if (i < 8) 1 else if (i < 15) 2 else 3
        com.lumenarcanus.dialife.data.MeterPair(now - (24 - i) * day, m, s.toDouble(), k, used = i != 4)
    }
    return com.lumenarcanus.dialife.data.MeterReport(
        ready = true, used = 18, total = 19, sensors = 3, biasPct = -7.0, mard = 9.4,
        current = com.lumenarcanus.dialife.data.MeterCurrent(starts[2], 5, 4, -3.0),
        bands = listOf(
            com.lumenarcanus.dialife.data.MeterBand(0, 100, "mgdl", -4.0, 5),
            com.lumenarcanus.dialife.data.MeterBand(100, 200, "pct", -6.0, 8),
            com.lumenarcanus.dialife.data.MeterBand(200, 0, "pct", -11.0, 5),
        ),
        model = com.lumenarcanus.dialife.data.MeterModel(a = 2.0, b = 0.07, offset = -0.02, sd = 0.09),
        pairs = pairs, starts = starts,
    )
}
