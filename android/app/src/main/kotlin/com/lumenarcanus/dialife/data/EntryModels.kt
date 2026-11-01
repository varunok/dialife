package com.lumenarcanus.dialife.data

import kotlin.math.round
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Shapes of the server API (.local/notes/api-app.md); glucose is always mg/dL, time unix ms.
// Entries, their dishes, and carbs for food: suggestions and the tables.

@Serializable
data class Treatment(
    val id: String,
    val t: Long,
    @SerialName("event_type") val eventType: String? = null,
    val insulin: Double? = null,
    val carbs: Double? = null,
    val notes: String? = null,
    @SerialName("entered_by") val enteredBy: String? = null,
    @SerialName("insulin_type") val insulinType: String? = null, // "rapid" | "basal"; null: rapid (older entries)
    @SerialName("insulin_name") val insulinName: String? = null, // the brand, e.g. "Lantus"
    val glucose: Int? = null, // a glucometer reading ("BG Check"), mg/dL
    val duration: Int? = null, // minutes of activity ("Exercise")
    val intensity: String? = null, // "light" | "moderate" | "vigorous"
    val pending: Boolean = false, // made on this phone, not on the server yet (never sent by the server)
    @SerialName("confirmed_new") val confirmedNew: String? = null, // saved after the warning about this other dose (sent as confirmedNew)
    @SerialName("food_items") val foodItems: List<FoodItem>? = null, // a meal logged as a list; carbs is their sum
    @SerialName("meal_id") val mealId: String? = null, // insulin: the meal it was given for
    // insulin: "meal" | "corr"
    @SerialName("insulin_role") val insulinRole: String? = null
) {
    /** "борщ 18 ≈ · хліб 22": the meal's lines, "≈" on numbers taken from a suggestion. */
    fun foodText(): String? = foodItems?.takeIf { it.isNotEmpty() }?.joinToString(" · ") { f ->
        listOfNotNull(
            f.name.ifBlank {
                null
            },
            f.carbs?.let { round(it).toInt().toString() + if (f.suggested) " ≈" else "" }
        ).joinToString(" ")
    }
}

/** One line of a meal: the food, its portion, its carbs and where the number came from. */
@Serializable
data class FoodItem(
    val name: String,
    val portion: String? = null,
    val carbs: Double? = null,
    // "mine" (the family's earlier entry) | "table" (a carb table) | "estimate" (models) | "manual"
    val source: String? = null
) {
    val suggested get() = source == "mine" || source == "table" || source == "estimate"
}

/** POST /api/carbs/suggest: per item, the family's own number and the models' estimate. */
@Serializable
data class CarbSuggestions(val models: Boolean = false, val items: List<CarbSuggestion> = emptyList())

@Serializable
data class CarbSuggestion(
    val mine: CarbMine? = null,
    val estimate: CarbEstimate? = null,
    val table: List<CarbTableHit> = emptyList(),
    // table foods that mean the same in other words
    val similar: List<CarbTableHit> = emptyList()
)

/** A portion from an imported carb table: carbs for it, or for the typed portion when scaled. */
@Serializable
data class CarbTableHit(
    val id: String,
    val carbs: Int,
    val base: Int = carbs,
    val scaled: Boolean = false,
    val per100: Double? = null, // counted from carbs per 100 g (a portion typed in grams)
    @SerialName("per100_src") val per100Src: String = "",
    val fiber100: Double? = null, // for information only, where the source gives them
    val avail100: Double? = null,
    val names: Map<String, String> = emptyMap(),
    val portions: Map<String, String> = emptyMap(),
    val source: String = "",
    // a look-alike: how close the names are
    val score: Float = 0f
)

/** GET /api/foods: the carb tables to pick from and the family's most logged foods. */
@Serializable
data class FoodCatalog(val tables: List<FoodTable> = emptyList(), val frequent: List<FrequentFood> = emptyList())

@Serializable
data class FoodTable(
    val source: String,
    val title: String = "",
    val note: Map<String, String> = emptyMap(),
    val categories: List<FoodCategory> = emptyList(),
    val items: List<TableFood> = emptyList()
)

@Serializable
data class FoodCategory(val id: String, val names: Map<String, String> = emptyMap())

@Serializable
data class TableFood(
    val id: String,
    val cat: String,
    val carbs: Int,
    val names: Map<String, String> = emptyMap(),
    val portions: Map<String, String> = emptyMap()
)

@Serializable
data class FrequentFood(val name: String, val portion: String = "", val carbs: Int = 0, val times: Int = 1)

/** A table's text in the app's language: Ukrainian, Spanish, otherwise English. */
fun Map<String, String>.inLang(lang: String): String =
    (this[if (lang == "uk" || lang == "es") lang else "en"] ?: this["en"] ?: values.firstOrNull()).orEmpty()

@Serializable
data class CarbMine(
    val carbs: Int,
    val portion: String = "",
    val times: Int = 1,
    val like: String? = null, // found by meaning: the family's food it came from
    val score: Float = 0f
)

@Serializable
data class CarbEstimate(val carbs: Int, val lo: Int, val hi: Int, val models: Int = 1)
