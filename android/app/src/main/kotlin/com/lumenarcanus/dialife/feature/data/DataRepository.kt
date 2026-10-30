package com.lumenarcanus.dialife.feature.data

import com.lumenarcanus.dialife.data.Credentials
import com.lumenarcanus.dialife.data.DiaLifeApi
import com.lumenarcanus.dialife.data.History

/** Readings and entries older than the live day. */
class DataRepository(private val creds: () -> Credentials?, private val api: DiaLifeApi = DiaLifeApi()) {
    suspend fun history(from: Long, to: Long): History? = creds()?.let { api.history(it, from, to).getOrNull() }
}
