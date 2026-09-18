package com.example.server.core

import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [ScheduleStore] for the explicit local-only mode and for tests.
 * The compare-and-set is a synchronized check-and-set, so the same single-flight
 * rule the SQL store enforces with `WHERE next_run_at = observed` holds here.
 */
class MemoryScheduleStore(
    seed: List<ScheduleDefinition> = emptyList(),
) : ScheduleStore {

    private val lock = Any()
    private val rows = ConcurrentHashMap<String, ScheduleDefinition>()

    init {
        seed.forEach { rows[it.name] = it }
    }

    override suspend fun upsert(definition: ScheduleDefinition) {
        synchronized(lock) { rows[definition.name] = definition }
    }

    override suspend fun get(name: String): ScheduleDefinition? = rows[name]

    override suspend fun list(): List<ScheduleDefinition> =
        rows.values.sortedBy { it.nextRunAtEpochMs }

    override suspend fun due(nowEpochMs: Long, limit: Int): List<ScheduleDefinition> =
        rows.values
            .filter { it.enabled && it.nextRunAtEpochMs <= nowEpochMs }
            .sortedBy { it.nextRunAtEpochMs }
            .take(limit)

    override suspend fun advance(
        name: String,
        observedNextRunAtEpochMs: Long,
        nextRunAtEpochMs: Long,
    ): Boolean {
        synchronized(lock) {
            val current = rows[name] ?: return false
            if (current.nextRunAtEpochMs != observedNextRunAtEpochMs) return false
            rows[name] = current.copy(nextRunAtEpochMs = nextRunAtEpochMs)
            return true
        }
    }
}
