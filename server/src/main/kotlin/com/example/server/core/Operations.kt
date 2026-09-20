package com.example.server.core

import java.util.concurrent.ConcurrentHashMap

enum class OperationState { RUNNING, COMPLETE, FAILED, CANCELLED }

/** One registered operation. [payloadHash] detects "same id, different request". */
data class OperationRecord(
    val ownerId: String,
    val operationId: String,
    val kind: String,
    val payloadHash: String,
    val state: OperationState,
    val resultJson: String? = null,
    val failureCode: String? = null,
    val failureMessage: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

/** [begin] outcomes. [Attached] means this exact request is already registered; the caller must not start a second attempt. */
sealed interface OperationBegin {
    data class Started(val record: OperationRecord) : OperationBegin
    data class Attached(val record: OperationRecord) : OperationBegin
    data class Conflict(val current: OperationRecord) : OperationBegin
}

/**
 * Operation-id idempotency registry for work that may be retried.
 *
 * [begin] is an atomic first-writer-wins registration keyed on owner +
 * operation id. A repeat with the same kind and [payloadHash] attaches to the
 * existing record whatever its state (the product decides what to do with a
 * completed or failed one); a different kind or hash conflicts and carries the
 * current record. First registration inserts RUNNING.
 *
 * Completion, failure, and cancellation are transitions from RUNNING only, so
 * a terminal operation is never overwritten by a second attempt.
 */
interface OperationStore {
    /** Atomic first-writer-wins registration keyed on (ownerId, operationId). */
    suspend fun begin(
        ownerId: String,
        operationId: String,
        kind: String,
        payloadHash: String,
        now: Long,
    ): OperationBegin

    /** Marks a RUNNING operation complete with its result JSON. Returns false when it was not running. */
    suspend fun complete(ownerId: String, operationId: String, resultJson: String, now: Long): Boolean

    /** Marks a RUNNING operation failed with a stable code and human message. Returns false when it was not running. */
    suspend fun fail(ownerId: String, operationId: String, code: String, message: String, now: Long): Boolean

    /** Cancellation is terminal, never success. Returns false when it was not running. */
    suspend fun cancel(ownerId: String, operationId: String, now: Long): Boolean

    suspend fun get(ownerId: String, operationId: String): OperationRecord?
}

/**
 * In-memory [OperationStore] for the explicit local-only mode and for tests.
 * Registration and every transition run inside one synchronized critical
 * section, so the first-writer-wins and RUNNING-only rules hold under
 * concurrency the same way the SQL store's primary key and conditional UPDATE
 * do. Keeps no state across restarts.
 */
class MemoryOperationStore : OperationStore {

    private val lock = Any()
    private val rows = ConcurrentHashMap<Pair<String, String>, OperationRecord>()

    override suspend fun begin(
        ownerId: String,
        operationId: String,
        kind: String,
        payloadHash: String,
        now: Long,
    ): OperationBegin {
        synchronized(lock) {
            val key = ownerId to operationId
            rows[key]?.let { return it.decideBegin(kind, payloadHash) }
            val started = OperationRecord(
                ownerId = ownerId,
                operationId = operationId,
                kind = kind,
                payloadHash = payloadHash,
                state = OperationState.RUNNING,
                resultJson = null,
                failureCode = null,
                failureMessage = null,
                createdAt = now,
                updatedAt = now,
            )
            rows[key] = started
            return OperationBegin.Started(started)
        }
    }

    override suspend fun complete(
        ownerId: String,
        operationId: String,
        resultJson: String,
        now: Long,
    ): Boolean = transition(ownerId, operationId) {
        it.copy(state = OperationState.COMPLETE, resultJson = resultJson, updatedAt = now)
    }

    override suspend fun fail(
        ownerId: String,
        operationId: String,
        code: String,
        message: String,
        now: Long,
    ): Boolean = transition(ownerId, operationId) {
        it.copy(
            state = OperationState.FAILED,
            failureCode = code,
            failureMessage = message,
            updatedAt = now,
        )
    }

    override suspend fun cancel(ownerId: String, operationId: String, now: Long): Boolean =
        transition(ownerId, operationId) {
            it.copy(state = OperationState.CANCELLED, updatedAt = now)
        }

    override suspend fun get(ownerId: String, operationId: String): OperationRecord? =
        rows[ownerId to operationId]

    private fun transition(
        ownerId: String,
        operationId: String,
        mutate: (OperationRecord) -> OperationRecord,
    ): Boolean {
        synchronized(lock) {
            val key = ownerId to operationId
            val current = rows[key] ?: return false
            if (current.state != OperationState.RUNNING) return false
            rows[key] = mutate(current)
            return true
        }
    }
}

/**
 * The begin rule shared by every [OperationStore]: the same id with the same
 * kind and payload hash attaches, anything else conflicts and carries the
 * current record.
 */
internal fun OperationRecord.decideBegin(kind: String, payloadHash: String): OperationBegin =
    if (this.kind == kind && this.payloadHash == payloadHash) {
        OperationBegin.Attached(this)
    } else {
        OperationBegin.Conflict(this)
    }
