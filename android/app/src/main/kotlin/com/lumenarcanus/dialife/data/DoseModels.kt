package com.lumenarcanus.dialife.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Shapes of the server API (.local/notes/api-app.md); glucose is always mg/dL, time unix ms.
// The dose calculation, planned doses, who is giving insulin, when to eat.

/** How the food form works out a rapid dose: shared by the family, set in settings. */
@Serializable
data class DoseSettings(
    val ratio: Int = 20, // 1 unit per this many grams of carbs
    val rule: String = "halfDown", // "nearest" | "halfDown" | "down" | "up"
    val step: Double = 1.0, // the pen's step: 1 or 0.5
    @SerialName("fresh_min") val freshMin: Int = 15, // a glucometer reading older than this: no correction
    @SerialName("max_rapid") val maxRapid: Double = 10.0,
    @SerialName("max_basal") val maxBasal: Double = 20.0,
    @SerialName("max_carbs") val maxCarbs: Int = 150,
    val table: List<CorrectionRow> =
        listOf(CorrectionRow(140, 1.0), CorrectionRow(180, 2.0), CorrectionRow(220, 3.0), CorrectionRow(260, 4.0), CorrectionRow(300, 5.0)),
    val isf: Int = 0, // mg/dL one rapid unit lowers; 0: from the correction table's steps
    @SerialName("dia_min") val diaMin: Int = 240, // how long rapid insulin works
    @SerialName("carb_min") val carbMin: Int = 180, // how long carbs take to come in
    // when to eat after a rapid dose (a suggestion): minutes in range / when high, and the bounds in mg/dL
    @SerialName("wait_min") val waitMin: Int = 15,
    @SerialName("wait_high_min") val waitHighMin: Int = 20,
    @SerialName("wait_high_from") val waitHighFrom: Int = 180,
    @SerialName("wait_low_below") val waitLowBelow: Int = 80,
    @SerialName("updated_at") val updatedAt: Long = 0,
    @SerialName("updated_by") val updatedBy: String = ""
)

/** From [mg] mg/dL on the glucometer, add [u] units. */
@Serializable
data class CorrectionRow(val mg: Int, val u: Double)

/** A dose worked out for a meal and not given yet: nothing counts it as insulin. */
@Serializable
data class PlannedDose(
    val id: String,
    @SerialName("meal_id") val mealId: String,
    val dose: Double,
    val food: Double = 0.0, // the meal's part, before rounding
    val corr: Double = 0.0,
    val carbs: Int = 0,
    val meter: Int? = null,
    @SerialName("meter_at") val meterAt: Long? = null,
    @SerialName("created_at") val createdAt: Long,
    val by: String = "",
    @SerialName("device_id") val deviceId: Long = 0,
    val state: String = "planned"
)

/** "Dad is giving insulin": someone opened the insulin form; it ends when the form closes. */
@Serializable
data class InsulinIntent(
    @SerialName("device_id") val deviceId: Long,
    val person: String,
    val insulin: String = "rapid", // "rapid" | "basal"
    val since: Long,
    val until: Long
)

@Serializable
data class IntentEvent(val intents: List<InsulinIntent> = emptyList())

/** After a rapid dose with food beside it, when to start eating (the server's suggestion, never a rule). */
@Serializable
data class Wait(
    @SerialName("dose_id") val doseId: String,
    @SerialName("dose_at") val doseAt: Long,
    val units: Double,
    @SerialName("eat_from") val eatFrom: Long,
    val reason: String, // "range" | "high" | "unseen": wait; "low" | "falling": eat now
    val sgv: Int? = null,
    @SerialName("covers_g") val coversG: Double = 0.0, // carbs the dose covers at the ratio
    @SerialName("food_g") val foodG: Double = 0.0, // carbs logged since half an hour before it
    val closed: Boolean = false, // "already ate", on any phone
    // the "more insulin than food" card closed
    @SerialName("more_closed") val moreClosed: Boolean = false
)
