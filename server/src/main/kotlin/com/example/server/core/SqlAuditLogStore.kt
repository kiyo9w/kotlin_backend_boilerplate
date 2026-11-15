package com.example.server.core

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

internal object AuditEvents : Table("audit_events") {
    val id = text("id")
    val workspaceId = text("workspace_id").nullable()
    val ownerId = text("owner_id").nullable()
    val actorId = text("actor_id")
    val impersonatorId = text("impersonator_id").nullable()
    val action = text("action")
    val targetType = text("target_type").nullable()
    val targetId = text("target_id").nullable()
    val ipAddress = text("ip_address").nullable()
    val deviceId = text("device_id").nullable()
    val userAgent = text("user_agent").nullable()
    val metadataJson = text("metadata_json").nullable()
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

/**
 * SQL-backed [AuditLogStore] over `audit_events` (`V9__production_saas_hardening.sql`).
 */
class SqlAuditLogStore : AuditLogStore {

    override suspend fun append(event: AuditEventRow): AuditEventRow {
        coreDbQuery {
            AuditEvents.insert {
                it[id] = event.id
                it[workspaceId] = event.workspaceId
                it[ownerId] = event.ownerId
                it[actorId] = event.actorId
                it[impersonatorId] = event.impersonatorId
                it[action] = event.action
                it[targetType] = event.targetType
                it[targetId] = event.targetId
                it[ipAddress] = event.ipAddress
                it[deviceId] = event.deviceId
                it[userAgent] = event.userAgent
                it[metadataJson] = event.metadataJson
                it[createdAt] = event.createdAt
            }
        }
        return event
    }

    override suspend fun forWorkspace(workspaceId: String, limit: Int): List<AuditEventRow> {
        require(limit > 0) { "limit must be positive" }
        return coreDbQuery {
            AuditEvents.selectAll()
                .where { AuditEvents.workspaceId eq workspaceId }
                .orderBy(AuditEvents.createdAt, SortOrder.DESC)
                .limit(limit)
                .map { it.toAuditEventRow() }
        }
    }

    override suspend fun forOwner(ownerId: String, limit: Int): List<AuditEventRow> {
        require(limit > 0) { "limit must be positive" }
        return coreDbQuery {
            AuditEvents.selectAll()
                .where { AuditEvents.ownerId eq ownerId }
                .orderBy(AuditEvents.createdAt, SortOrder.DESC)
                .limit(limit)
                .map { it.toAuditEventRow() }
        }
    }

    override suspend fun findMatchingFootprint(
        ownerId: String,
        fromEpochMs: Long,
        toEpochMs: Long,
        ipAddress: String?,
        deviceId: String?,
    ): List<AuditEventRow> {
        require(toEpochMs >= fromEpochMs) { "toEpochMs must be >= fromEpochMs" }
        val cleanIp = ipAddress?.trim()?.takeIf { it.isNotEmpty() }
        val cleanDevice = deviceId?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanIp == null && cleanDevice == null) return emptyList()

        return coreDbQuery {
            val footprintMatch = when {
                cleanIp != null && cleanDevice != null ->
                    (AuditEvents.ipAddress eq cleanIp) or (AuditEvents.deviceId eq cleanDevice)
                cleanIp != null -> AuditEvents.ipAddress eq cleanIp
                else -> AuditEvents.deviceId eq cleanDevice!!
            }
            AuditEvents.selectAll()
                .where {
                    (AuditEvents.ownerId eq ownerId) and
                        AuditEvents.impersonatorId.isNull() and
                        (AuditEvents.createdAt greaterEq fromEpochMs) and
                        (AuditEvents.createdAt lessEq toEpochMs) and
                        footprintMatch
                }
                .orderBy(AuditEvents.createdAt, SortOrder.DESC)
                .map { it.toAuditEventRow() }
        }
    }

    override suspend fun pseudonymizeForOwner(ownerId: String): Int {
        val tombstone = deletedActorPseudonym(ownerId)
        if (ownerId == tombstone) return 0
        return coreDbQuery {
            val bothCount = AuditEvents.update({
                (AuditEvents.ownerId eq ownerId) and (AuditEvents.actorId eq ownerId)
            }) {
                it[AuditEvents.ownerId] = tombstone
                it[AuditEvents.actorId] = tombstone
                it[ipAddress] = null
                it[deviceId] = null
                it[userAgent] = null
                it[metadataJson] = null
            }
            val ownerOnlyCount = AuditEvents.update({ AuditEvents.ownerId eq ownerId }) {
                it[AuditEvents.ownerId] = tombstone
                it[ipAddress] = null
                it[deviceId] = null
                it[userAgent] = null
                it[metadataJson] = null
            }
            val actorOnlyCount = AuditEvents.update({ AuditEvents.actorId eq ownerId }) {
                it[AuditEvents.actorId] = tombstone
                it[ipAddress] = null
                it[deviceId] = null
                it[userAgent] = null
                it[metadataJson] = null
            }
            bothCount + ownerOnlyCount + actorOnlyCount
        }
    }

    private fun ResultRow.toAuditEventRow() = AuditEventRow(
        id = this[AuditEvents.id],
        workspaceId = this[AuditEvents.workspaceId],
        ownerId = this[AuditEvents.ownerId],
        actorId = this[AuditEvents.actorId],
        impersonatorId = this[AuditEvents.impersonatorId],
        action = this[AuditEvents.action],
        targetType = this[AuditEvents.targetType],
        targetId = this[AuditEvents.targetId],
        ipAddress = this[AuditEvents.ipAddress],
        deviceId = this[AuditEvents.deviceId],
        userAgent = this[AuditEvents.userAgent],
        metadataJson = this[AuditEvents.metadataJson],
        createdAt = this[AuditEvents.createdAt],
    )
}
