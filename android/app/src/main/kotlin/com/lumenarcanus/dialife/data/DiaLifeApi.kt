package com.lumenarcanus.dialife.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.add
import kotlinx.serialization.json.encodeToJsonElement
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

    /** Carb suggestions for the foods typed in the form; only ever suggestions. */
    suspend fun suggestCarbs(c: Credentials, items: List<Pair<String, String>>): Result<CarbSuggestions> = postFor(
        c, "/api/carbs/suggest",
        buildJsonObject {
            put("items", kotlinx.serialization.json.buildJsonArray {
                items.forEach { (name, portion) -> add(buildJsonObject { put("name", name); put("portion", portion) }) }
            })
        }.toString(),
    )

    suspend fun putDose(c: Credentials, d: DoseSettings): Result<DoseSettings> = sendFor(
        c, "PUT", "/api/dose",
        buildJsonObject {
            put("ratio", d.ratio); put("rule", d.rule); put("step", d.step); put("fresh_min", d.freshMin)
            put("max_rapid", d.maxRapid); put("max_basal", d.maxBasal); put("max_carbs", d.maxCarbs)
            put("isf", d.isf); put("dia_min", d.diaMin); put("carb_min", d.carbMin)
            put("table", kotlinx.serialization.json.buildJsonArray { d.table.forEach { r -> add(buildJsonObject { put("mg", r.mg); put("u", r.u) }) } })
        }.toString(),
    )

    suspend fun planDose(c: Credentials, p: PlannedDose): Result<Unit> = post(
        c, "/api/planned",
        buildJsonObject {
            put("id", p.id); put("meal_id", p.mealId); put("dose", p.dose); put("food", p.food); put("corr", p.corr)
            put("carbs", p.carbs); p.meter?.let { put("meter", it) }; p.meterAt?.let { put("meter_at", it) }; put("created_at", p.createdAt)
        }.toString(),
    )

    suspend fun plannedGiven(c: Credentials, id: String, insulinId: String): Result<Unit> =
        post(c, "/api/planned/$id/given", buildJsonObject { put("insulin_id", insulinId) }.toString())

    suspend fun plannedSkip(c: Credentials, id: String): Result<Unit> = post(c, "/api/planned/$id/skip", "{}")

    /** The carb tables and the family's most logged foods, for the food form. */
    suspend fun foods(c: Credentials): Result<FoodCatalog> = get(c.server, c.token, "/api/foods")

    /** The insulin form is open here: the other phones show "… is giving insulin". */
    suspend fun insulinIntent(c: Credentials, insulin: String): Result<Unit> =
        post(c, "/api/insulin/intent", buildJsonObject { put("insulin", insulin) }.toString())

    suspend fun insulinIntentEnd(c: Credentials): Result<Unit> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(c.server + "/api/insulin/intent").header("Authorization", "Bearer ${c.token}").delete().build()
        try {
            http.newCall(req).execute().use { Result.success(Unit) }
        } catch (e: IOException) {
            Result.failure(ApiException(ApiError.Network))
        }
    }

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
            t.confirmedNew?.let { put("confirmedNew", it) } // the server asks nobody about this pair
            t.foodItems?.takeIf { it.isNotEmpty() }?.let { put("foodItems", json.encodeToJsonElement(it)) }
            t.mealId?.let { put("mealId", it) }
            t.insulinRole?.let { put("insulinRole", it) }
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

    /** Which browser a scanned web-login code belongs to; 404 or 410: no longer valid. */
    suspend fun webInspect(c: Credentials, link: WebLoginLink): Result<WebLoginInfo> =
        postFor(c, "/api/web/inspect", buildJsonObject { put("id", link.id); put("code", link.code) }.toString())

    suspend fun webApprove(c: Credentials, link: WebLoginLink, minutes: Int): Result<Unit> =
        postFor<Unit?>(c, "/api/web/approve", buildJsonObject { put("id", link.id); put("code", link.code); put("minutes", minutes) }.toString()).map { }

    suspend fun webReject(c: Credentials, link: WebLoginLink): Result<Unit> =
        postFor<Unit?>(c, "/api/web/reject", buildJsonObject { put("id", link.id); put("code", link.code) }.toString()).map { }

    suspend fun webSessions(c: Credentials): Result<List<WebSession>> = get(c.server, c.token, "/api/web/sessions")

    suspend fun webRevoke(c: Credentials, id: Long): Result<Unit> = post(c, "/api/web/sessions/$id/revoke", "{}")

    /** A POST whose answer matters, every status as it is (unlike the alarm actions). */
    private suspend inline fun <reified T> postFor(c: Credentials, path: String, body: String): Result<T> = sendFor(c, "POST", path, body)

    private suspend inline fun <reified T> sendFor(c: Credentials, method: String, path: String, body: String): Result<T> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(c.server + path).header("Authorization", "Bearer ${c.token}")
            .method(method, body.toRequestBody("application/json".toMediaType())).build()
        try {
            http.newCall(req).execute().use { resp ->
                when {
                    resp.code == 401 || resp.code == 403 -> Result.failure(ApiException(ApiError.Unauthorized))
                    !resp.isSuccessful -> Result.failure(ApiException(ApiError.Server(resp.code)))
                    resp.code == 204 -> Result.success(null as T)
                    else -> Result.success(json.decodeFromString<T>(resp.body.string()))
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
