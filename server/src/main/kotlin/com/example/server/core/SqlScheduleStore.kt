package com.example.server.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.inTopLevelSuspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.javatime.timestamp
import java.time.DayOfWeek
import java.time.Instant
import java.time.Month

/**
 * Generic schedules table. One row per named schedule; [nextRunAt] is the
 * cursor the scheduler reads and compare-and-sets.
 *
 * A product owns the row's meaning through `job_type` and `payload`; the
 * scheduler only moves the cursor and enqueues.
 */
object Schedules : Table("schedules") {
    val name = text("name")
    val cadence = text("cadence")
    val zoneId = text("zone_id")
    // Column names avoid reserved words (H2 reserves `hour` and `minute`).
    val hour = integer("hour_of_day")
    val minute = integer("minute_of_hour")
    val dayOfWeek = text("day_of_week").nullable()
    val dayOfMonth = integer("day_of_month").nullable()
    val month = text("month_of_year").nullable()
    val jobType = text("job_type")
    val payload = text("payload")
    val enabled = bool("enabled")
    val nextRunAt = timestamp("next_run_at")
    override val primaryKey = PrimaryKey(name)
}

/**
 * SQL [ScheduleStore]. The single-flight guarantee is the compare-and-set in
 * [advance]: `UPDATE ... WHERE name = ? AND next_run_at = ?`. Two instances
 * reading the same due row both see the same observed cursor; exactly one
 * update reports a changed row and only that caller enqueues.
 *
 * A database advisory lock is deliberately not used: the compare-and-set is
 * portable across H2 (tests) and Postgres (S0) and needs no session affinity.
 */
class SqlScheduleStore : ScheduleStore {

    override suspend fun upsert(definition: ScheduleDefinition) {
        scheduleQuery {
        val existing = Schedules.selectAll().where { Schedules.name eq definition.name }.firstOrNull()
        if (existing == null) {
            Schedules.insert {
                it[name] = definition.name
                it[cadence] = definition.spec.cadence.name
                it[zoneId] = definition.zoneId
                it[hour] = definition.spec.hour
                it[minute] = definition.spec.minute
                it[dayOfWeek] = definition.spec.dayOfWeek?.name
                it[dayOfMonth] = definition.spec.dayOfMonth
                it[month] = definition.spec.month?.name
                it[jobType] = definition.jobType
                it[payload] = definition.payload
                it[enabled] = definition.enabled
                it[nextRunAt] = Instant.ofEpochMilli(definition.nextRunAtEpochMs)
            }
        } else {
            Schedules.update({ Schedules.name eq definition.name }) {
                it[cadence] = definition.spec.cadence.name
                it[zoneId] = definition.zoneId
                it[hour] = definition.spec.hour
                it[minute] = definition.spec.minute
                it[dayOfWeek] = definition.spec.dayOfWeek?.name
                it[dayOfMonth] = definition.spec.dayOfMonth
                it[month] = definition.spec.month?.name
                it[jobType] = definition.jobType
                it[payload] = definition.payload
                it[enabled] = definition.enabled
                it[nextRunAt] = Instant.ofEpochMilli(definition.nextRunAtEpochMs)
            }
        }
        }
    }

    override suspend fun get(name: String): ScheduleDefinition? = scheduleQuery {
        Schedules.selectAll().where { Schedules.name eq name }.firstOrNull()?.toDefinition()
    }

    override suspend fun list(): List<ScheduleDefinition> = scheduleQuery {
        Schedules.selectAll().orderBy(Schedules.nextRunAt, SortOrder.ASC).map { it.toDefinition() }
    }

    override suspend fun due(nowEpochMs: Long, limit: Int): List<ScheduleDefinition> = scheduleQuery {
        val now = Instant.ofEpochMilli(nowEpochMs)
        Schedules.selectAll()
            .where { (Schedules.enabled eq true) and (Schedules.nextRunAt lessEq now) }
            .orderBy(Schedules.nextRunAt, SortOrder.ASC)
            .limit(limit)
            .map { it.toDefinition() }
    }

    override suspend fun advance(
        name: String,
        observedNextRunAtEpochMs: Long,
        nextRunAtEpochMs: Long,
    ): Boolean = scheduleQuery {
        val updated = Schedules.update({
            (Schedules.name eq name) and
                (Schedules.nextRunAt eq Instant.ofEpochMilli(observedNextRunAtEpochMs))
        }) {
            it[nextRunAt] = Instant.ofEpochMilli(nextRunAtEpochMs)
        }
        updated == 1
    }

    private fun org.jetbrains.exposed.v1.core.ResultRow.toDefinition() = ScheduleDefinition(
        name = this[Schedules.name],
        spec = ScheduleSpec(
            cadence = Cadence.valueOf(this[Schedules.cadence]),
            hour = this[Schedules.hour],
            minute = this[Schedules.minute],
            dayOfWeek = this[Schedules.dayOfWeek]?.let { DayOfWeek.valueOf(it) },
            dayOfMonth = this[Schedules.dayOfMonth],
            month = this[Schedules.month]?.let { Month.valueOf(it) },
        ),
        zoneId = this[Schedules.zoneId],
        jobType = this[Schedules.jobType],
        payload = this[Schedules.payload],
        enabled = this[Schedules.enabled],
        nextRunAtEpochMs = this[Schedules.nextRunAt].toEpochMilli(),
    )
}

private suspend fun <T> scheduleQuery(block: suspend JdbcTransaction.() -> T): T =
    withContext(Dispatchers.IO) { inTopLevelSuspendTransaction { block() } }
