package com.example.server.core

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

internal object EmailSuppressions : Table("email_suppressions") {
    val email = text("email")
    val reason = text("reason")
    val sourceEventId = text("source_event_id").nullable()
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(email)
}

/**
 * SQL-backed [EmailSuppressionStore] over `email_suppressions`
 * (`V8__commercial_backbone.sql`).
 *
 * Enforces severity monotonicity (`SPAM_COMPLAINT` > `HARD_BOUNCE` > `UNSUBSCRIBE`)
 * under `FOR UPDATE` row locking so an out-of-order unsubscribe webhook can never
 * re-enable transactional delivery to a hard-bouncing or complaining address.
 */
class SqlEmailSuppressionStore : EmailSuppressionStore {

    override suspend fun suppress(
        email: String,
        reason: EmailSuppressionReason,
        sourceEventId: String?,
        now: Long,
    ) {
        val key = normalizeAddress(email)
        var attempt = 0
        while (true) {
            try {
                coreDbQuery {
                    val existing = EmailSuppressions.selectAll()
                        .where { EmailSuppressions.email eq key }
                        .forUpdate()
                        .firstOrNull()
                    if (existing == null) {
                        EmailSuppressions.insert {
                            it[EmailSuppressions.email] = key
                            it[EmailSuppressions.reason] = reason.name
                            it[EmailSuppressions.sourceEventId] = sourceEventId
                            it[createdAt] = now
                            it[updatedAt] = now
                        }
                    } else {
                        val currentReason = EmailSuppressionReason.valueOf(existing[EmailSuppressions.reason])
                        val effectiveReason = if (currentReason.severity > reason.severity) currentReason else reason
                        EmailSuppressions.update({ EmailSuppressions.email eq key }) {
                            it[EmailSuppressions.reason] = effectiveReason.name
                            it[EmailSuppressions.sourceEventId] = sourceEventId ?: existing[EmailSuppressions.sourceEventId]
                            it[updatedAt] = now
                        }
                    }
                }
                return
            } catch (race: ExposedSQLException) {
                if (++attempt >= UPSERT_RACE_ATTEMPTS) throw race
            }
        }
    }

    override suspend fun find(email: String): EmailSuppressionRow? {
        val key = normalizeAddress(email)
        return coreDbQuery {
            EmailSuppressions.selectAll()
                .where { EmailSuppressions.email eq key }
                .firstOrNull()
                ?.toSuppressionRow()
        }
    }

    override suspend fun remove(email: String) {
        val key = normalizeAddress(email)
        coreDbQuery {
            EmailSuppressions.deleteWhere { EmailSuppressions.email eq key }
        }
    }

    private fun ResultRow.toSuppressionRow() = EmailSuppressionRow(
        email = this[EmailSuppressions.email],
        reason = EmailSuppressionReason.valueOf(this[EmailSuppressions.reason]),
        sourceEventId = this[EmailSuppressions.sourceEventId],
        createdAt = this[EmailSuppressions.createdAt],
        updatedAt = this[EmailSuppressions.updatedAt],
    )

    private companion object {
        const val UPSERT_RACE_ATTEMPTS = 3
    }
}
