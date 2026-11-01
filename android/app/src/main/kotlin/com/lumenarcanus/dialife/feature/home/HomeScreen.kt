package com.lumenarcanus.dialife.feature.home

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
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
    val haptics = LocalHapticFeedback.current
    // the add/edit sheet: which tab it opened on, what it edits, the question it answers
    var sheet by remember { mutableStateOf<SheetRequest?>(null) }
    var confirmDelete by remember { mutableStateOf<Treatment?>(null) }
    var fullChart by rememberSaveable { mutableStateOf(false) }
    EntrySheetHost(
        sheet,
        ui,
        state?.units ?: fallbackUnits,
        onClose = { sheet = null },
        onOpenMeal = onOpenMeal,
        onSave = { tr ->
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            onSaveTreatment(tr)
            sheet?.question?.let { q -> onAnswerQuestion(q.id, if (q.kind == "rise") "food" else "insulin", tr.id) }
            sheet = null
        },
        onAnnounce = onAnnounceInsulin,
        foods = foods
    )
    confirmDelete?.let { tr ->
        DeleteDialog(onDelete = {
            onDeleteTreatment(tr.id)
            confirmDelete = null
        }, onDismiss = { confirmDelete = null })
    }
    @Composable
    fun Chart(modifier: Modifier, expanded: Boolean, onExpand: () -> Unit, onOpenTable: (() -> Unit)?) = HomeChart(
        ui, state?.units ?: fallbackUnits, modifier, expanded, onExpand, onOpenTable, onMeterLine, onRange, onDismissQuestion,
        onEdit = { sheet = SheetRequest(TreatmentKind.Insulin, it) },
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
        AnimatedVisibility(ui.offline) { OfflineBanner(ui, onRetry) }
        AnimatedVisibility(!ui.offline && state != null && state.ageMin >= STALE_MIN) {
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
        questionsSection { q -> sheet = SheetRequest(if (q.kind == "rise") TreatmentKind.Food else TreatmentKind.Insulin, question = q) }
        if (setup.needed) SetupCard(setup, onAllowNotifications, onAllowBackground, onAllowFullScreen)
        Chart(Modifier.fillMaxWidth(), expanded = false, onExpand = { fullChart = true }, onOpenTable = onOpenData)
        state?.let { Tiles(it, onOpenStats, onOpenLog) }
        EntryButtons { kind -> sheet = SheetRequest(kind) }
    }
}

@Preview(name = "Dark · uk", locale = "uk", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 412, heightDp = 915)
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
    BoxWithConstraints {
        val gap = 10.dp
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val kinds = listOf(TreatmentKind.Insulin, TreatmentKind.Food, TreatmentKind.Meter)
        val labels = kinds.map { stringResource(kindLook(it).name) }
        val style = LocalTextStyle.current.merge(
            TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
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

/** What the entry sheet opens with: its kind, the entry it edits (null: a new one), the question it answers. */
private data class SheetRequest(val kind: TreatmentKind, val editing: Treatment? = null, val question: Question? = null) {
    /** Food has a screen of its own. */
    val isFood: Boolean
        get() = editing?.let { (it.carbs ?: 0.0) > 0 && (it.insulin ?: 0.0) <= 0 && it.glucose == null && it.duration == null }
            ?: (kind == TreatmentKind.Food)
}

/** The entry sheet for [request]; food goes to its own screen instead. */
@Composable
private fun EntrySheetHost(
    request: SheetRequest?,
    ui: HomeUi,
    units: Units,
    onClose: () -> Unit,
    onOpenMeal: (editing: Treatment?, question: Question?) -> Unit,
    onSave: (Treatment) -> Unit,
    onAnnounce: (InsulinType?) -> Unit,
    foods: FoodRepository?
) {
    val food = request?.isFood == true
    LaunchedEffect(food) {
        if (food) {
            onOpenMeal(request?.editing, request?.question)
            onClose()
        }
    }
    request?.takeIf { !food }?.let { r ->
        TreatmentSheet(
            r.kind,
            r.editing,
            onSave = onSave,
            onDismiss = onClose,
            initialTime = r.question?.from,
            recent = ui.history?.treatments.orEmpty(),
            units = units,
            dose = ui.state?.dose ?: DoseSettings(),
            mealRules = ui.state?.mealRules ?: MealRules(),
            state = ui.state,
            myId = ui.myId,
            onAnnounce = onAnnounce,
            foods = foods
        )
    }
}
