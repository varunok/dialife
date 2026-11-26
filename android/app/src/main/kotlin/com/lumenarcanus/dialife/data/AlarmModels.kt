package com.lumenarcanus.dialife.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Shapes of the server API (.local/notes/api-app.md); glucose is always mg/dL, time unix ms.
// Alarm episodes, live and in the log.

@Serializable
data class AlarmView(
    val id: Long,
    val kind: String, // low | high | predicted_low | predicted_high | stale
    val level: String, // warn | urgent
    val state: String = "firing", // firing | handled | cleared
    @SerialName("started_at") val startedAt: Long = 0,
    val sgv: Int? = null,
    val reason: String? = null,
    @SerialName("handled_by") val handledBy: String? = null,
    @SerialName("handled_by_id") val handledById: Long = 0,
    @SerialName("handled_at") val handledAt: Long? = null,
    @SerialName("handled_sgv") val handledSgv: Int? = null,
    @SerialName("quiet_until") val quietUntil: Long? = null,
    @SerialName("checkin_pending") val checkinPending: Boolean = false,
    @SerialName("checkin_sent_at") val checkinSentAt: Long? = null,
    @SerialName("next_checkin_at") val nextCheckinAt: Long? = null,
    @SerialName("help_at") val helpAt: Long? = null,
    @SerialName("coming_by") val comingBy: String? = null,
    @SerialName("coming_by_id") val comingById: Long = 0,
    @SerialName("coming_at") val comingAt: Long? = null
) {
    val urgentLow get() = kind == "low" && level == "urgent"

    /** A forecast: a warning, never an episode. Amber, one "Close" for every phone, nobody takes it. */
    val warning get() = kind == "predicted_low" || kind == "predicted_high"
}

@Serializable
data class AlarmSummary(
    @SerialName("duration_min") val durationMin: Int = 0,
    @SerialName("handled_by") val handledBy: String? = null,
    val manual: Boolean = false,
    @SerialName("resolved_by") val resolvedBy: String? = null
)

/** SSE alarm/handled/checkin/help/coming/clear. */
@Serializable
data class AlarmEvent(
    val alarm: AlarmView,
    val reason: String? = null,
    @SerialName("was_handled_by") val wasHandledBy: String? = null,
    val by: String? = null,
    val summary: AlarmSummary? = null
)

@Serializable
data class LogEvent(val t: Long, val type: String, val reason: String? = null, val by: String? = null, val sgv: Int? = null)

/** One episode of GET /api/alarms/log. */
@Serializable
data class LogEpisode(
    val id: Long,
    val kind: String,
    val level: String,
    val state: String = "cleared",
    @SerialName("started_at") val startedAt: Long = 0,
    val sgv: Int? = null,
    @SerialName("trigger_sgv") val triggerSgv: Int? = null,
    @SerialName("cleared_at") val clearedAt: Long? = null,
    @SerialName("handled_by") val handledBy: String? = null,
    @SerialName("resolved_by") val resolvedBy: String? = null,
    val manual: Boolean = false,
    val events: List<LogEvent> = emptyList()
) {
    fun asView() = AlarmView(id = id, kind = kind, level = level, state = state, startedAt = startedAt, sgv = sgv)
}

@Serializable
data class AlarmLog(val episodes: List<LogEpisode> = emptyList())
