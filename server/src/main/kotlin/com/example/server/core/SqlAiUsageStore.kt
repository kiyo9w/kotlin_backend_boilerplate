package com.example.server.core

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

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
            val rows = AiUsageLog.selectAll()
                .where { (AiUsageLog.ownerId eq ownerId) and (AiUsageLog.createdAt greaterEq sinceEpochMs) }
                .toList()
            AiUsageSummary(
                calls = rows.size,
                inputTokens = rows.sumOf { it[AiUsageLog.inputTokens].toLong() },
                outputTokens = rows.sumOf { it[AiUsageLog.outputTokens].toLong() },
                costMicros = rows.sumOf { it[AiUsageLog.costMicros] },
            )
        }

    override suspend fun deleteAllForOwner(ownerId: String) {
        coreDbQuery {
            AiUsageLog.deleteWhere { AiUsageLog.ownerId eq ownerId }
        }
    }
}
