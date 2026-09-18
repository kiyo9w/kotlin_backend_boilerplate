package com.example.server.core

import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The SQL scheduler against a real database: the compare-and-set single-flight
 * and the cursor advance are the production path, so they are proven on H2
 * (Postgres mode) rather than only in memory.
 */
class SqlSchedulerTest {

    private val pool = openCoreDataSource(
        jdbcUrl = "jdbc:h2:mem:sched_${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        user = "sa",
        password = "",
    )

    @AfterTest
    fun closePool() {
        (pool as? AutoCloseable)?.close()
    }

    private fun definition(nextRunAtEpochMs: Long) = ScheduleDefinition(
        name = "nightly",
        spec = ScheduleSpec(Cadence.DAILY, hour = 9, minute = 0),
        zoneId = "Asia/Tokyo",
        jobType = "content/daily",
        payload = """{"kind":"daily"}""",
        nextRunAtEpochMs = nextRunAtEpochMs,
    )

    @Test
    fun aSqlTickEnqueuesOnceAndAdvancesTheCursorDurably() = runBlocking {
        val store = SqlScheduleStore()
        val jobs = SqlJobStore()
        val midnightUtc = Instant.parse("2026-09-18T00:00:00Z").toEpochMilli()
        store.upsert(definition(midnightUtc))

        val scheduler = Scheduler(store, jobs) { midnightUtc }
        val decision = assertIs<SchedulerDecision.Enqueued>(scheduler.tick().single())

        assertEquals("nightly:2026-09-18", decision.periodKey)
        assertTrue(scheduler.tick().isEmpty(), "the cursor moved, so a second tick is not due")
        assertEquals(
            Instant.parse("2026-09-19T00:00:00Z").toEpochMilli(),
            SqlScheduleStore().get("nightly")!!.nextRunAtEpochMs,
            "the advance is durable across store instances",
        )
    }

    @Test
    fun twoSqlInstancesDoNotDoubleEnqueue() = runBlocking {
        val seedStore = SqlScheduleStore()
        val jobs = SqlJobStore()
        val midnightUtc = Instant.parse("2026-09-18T00:00:00Z").toEpochMilli()
        seedStore.upsert(definition(midnightUtc))

        // Both instances read the same due row before either advances it.
        val snapshot = SqlScheduleStore().due(midnightUtc)
        val racyView = object : ScheduleStore by SqlScheduleStore() {
            override suspend fun due(nowEpochMs: Long, limit: Int): List<ScheduleDefinition> = snapshot
        }
        val instanceA = Scheduler(racyView, jobs) { midnightUtc }
        val instanceB = Scheduler(racyView, jobs) { midnightUtc }

        val a = instanceA.tick()
        val b = instanceB.tick()

        val enqueued = listOf(a, b).flatten().filterIsInstance<SchedulerDecision.Enqueued>()
        val lost = listOf(a, b).flatten().filterIsInstance<SchedulerDecision.LostRace>()
        assertEquals(1, enqueued.size, "the compare-and-set lets exactly one instance enqueue")
        assertEquals(1, lost.size, "the loser is told it lost rather than silently skipping")
    }

    @Test
    fun aDisabledScheduleIsPersistedButNotFired() = runBlocking {
        val store = SqlScheduleStore()
        val midnightUtc = Instant.parse("2026-09-18T00:00:00Z").toEpochMilli()
        store.upsert(definition(midnightUtc).copy(enabled = false))

        val due = SqlScheduleStore().due(midnightUtc)
        assertTrue(due.isEmpty(), "a disabled schedule is never due")
        assertEquals(false, SqlScheduleStore().get("nightly")!!.enabled)
    }
}
