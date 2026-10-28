package com.lumenarcanus.dialife.data

/**
 * A meal as the family eats it: food entries close to one another (seconds,
 * a dessert later) and the rapid insulin given for them. Insulin linked to a
 * food entry belongs to its meal; unlinked rapid near the meal does too,
 * unless it was marked a correction.
 */
data class Meal(val foods: List<Treatment>, val insulin: List<Treatment>) {
    val from get() = foods.first().t
    val to get() = foods.last().t
    val carbs get() = foods.sumOf { it.carbs ?: 0.0 }.toInt()
    val given get() = insulin.sumOf { it.insulin ?: 0.0 }
}

object Meals {
    const val DEFAULT_GAP_MIN = 30 // the server's until /api/state brings it
    private const val BEFORE_MIN = 90L // rapid this long before the first food still counts
    private const val AFTER_MIN = 60L // and this long after the last

    fun isFood(t: Treatment) = (t.carbs ?: 0.0) > 0
    fun isRapid(t: Treatment) = (t.insulin ?: 0.0) > 0 && InsulinType.of(t.insulinType) == InsulinType.Rapid

    /** [gapMin]: how far apart two entries of one meal may be; the server works it out for every phone. */
    fun group(treatments: List<Treatment>, gapMin: Int): List<Meal> {
        val foods = treatments.filter(::isFood).sortedBy { it.t }
        val runs = mutableListOf<MutableList<Treatment>>()
        for (f in foods) {
            val last = runs.lastOrNull()
            if (last != null && f.t - last.last().t <= gapMin * 60_000L) last.add(f) else runs.add(mutableListOf(f))
        }
        val rapid = treatments.filter(::isRapid)
        val ids = runs.map { r -> r.map { it.id }.toSet() }
        val taken = mutableSetOf<String>()
        // linked first: a dose says which meal it was for
        val linked = runs.indices.map { i -> rapid.filter { it.mealId != null && it.mealId in ids[i] }.also { l -> taken += l.map { it.id } } }
        val known = foods.map { it.id }.toSet()
        return runs.mapIndexed { i, r ->
            val from = r.first().t - BEFORE_MIN * 60_000L
            val to = r.last().t + AFTER_MIN * 60_000L
            val near = rapid.filter {
                it.id !in taken && it.insulinRole != "corr" && (it.mealId == null || it.mealId !in known) && it.t in from..to
            }
            taken += near.map { it.id }
            Meal(r, (linked[i] + near).sortedBy { it.t })
        }
    }

    /** The meal an entry at [t] joins: the last one, if [t] is within the gap after it. */
    fun joining(meals: List<Meal>, t: Long, gapMin: Int): Meal? =
        meals.lastOrNull { it.from <= t + gapMin * 60_000L && t - it.to <= gapMin * 60_000L }
}
