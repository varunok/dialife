package com.lumenarcanus.dialife.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
data class Me(
    val id: Long,
    val name: String,
    val role: String,
    @SerialName("display_units") val displayUnits: String,
)

sealed interface ApiError {
    data object Network : ApiError
    data object Unauthorized : ApiError
    data class Server(val code: Int) : ApiError
}

class ApiException(val error: ApiError) : Exception(error.toString())

/** Thin client for the DiaLife server API (see .local/notes/api-app.md). */
class DiaLifeApi(
    private val http: OkHttpClient = defaultClient,
) {
    suspend fun me(server: String, token: String): Result<Me> = get(server, token, "/api/me")

    suspend fun state(c: Credentials): Result<StateResponse> = get(c.server, c.token, "/api/state")

    suspend fun history(c: Credentials, from: Long, to: Long): Result<History> =
        get(c.server, c.token, "/api/history?from=$from&to=$to")

    suspend fun alarmLog(c: Credentials, days: Int): Result<AlarmLog> = get(c.server, c.token, "/api/alarms/log?days=$days")

    suspend fun stats(c: Credentials, days: Int): Result<Stats> = get(c.server, c.token, "/api/stats?days=$days")

    suspend fun meter(c: Credentials): Result<MeterReport> = get(c.server, c.token, "/api/meter")

    /** "I'm on it"; quietMin > 0 also keeps it quiet that long (refused for an urgent low). */
    suspend fun handle(c: Credentials, id: Long, quietMin: Int = 0): Result<Unit> =
        post(c, "/api/alarms/$id/handle", if (quietMin > 0) """{"quiet_min":$quietMin}""" else "{}")

    /** Check-in answer: "ok" (all under control) or "help". */
    suspend fun checkin(c: Credentials, id: Long, answer: String): Result<Unit> =
        post(c, "/api/alarms/$id/checkin", """{"answer":"$answer"}""")

    suspend fun coming(c: Credentials, id: Long): Result<Unit> = post(c, "/api/alarms/$id/coming", "{}")

    /** An answer to "did you forget to log?"; a closed question (409) counts as done. */
    suspend fun answerQuestion(c: Credentials, id: Long, answer: String, treatmentId: String? = null): Result<Unit> =
        post(c, "/api/questions/$id/answer", buildJsonObject { put("answer", answer); treatmentId?.let { put("treatment_id", it) } }.toString()).closedIsDone()

    suspend fun dismissQuestion(c: Credentials, id: Long): Result<Unit> = post(c, "/api/questions/$id/dismiss", "{}").closedIsDone()

    private fun Result<Unit>.closedIsDone(): Result<Unit> =
        recoverCatching { e -> if ((e as? ApiException)?.error == ApiError.Server(409)) Unit else throw e }

    suspend fun resolve(c: Credentials, id: Long): Result<Unit> = post(c, "/api/alarms/$id/resolve", "{}")

    /** Create or replace one treatment; the id is ours, so a retry after a lost answer changes nothing. */
    suspend fun upsertTreatment(c: Credentials, t: Treatment, enteredBy: String): Result<Unit> {
        val doc = buildJsonObject {
            put("_id", t.id)
            put("eventType", t.eventType ?: eventTypeOf(t))
            put("created_at", java.time.Instant.ofEpochMilli(t.t).toString())
            t.insulin?.let { put("insulin", it) }
            t.carbs?.let { put("carbs", it) }
            t.glucose?.let { put("glucose", it); put("glucoseType", "Finger"); put("units", "mg/dl") }
            t.duration?.let { put("duration", it) }
            t.intensity?.let { put("exerciseIntensity", it) }
            if ((t.insulin ?: 0.0) > 0) {
                put("insulinType", InsulinType.of(t.insulinType).wire)
                t.insulinName?.let { put("insulinName", it) }
            }
            t.notes?.takeIf { it.isNotBlank() }?.let { put("notes", it) }
            put("enteredBy", enteredBy)
        }
        return post(c, "/api/v1/treatments", "[$doc]")
    }

    suspend fun deleteTreatment(c: Credentials, id: String): Result<Unit> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(c.server + "/api/v1/treatments/$id").header("Authorization", "Bearer ${c.token}").delete().build()
        try {
            http.newCall(req).execute().use { resp ->
                when {
                    resp.isSuccessful -> Result.success(Unit)
                    resp.code == 401 || resp.code == 403 -> Result.failure(ApiException(ApiError.Unauthorized))
                    else -> Result.failure(ApiException(ApiError.Server(resp.code)))
                }
            }
        } catch (e: IOException) {
            Result.failure(ApiException(ApiError.Network))
        }
    }

    /** Alarm actions; 404 (episode already over) counts as done, 409 is a refused quiet. */
    private suspend fun post(c: Credentials, path: String, body: String): Result<Unit> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(c.server + path)
            .header("Authorization", "Bearer ${c.token}")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        try {
            http.newCall(req).execute().use { resp ->
                when {
                    resp.isSuccessful || resp.code == 404 -> Result.success(Unit)
                    resp.code == 401 || resp.code == 403 -> Result.failure(ApiException(ApiError.Unauthorized))
                    else -> Result.failure(ApiException(ApiError.Server(resp.code)))
                }
            }
        } catch (e: IOException) {
            Result.failure(ApiException(ApiError.Network))
        }
    }

    private suspend inline fun <reified T> get(server: String, token: String, path: String): Result<T> =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(server + path).header("Authorization", "Bearer $token").build()
            try {
                http.newCall(req).execute().use { resp ->
                    when {
                        resp.code == 401 || resp.code == 403 -> Result.failure(ApiException(ApiError.Unauthorized))
                        !resp.isSuccessful -> Result.failure(ApiException(ApiError.Server(resp.code)))
                        else -> Result.success(json.decodeFromString<T>(resp.body.string()))
                    }
                }
            } catch (e: IOException) {
                Result.failure(ApiException(ApiError.Network))
            }
        }

    companion object {
        /** Nightscout event types, so xDrip+ and Nightscout tools read our entries right. */
        fun eventTypeOf(t: Treatment): String = when {
            t.glucose != null -> "BG Check"
            t.duration != null -> "Exercise"
            (t.insulin ?: 0.0) > 0 && InsulinType.of(t.insulinType) == InsulinType.Basal -> "Basal Injection"
            (t.insulin ?: 0.0) > 0 && (t.carbs ?: 0.0) > 0 -> "Meal Bolus"
            (t.insulin ?: 0.0) > 0 -> "Correction Bolus"
            (t.carbs ?: 0.0) > 0 -> "Carb Correction"
            else -> "Note"
        }

        val json = Json { ignoreUnknownKeys = true }
        val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
