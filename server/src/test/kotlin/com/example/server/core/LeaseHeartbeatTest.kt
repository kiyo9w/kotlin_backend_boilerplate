package com.example.server.core

import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * The generic queue's lease heartbeat. Same mechanism as the product ledger:
 * the lease timestamp is the heartbeat, a renewal keeps a long handler from
 * being reclaimed mid-flight, and only the holder may renew.
 */
class LeaseHeartbeatTest {

    private val leaseMs = 1_000L

    // ---- Memory store ----

    @Test
    fun aRenewedLeaseSurvivesTheOriginalExpiry() = runBlocking<Unit> {
        val store = MemoryJobStore()
        store.enqueue("k", "example/echo", "payload")

        val held = store.claimPending(0, leaseMs)!!
        assertEquals(1, held.attempt)

        store.renewLease(held.id, attempt = 1, nowEpochMs = leaseMs / 2, leaseMs = leaseMs)

        assertNull(store.claimPending(leaseMs + 1, leaseMs), "a heartbeated claim is not reclaimable")

        val recovered = store.claimPending((leaseMs / 2) + leaseMs + 1, leaseMs)!!
        assertEquals(held.id, recovered.id)
        assertEquals(2, recovered.attempt)
    }

    @Test
    fun onlyTheHolderMayRenew() = runBlocking<Unit> {
        val store = MemoryJobStore()
        store.enqueue("k", "example/echo", "payload")
        val held = store.claimPending(0, leaseMs)!!

        assertFailsWith<StaleClaimException> {
            store.renewLease(held.id, attempt = 2, nowEpochMs = 0, leaseMs = leaseMs)
        }
    }

    // ---- SQL store ----

    private val pool = openCoreDataSource(
        jdbcUrl = "jdbc:h2:mem:core_hb_${UUID.randomUUID()};MODE=PostgreSQL;" +
            "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        user = "sa",
        password = "",
    )

    @AfterTest
    fun closePool() {
        (pool as? AutoCloseable)?.close()
    }

    @Test
    fun theSqlRenewalIsDurableAndFenced() = runBlocking<Unit> {
        val store = SqlJobStore()
        store.enqueue("k-sql", "example/echo", "payload")
        val held = store.claimPending(0, leaseMs)!!

        store.renewLease(held.id, attempt = 1, nowEpochMs = leaseMs / 2, leaseMs = leaseMs)

        assertNull(SqlJobStore().claimPending(leaseMs + 1, leaseMs), "the renewal is durable across instances")
        assertFailsWith<StaleClaimException> {
            SqlJobStore().renewLease(held.id, attempt = 9, nowEpochMs = 0, leaseMs = leaseMs)
        }
    }

    // ---- Worker ----

    private class SpyStore(private val delegate: JobStore) : JobStore by delegate {
        var renewCount = 0
            private set

        override suspend fun renewLease(jobId: String, attempt: Int, nowEpochMs: Long, leaseMs: Long) {
            renewCount += 1
            delegate.renewLease(jobId, attempt, nowEpochMs, leaseMs)
        }
    }

    @Test
    fun theWorkerHeartbeatsWhileTheHandlerRuns() = runBlocking<Unit> {
        val store = SpyStore(MemoryJobStore())
        store.enqueue("k", "slow", "payload")
        val registry = JobHandlerRegistry().register("slow") {
            withTimeout(5_000) { while (store.renewCount < 2) delay(2) }
            JobResult(result = "done", outcome = "OK")
        }
        val worker = Worker(store, registry, leaseMs = 200, heartbeatIntervalMs = 5)

        val done = worker.tick()!!
        assertEquals(JobState.SUCCEEDED, done.status)
        assertTrue(store.renewCount >= 2, "the lease must be renewed while the handler is in flight")
    }

    @Test
    fun aCancelledHandlerIsNotMarkedFailed() = runBlocking<Unit> {
        val store = MemoryJobStore()
        val record = store.enqueue("k", "slow", "payload")
        val registry = JobHandlerRegistry().register("slow") { awaitCancellation() }
        val worker = Worker(store, registry, leaseMs = 1_000, heartbeatIntervalMs = 50)

        val running = launch { worker.tick() }
        delay(100)
        running.cancelAndJoin()

        assertEquals(
            JobState.RUNNING,
            store.job(record.id)!!.status,
            "shutdown must leave the claim for the lease to recover, not write FAILED",
        )
    }
}
