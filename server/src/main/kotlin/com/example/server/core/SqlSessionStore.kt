package com.example.server.core

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Session rows. One row is one issued pair: the hashes are what lookups run
 * against, and `revoked_at` is the liveness flag that both rotation and
 * revocation set. Raw tokens never reach the table.
 *
 * Indexes live in the migration (owner revoke-all, revocation sweeps), which
 * [SqlSessionStore] mirrors but does not create — Flyway owns the schema.
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
    override val primaryKey = PrimaryKey(id)
}

/**
 * SQL-backed [SessionStore] over the `sessions` table (V3__sessions.sql).
 *
 * The rotation guarantee is a compare-and-set: the presented row is revoked
 * with `UPDATE ... WHERE id = ? AND revoked_at IS NULL` and the successor is
 * inserted in the same transaction. Two refreshes replaying one token read the
 * same live row; exactly one update reports a changed row and only that caller
 * inserts a successor. The loser throws [StaleClaimException], so a replayed
 * refresh token can never mint a second pair.
 *
 * A database advisory lock is deliberately not used: the compare-and-set is
 * portable across H2 (tests) and Postgres and needs no session affinity, the
 * same reasoning as [SqlScheduleStore.advance].
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

    override suspend fun rotate(oldId: String, next: SessionRow, now: Long) {
        coreDbQuery {
            val retired = Sessions.update({
                (Sessions.id eq oldId) and Sessions.revokedAt.isNull()
            }) {
                it[revokedAt] = now
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

    override suspend fun revokeAll(ownerId: String, now: Long) {
        coreDbQuery {
            Sessions.update({ (Sessions.ownerId eq ownerId) and Sessions.revokedAt.isNull() }) {
                it[revokedAt] = now
            }
        }
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
    )
}
