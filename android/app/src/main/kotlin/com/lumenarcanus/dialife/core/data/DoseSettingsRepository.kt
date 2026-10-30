package com.lumenarcanus.dialife.core.data

import com.lumenarcanus.dialife.data.ApiError
import com.lumenarcanus.dialife.data.ApiException
import com.lumenarcanus.dialife.data.Credentials
import com.lumenarcanus.dialife.data.DiaLifeApi
import com.lumenarcanus.dialife.data.DoseSettings

/** The family's dose calculation: read with the live state, saved here (every phone gets it back). */
class DoseSettingsRepository(private val creds: () -> Credentials?, private val api: DiaLifeApi = DiaLifeApi()) {
    suspend fun save(d: DoseSettings): Result<DoseSettings> =
        creds()?.let { api.putDose(it, d) } ?: Result.failure(ApiException(ApiError.Unauthorized))
}
