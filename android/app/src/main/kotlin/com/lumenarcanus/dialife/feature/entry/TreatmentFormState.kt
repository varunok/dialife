package com.lumenarcanus.dialife.feature.entry

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import com.lumenarcanus.dialife.core.ui.Units
import com.lumenarcanus.dialife.core.ui.formatGlucose
import com.lumenarcanus.dialife.data.DoseCalc
import com.lumenarcanus.dialife.data.DoseSettings
import com.lumenarcanus.dialife.data.InsulinType
import com.lumenarcanus.dialife.data.MealRules
import com.lumenarcanus.dialife.data.Meals
import com.lumenarcanus.dialife.data.Prefs
import com.lumenarcanus.dialife.data.Treatment
import com.lumenarcanus.dialife.live.TreatmentQueue
import java.util.Locale

/** The kind an entry is edited as; a new one is the kind its button opened. */
fun kindOf(editing: Treatment?, opened: TreatmentKind): TreatmentKind = when {
    editing == null -> opened
    editing.glucose != null -> TreatmentKind.Meter
    editing.duration != null -> TreatmentKind.Activity
    (editing.insulin ?: 0.0) > 0 -> TreatmentKind.Insulin
    (editing.carbs ?: 0.0) > 0 -> TreatmentKind.Food
    else -> TreatmentKind.Note
}

/**
 * What is typed on the entry sheet: the amounts for each kind, when, the note,
 * and which dialog is up. Starts from the entry being edited, or empty with
 * the insulin most likely due. [amountOk] and [build] are the sheet's rules.
 */
@Stable
class TreatmentFormState(
    val kind: TreatmentKind,
    val editing: Treatment?,
    private val recent: List<Treatment>,
    private val units: Units,
    // "now" for the time chips: when the sheet opened
    val openedAt: Long,
    locale: Locale,
    initialTime: Long? = null,
    basalTimes: List<Int> = Prefs.basalTimes
) {
    var insulin by mutableDoubleStateOf(editing?.insulin ?: 0.0)
        private set
    var insType by mutableStateOf(editing?.let { InsulinType.of(it.insulinType) } ?: guessInsulinType(openedAt, recent, basalTimes))

    // rapid: for a meal or a correction; null: the meal near it chooses
    var insRole by mutableStateOf(editing?.insulinRole)

    // a dose over the limit, confirmed
    var okBig by mutableStateOf(false)
    val foodRows = mutableStateListOf<FoodRow>().apply { addAll(foodRowsOf(editing?.foodItems, editing?.carbs)) }
    var meter by mutableStateOf(editing?.glucose?.let { formatGlucose(it, units, locale) }.orEmpty())
    var minutes by mutableDoubleStateOf(editing?.duration?.toDouble() ?: 30.0)
    var intensity by mutableStateOf(editing?.intensity ?: "moderate")

    // the Activity sheet's "Shower" tab: how long, in steps of 5 minutes
    var shower by mutableStateOf(editing?.isShower == true)
    var showerMinutes by mutableDoubleStateOf(editing?.duration?.takeIf { editing.isShower }?.toDouble() ?: SHOWER_DEFAULT_MIN)
    var note by mutableStateOf(editing?.notes.orEmpty())
    var time by mutableLongStateOf(editing?.t ?: initialTime ?: openedAt)

    // a dose just like one already logged: "the same injection?" is asked
    var confirming by mutableStateOf<NearDose?>(null)

    // the clock dial is up
    var picking by mutableStateOf(false)

    val carbs get() = foodRows.sumOf { it.grams ?: 0 }.toDouble()

    val lastBasal get() = recent.lastOrNull { InsulinType.of(it.insulinType) == InsulinType.Basal && (it.insulin ?: 0.0) > 0 }?.insulin

    /** A new amount: a confirmation of a big dose was for the old one. */
    fun changeInsulin(v: Double) {
        insulin = v
        okBig = false
    }

    /** Long-acting is usually the same dose every day: a new one starts from the last. */
    fun startFromLastBasal() {
        val last = lastBasal
        if (editing == null && insType == InsulinType.Basal && insulin == 0.0 && last != null) insulin = last
    }

    /** The meal this dose is near, if any: it decides "for a meal" until a tap does. */
    fun nearMeal(rules: MealRules) = Meals.group(recent, rules).lastOrNull { time in (it.from - rules.beforeMs)..(it.to + rules.afterMs) }

    /** What a rapid dose is for: as tapped, else by the meal near it. */
    fun role(rules: MealRules): String = insRole ?: if (nearMeal(rules) != null) "meal" else "corr"

    fun limit(dose: DoseSettings) = if (insType == InsulinType.Basal) dose.maxBasal else dose.maxRapid

    fun level(dose: DoseSettings) = DoseCalc.level(insulin, limit(dose), null)

    /** Whether there is something to save: an amount, and a big dose confirmed. */
    fun amountOk(dose: DoseSettings): Boolean = when (kind) {
        TreatmentKind.Insulin -> insulin > 0 && level(dose).let { it == DoseCalc.Level.Ok || it == DoseCalc.Level.Unusual || okBig }
        TreatmentKind.Food -> carbs > 0
        TreatmentKind.Meter -> parseMeter(meter, units) != null
        TreatmentKind.Activity -> if (shower) showerMinutes > 0 else minutes > 0
        TreatmentKind.Note -> note.isNotBlank()
    }

    /** A dose like this one logged a moment ago: asked about before saving. */
    fun nearOnSave(): NearDose? = if (editing == null && kind == TreatmentKind.Insulin) nearDose(recent, insType, time, null) else null

    /** The entry to save; [confirmedNew]: the dose near it that this one is not. */
    fun build(confirmedNew: String?, rules: MealRules, nameOf: (InsulinType) -> String? = Prefs::nameOf): Treatment {
        val e = editing
        val isShower = kind == TreatmentKind.Activity && shower
        return Treatment(
            id = e?.id ?: TreatmentQueue.newId(),
            t = time,
            eventType = if (isShower) Treatment.SHOWER else null,
            // an edit keeps the other half of a meal bolus untouched
            insulin = if (kind == TreatmentKind.Insulin) insulin else e?.insulin?.takeIf { kind == TreatmentKind.Food },
            carbs = if (kind == TreatmentKind.Food) carbs else e?.carbs?.takeIf { kind == TreatmentKind.Insulin },
            glucose = if (kind == TreatmentKind.Meter) parseMeter(meter, units) else null,
            duration = when {
                isShower -> showerMinutes.toInt()
                kind == TreatmentKind.Activity -> minutes.toInt()
                else -> null
            },
            intensity = if (kind == TreatmentKind.Activity && !isShower) intensity else null,
            notes = note.trim().ifBlank { null },
            insulinType = if (kind == TreatmentKind.Insulin) insType.wire else e?.insulinType,
            insulinName = if (kind == TreatmentKind.Insulin) {
                e?.insulinName?.takeIf { InsulinType.of(e.insulinType) == insType } ?: nameOf(insType)
            } else {
                e?.insulinName
            },
            confirmedNew = confirmedNew,
            foodItems = if (kind == TreatmentKind.Food) foodItemsOf(foodRows) else e?.foodItems?.takeIf { kind == TreatmentKind.Insulin },
            insulinRole = if (kind == TreatmentKind.Insulin && insType == InsulinType.Rapid) role(rules) else e?.insulinRole,
            mealId = e?.mealId
        )
    }

    companion object {
        const val SHOWER_DEFAULT_MIN = 10.0
        const val SHOWER_MAX_MIN = 120.0 // the server caps it the same

        /** Keeps what was typed across a rotation (the dishes and dialogs as before: not kept). */
        fun saver(make: () -> TreatmentFormState): Saver<TreatmentFormState, Any> = listSaver(
            save = {
                listOf(
                    it.insulin, it.insType.name, it.insRole, it.meter, it.minutes, it.intensity, it.note, it.time,
                    it.shower, it.showerMinutes
                )
            },
            restore = { v ->
                make().apply {
                    insulin = v[0] as Double
                    insType = InsulinType.valueOf(v[1] as String)
                    insRole = v[2] as String?
                    meter = v[3] as String
                    minutes = v[4] as Double
                    intensity = v[5] as String
                    note = v[6] as String
                    time = v[7] as Long
                    shower = v[8] as Boolean
                    showerMinutes = v[9] as Double
                }
            }
        )
    }
}

@Composable
fun rememberTreatmentFormState(
    opened: TreatmentKind,
    editing: Treatment?,
    recent: List<Treatment>,
    units: Units,
    initialTime: Long?
): TreatmentFormState {
    val locale = LocalConfiguration.current.locales[0]
    val make = { TreatmentFormState(kindOf(editing, opened), editing, recent, units, System.currentTimeMillis(), locale, initialTime) }
    return rememberSaveable(saver = TreatmentFormState.saver(make)) { make() }
}
