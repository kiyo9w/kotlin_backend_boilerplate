package com.example.server.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.Month
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The scheduler's five required behaviours. Each test names the behaviour it
 * proves; together they are the section-8 acceptance.
 */
class SchedulerTest {

    private val tokyo = ZoneId.of("Asia/Tokyo")

    // 2026-09-18T00:00:00Z == 2026-09-18T09:00:00+09:00
    private val midnightUtc = Instant.parse("2026-09-18T00:00:00Z").toEpochMilli()

    private fun daily(hour: Int, minute: Int) = ScheduleSpec(Cadence.DAILY, hour, minute)

    private fun definition(
        name: String = "nightly",
        spec: ScheduleSpec = daily(9, 0),
        zone: ZoneId = tokyo,
        nextRunAtEpochMs: Long,
        enabled: Boolean = true,
    ) = ScheduleDefinition(
        name = name,
        spec = spec,
        zoneId = zone.id,
        jobType = "content/daily",
        payload = """{"kind":"daily"}""",
        enabled = enabled,
        nextRunAtEpochMs = nextRunAtEpochMs,
    )

    private fun scheduler(
        store: ScheduleStore,
        jobs: JobStore,
        nowMs: Long,
        conditions: Map<String, ScheduleCondition> = emptyMap(),
        clock: () -> Long = { nowMs },
    ) = Scheduler(store, jobs, conditions, clock)

    @Test
    fun aDoubledTickEnqueuesOnce() = runBlocking {
        val store = MemoryScheduleStore(listOf(definition(nextRunAtEpochMs = midnightUtc)))
        val jobs = MemoryJobStore()
        val scheduler = scheduler(store, jobs, midnightUtc)

        val first = scheduler.tick()
        val second = scheduler.tick()

        assertEquals(1, first.size)
        assertIs<SchedulerDecision.Enqueued>(first.single())
        assertTrue(second.isEmpty(), "the cursor moved past now, so the second tick sees nothing due")
        assertEquals("nightly:2026-09-18", assertIs<SchedulerDecision.Enqueued>(first.single()).periodKey)
    }

    @Test
    fun aMissedRunIsRecoveredOnceAtTheLatestPeriod() = runBlocking {
        val store = MemoryScheduleStore(listOf(definition(nextRunAtEpochMs = midnightUtc)))
        val jobs = MemoryJobStore()
        // Three days late: 2026-09-21T00:00:30Z == 09:00:30 JST on the 21st.
        val nowMs = Instant.parse("2026-09-21T00:00:30Z").toEpochMilli()
        val scheduler = scheduler(store, jobs, nowMs)

        val decision = assertIs<SchedulerDecision.Enqueued>(scheduler.tick().single())

        assertEquals("nightly:2026-09-21", decision.periodKey, "catches up the latest missed period")
        val advanced = store.get("nightly")!!.nextRunAtEpochMs
        assertEquals(Instant.parse("2026-09-22T00:00:00Z").toEpochMilli(), advanced)
        assertTrue(scheduler.tick().isEmpty(), "no backlog is replayed after recovery")
    }

    @Test
    fun theTimezoneBoundaryIsCivilNotUtc() = runBlocking {
        // The 2026-09-17 09:00 JST occurrence is already stored as the cursor.
        val store = MemoryScheduleStore(
            listOf(definition(nextRunAtEpochMs = Instant.parse("2026-09-17T00:00:00Z").toEpochMilli())),
        )
        val jobs = MemoryJobStore()
        // 2026-09-17T23:30:00Z == 2026-09-18T08:30 JST, before 09:00 JST.
        val nowMs = Instant.parse("2026-09-17T23:30:00Z").toEpochMilli()
        val scheduler = scheduler(store, jobs, nowMs)

        val decision = assertIs<SchedulerDecision.Enqueued>(scheduler.tick().single())

        assertEquals(
            "nightly:2026-09-17",
            decision.periodKey,
            "the current Tokyo civil day is still the 17th, though UTC is already the 18th",
        )
        assertEquals(Instant.parse("2026-09-18T00:00:00Z").toEpochMilli(), store.get("nightly")!!.nextRunAtEpochMs)
    }

    @Test
    fun twoInstancesDoNotDoubleEnqueue() = runBlocking {
        val base = MemoryScheduleStore(listOf(definition(nextRunAtEpochMs = midnightUtc)))
        // Both instances read the same due row before either advances it. The
        // snapshot makes that race deterministic instead of timing-dependent.
        val snapshot = base.list()
        val racyView = object : ScheduleStore by base {
            override suspend fun due(nowEpochMs: Long, limit: Int): List<ScheduleDefinition> = snapshot
        }
        val jobs = MemoryJobStore()
        val instanceA = Scheduler(racyView, jobs) { midnightUtc }
        val instanceB = Scheduler(racyView, jobs) { midnightUtc }

        val a = instanceA.tick()
        val b = instanceB.tick()

        val enqueued = listOf(a, b).flatten().filterIsInstance<SchedulerDecision.Enqueued>()
        val lost = listOf(a, b).flatten().filterIsInstance<SchedulerDecision.LostRace>()
        assertEquals(1, enqueued.size, "exactly one instance may enqueue")
        assertEquals(1, lost.size, "the other loses the cursor race")
    }

    @Test
    fun aManualTriggerRunsImmediatelyAndIsIdempotent() = runBlocking {
        val store = MemoryScheduleStore(listOf(definition(nextRunAtEpochMs = midnightUtc)))
        val jobs = MemoryJobStore()
        // now is before the stored cursor: the schedule is not due.
        val nowMs = Instant.parse("2026-09-17T20:00:00Z").toEpochMilli()
        val scheduler = scheduler(store, jobs, nowMs)

        val first = assertIs<SchedulerDecision.Enqueued>(scheduler.runNow("nightly"))
        val second = assertIs<SchedulerDecision.Enqueued>(scheduler.runNow("nightly"))

        assertEquals("nightly:2026-09-17", first.periodKey)
        assertEquals(first.jobId, second.jobId, "the same period collapses to one job")
        assertIs<SchedulerDecision.Unknown>(scheduler.runNow("not-a-schedule"))
    }

    @Test
    fun aConditionCanDeclineOnePeriodAndStillAdvances() = runBlocking {
        val store = MemoryScheduleStore(listOf(definition(nextRunAtEpochMs = midnightUtc)))
        val jobs = MemoryJobStore()
        val scheduler = scheduler(
            store,
            jobs,
            nowMs = midnightUtc,
            conditions = mapOf("nightly" to ScheduleCondition { _, _ -> false }),
        )

        val decision = assertIs<SchedulerDecision.DeclinedByCondition>(scheduler.tick().single())

        assertEquals("nightly:2026-09-18", decision.periodKey)
        assertEquals(
            Instant.parse("2026-09-19T00:00:00Z").toEpochMilli(),
            store.get("nightly")!!.nextRunAtEpochMs,
            "a declined period is skipped, not retried",
        )
    }

    @Test
    fun theSchedulerEnqueuesAndNeverExecutes() = runBlocking {
        val store = MemoryScheduleStore(listOf(definition(nextRunAtEpochMs = midnightUtc)))
        val jobs = MemoryJobStore()
        var handlerRuns = 0
        val registry = JobHandlerRegistry().register("content/daily") {
            handlerRuns += 1
            JobResult(result = "done", outcome = "OK")
        }
        val worker = Worker(jobs, registry, now = { midnightUtc })

        val decision = assertIs<SchedulerDecision.Enqueued>(scheduler(store, jobs, midnightUtc).tick().single())

        val job = jobs.job(decision.jobId!!)!!
        assertEquals(JobState.PENDING, job.status, "the scheduler leaves the job for a worker")
        assertEquals("content/daily", job.jobType)
        assertEquals(0, handlerRuns, "the scheduler does not execute work")

        worker.tick()
        assertEquals(1, handlerRuns, "the ordinary worker path consumes it")
    }

    @Test
    fun weeklyAndMonthlySpecsFireOnTheirOwnCivilDates() {
        val weekly = ScheduleSpec(Cadence.WEEKLY, hour = 9, minute = 0, dayOfWeek = DayOfWeek.MONDAY)
        val from = Instant.parse("2026-09-18T00:00:00Z").toEpochMilli() // Friday
        assertEquals(
            Instant.parse("2026-09-21T00:00:00Z").toEpochMilli(),
            weekly.firstOccurrenceAfter(from, tokyo),
            "the next Monday in Tokyo",
        )
        assertEquals("2026-W39", weekly.periodKey(weekly.firstOccurrenceAfter(from, tokyo), tokyo))

        val monthly = ScheduleSpec(Cadence.MONTHLY, hour = 9, minute = 0, dayOfMonth = 31)
        val february = Instant.parse("2026-01-31T01:00:00Z").toEpochMilli() // Feb 1 JST
        assertEquals(
            Instant.parse("2026-02-28T00:00:00Z").toEpochMilli(),
            monthly.firstOccurrenceAfter(february, tokyo),
            "day 31 clamps to the last day of a short month",
        )

        val yearly = ScheduleSpec(Cadence.YEARLY, hour = 0, minute = 0, dayOfMonth = 1, month = Month.JANUARY)
        assertEquals("2027", yearly.periodKey(yearly.firstOccurrenceAfter(february, tokyo), tokyo))
    }
}
