package com.lumenarcanus.dialife.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Shapes of the server API (.local/notes/api-app.md); glucose is always mg/dL, time unix ms.
// "Did you forget to log?" and the answers to it.

/** "Did you forget to log?": a rise without food or a fall without insulin. */
@Serializable
data class Question(
    val id: Long,
    val kind: String, // "rise" | "fall" | "bolus" | "double" (two doses too close, one from xDrip+)
    val state: String, // "open" | "answered" | "unknown" (a suspect mark) | "dismissed"
    val from: Long,
    val to: Long,
    @SerialName("sgv_from") val sgvFrom: Int,
    @SerialName("sgv_to") val sgvTo: Int,
    val round: Int = 1,
    val outcome: String? = null,
    @SerialName("treatment_id") val treatmentId: String? = null,
    val answers: List<QuestionAnswer> = emptyList(),
    @SerialName("planned_id") val plannedId: String? = null, // "planned": the dose nobody confirmed
    val dose: Double? = null, // "planned": its units
    val waiting: List<String> = emptyList(), // people yet to answer this round
    @SerialName("waiting_ids") val waitingIds: List<Long> = emptyList(),
    val remind: Boolean = false,
    val quiet: Boolean = false, // night: no sound
    val doses: List<Dose> = emptyList(), // "double": the two, the one "one injection" removes first
    val insulin: String? = null, // "double": "rapid" | "basal"
    // "double" answered "two": watch for a low until then
    @SerialName("watch_until") val watchUntil: Long? = null
) {
    fun asks(deviceId: Long) = state == "open" && deviceId in waitingIds

    /** Two doses were given: everyone watches for a low until [watchUntil]. */
    val watch get() = kind == "double" && state == "answered" && outcome == "two"

    /** What the others said last round, when they disagreed: shown with the repeated question. */
    fun previous(): List<QuestionAnswer> = if (round > 1) answers.filter { it.round == round - 1 } else emptyList()
}

@Serializable
data class Dose(val id: String, val t: Long, val units: Double, val by: String = "")

@Serializable
data class QuestionAnswer(
    val person: String,
    val answer: String, // "food" | "food_unknown" | "insulin" | "nothing"
    val round: Int,
    val at: Long,
    @SerialName("treatment_id") val treatmentId: String? = null
)
