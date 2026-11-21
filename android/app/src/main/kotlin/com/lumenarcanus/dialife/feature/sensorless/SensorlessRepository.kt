package com.lumenarcanus.dialife.feature.sensorless

import com.lumenarcanus.dialife.data.ApiError
import com.lumenarcanus.dialife.data.ApiException
import com.lumenarcanus.dialife.data.Credentials
import com.lumenarcanus.dialife.data.DiaLifeApi
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The "no sensor" mode's requests; its state comes with the live stream. Everything here is the family's, on every phone. */
interface SensorlessRepository {
    /** On or off for every phone; fails with [SensorSending] while a sensor is sending. */
    suspend fun setOn(on: Boolean): Result<Unit>

    /** Minutes without a glucometer check before every phone is reminded; 0: never. */
    suspend fun setRemind(minutes: Int): Result<Unit>

    /** The out-of-range card of the check taken at [t] is gone on every phone. */
    suspend fun closeCard(t: Long): Result<Unit>
}

/** A sensor is sending now: the mode is not needed (the server's 409). */
class SensorSending : Exception("a sensor is sending")

class DefaultSensorlessRepository(private val creds: () -> Credentials?, private val api: DiaLifeApi = DiaLifeApi()) :
    SensorlessRepository {
    override suspend fun setOn(on: Boolean): Result<Unit> {
        val c = creds() ?: return Result.failure(ApiException(ApiError.Unauthorized))
        return api.put(c, "/api/sensorless", buildJsonObject { put("on", on) }.toString()).recoverCatching { e ->
            throw if ((e as? ApiException)?.error == ApiError.Server(409)) SensorSending() else e
        }
    }

    override suspend fun setRemind(minutes: Int): Result<Unit> {
        val c = creds() ?: return Result.failure(ApiException(ApiError.Unauthorized))
        return api.put(c, "/api/sensorless/settings", buildJsonObject { put("remind_min", minutes) }.toString())
    }

    override suspend fun closeCard(t: Long): Result<Unit> {
        val c = creds() ?: return Result.failure(ApiException(ApiError.Unauthorized))
        return api.post(c, "/api/sensorless/checks/$t/close", "{}")
    }
}
