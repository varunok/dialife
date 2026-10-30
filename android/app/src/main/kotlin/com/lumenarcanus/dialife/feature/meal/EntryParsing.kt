package com.lumenarcanus.dialife.feature.meal

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.lumenarcanus.dialife.feature.meal.MealDraft
import com.lumenarcanus.dialife.feature.meal.MealDrafts

/** "борщ з хлібом" → ["борщ", "хлібом"]: a hint to split, never done by itself. */
fun splitParts(name: String): List<String>? =
    name.split(Regex("\\s*(?:,|\\+|\\sз\\s|\\sі\\s|\\sта\\s|\\sй\\s|\\sy\\s|\\scon\\s|\\swith\\s|\\sand\\s)\\s*", RegexOption.IGNORE_CASE))
        .map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.size > 1 }

/** "гречка 150 г" → "гречка", "150 г": the portion may come with the name. */
fun parseEntry(text: String): Pair<String, String> {
    val m = Regex("^(.*?)[\\s,]+(\\d.*)$").find(text.trim()) ?: return text.trim() to ""
    return m.groupValues[1] to m.groupValues[2]
}
