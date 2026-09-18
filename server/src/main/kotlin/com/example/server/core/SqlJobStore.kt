package com.example.server.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.inTopLevelSuspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.UUID
import kotlin.uuid.Uuid

/**
 * How many eligible rows one claim pass inspects before giving up. The claim is
 * a compare-and-set, so a row another instance already took simply misses and
 * the pass moves on. Lifted verbatim from the reference product's `SqlLedger`.
 */
private const val CLAIM_CANDIDATES: Int = 8

/**
 * SQL-backed [JobStore] over the generic `jobs` table. The lease-and-fence
 * mechanism is lifted verbatim from the reference product's `SqlLedger.claimPending` and
 * `SqlLedger.finish` — the best-designed thing in the server.
 *
 * A product that needs domain columns beside the generic ones (the reference product's l1, l2,
 * seed_text) extends this store or wraps it; the template ships this one.
 */
class SqlJobStore : JobStore {

    override suspend fun enqueue(key: String, jobType: String, payload: String, userId: String?): JobRecord =
        coreDbQuery {
            val existing = GenericJobs.selectAll()
                .where { GenericJobs.key eq key }
                .firstOrNull()
            if (existing != null) {
                val status = JobState.valueOf(existing[GenericJobs.status])
                if (status == JobState.FAILED || status == JobState.STALE) {
                    GenericJobs.update({ GenericJobs.id eq existing[GenericJobs.id] }) {
                        it[GenericJobs.status] = JobState.PENDING.name
                        it[attempt] = existing[GenericJobs.attempt] + 1
                        it[lastError] = null
                        it[GenericJobs.payload] = payload
                    }
                    return@coreDbQuery existing.toRecord().copy(
                        status = JobState.PENDING,
                        attempt = existing[GenericJobs.attempt] + 1,
                        payload = payload,
                    )
                }
                return@coreDbQuery existing.toRecord()
            }
            val jobId = Uuid.random()
            val now = Instant.now()
            GenericJobs.insert {
                it[id] = jobId
                it[GenericJobs.userId] = userId?.let { u -> runCatching { Uuid.parse(u) }.getOrNull() }
                it[GenericJobs.key] = key
                it[GenericJobs.jobType] = jobType
                it[status] = JobState.PENDING.name
                it[attempt] = 0
                it[result] = ""
                it[GenericJobs.payload] = payload
                it[createdAt] = now
            }
            JobRecord(
                id = jobId.toString(),
                key = key,
                jobType = jobType,
                status = JobState.PENDING,
                attempt = 0,
                leaseExpiresAtEpochMs = null,
                lastError = null,
                result = null,
                outcome = null,
                payload = payload,
                createdAtEpochMs = now.toEpochMilli(),
            )
        }

    override suspend fun claimPending(nowEpochMs: Long, leaseMs: Long): JobRecord? = coreDbQuery {
        val now = Instant.ofEpochMilli(nowEpochMs)
        val expired = (GenericJobs.status eq JobState.RUNNING.name) and
            (GenericJobs.leaseExpiresAt lessEq now)
        val candidates = GenericJobs.selectAll()
            .where { (GenericJobs.status eq JobState.PENDING.name) or expired }
            .orderBy(GenericJobs.createdAt, SortOrder.ASC)
            .limit(CLAIM_CANDIDATES)
            .toList()
        val leaseUntil = Instant.ofEpochMilli(nowEpochMs + leaseMs)
        for (row in candidates) {
            val id = row[GenericJobs.id]
            val observedAttempt = row[GenericJobs.attempt]
            val observedStatus = row[GenericJobs.status]
            val claimed = GenericJobs.update({
                (GenericJobs.id eq id) and
                    (GenericJobs.attempt eq observedAttempt) and
                    (GenericJobs.status eq observedStatus)
            }) {
                it[status] = JobState.RUNNING.name
                it[attempt] = observedAttempt + 1
                it[leaseExpiresAt] = leaseUntil
            }
            if (claimed == 1) {
                return@coreDbQuery row.toRecord().copy(
                    status = JobState.RUNNING,
                    attempt = observedAttempt + 1,
                    leaseExpiresAtEpochMs = nowEpochMs + leaseMs,
                )
            }
        }
        null
    }

    override suspend fun renewLease(jobId: String, attempt: Int, nowEpochMs: Long, leaseMs: Long) =
        coreDbQuery {
            val uuid = Uuid.parse(jobId)
            val updated = GenericJobs.update({
                (GenericJobs.id eq uuid) and
                    (GenericJobs.attempt eq attempt) and
                    (GenericJobs.status eq JobState.RUNNING.name)
            }) {
                it[leaseExpiresAt] = java.time.Instant.ofEpochMilli(nowEpochMs + leaseMs)
            }
            if (updated == 0) {
                val exists = GenericJobs.selectAll().where { GenericJobs.id eq uuid }.firstOrNull()
                    ?: throw NoSuchElementException("job $jobId not found")
                throw StaleClaimException(
                    "job $jobId is not held by attempt $attempt (is ${exists[GenericJobs.attempt]})",
                )
            }
        }

    override suspend fun finish(jobId: String, attempt: Int, result: String, outcome: String): JobRecord =
        coreDbQuery {
            val uuid = Uuid.parse(jobId)
            val updated = GenericJobs.update({
                (GenericJobs.id eq uuid) and
                    (GenericJobs.attempt eq attempt) and
                    (GenericJobs.status eq JobState.RUNNING.name)
            }) {
                it[status] = JobState.SUCCEEDED.name
                it[GenericJobs.result] = result
                it[GenericJobs.outcome] = outcome
                it[leaseExpiresAt] = null
                it[lastError] = null
            }
            if (updated == 0) {
                val exists = GenericJobs.selectAll().where { GenericJobs.id eq uuid }.firstOrNull()
                    ?: throw NoSuchElementException("job $jobId not found")
                throw StaleClaimException(
                    "job $jobId is not held by attempt $attempt (is ${exists[GenericJobs.attempt]})",
                )
            }
            GenericJobs.selectAll().where { GenericJobs.id eq uuid }.single().toRecord()
        }

    override suspend fun recordFact(jobId: String, attempt: Int, fact: Int?) {
        // The generic store has no fact column; products add their own.
        // the reference product's SqlLedger.recordQa writes jobs.qa_rejected.
    }

    override suspend fun job(jobId: String): JobRecord? = coreDbQuery {
        val uuid = runCatching { Uuid.parse(jobId) }.getOrNull() ?: return@coreDbQuery null
        GenericJobs.selectAll().where { GenericJobs.id eq uuid }.firstOrNull()?.toRecord()
    }

    private fun org.jetbrains.exposed.v1.core.ResultRow.toRecord() = JobRecord(
        id = this[GenericJobs.id].toString(),
        key = this[GenericJobs.key],
        jobType = this[GenericJobs.jobType].orEmpty(),
        status = JobState.valueOf(this[GenericJobs.status]),
        attempt = this[GenericJobs.attempt],
        leaseExpiresAtEpochMs = this[GenericJobs.leaseExpiresAt]?.toEpochMilli(),
        lastError = this[GenericJobs.lastError],
        result = this[GenericJobs.result].takeIf { it.isNotEmpty() },
        outcome = this[GenericJobs.outcome],
        payload = this[GenericJobs.payload],
        createdAtEpochMs = this[GenericJobs.createdAt].toEpochMilli(),
    )
}

internal suspend fun <T> coreDbQuery(block: suspend JdbcTransaction.() -> T): T =
    withContext(Dispatchers.IO) {
        inTopLevelSuspendTransaction { block() }
    }
