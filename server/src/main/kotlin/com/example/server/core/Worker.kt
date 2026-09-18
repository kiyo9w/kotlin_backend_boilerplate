package com.example.server.core

/**
 * Generic in-process worker. Claims one job per tick, routes it to the
 * registered [JobHandler], and writes the terminal state through [JobStore].
 *
 * The lease-and-fence mechanism is the same one the reference product's `SeedWorker` uses:
 * every write carries the claim's attempt number, and a stale attempt is
 * rejected with [StaleClaimException] rather than overwriting the live one.
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
) {
    /**
     * Claim and process one job. Returns the terminal [JobRecord], or null when
     * no job was available or the claim was lost to a stale-lease recovery.
     */
    suspend fun tick(): JobRecord? {
        val claimed = store.claimPending(now(), leaseMs) ?: return null
        return runCatching { produce(claimed) }.getOrElse { error ->
            if (error is StaleClaimException) return null
            throw error
        }
    }

    private suspend fun produce(claimed: JobRecord): JobRecord {
        if (claimed.attempt > maxAttempts) {
            return store.finish(claimed.id, claimed.attempt, result = "", outcome = "MAX_ATTEMPTS")
        }
        val handler = registry.handler(claimed.jobType)
            ?: return store.finish(claimed.id, claimed.attempt, result = "", outcome = "NO_HANDLER")
        val result = runCatching { handler.handle(claimed) }.getOrElse { error ->
            if (error is StaleClaimException) throw error
            return store.finish(claimed.id, claimed.attempt, result = "", outcome = "FAILED")
        }
        if (result.fact != null) {
            store.recordFact(claimed.id, claimed.attempt, result.fact)
        }
        return store.finish(claimed.id, claimed.attempt, result.result, result.outcome)
    }
}
