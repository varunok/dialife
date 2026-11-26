package com.lumenarcanus.dialife.core.info

import com.lumenarcanus.dialife.data.ApiError
import com.lumenarcanus.dialife.data.ApiException
import com.lumenarcanus.dialife.data.Credentials
import com.lumenarcanus.dialife.data.DiaLifeApi
import com.lumenarcanus.dialife.data.Methods
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The ⓘ texts, read from the server once per process. */
interface MethodsRepository {
    suspend fun methods(): Result<Methods>
}

class DefaultMethodsRepository(private val creds: () -> Credentials?, private val api: DiaLifeApi = DiaLifeApi()) : MethodsRepository {
    private val lock = Mutex()
    private var cached: Methods? = null

    override suspend fun methods(): Result<Methods> = lock.withLock {
        cached?.let { return@withLock Result.success(it) }
        val c = creds() ?: return@withLock Result.failure(ApiException(ApiError.Unauthorized))
        api.methods(c).onSuccess { cached = it }
    }
}
