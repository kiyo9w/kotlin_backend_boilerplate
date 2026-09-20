package com.example.server.core

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Generic operations table, created by `V5__operations.sql`. One row per owner
 * and operation id; the composite primary key is the registration gate and
 * [state] carries the RUNNING predicate that makes every transition one-way.
 */
object Operations : Table("operations") {
    val ownerId = text("owner_id")
    val operationId = text("operation_id")
    val kind = text("kind")
    val payloadHash = text("payload_hash")
    val state = text("state")
    val resultJson = text("result_json").nullable()
    val failureCode = text("failure_code").nullable()
    val failureMessage = text("failure_message").nullable()
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(ownerId, operationId)
}

/**
 * SQL [OperationStore].
 *
 * Registration relies on the composite primary key: of two concurrent first
 * attempts exactly one insert commits, and the loser's duplicate-key failure is
 * caught and re-read as an attach or conflict instead of leaking the SQL error.
 *
 * Every transition is a single conditional `UPDATE ... WHERE state = 'RUNNING'`.
 * The predicate is the compare-and-set: a second completion, or a completion
 * after cancellation, changes zero rows and returns false rather than
 * overwriting a terminal record. A `SELECT ... FOR UPDATE` pass is not needed;
 * one statement is atomic on both H2 (tests) and Postgres.
 */
class SqlOperationStore : OperationStore {

    override suspend fun begin(
        ownerId: String,
        operationId: String,
        kind: String,
        payloadHash: String,
        now: Long,
    ): OperationBegin {
        get(ownerId, operationId)?.let { return it.decideBegin(kind, payloadHash) }
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
        try {
            coreDbQuery {
                Operations.insert {
                    it[Operations.ownerId] = ownerId
                    it[Operations.operationId] = operationId
                    it[Operations.kind] = kind
                    it[Operations.payloadHash] = payloadHash
                    it[Operations.state] = OperationState.RUNNING.name
                    it[Operations.resultJson] = null
                    it[Operations.failureCode] = null
                    it[Operations.failureMessage] = null
                    it[Operations.createdAt] = now
                    it[Operations.updatedAt] = now
                }
            }
        } catch (e: ExposedSQLException) {
            // Two first attempts raced and the primary key let exactly one land.
            // The loser's transaction rolled back with the exception, so read the
            // winner in a fresh transaction. A null read means the failure was
            // not a duplicate-key race; surface it.
            return (get(ownerId, operationId) ?: throw e).decideBegin(kind, payloadHash)
        }
        return OperationBegin.Started(started)
    }

    override suspend fun complete(
        ownerId: String,
        operationId: String,
        resultJson: String,
        now: Long,
    ): Boolean = transition(ownerId, operationId, OperationState.COMPLETE, now, resultJson = resultJson)

    override suspend fun fail(
        ownerId: String,
        operationId: String,
        code: String,
        message: String,
        now: Long,
    ): Boolean = transition(
        ownerId,
        operationId,
        OperationState.FAILED,
        now,
        failureCode = code,
        failureMessage = message,
    )

    override suspend fun cancel(ownerId: String, operationId: String, now: Long): Boolean =
        transition(ownerId, operationId, OperationState.CANCELLED, now)

    override suspend fun get(ownerId: String, operationId: String): OperationRecord? = coreDbQuery {
        Operations.selectAll()
            .where { (Operations.ownerId eq ownerId) and (Operations.operationId eq operationId) }
            .firstOrNull()
            ?.toRecord()
    }

    private suspend fun transition(
        ownerId: String,
        operationId: String,
        state: OperationState,
        now: Long,
        resultJson: String? = null,
        failureCode: String? = null,
        failureMessage: String? = null,
    ): Boolean = coreDbQuery {
        val updated = Operations.update({
            (Operations.ownerId eq ownerId) and
                (Operations.operationId eq operationId) and
                (Operations.state eq OperationState.RUNNING.name)
        }) {
            it[Operations.state] = state.name
            it[Operations.resultJson] = resultJson
            it[Operations.failureCode] = failureCode
            it[Operations.failureMessage] = failureMessage
            it[Operations.updatedAt] = now
        }
        updated == 1
    }

    private fun org.jetbrains.exposed.v1.core.ResultRow.toRecord() = OperationRecord(
        ownerId = this[Operations.ownerId],
        operationId = this[Operations.operationId],
        kind = this[Operations.kind],
        payloadHash = this[Operations.payloadHash],
        state = OperationState.valueOf(this[Operations.state]),
        resultJson = this[Operations.resultJson],
        failureCode = this[Operations.failureCode],
        failureMessage = this[Operations.failureMessage],
        createdAt = this[Operations.createdAt],
        updatedAt = this[Operations.updatedAt],
    )
}
