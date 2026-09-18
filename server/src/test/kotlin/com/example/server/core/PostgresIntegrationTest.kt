package com.example.server.core

import java.util.UUID
import javax.sql.DataSource
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach

/**
 * The real-Postgres path.
 *
 * Every other test runs on H2 in PostgreSQL mode, which is fast but is not the
 * production engine. This one proves the claims H2 cannot:
 *
 * - the Flyway migrations apply to real Postgres;
 * - the operations report is genuinely portable (its `INTERVAL` and
 *   `CURRENT_TIMESTAMP` expressions, not just its syntax);
 * - the queue's compare-and-set gives exactly one owner under real concurrent
 *   transactions;
 * - the scheduler's compare-and-set lets exactly one instance enqueue.
 *
 * It is skipped unless `TEST_POSTGRES_URL` is set, so the fast suite stays
 * infrastructure-free. Run it against a container:
 *
 * ```
 * docker run -d --name tpl-pg -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=boilerplate \
 *   -p 5433:5432 postgres:16-alpine
 * TEST_POSTGRES_URL=jdbc:postgresql://localhost:5433/boilerplate \
 *   TEST_POSTGRES_USER=postgres TEST_POSTGRES_PASSWORD=postgres \
 *   ./gradlew :server:test --tests '*PostgresIntegrationTest'
 * ```
 *
 * The suite shares one database, so every test starts from empty queue and
 * schedule tables. Without that reset, "a fresh database" is not fresh and a
 * concurrent-claim test can hand two winners two different rows.
 */
class PostgresIntegrationTest {

    private val url: String = System.getenv("TEST_POSTGRES_URL").orEmpty()

    private lateinit var pool: DataSource

    @BeforeEach
    fun setUp() {
        assumeTrue(url.isNotBlank(), "TEST_POSTGRES_URL is not set")
        pool = openCoreDataSource(
            jdbcUrl = url,
            user = System.getenv("TEST_POSTGRES_USER").orEmpty().ifBlank { "postgres" },
            password = System.getenv("TEST_POSTGRES_PASSWORD").orEmpty(),
        )
        // Reaching here at all means Flyway migrated a real Postgres.
        pool.connection.use { connection ->
            connection.autoCommit = true
            connection.createStatement().use { it.execute("TRUNCATE TABLE jobs, schedules") }
        }
    }

    @AfterTest
    fun tearDown() {
        if (::pool.isInitialized) (pool as? AutoCloseable)?.close()
    }

    @Test
    fun migrationsAndTheOperationsReportRunOnRealPostgres() {
        val metrics = OperationsReport.run(pool)
        listOf(
            "jobs_pending", "jobs_pending_older_than_1h", "jobs_running",
            "jobs_stale_lease", "jobs_retried", "jobs_terminal_failed",
            "jobs_terminal_succeeded", "schedules_enabled", "schedules_disabled",
            "schedules_due",
        ).forEach { stage ->
            assertEquals(0.0, metrics.getValue(stage), 0.0001, "$stage must be 0 on a fresh database")
        }
    }

    @Test
    fun concurrentClaimsYieldExactlyOneOwnerOnRealPostgres() = runBlocking<Unit> {
        val store = SqlJobStore()
        store.enqueue("pg-race-${UUID.randomUUID()}", "example/echo", "{}")

        val now = System.currentTimeMillis()
        val winners = listOf(
            async { store.claimPending(now, 30_000) },
            async { store.claimPending(now, 30_000) },
        ).awaitAll().filterNotNull()

        assertEquals(1, winners.size, "exactly one concurrent claim may win")
        assertEquals(1, winners.single().attempt)
    }

    @Test
    fun twoSchedulerInstancesEnqueueOnceOnRealPostgres() = runBlocking<Unit> {
        val schedules = SqlScheduleStore()
        val jobs = SqlJobStore()
        val nowMs = System.currentTimeMillis()
        schedules.upsert(
            ScheduleDefinition(
                name = "pg-sched-${UUID.randomUUID()}",
                spec = ScheduleSpec(Cadence.DAILY, hour = 3, minute = 0),
                zoneId = "UTC",
                jobType = "example/echo",
                payload = "{}",
                nextRunAtEpochMs = nowMs - 3_600_000L,
            ),
        )

        // Both instances read the same due row before either advances it.
        val snapshot = schedules.due(nowMs)
        val racyView = object : ScheduleStore by schedules {
            override suspend fun due(nowEpochMs: Long, limit: Int): List<ScheduleDefinition> = snapshot
        }
        val a = Scheduler(racyView, jobs) { nowMs }
        val b = Scheduler(racyView, jobs) { nowMs }

        val decisions = listOf(async { a.tick() }, async { b.tick() }).awaitAll().flatten()
        val enqueued = decisions.filterIsInstance<SchedulerDecision.Enqueued>()
        val lost = decisions.filterIsInstance<SchedulerDecision.LostRace>()

        assertEquals(1, enqueued.size, "exactly one instance may enqueue")
        assertEquals(1, lost.size, "the other must be told it lost the compare-and-set")
        assertEquals(
            enqueued.single().periodKey,
            jobs.job(enqueued.single().jobId!!)!!.key,
            "the dedupe key is the period key",
        )
    }

    @Test
    fun aHeartbeatRenewalSurvivesAReopenOnRealPostgres() = runBlocking<Unit> {
        val store = SqlJobStore()
        store.enqueue("pg-hb-${UUID.randomUUID()}", "example/echo", "{}")
        val held = store.claimPending(0, 1_000)!!

        store.renewLease(held.id, attempt = 1, nowEpochMs = 500, leaseMs = 1_000)

        // A fresh store over the same database sees the renewal.
        assertEquals(null, SqlJobStore().claimPending(1_001, 1_000), "the renewal is durable")
        assertIs<JobRecord>(SqlJobStore().job(held.id))
    }
}
