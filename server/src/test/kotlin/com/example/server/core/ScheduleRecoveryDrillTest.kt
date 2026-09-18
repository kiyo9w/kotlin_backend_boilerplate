package com.example.server.core

import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking

/**
 * The second drilled recovery behind the operations report: the "scheduler not
 * ticking" row.
 *
 * A registered schedule whose cursor has passed shows up as `schedules_due` and
 * nothing happens until a tick. The drill proves the signal, the recovery
 * (restart the process, i.e. run a tick), and that the recovery enqueues exactly
 * one job for the missed period — never a backlog.
 */
class ScheduleRecoveryDrillTest {

    private val ds = openCoreDataSource(
        jdbcUrl = "jdbc:h2:mem:core_sched_drill_${UUID.randomUUID()};MODE=PostgreSQL;" +
            "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        user = "sa",
        password = "",
    )

    @AfterTest
    fun closePool() {
        (ds as? AutoCloseable)?.close()
    }

    @Test
    fun aDueScheduleIsVisibleThenHealedByATick() = runBlocking {
        val schedules = SqlScheduleStore()
        val jobs = SqlJobStore()
        val nowMs = System.currentTimeMillis()
        schedules.upsert(
            ScheduleDefinition(
                name = "drill-daily",
                spec = ScheduleSpec(Cadence.DAILY, hour = 3, minute = 0),
                zoneId = "UTC",
                jobType = "example/echo",
                payload = """{"kind":"daily"}""",
                nextRunAtEpochMs = nowMs - 3_600_000L,
            ),
        )

        val before = OperationsReport.run(ds)
        assertEquals(1.0, before.getValue("schedules_due"), 0.0001, "the failure signal must be visible")
        assertEquals(0.0, before.getValue("jobs_pending"), 0.0001, "nothing has been enqueued yet")

        // Recovery: restart the process. One tick is what the restart does.
        val scheduler = Scheduler(schedules, jobs) { nowMs }
        val decision = assertIs<SchedulerDecision.Enqueued>(scheduler.tick().single())

        val after = OperationsReport.run(ds)
        assertEquals(0.0, after.getValue("schedules_due"), 0.0001, "the recovery clears the signal")
        assertEquals(1.0, after.getValue("jobs_pending"), 0.0001, "the missed period is enqueued once")
        assertEquals(
            decision.periodKey,
            jobs.job(decision.jobId!!)!!.key,
            "the job's dedupe key is the schedule-period key, so a second tick cannot double-enqueue",
        )
    }
}
