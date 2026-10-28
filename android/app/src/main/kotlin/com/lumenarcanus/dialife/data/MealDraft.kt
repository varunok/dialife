package com.lumenarcanus.dialife.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A new meal being typed, kept on the phone at every change: closing the
 * screen, turning the phone or restarting it loses nothing. It goes only on
 * a save or an explicit "discard".
 */
@Serializable
data class MealDraft(
    val rows: List<Row> = emptyList(),
    val whenIdx: Int = 0, // 0 now, 1 15 min ago, 2 30 min ago, 3 a picked time
    val pickedAt: Long? = null,
    val plan: Boolean = false,
    val join: Boolean = true,
    val meterText: String = "",
    val startedAt: Long = 0, // "now" for the time chips: when the draft began
) {
    @Serializable
    data class Row(val name: String, val portion: String = "", val carbs: String = "", val source: String? = null, val tableId: String? = null)

    val isEmpty get() = rows.none { it.name.isNotBlank() || it.carbs.isNotBlank() }
}

object MealDrafts {
    private val json = Json { ignoreUnknownKeys = true }
    private const val KEY = "meal_draft"

    fun load(context: Context): MealDraft? {
        val s = context.getSharedPreferences("meal", Context.MODE_PRIVATE).getString(KEY, null) ?: return null
        return runCatching { json.decodeFromString(MealDraft.serializer(), s) }.getOrNull()?.takeUnless { it.isEmpty }
    }

    fun save(context: Context, d: MealDraft) {
        context.getSharedPreferences("meal", Context.MODE_PRIVATE).edit()
            .putString(KEY, json.encodeToString(MealDraft.serializer(), d)).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences("meal", Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
