package com.example.server.core

/**
 * Generic durable job queue. The lease-and-fence mechanism is lifted verbatim
 * from the reference product's [com.example.server.Ledger] — claim with a lease, fence
 * every write by attempt number, reject stale writes with
 * [StaleClaimException].
 *
 * A product's own store (the reference product's `Ledger`) extends or wraps this interface and
 * adds domain methods. The template ships this interface plus [MemoryJobStore]
 * and [SqlJobStore]; a product never re-implements the queue.
 */
interface JobStore {

    /**
     * Enqueue a job. [key] is the deduplication identity: two enqueues with the
     * same key collapse to one row. [jobType] routes the job to a handler in the
     * [JobHandlerRegistry]. [payload] is opaque product input.
     */
    suspend fun enqueue(key: String, jobType: String, payload: String, userId: String? = null): JobRecord

    /**
     * Atomically claim one eligible job and return it, or null when none is
     * ready. The returned [JobRecord.attempt] is the fence every later write
     * for this claim must carry.
     */
    suspend fun claimPending(nowEpochMs: Long, leaseMs: Long): JobRecord?

    /**
     * Record the terminal state for [attempt]. A write whose [attempt] is no
     * longer the job's current attempt throws [StaleClaimException] instead of
     * overwriting the live attempt.
     */
    suspend fun finish(jobId: String, attempt: Int, result: String, outcome: String): JobRecord

    /**
     * Record an optional integer fact for [attempt] (e.g. QA rejection count).
     * A stale [attempt] writes nothing.
     */
    suspend fun recordFact(jobId: String, attempt: Int, fact: Int?)

    /**
     * Look up one job by id. Returns null when not found.
     */
    suspend fun job(jobId: String): JobRecord?
}
