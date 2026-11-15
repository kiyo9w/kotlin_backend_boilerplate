package com.example.server.core

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Session rows (`V3__sessions.sql` + `V9__production_saas_hardening.sql`).
 *
 * Raw tokens never reach the table; lookups run against SHA-256 hashes, while
 * `family_id` and `rotated_at` enforce `RFC 9700` refresh-token reuse detection.
 */
private object Sessions : Table("sessions") {
    val id = text("id")
    val ownerId = text("owner_id")
    val deviceId = text("device_id").nullable()
    val accessHash = text("access_hash")
    val refreshHash = text("refresh_hash")
    val accessExpiresAt = long("access_expires_at")
    val refreshExpiresAt = long("refresh_expires_at")
    val rotatedFrom = text("rotated_from").nullable()
    val revokedAt = long("revoked_at").nullable()
    val createdAt = long("created_at")
    val lastUsedAt = long("last_used_at").nullable()
    val familyId = text("family_id")
    val rotatedAt = long("rotated_at").nullable()
    val sudoUntil = long("sudo_until").nullable()
    val ipAddress = text("ip_address").nullable()
    val userAgent = text("user_agent").nullable()
    override val primaryKey = PrimaryKey(id)
}

/**
 * SQL-backed [SessionStore] over the `sessions` table.
 *
 * Rotation is an atomic compare-and-set (`WHERE id = ? AND revoked_at IS NULL`)
 * paired with inserting the successor in the same transaction.
 */
class SqlSessionStore : SessionStore {

    override suspend fun insert(row: SessionRow) {
        coreDbQuery { insertRow(row) }
    }

    override suspend fun byAccessHash(hash: String): SessionRow? = coreDbQuery {
        Sessions.selectAll().where { Sessions.accessHash eq hash }.firstOrNull()?.toRow()
    }

    override suspend fun byRefreshHash(hash: String): SessionRow? = coreDbQuery {
        Sessions.selectAll().where { Sessions.refreshHash eq hash }.firstOrNull()?.toRow()
    }

    override suspend fun childrenOf(parentId: String): List<SessionRow> = coreDbQuery {
        Sessions.selectAll().where { Sessions.rotatedFrom eq parentId }.map { it.toRow() }
    }

    override suspend fun rotate(oldId: String, next: SessionRow, now: Long) {
        coreDbQuery {
            val retired = Sessions.update({
                (Sessions.id eq oldId) and Sessions.revokedAt.isNull()
            }) {
                it[revokedAt] = now
                it[rotatedAt] = now
            }
            if (retired != 1) {
                throw StaleClaimException("session $oldId is already rotated or revoked")
            }
            insertRow(next)
        }
    }

    override suspend fun revoke(id: String, now: Long) {
        coreDbQuery {
            Sessions.update({ (Sessions.id eq id) and Sessions.revokedAt.isNull() }) {
                it[revokedAt] = now
            }
        }
    }

    override suspend fun revokeFamily(familyId: String, now: Long) {
        coreDbQuery {
            Sessions.update({ (Sessions.familyId eq familyId) and Sessions.revokedAt.isNull() }) {
                it[revokedAt] = now
            }
        }
    }

    override suspend fun revokeForOwner(ownerId: String, sessionId: String, now: Long): Boolean = coreDbQuery {
        Sessions.update({
            (Sessions.id eq sessionId) and
                (Sessions.ownerId eq ownerId) and
                Sessions.revokedAt.isNull()
        }) {
            it[revokedAt] = now
        } == 1
    }

    override suspend fun revokeAll(ownerId: String, now: Long) {
        coreDbQuery {
            Sessions.update({ (Sessions.ownerId eq ownerId) and Sessions.revokedAt.isNull() }) {
                it[revokedAt] = now
            }
        }
    }

    override suspend fun activeForOwner(ownerId: String, now: Long): List<SessionRow> = coreDbQuery {
        Sessions.selectAll()
            .where {
                (Sessions.ownerId eq ownerId) and
                    Sessions.revokedAt.isNull() and
                    (Sessions.refreshExpiresAt greater now)
            }
            .map { it.toRow() }
            .sortedByDescending { it.lastUsedAt ?: it.createdAt }
    }

    override suspend fun elevateSudo(ownerId: String, sessionId: String, sudoUntil: Long, now: Long): Boolean =
        coreDbQuery {
            Sessions.update({
                (Sessions.id eq sessionId) and
                    (Sessions.ownerId eq ownerId) and
                    Sessions.revokedAt.isNull() and
                    (Sessions.refreshExpiresAt greater now)
            }) {
                it[Sessions.sudoUntil] = sudoUntil
            } == 1
        }

    override suspend fun touch(id: String, now: Long) {
        coreDbQuery {
            Sessions.update({ Sessions.id eq id }) {
                it[lastUsedAt] = now
            }
        }
    }

    private fun insertRow(row: SessionRow) {
        Sessions.insert {
            it[id] = row.id
            it[ownerId] = row.ownerId
            it[deviceId] = row.deviceId
            it[accessHash] = row.accessHash
            it[refreshHash] = row.refreshHash
            it[accessExpiresAt] = row.accessExpiresAt
            it[refreshExpiresAt] = row.refreshExpiresAt
            it[rotatedFrom] = row.rotatedFrom
            it[revokedAt] = row.revokedAt
            it[createdAt] = row.createdAt
            it[lastUsedAt] = row.lastUsedAt
            it[familyId] = row.familyId
            it[rotatedAt] = row.rotatedAt
            it[sudoUntil] = row.sudoUntil
            it[ipAddress] = row.ipAddress
            it[userAgent] = row.userAgent
        }
    }

    private fun ResultRow.toRow() = SessionRow(
        id = this[Sessions.id],
        ownerId = this[Sessions.ownerId],
        deviceId = this[Sessions.deviceId],
        accessHash = this[Sessions.accessHash],
        refreshHash = this[Sessions.refreshHash],
        accessExpiresAt = this[Sessions.accessExpiresAt],
        refreshExpiresAt = this[Sessions.refreshExpiresAt],
        rotatedFrom = this[Sessions.rotatedFrom],
        revokedAt = this[Sessions.revokedAt],
        createdAt = this[Sessions.createdAt],
        lastUsedAt = this[Sessions.lastUsedAt],
        familyId = this[Sessions.familyId],
        rotatedAt = this[Sessions.rotatedAt],
        sudoUntil = this[Sessions.sudoUntil],
        ipAddress = this[Sessions.ipAddress],
        userAgent = this[Sessions.userAgent],
    )
}
