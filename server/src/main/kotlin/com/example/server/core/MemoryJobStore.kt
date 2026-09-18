package com.example.server.core

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [JobStore] for the explicit local-only mode (blank DATABASE_URL)
 * and for tests. Keeps no state across restarts. The lease-and-fence mechanism
 * is identical to [SqlJobStore]: a stale attempt throws [StaleClaimException].
 *
 * Lifted from the reference product's `MemoryLedger` queue methods — the shape is kept exactly.
 */
class MemoryJobStore(
    private val clock: () -> Long = { System.currentTimeMillis() },
) : JobStore {

    private val lock = Any()
    private val jobs = ConcurrentHashMap<String, StoredJob>()
    private val activeByKey = ConcurrentHashMap<String, String>()

    private data class StoredJob(val userId: String?, val record: JobRecord)

    override suspend fun enqueue(key: String, jobType: String, payload: String, userId: String?): JobRecord {
        synchronized(lock) {
            activeByKey[key]?.let { existingId ->
                jobs[existingId]?.let { return it.record }
            }
            val record = JobRecord(
                id = UUID.randomUUID().toString(),
                key = key,
                jobType = jobType,
                status = JobState.PENDING,
                attempt = 0,
                leaseExpiresAtEpochMs = null,
                lastError = null,
                result = null,
                outcome = null,
                payload = payload,
                createdAtEpochMs = clock(),
            )
            jobs[record.id] = StoredJob(userId, record)
            activeByKey[key] = record.id
            return record
        }
    }

    override suspend fun claimPending(nowEpochMs: Long, leaseMs: Long): JobRecord? {
        synchronized(lock) {
            val hit = jobs.values.firstOrNull { stored ->
                stored.record.status == JobState.PENDING ||
                    (stored.record.status == JobState.RUNNING &&
                        (stored.record.leaseExpiresAtEpochMs ?: 0L) <= nowEpochMs)
            } ?: return null
            val claimed = hit.record.copy(
                status = JobState.RUNNING,
                attempt = hit.record.attempt + 1,
                leaseExpiresAtEpochMs = nowEpochMs + leaseMs,
            )
            jobs[claimed.id] = hit.copy(record = claimed)
            return claimed
        }
    }

    override suspend fun renewLease(jobId: String, attempt: Int, nowEpochMs: Long, leaseMs: Long) {
        synchronized(lock) {
            val hit = jobs[jobId] ?: throw NoSuchElementException("job $jobId not found")
            requireCurrentClaim(hit.record, attempt)
            jobs[jobId] = hit.copy(
                record = hit.record.copy(leaseExpiresAtEpochMs = nowEpochMs + leaseMs),
            )
        }
    }

    override suspend fun finish(jobId: String, attempt: Int, result: String, outcome: String): JobRecord {
        synchronized(lock) {
            val hit = jobs[jobId] ?: throw NoSuchElementException("job $jobId not found")
            requireCurrentClaim(hit.record, attempt)
            val done = hit.record.copy(
                status = JobState.SUCCEEDED,
                result = result,
                outcome = outcome,
                leaseExpiresAtEpochMs = null,
                lastError = null,
            )
            jobs[jobId] = hit.copy(record = done)
            return done
        }
    }

    override suspend fun recordFact(jobId: String, attempt: Int, fact: Int?) {
        // Memory mode keeps no durable facts; this is a no-op by design.
    }

    override suspend fun job(jobId: String): JobRecord? = jobs[jobId]?.record

    private fun requireCurrentClaim(record: JobRecord, attempt: Int) {
        if (record.status != JobState.RUNNING || record.attempt != attempt) {
            throw StaleClaimException("job ${record.id} is not held by attempt $attempt")
        }
    }
}
