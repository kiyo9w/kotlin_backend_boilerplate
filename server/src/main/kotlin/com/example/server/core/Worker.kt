package com.example.server.core

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Generic in-process worker. Claims one job per tick, routes it to the
 * registered [JobHandler], and writes the terminal state through [JobStore].
 *
 * The lease-and-fence mechanism is the same one the reference product's `SeedWorker` uses:
 * every write carries the claim's attempt number, and a stale attempt is
 * rejected with [StaleClaimException] rather than overwriting the live one.
 *
 * A handler that can outlive its lease runs under a heartbeat: while it is in
 * flight a background coroutine renews the claim's lease, so a long job is never
 * reclaimed mid-flight by another worker. That mechanism is lifted from
 * db-scheduler's heartbeat/dead-execution design (the lease timestamp is the
 * heartbeat; a missed renewal is what makes a run reclaimable).
 *
 * A product that needs domain-specific fallback logic (the reference product's authored-pack
 * fallback) wraps this worker or implements its own; the template ships this
 * one as the default.
 */
class Worker(
    private val store: JobStore,
    private val registry: JobHandlerRegistry,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val leaseMs: Long = 30_000,
    private val maxAttempts: Int = 5,
    /**
     * How often the lease is renewed while a handler is in flight. Must be
     * shorter than [leaseMs]; the default is a third of the lease. Zero disables
     * the heartbeat (tests that drive the clock by hand).
     */
    private val heartbeatIntervalMs: Long = if (leaseMs > 0) (leaseMs / 3).coerceAtLeast(1) else 0,
) {
    init {
        require(heartbeatIntervalMs <= 0 || heartbeatIntervalMs < leaseMs) {
            "heartbeatIntervalMs ($heartbeatIntervalMs) must be positive and shorter than leaseMs ($leaseMs)"
        }
    }

    /**
     * Claim and process one job. Returns the terminal [JobRecord], or null when
     * no job was available or the claim was lost to a stale-lease recovery.
     */
    suspend fun tick(): JobRecord? {
        val claimed = store.claimPending(now(), leaseMs) ?: return null
        return runCatching { produce(claimed) }.getOrElse { error ->
            if (error is kotlin.coroutines.cancellation.CancellationException) throw error
            if (error is StaleClaimException) return null
            throw error
        }
    }

    private suspend fun produce(claimed: JobRecord): JobRecord = withHeartbeat(claimed) {
        if (claimed.attempt > maxAttempts) {
            return@withHeartbeat store.finish(claimed.id, claimed.attempt, result = "", outcome = "MAX_ATTEMPTS")
        }
        val handler = registry.handler(claimed.jobType)
            ?: return@withHeartbeat store.finish(claimed.id, claimed.attempt, result = "", outcome = "NO_HANDLER")
        val result = runCatching { handler.handle(claimed) }.getOrElse { error ->
            // A cancelled coroutine is shutdown, not a job failure: never write a
            // terminal state for work the shutdown interrupted. The lease is left
            // to expire so another instance can pick the job up cleanly.
            if (error is kotlin.coroutines.cancellation.CancellationException) throw error
            if (error is StaleClaimException) throw error
            return@withHeartbeat store.finish(claimed.id, claimed.attempt, result = "", outcome = "FAILED")
        }
        if (result.fact != null) {
            store.recordFact(claimed.id, claimed.attempt, result.fact)
        }
        store.finish(claimed.id, claimed.attempt, result.result, result.outcome)
    }

    /**
     * Run [block] under a lease heartbeat. While the block is in flight, a
     * background coroutine renews the claim's lease so a handler longer than
     * [leaseMs] is never reclaimed mid-flight. The heartbeat stops the moment the
     * block returns or the claim is lost (a stale renewal throws
     * [StaleClaimException]); a transient renewal error is retried on the next
     * tick rather than killing the handler.
     */
    private suspend fun <T> withHeartbeat(job: JobRecord, block: suspend () -> T): T {
        if (heartbeatIntervalMs <= 0) return block()
        return coroutineScope {
            val ticker = launch {
                while (isActive) {
                    delay(heartbeatIntervalMs)
                    try {
                        store.renewLease(job.id, job.attempt, now(), leaseMs)
                    } catch (lost: StaleClaimException) {
                        return@launch
                    } catch (error: Throwable) {
                        if (error is kotlin.coroutines.cancellation.CancellationException) throw error
                        // Transient store error: retry on the next tick.
                    }
                }
            }
            try {
                block()
            } finally {
                ticker.cancel()
            }
        }
    }
}
