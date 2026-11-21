package com.lumenarcanus.dialife.feature.meal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.lumenarcanus.dialife.data.Treatment
import com.lumenarcanus.dialife.feature.entry.FoodRow
import com.lumenarcanus.dialife.feature.entry.foodRowsOf
import com.lumenarcanus.dialife.feature.entry.newFoodRow
import com.lumenarcanus.dialife.live.TreatmentQueue
import java.util.UUID

/** Minutes ago for the time chips; the last chip picks a time. */
internal val TimeShifts = listOf(0, 15, 30)

/** The chip index that means "a picked time". */
internal const val PICKED_TIME = 3

/**
 * What is typed on the food form: the dishes, when it was eaten, the plan and
 * join choices, the glucometer field, and which dialog is up. Starts from the
 * saved draft, the entry being edited, or empty; [toDraft] is what goes back
 * to the draft on every change.
 */
@Stable
class MealFormState(draft: MealDraft?, editing: Treatment?, initialTime: Long?, now: Long) {
    val rows = mutableStateListOf<FoodRow>().apply {
        when {
            draft != null -> addAll(draft.rows.map { newFoodRow(it.name, it.carbs, it.source, it.portion, it.tableId) })
            editing != null -> addAll(foodRowsOf(editing.foodItems, editing.carbs))
        }
    }

    // "now" for the time chips: when the meal began to be typed
    val startedAt = draft?.startedAt?.takeIf { it > 0 } ?: now
    var whenIdx by mutableStateOf(draft?.whenIdx ?: if (editing != null || initialTime != null) PICKED_TIME else 0)
    var pickedAt by mutableStateOf(draft?.pickedAt ?: editing?.t ?: initialTime)
    var plan by mutableStateOf(draft?.plan ?: false)
    var join by mutableStateOf(draft?.join ?: true)
    var meterText by mutableStateOf(draft?.meterText.orEmpty())

    // a new meal's ids, the same on every try: a save that failed and is tried again makes no second entry
    val newFoodId = TreatmentQueue.newId()
    val newPlanId = UUID.randomUUID().toString()

    // the "add a dish" field
    var newName by mutableStateOf("")

    // the dish opened for editing
    var open by mutableStateOf<Long?>(null)

    // the dish swiped left, showing "rename" and "remove"; the one being renamed
    var revealed by mutableStateOf<Long?>(null)
    var renaming by mutableStateOf<Long?>(null)

    // the dish just taken away, for "undo": (where it was, what it was)
    var removed by mutableStateOf<Pair<Int, FoodRow>?>(null)

    // "discard?" asked
    var closing by mutableStateOf(false)

    // the save dialog
    var asking by mutableStateOf(false)
    var okBig by mutableStateOf(false)
    var picking by mutableStateOf(false)
    var pickingTime by mutableStateOf(false)

    /** Grams of carbohydrate on the form. */
    val current get() = rows.sumOf { it.grams ?: 0 }

    /** When it was eaten: now, 15 or 30 min ago, or the picked time. */
    fun time(now: Long): Long = if (whenIdx == PICKED_TIME) pickedAt ?: now else now - TimeShifts[whenIdx] * 60_000L

    /** A new dish: fills the empty last row, or goes after it; it opens. */
    fun place(row: FoodRow) {
        val last = rows.lastOrNull()
        if (last != null && last.name.isBlank() && last.carbs.isBlank()) rows[rows.lastIndex] = row else rows.add(row)
        open = row.id
    }

    fun update(id: Long, f: (FoodRow) -> FoodRow) {
        val i = rows.indexOfFirst { it.id == id }
        if (i >= 0) rows[i] = f(rows[i])
    }

    /** Takes a dish away, keeping it for "undo". */
    fun remove(id: Long) {
        val i = rows.indexOfFirst { it.id == id }
        if (i < 0) return
        removed = i to rows.removeAt(i)
        if (open == id) open = null
    }

    /** "Undo": the dish taken away goes back where it was. */
    fun undoRemove(at: Int, row: FoodRow) {
        rows.add(at.coerceAtMost(rows.size), row)
        removed = null
    }

    /**
     * A dish renamed ("гречка 150 г" may bring a portion too): its carbs were for
     * the old one, so they go and the dish opens for the new suggestions to pick.
     * The same name again changes nothing.
     */
    fun rename(id: Long, text: String) {
        renaming = null
        revealed = null
        val (name, portion) = parseEntry(text.replace('\n', ' '))
        if (name.isBlank()) return
        val newName = name.trim().replaceFirstChar { it.uppercase() }
        val row = rows.firstOrNull { it.id == id } ?: return
        if (newName == row.name && (portion.isBlank() || portion == row.portion)) return
        update(id) {
            it.copy(
                name = newName,
                portion = portion.ifBlank { it.portion },
                carbs = "",
                source = null,
                tableId = null,
                suggestion = null,
                asked = false,
                loading = false
            )
        }
        open = id
    }

    /** "гречка 150 г" typed in the field: a dish with its portion. */
    fun addTyped() {
        val (name, portion) = parseEntry(newName)
        if (name.isBlank()) return
        place(newFoodRow(name = name.replaceFirstChar { it.uppercase() }, portion = portion))
        newName = ""
    }

    fun toDraft() = MealDraft(
        rows.map { MealDraft.Row(it.name, it.portion, it.carbs, it.source, it.tableId) },
        whenIdx,
        pickedAt,
        plan,
        join,
        meterText,
        startedAt
    )
}

/** The form's state for this screen: a new meal keeps its draft ([keepsDraft]), an edit or carbs for a low do not. */
@Composable
fun rememberMealFormState(editing: Treatment?, initialTime: Long?, keepsDraft: Boolean, now: () -> Long): MealFormState {
    val ctx = LocalContext.current
    return remember { MealFormState(if (keepsDraft) MealDrafts.load(ctx) else null, editing, initialTime, now()) }
}
