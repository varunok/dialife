package com.lumenarcanus.dialife.feature.recheck

import com.lumenarcanus.dialife.data.ApiError
import com.lumenarcanus.dialife.data.ApiException
import com.lumenarcanus.dialife.data.Credentials
import com.lumenarcanus.dialife.data.DiaLifeApi

/** "Measure again" after carbs for a low; its state comes with the live stream. */
interface RecheckRepository {
    /** The card is gone on every phone. */
    suspend fun close(id: Long): Result<Unit>
}

class DefaultRecheckRepository(private val creds: () -> Credentials?, private val api: DiaLifeApi = DiaLifeApi()) : RecheckRepository {
    override suspend fun close(id: Long): Result<Unit> {
        val c = creds() ?: return Result.failure(ApiException(ApiError.Unauthorized))
        return api.post(c, "/api/recheck/$id/close", "{}")
    }
}
