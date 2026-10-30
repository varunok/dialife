package com.lumenarcanus.dialife.feature.stats

import com.lumenarcanus.dialife.data.ApiError
import com.lumenarcanus.dialife.data.ApiException
import com.lumenarcanus.dialife.data.Credentials
import com.lumenarcanus.dialife.data.DiaLifeApi
import com.lumenarcanus.dialife.data.MeterReport
import com.lumenarcanus.dialife.data.Stats

/** The reports, asked for when the screen opens: they are not in the live state. */
class StatsRepository(private val creds: () -> Credentials?, private val api: DiaLifeApi = DiaLifeApi()) {
    suspend fun stats(days: Int): Result<Stats> = creds()?.let { api.stats(it, days) } ?: unauthorized()

    suspend fun meter(): Result<MeterReport> = creds()?.let { api.meter(it) } ?: unauthorized()

    private fun <T> unauthorized(): Result<T> = Result.failure(ApiException(ApiError.Unauthorized))
}
