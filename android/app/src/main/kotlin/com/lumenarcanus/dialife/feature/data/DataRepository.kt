package com.lumenarcanus.dialife.feature.data

import com.lumenarcanus.dialife.data.Credentials
import com.lumenarcanus.dialife.data.DiaLifeApi
import com.lumenarcanus.dialife.data.History

/** Readings and entries older than the live day; null when they could not be had. */
interface DataRepository {
    suspend fun history(from: Long, to: Long): History?
}

/** Readings and entries older than the live day. */
class DefaultDataRepository(private val creds: () -> Credentials?, private val api: DiaLifeApi = DiaLifeApi()) : DataRepository {
    override suspend fun history(from: Long, to: Long): History? = creds()?.let { api.history(it, from, to).getOrNull() }
}
