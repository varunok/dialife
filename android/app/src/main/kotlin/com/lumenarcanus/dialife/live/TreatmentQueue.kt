package com.lumenarcanus.dialife.live

import com.lumenarcanus.dialife.data.DiaLifeApi
import com.lumenarcanus.dialife.data.Treatment
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.security.SecureRandom

/** One change made on this phone that the server has not confirmed yet. */
@Serializable
data class PendingOp(
    val id: String, // treatment id; one op per id, the newest wins
    val delete: Boolean = false,
    val treatment: Treatment? = null, // for an upsert
)

/**
 * Food and insulin entered here survive no signal, airplane mode and a
 * restart: they wait in a file until the server has them.
 */
class TreatmentQueue(dir: File) {
    private val file = File(dir, "treatment_queue.json")
    private val serializer = ListSerializer(PendingOp.serializer())

    @Synchronized
    fun all(): List<PendingOp> = try {
        DiaLifeApi.json.decodeFromString(serializer, file.readText())
    } catch (e: Exception) {
        emptyList()
    }

    @Synchronized
    fun put(op: PendingOp): List<PendingOp> = write(all().filter { it.id != op.id } + op)

    /** Drops [op] if it is still the newest change for its id. */
    @Synchronized
    fun done(op: PendingOp): List<PendingOp> = write(all().filter { it != op })

    @Synchronized
    fun clear() {
        file.delete()
    }

    private fun write(ops: List<PendingOp>): List<PendingOp> {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(DiaLifeApi.json.encodeToString(serializer, ops))
        tmp.renameTo(file)
        return ops
    }

    companion object {
        private val random = SecureRandom()

        /** A Nightscout-style id (24 hex), made here so the entry has it before the server does. */
        fun newId(): String = ByteArray(12).also(random::nextBytes).joinToString("") { "%02x".format(it) }
    }
}

/** The day's treatments as the chart should show them: the server's, with our unsent changes on top. */
fun List<Treatment>.withPending(ops: List<PendingOp>): List<Treatment> {
    if (ops.isEmpty()) return this
    val touched = ops.map { it.id }.toSet()
    val mine = ops.mapNotNull { op -> op.treatment?.takeIf { !op.delete }?.copy(pending = true) }
    return (filter { it.id !in touched } + mine).sortedBy { it.t }
}
