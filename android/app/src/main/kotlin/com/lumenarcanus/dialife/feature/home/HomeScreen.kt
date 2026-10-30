package com.lumenarcanus.dialife.feature.home

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.core.data.FoodRepository
import com.lumenarcanus.dialife.core.ui.Units
import com.lumenarcanus.dialife.core.ui.theme.DiaLifeTheme
import com.lumenarcanus.dialife.core.ui.theme.DiaTheme
import com.lumenarcanus.dialife.data.DoseSettings
import com.lumenarcanus.dialife.data.InsulinType
import com.lumenarcanus.dialife.data.MealRules
import com.lumenarcanus.dialife.data.Question
import com.lumenarcanus.dialife.data.Thresholds
import com.lumenarcanus.dialife.data.Treatment
import com.lumenarcanus.dialife.feature.entry.IntentWarning
import com.lumenarcanus.dialife.feature.entry.TreatmentKind
import com.lumenarcanus.dialife.feature.entry.TreatmentSheet
import com.lumenarcanus.dialife.feature.entry.kindLook

@Composable
fun HomeScreen(
    ui: HomeUi,
    fallbackUnits: Units = Units.MgDl,
    deviceName: String = "",
    setup: BackgroundSetup = BackgroundSetup(),
    onAllowNotifications: () -> Unit = {},
    onAllowBackground: () -> Unit = {},
    onAllowFullScreen: () -> Unit = {},
    // live episodes: a feature with its own state (feature/alarms)
    alarmsSection: @Composable () -> Unit = {},
    onSaveTreatment: (Treatment) -> Unit = {},
    onDeleteTreatment: (String) -> Unit = {},
    onOpenStats: () -> Unit = {},
    onOpenLog: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onRetry: () -> Unit = {},
    onRange: (Int) -> Unit = {},
    onDisconnect: () -> Unit = {},
    onAnswerQuestion: (id: Long, answer: String, treatmentId: String?) -> Unit = { _, _, _ -> },
    onDismissQuestion: (Long) -> Unit = {},
    onOpenData: () -> Unit = {},
    onOpenWebLogin: () -> Unit = {},
    onOpenMeal: (editing: Treatment?, question: Question?) -> Unit = { _, _ -> }, // the food screen
    // doses worked out for a meal, not given yet: a feature with its own state (feature/planned)
    plannedSection: @Composable () -> Unit = {},
    // "did you forget to log?" and the watched doses: a feature with its own state (feature/questions)
    questionsSection: @Composable (onLog: (Question) -> Unit) -> Unit = {},
    watchSection: @Composable () -> Unit = {},
    // when to eat: a feature with its own state (feature/wait)
    waitSection: @Composable () -> Unit = {},
    // the entry sheet's: "… is giving insulin" on the other phones, food suggestions
    onAnnounceInsulin: (InsulinType?) -> Unit = {},
    foods: FoodRepository? = null,
    // the chart's "≈ meter" switch
    onMeterLine: (Boolean) -> Unit = {}
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
        editing?.let { (it.carbs ?: 0.0) > 0 && (it.insulin ?: 0.0) <= 0 && it.glucose == null && it.duration == null }
            ?: (kind == TreatmentKind.Food)
    } == true
    androidx.compose.runtime.LaunchedEffect(food) {
        if (food) {
            onOpenMeal(sheet?.second, sheetFor)
            sheet = null
            sheetFor = null
        }
    }
    sheet?.takeIf { !food }?.let { (kind, editing) ->
        TreatmentSheet(
            kind, editing,
            onSave = {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                onSaveTreatment(it)
                sheetFor?.let { q -> onAnswerQuestion(q.id, if (q.kind == "rise") "food" else "insulin", it.id) }
                sheet = null
                sheetFor = null
            },
            onDismiss = {
                sheet = null
                sheetFor = null
            },
            initialTime = sheetFor?.from,
            recent = ui.history?.treatments.orEmpty(),
            units = state?.units ?: fallbackUnits,
            dose = ui.state?.dose ?: DoseSettings(),
            mealRules = ui.state?.mealRules ?: MealRules(),
            state = ui.state,
            myId = ui.myId,
            onAnnounce = onAnnounceInsulin,
            foods = foods
        )
    }
    confirmDelete?.let { tr ->
        DeleteDialog(onDelete = {
            onDeleteTreatment(tr.id)
            confirmDelete = null
        }, onDismiss = { confirmDelete = null })
    }
    @Composable
    fun Chart(modifier: Modifier, expanded: Boolean, onExpand: () -> Unit, onOpenTable: (() -> Unit)?) = HomeChart(
        ui, state?.units ?: fallbackUnits, modifier, expanded, onExpand, onOpenTable, onMeterLine, onRange, onDismissQuestion,
        onEdit = { sheet = TreatmentKind.Insulin to it },
        onDelete = { confirmDelete = it }
    )
    if (fullChart) {
        FullScreenChart(onClose = { fullChart = false }) {
            Chart(Modifier.fillMaxSize(), expanded = true, onExpand = { fullChart = false }, onOpenTable = null)
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
        verticalArrangement = Arrangement.spacedBy(18.dp)
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
            alarmsSection()
        }
        // someone else has the insulin form open: talk before reaching for a pen too
        ui.state?.intents.orEmpty().filter { it.deviceId != ui.myId && it.until > ui.now }.takeIf { it.isNotEmpty() }?.let {
            IntentWarning(it, ui.now)
        }
        watchSection()
        plannedSection()
        waitSection()
        questionsSection { q ->
            sheetFor = q
            sheet = (if (q.kind == "rise") TreatmentKind.Food else TreatmentKind.Insulin) to null
        }
        if (setup.needed) SetupCard(setup, onAllowNotifications, onAllowBackground, onAllowFullScreen)
        Chart(Modifier.fillMaxWidth(), expanded = false, onExpand = { fullChart = true }, onOpenTable = onOpenData)
        state?.let { Tiles(it, onOpenStats, onOpenLog) }
        EntryButtons { kind -> sheet = kind to null }
    }
}

@Preview(name = "Dark · uk", locale = "uk", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES, widthDp = 412, heightDp = 915)
@Composable
private fun PreviewDarkUk() = DiaLifeTheme(dark = true) { HomeScreen(sampleHomeUi()) }

@Preview(name = "Light · en · mmol", locale = "en", widthDp = 412, heightDp = 915)
@Composable
private fun PreviewLightEn() = DiaLifeTheme(dark = false) {
    HomeScreen(sampleHomeUi().let { it.copy(state = it.state?.copy(displayUnits = "mmol")) })
}

/** The chart card on home or full screen: the same data, the same marks, the same taps. */
@Composable
private fun HomeChart(
    ui: HomeUi,
    units: Units,
    modifier: Modifier,
    expanded: Boolean,
    onExpand: () -> Unit,
    onOpenTable: (() -> Unit)?,
    onMeterLine: (Boolean) -> Unit,
    onRange: (Int) -> Unit,
    onDismissSuspect: (Long) -> Unit,
    onEdit: (Treatment) -> Unit,
    onDelete: (Treatment) -> Unit
) = ChartCard(
    history = ui.history,
    forecast = ui.state?.ar2,
    thresholds = ui.state?.thresholds ?: Thresholds(),
    units = units,
    rangeHours = ui.rangeHours,
    now = ui.now,
    onMeterLine = onMeterLine,
    onRange = onRange,
    modifier = modifier,
    onEdit = onEdit,
    onDelete = onDelete,
    highlightFrom = ui.state?.alarms?.filter { it.state != "cleared" }?.minOfOrNull { it.startedAt },
    expanded = expanded,
    onExpand = onExpand,
    meter = ui.meter,
    suspects = ui.state?.questions.orEmpty(),
    onDismissSuspect = onDismissSuspect,
    dose = ui.state?.dose ?: DoseSettings(),
    planned = ui.state?.planned.orEmpty(),
    mealRules = ui.state?.mealRules ?: MealRules(),
    onOpenTable = onOpenTable
)

/** "Delete the entry?" */
@Composable
private fun DeleteDialog(onDelete: () -> Unit, onDismiss: () -> Unit) {
    val c = DiaTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tr_delete_title)) },
        confirmButton = { TextButton(onClick = onDelete) { Text(stringResource(R.string.tr_delete), color = c.low) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.tr_cancel)) } }
    )
}

/**
 * A button for every kind of entry, each with its chart letter; the form opens on that kind.
 * The three side by side when every label fits next to its sign at full size; on a narrow
 * screen or a large font, the sign goes above the label.
 */
@Composable
private fun EntryButtons(onOpen: (TreatmentKind) -> Unit) {
    androidx.compose.foundation.layout.BoxWithConstraints {
        val gap = 10.dp
        val measurer = androidx.compose.ui.text.rememberTextMeasurer()
        val density = androidx.compose.ui.platform.LocalDensity.current
        val kinds = listOf(TreatmentKind.Insulin, TreatmentKind.Food, TreatmentKind.Meter)
        val labels = kinds.map { stringResource(kindLook(it).name) }
        val style = androidx.compose.material3.LocalTextStyle.current.merge(
            androidx.compose.ui.text.TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        )
        val stacked = kinds.zip(labels).any { (k, l) ->
            // insulin carries two signs, overlapping
            val inner = ACTION_INNER + if (k == TreatmentKind.Insulin) 15.dp else 0.dp
            measurer.measure(l, style).size.width > with(density) { ((maxWidth - gap * 2) / 3 - inner).roundToPx() }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            ActionButton(TreatmentKind.Insulin, Modifier.weight(1f), stacked = stacked) { onOpen(TreatmentKind.Insulin) }
            ActionButton(TreatmentKind.Food, Modifier.weight(1f), stacked = stacked) { onOpen(TreatmentKind.Food) }
            ActionButton(TreatmentKind.Meter, Modifier.weight(1f), stacked = stacked) { onOpen(TreatmentKind.Meter) }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ActionButton(TreatmentKind.Activity, Modifier.weight(1f), small = true) { onOpen(TreatmentKind.Activity) }
        ActionButton(TreatmentKind.Note, Modifier.weight(1f), small = true) { onOpen(TreatmentKind.Note) }
    }
}
