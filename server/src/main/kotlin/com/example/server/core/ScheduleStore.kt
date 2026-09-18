package com.example.server.core

import java.time.ZoneId

/**
 * One durable schedule row. [nextRunAtEpochMs] is the stored cursor the tick
 * reads and advances; it is the optimistic-concurrency token for single-flight.
 */
data class ScheduleDefinition(
    val name: String,
    val spec: ScheduleSpec,
    val zoneId: String,
    val jobType: String,
    val payload: String,
    val enabled: Boolean = true,
    val nextRunAtEpochMs: Long,
) {
    val zone: ZoneId get() = ZoneId.of(zoneId)
}

/**
 * A schedule can decline to fire for one period, the way Micronaut's
 * `@Scheduled(condition = ...)` allows. The default fires every period. The
 * condition is consulted only by the instance that won the advance race, and a
 * false answer still advances `next_run_at` — a declined period is skipped, not
 * retried forever.
 */
fun interface ScheduleCondition {
    suspend fun shouldEnqueue(definition: ScheduleDefinition, occurrenceEpochMs: Long): Boolean
}

val AlwaysRun = ScheduleCondition { _, _ -> true }

/**
 * Durable storage for schedules. Implementations keep [nextRunAtEpochMs] and
 * provide the compare-and-set that makes a tick single-flight across instances:
 * only the caller whose observed cursor still matches may move it.
 */
interface ScheduleStore {
    /** Insert or replace a definition by [ScheduleDefinition.name]. */
    suspend fun upsert(definition: ScheduleDefinition)

    suspend fun get(name: String): ScheduleDefinition?

    suspend fun list(): List<ScheduleDefinition>

    /** Schedules whose cursor is due at [nowEpochMs], oldest cursor first. */
    suspend fun due(nowEpochMs: Long, limit: Int = DUE_LIMIT): List<ScheduleDefinition>

    /**
     * Move the cursor from [observedNextRunAtEpochMs] to
     * [nextRunAtEpochMs], but only when the stored cursor still equals the
     * observed one. Returns true for the winner and false for every loser.
     */
    suspend fun advance(
        name: String,
        observedNextRunAtEpochMs: Long,
        nextRunAtEpochMs: Long,
    ): Boolean

    companion object {
        const val DUE_LIMIT = 100
    }
}
