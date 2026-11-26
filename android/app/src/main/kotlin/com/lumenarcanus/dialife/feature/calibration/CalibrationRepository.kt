package com.lumenarcanus.dialife.feature.calibration

import com.lumenarcanus.dialife.data.ApiError
import com.lumenarcanus.dialife.data.ApiException
import com.lumenarcanus.dialife.data.Credentials
import com.lumenarcanus.dialife.data.DiaLifeApi

/** The calibration advice's one request; the advice comes with the live stream. */
interface CalibrationRepository {
    /** Gone on every phone until a newer check is off again. */
    suspend fun close(): Result<Unit>
}

class DefaultCalibrationRepository(private val creds: () -> Credentials?, private val api: DiaLifeApi = DiaLifeApi()) :
    CalibrationRepository {
    override suspend fun close(): Result<Unit> {
        val c = creds() ?: return Result.failure(ApiException(ApiError.Unauthorized))
        return api.post(c, "/api/calibration/close", "{}")
    }
}
