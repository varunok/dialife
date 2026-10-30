package com.lumenarcanus.dialife.navigation

import android.net.Uri
import android.os.Bundle
import androidx.navigation.NavType
import com.lumenarcanus.dialife.data.Treatment
import kotlin.reflect.KType
import kotlin.reflect.typeOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The app's screens once connected, with what each opens with. */
sealed interface Destination {
    @Serializable data object Home : Destination

    @Serializable data object Stats : Destination

    @Serializable data object Log : Destination

    @Serializable data object Settings : Destination

    @Serializable data object DoseSettings : Destination

    @Serializable data object WebLogin : Destination

    /** "You are handling" for an alarm someone on this phone took. */
    @Serializable data class Handling(val alarmId: Long) : Destination

    /** The data table, paged by the chart's range when it was opened. */
    @Serializable data class Data(val spanHours: Int) : Destination

    /**
     * The food form: a new meal, an entry to edit ([editingId], with [entry] for
     * one older than the loaded history), carbs for a low, or the food a "did
     * you forget to log?" question ([questionId], from [initialTime]) asks for.
     */
    @Serializable data class Meal(
        val editingId: String? = null,
        val entry: Treatment? = null,
        val initialTime: Long? = null,
        val forLow: Boolean = false,
        val questionId: Long = 0
    ) : Destination {
        companion object {
            val typeMap: Map<KType, NavType<*>> = mapOf(typeOf<Treatment?>() to TreatmentNavType)
        }
    }
}

/** An entry carried to the food form as JSON, for one the live history no longer holds. */
internal object TreatmentNavType : NavType<Treatment?>(isNullableAllowed = true) {
    override fun get(bundle: Bundle, key: String): Treatment? = bundle.getString(key)?.let(::parseValue)

    override fun put(bundle: Bundle, key: String, value: Treatment?) = bundle.putString(key, value?.let(::serializeAsValue))

    override fun parseValue(value: String): Treatment? =
        if (value == "null") null else Json.decodeFromString(Treatment.serializer(), Uri.decode(value))

    // the route is a URI: the JSON goes into it encoded
    override fun serializeAsValue(value: Treatment?): String =
        value?.let { Uri.encode(Json.encodeToString(Treatment.serializer(), it)) } ?: "null"
}
