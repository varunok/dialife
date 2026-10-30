package com.lumenarcanus.dialife.feature.wait

import com.lumenarcanus.dialife.data.ApiError
import com.lumenarcanus.dialife.data.ApiException
import com.lumenarcanus.dialife.data.Credentials
import com.lumenarcanus.dialife.data.DiaLifeApi
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The wait's data: the state comes with the live stream; closing goes to the server. */
class WaitRepository(private val creds: () -> Credentials?, private val api: DiaLifeApi = DiaLifeApi()) {
    suspend fun close(a: WaitAction): Result<Unit> {
        val c = creds() ?: return Result.failure(ApiException(ApiError.Unauthorized))
        return api.post(c, "/api/wait/${a.doseId}/close", buildJsonObject { put("kind", a.kind) }.toString())
    }
}
