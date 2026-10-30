package com.lumenarcanus.dialife.core.data

import com.lumenarcanus.dialife.data.ApiError
import com.lumenarcanus.dialife.data.ApiException
import com.lumenarcanus.dialife.data.CarbSuggestions
import com.lumenarcanus.dialife.data.Credentials
import com.lumenarcanus.dialife.data.DiaLifeApi
import com.lumenarcanus.dialife.data.FoodCatalog

/**
 * Foods for the food form and the entry sheet: carb suggestions for a typed
 * food, and the carb tables with the family's most logged foods (kept five
 * minutes: they change only on an import).
 */
interface FoodRepository {
    /** Carbs for a food as typed: the family's own, the tables', the models' estimate. */
    suspend fun suggest(name: String, portion: String): Result<CarbSuggestions>

    /** The carb tables and the family's most logged foods. */
    suspend fun catalog(): Result<FoodCatalog>
}

class DefaultFoodRepository(private val creds: () -> Credentials?, private val api: DiaLifeApi = DiaLifeApi()) : FoodRepository {
    @Volatile private var catalog: Pair<Long, FoodCatalog>? = null

    override suspend fun suggest(name: String, portion: String): Result<CarbSuggestions> {
        val c = creds() ?: return Result.failure(ApiException(ApiError.Unauthorized))
        return api.suggestCarbs(c, listOf(name to portion))
    }

    override suspend fun catalog(): Result<FoodCatalog> {
        catalog?.takeIf { System.currentTimeMillis() - it.first < CATALOG_FOR_MS }?.let { return Result.success(it.second) }
        val c = creds() ?: return Result.failure(ApiException(ApiError.Unauthorized))
        return api.foods(c).onSuccess { catalog = System.currentTimeMillis() to it }
    }

    private companion object {
        const val CATALOG_FOR_MS = 5 * 60_000L
    }
}
