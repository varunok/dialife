package com.lumenarcanus.dialife.core.data

import com.lumenarcanus.dialife.data.ApiError
import com.lumenarcanus.dialife.data.ApiException
import com.lumenarcanus.dialife.data.Credentials
import com.lumenarcanus.dialife.data.DiaLifeApi
import com.lumenarcanus.dialife.data.PlannedDose
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Planned doses on the server: the food form makes them, the planned dose
 * feature closes them; the list itself comes with the live state.
 */
interface PlannedDoseRepository {
    /** Makes a planned dose, or changes it while it is still planned. */
    suspend fun save(p: PlannedDose): Result<Unit>

    /** It was injected; [insulinId] is its insulin entry. */
    suspend fun given(id: String, insulinId: String): Result<Unit>

    /** It was not injected. */
    suspend fun skip(id: String): Result<Unit>
}

class DefaultPlannedDoseRepository(private val creds: () -> Credentials?, private val api: DiaLifeApi = DiaLifeApi()) :
    PlannedDoseRepository {
    override suspend fun save(p: PlannedDose): Result<Unit> = post(
        "/api/planned",
        buildJsonObject {
            put("id", p.id)
            put("meal_id", p.mealId)
            put("dose", p.dose)
            put("food", p.food)
            put("corr", p.corr)
            put("carbs", p.carbs)
            p.meter?.let { put("meter", it) }
            p.meterAt?.let { put("meter_at", it) }
            put("created_at", p.createdAt)
        }.toString()
    )

    override suspend fun given(id: String, insulinId: String): Result<Unit> =
        post("/api/planned/$id/given", buildJsonObject { put("insulin_id", insulinId) }.toString())

    override suspend fun skip(id: String): Result<Unit> = post("/api/planned/$id/skip", "{}")

    private suspend fun post(path: String, body: String): Result<Unit> {
        val c = creds() ?: return Result.failure(ApiException(ApiError.Unauthorized))
        return api.post(c, path, body)
    }
}
