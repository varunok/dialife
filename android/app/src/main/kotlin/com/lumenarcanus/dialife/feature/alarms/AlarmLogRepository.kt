package com.lumenarcanus.dialife.feature.alarms

import com.lumenarcanus.dialife.data.ApiError
import com.lumenarcanus.dialife.data.ApiException
import com.lumenarcanus.dialife.data.Credentials
import com.lumenarcanus.dialife.data.DiaLifeApi
import com.lumenarcanus.dialife.data.LogEpisode

/** Past alarm episodes, from the server. */
interface AlarmLogRepository {
    suspend fun episodes(days: Int): Result<List<LogEpisode>>
}

/** Past alarm episodes, from the server. */
class DefaultAlarmLogRepository(private val creds: () -> Credentials?, private val api: DiaLifeApi = DiaLifeApi()) : AlarmLogRepository {
    override suspend fun episodes(days: Int): Result<List<LogEpisode>> =
        creds()?.let { c -> api.alarmLog(c, days).map { it.episodes } } ?: Result.failure(ApiException(ApiError.Unauthorized))
}
