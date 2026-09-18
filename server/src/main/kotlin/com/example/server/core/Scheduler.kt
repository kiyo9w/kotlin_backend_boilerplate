package com.example.server.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * What one tick or manual trigger decided. Returned for the caller and the
 * tests; the scheduler itself only enqueues.
 */
sealed interface SchedulerDecision {
    val name: String

    /** A job was enqueued (or already existed) for [periodKey]. */
    data class Enqueued(
        override val name: String,
        val periodKey: String,
        val occurrenceEpochMs: Long,
        val jobId: String?,
    ) : SchedulerDecision

    /** The condition declined this period; the cursor still advanced. */
    data class DeclinedByCondition(
        override val name: String,
        val periodKey: String,
        val occurrenceEpochMs: Long,
    ) : SchedulerDecision

    /** Another instance advanced the cursor first; this caller did not enqueue. */
    data class LostRace(override val name: String) : SchedulerDecision

    data class Disabled(override val name: String) : SchedulerDecision

    data class Unknown(override val name: String) : SchedulerDecision
}

/**
 * Durable scheduler. One tick reads due rows, advances each cursor with a
 * compare-and-set, and enqueues a job into the existing lease queue. It never
 * executes work: a [JobStore] handler does that.
 *
 * Five behaviours, each tested in `SchedulerTest`:
 *
 * 1. **Timezone is explicit.** A firing is a civil time in [ZoneId], and its
 *    period key is that zone's civil period, not a UTC one.
 * 2. **Misfire policy.** When occurrences were missed, the tick enqueues the
 *    most recent missed period once, then advances the cursor to the next
 *    occurrence after now. A backlog is never replayed period by period.
 * 3. **Condition hook.** A [ScheduleCondition] may decline one period; the
 *    cursor still advances, so a declined period is skipped, not retried.
 * 4. **Single-flight across instances.** Only the caller whose observed cursor
 *    still matches the stored one advances it, and only that caller enqueues.
 *    The job dedupe key is the second guarantee.
 * 5. **Manual trigger.** [runNow] fires one schedule immediately for the
 *    current period, idempotently, with the condition bypassed by default.
 */
class Scheduler(
    private val schedules: ScheduleStore,
    private val jobs: JobStore,
    private val conditions: Map<String, ScheduleCondition> = emptyMap(),
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    /** Fire every schedule whose cursor is due. */
    suspend fun tick(): List<SchedulerDecision> {
        val nowMs = now()
        return schedules.due(nowMs).map { fire(it, nowMs) }
    }

    /**
     * Run one schedule now, for testing or recovery. The occurrence is the most
     * recent period at or before now, so the dedupe key makes a repeated manual
     * trigger a no-op. The condition is bypassed unless [force] is false:
     * an operator asking for the run has already made the decision.
     */
    suspend fun runNow(name: String, force: Boolean = true): SchedulerDecision {
        val definition = schedules.get(name) ?: return SchedulerDecision.Unknown(name)
        val nowMs = now()
        val occurrence = definition.spec.lastOccurrenceAtOrBefore(nowMs, definition.zone)
        val key = periodKeyOf(definition, occurrence)
        val condition = conditions[name] ?: AlwaysRun
        if (!force && !condition.shouldEnqueue(definition, occurrence)) {
            return SchedulerDecision.DeclinedByCondition(name, key, occurrence)
        }
        val job = jobs.enqueue(key, definition.jobType, definition.payload)
        return SchedulerDecision.Enqueued(name, key, occurrence, job.id)
    }

    private suspend fun fire(definition: ScheduleDefinition, nowMs: Long): SchedulerDecision {
        if (!definition.enabled) return SchedulerDecision.Disabled(definition.name)
        val zone = definition.zone
        // Most recent period at or before now: catch up the latest missed run,
        // never a backlog.
        val occurrence = definition.spec.lastOccurrenceAtOrBefore(nowMs, zone)
        val nextRun = definition.spec.firstOccurrenceAfter(nowMs, zone)
        // The compare-and-set is the single-flight gate. Whoever moves the
        // cursor owns this period's enqueue.
        val won = schedules.advance(definition.name, definition.nextRunAtEpochMs, nextRun)
        if (!won) return SchedulerDecision.LostRace(definition.name)
        val key = periodKeyOf(definition, occurrence)
        val condition = conditions[definition.name] ?: AlwaysRun
        if (!condition.shouldEnqueue(definition, occurrence)) {
            return SchedulerDecision.DeclinedByCondition(definition.name, key, occurrence)
        }
        val job = jobs.enqueue(key, definition.jobType, definition.payload)
        return SchedulerDecision.Enqueued(definition.name, key, occurrence, job.id)
    }

    private fun periodKeyOf(definition: ScheduleDefinition, occurrenceEpochMs: Long): String =
        "${definition.name}:${definition.spec.periodKey(occurrenceEpochMs, definition.zone)}"

    /**
     * Tick on an interval until the coroutine is cancelled. The default is one
     * minute: a per-minute tick is what makes a missed-by-minutes run still
     * land, and the compare-and-set is cheap enough that it does not matter how
     * many instances tick. A failed tick is logged and retried, never fatal.
     */
    suspend fun runLoop(
        intervalMs: Long = 60_000,
        onError: (Throwable) -> Unit = {},
    ) {
        while (currentCoroutineContext().isActive) {
            runCatching { tick() }.onFailure(onError)
            delay(intervalMs)
        }
    }
}
