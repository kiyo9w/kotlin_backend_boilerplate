package com.example.server.core

import java.sql.Connection
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert

internal object AiUsageLog : Table("ai_usage_log") {
    val id = text("id")
    val ownerId = text("owner_id").nullable()
    val model = text("model")
    val inputTokens = integer("input_tokens")
    val outputTokens = integer("output_tokens")
    val costMicros = long("cost_micros")
    val latencyMs = long("latency_ms")
    val outcome = text("outcome")
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

/** SQL-backed [AiUsageStore] over `ai_usage_log` (`V8__commercial_backbone.sql`). */
class SqlAiUsageStore : AiUsageStore {

    override suspend fun record(entry: AiUsageRecord) {
        coreDbQuery {
            AiUsageLog.insert {
                it[id] = entry.id
                it[ownerId] = entry.ownerId
                it[model] = entry.model
                it[inputTokens] = entry.inputTokens
                it[outputTokens] = entry.outputTokens
                it[costMicros] = entry.costMicros
                it[latencyMs] = entry.latencyMs
                it[outcome] = entry.outcome
                it[createdAt] = entry.createdAt
            }
        }
    }

    override suspend fun summarizeForOwner(ownerId: String, sinceEpochMs: Long): AiUsageSummary =
        coreDbQuery {
            val jdbc = connection.connection as Connection
            jdbc.prepareStatement(SUMMARIZE_SQL).use { stmt ->
                stmt.setString(1, ownerId)
                stmt.setLong(2, sinceEpochMs)
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) {
                        AiUsageSummary(calls = 0, inputTokens = 0L, outputTokens = 0L, costMicros = 0L)
                    } else {
                        AiUsageSummary(
                            calls = rs.getLong(1).toInt(),
                            inputTokens = rs.getLong(2),
                            outputTokens = rs.getLong(3),
                            costMicros = rs.getLong(4),
                        )
                    }
                }
            }
        }

    override suspend fun deleteAllForOwner(ownerId: String) {
        coreDbQuery {
            AiUsageLog.deleteWhere { AiUsageLog.ownerId eq ownerId }
        }
    }

    private companion object {
        const val SUMMARIZE_SQL = """
            SELECT COUNT(*),
                   COALESCE(SUM(CAST(input_tokens AS BIGINT)), 0),
                   COALESCE(SUM(CAST(output_tokens AS BIGINT)), 0),
                   COALESCE(SUM(cost_micros), 0)
              FROM ai_usage_log
             WHERE owner_id = ? AND created_at >= ?
        """
    }
}
