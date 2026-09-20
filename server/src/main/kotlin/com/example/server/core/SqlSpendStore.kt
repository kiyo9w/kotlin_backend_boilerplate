package com.example.server.core

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Durable spend counters. One row per subject, period key, and kind; `count`
 * is how many units of that kind the subject has spent this period. The
 * composite primary key is the row identity a charge locks and decides on.
 */
object UsageCounters : Table("usage_counters") {
    val subjectId = text("subject_id")
    val periodKey = text("period_key")
    val kind = text("kind")
    val count = integer("count")
    override val primaryKey = PrimaryKey(subjectId, periodKey, kind)
}

/**
 * SQL store behind [SqlSpendGate], mirroring [SqlJobStore]'s shape: Exposed
 * maps the rows, the queries live here, and the caller owns the policy.
 *
 * A charge reads the subject's row `FOR UPDATE` before deciding, so parallel
 * chargers on one subject serialize on that row and the stored count never
 * passes the cap. The first charge of a period inserts the row; if a parallel
 * first charge wins that insert, the unique key rejects the loser, whose
 * transaction is retried and then sees the row.
 */
class SqlSpendStore {

    /**
     * Count one unit unless [cap] is already reached. Returns false when the
     * cap refuses the charge; the counter is left at [cap], never above it.
     */
    suspend fun tryCharge(subjectId: String, periodKey: String, kind: String, cap: Int): Boolean {
        var attempt = 0
        while (true) {
            try {
                return chargeOnce(subjectId, periodKey, kind, cap)
            } catch (failure: ExposedSQLException) {
                // Another charger inserted the period's first row between this
                // charge's read and its insert. Retry against the live row.
                if (++attempt >= INSERT_RACE_ATTEMPTS) throw failure
            }
        }
    }

    /** The stored count for one subject, period, and kind; zero when absent. */
    suspend fun count(subjectId: String, periodKey: String, kind: String): Int = coreDbQuery {
        UsageCounters.selectAll()
            .where { rowIdentity(subjectId, periodKey, kind) }
            .firstOrNull()
            ?.get(UsageCounters.count)
            ?: 0
    }

    private suspend fun chargeOnce(
        subjectId: String,
        periodKey: String,
        kind: String,
        cap: Int,
    ): Boolean = coreDbQuery {
        val existing = UsageCounters.selectAll()
            .where { rowIdentity(subjectId, periodKey, kind) }
            .forUpdate()
            .firstOrNull()
        when {
            existing == null -> {
                UsageCounters.insert {
                    it[UsageCounters.subjectId] = subjectId
                    it[UsageCounters.periodKey] = periodKey
                    it[UsageCounters.kind] = kind
                    it[count] = 1
                }
                true
            }
            existing[UsageCounters.count] >= cap -> false
            else -> {
                UsageCounters.update({ rowIdentity(subjectId, periodKey, kind) }) {
                    it[count] = existing[UsageCounters.count] + 1
                }
                true
            }
        }
    }

    private fun rowIdentity(subjectId: String, periodKey: String, kind: String) =
        (UsageCounters.subjectId eq subjectId) and
            (UsageCounters.periodKey eq periodKey) and
            (UsageCounters.kind eq kind)

    private companion object {
        const val INSERT_RACE_ATTEMPTS = 3
    }
}
