package com.example.server.core

import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

/**
 * The recovery drill behind the operations report.
 *
 * The report names a failure signal; this test EXECUTES the recovery for one of
 * them, so the signal and the fix are proven together rather than described:
 *
 * 1. a job is left RUNNING with an expired lease (the worker died mid-job);
 * 2. the report shows the failure signal (`jobs_stale_lease`);
 * 3. the recovery — restart the worker — is performed;
 * 4. the job finishes and the report's failure signal is gone.
 */
class OperationsRecoveryDrillTest {

    private val leaseMs = 1_000L

    private val ds = openCoreDataSource(
        jdbcUrl = "jdbc:h2:mem:core_drill_${UUID.randomUUID()};MODE=PostgreSQL;" +
            "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        user = "sa",
        password = "",
    )

    @AfterTest
    fun closePool() {
        (ds as? AutoCloseable)?.close()
    }

    @Test
    fun aStaleLeaseIsVisibleThenHealedByRestartingTheWorker() = runBlocking {
        val store = SqlJobStore()
        val job = store.enqueue("drill-1", "example/echo", """{"k":1}""")

        // The worker claims the job and dies before finishing. Its lease is
        // already expired, which is the shape the report watches for.
        val died = System.currentTimeMillis() - 2 * leaseMs
        val abandoned = store.claimPending(died, leaseMs)!!
        assertEquals(JobState.RUNNING, abandoned.status)
        assertEquals(1, abandoned.attempt)

        val before = OperationsReport.run(ds)
        assertEquals(1.0, before.getValue("jobs_stale_lease"), 0.0001, "the failure signal must be visible")
        assertEquals(0.0, before.getValue("jobs_terminal_succeeded"), 0.0001)

        // Recovery: restart the worker. It reclaims the stale claim and finishes
        // the job. No operator surgery on the row.
        val worker = Worker(
            store,
            JobHandlerRegistry().register("example/echo") { JobResult(result = "ok", outcome = "OK") },
            leaseMs = leaseMs,
        )
        val done = worker.tick()!!

        assertEquals(job.id, done.id)
        assertEquals(JobState.SUCCEEDED, done.status)
        assertEquals(2, done.attempt, "the reclaim is the second attempt")
        assertEquals("ok", done.result)

        val after = OperationsReport.run(ds)
        assertEquals(0.0, after.getValue("jobs_stale_lease"), 0.0001, "the recovery clears the signal")
        assertEquals(0.0, after.getValue("jobs_running"), 0.0001)
        assertEquals(1.0, after.getValue("jobs_terminal_succeeded"), 0.0001)
    }
}
