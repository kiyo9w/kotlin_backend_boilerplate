package com.example.server.core

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

internal object ApiKeys : Table("api_keys") {
    val id = text("id")
    val workspaceId = text("workspace_id")
    val createdByOwnerId = text("created_by_owner_id")
    val name = text("name")
    val keyPrefix = text("key_prefix")
    val keyHash = text("key_hash").uniqueIndex()
    val scopesCsv = text("scopes_csv")
    val expiresAt = long("expires_at").nullable()
    val graceExpiresAt = long("grace_expires_at").nullable()
    val revokedAt = long("revoked_at").nullable()
    val lastUsedAt = long("last_used_at").nullable()
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

/**
 * SQL-backed [ApiKeyStore] over `api_keys` (`V9__production_saas_hardening.sql`).
 */
class SqlApiKeyStore : ApiKeyStore {

    override suspend fun insert(row: ApiKeyRow) {
        coreDbQuery { insertRow(row) }
    }

    override suspend fun byId(workspaceId: String, keyId: String): ApiKeyRow? = coreDbQuery {
        ApiKeys.selectAll()
            .where { (ApiKeys.id eq keyId) and (ApiKeys.workspaceId eq workspaceId) }
            .firstOrNull()
            ?.toApiKeyRow()
    }

    override suspend fun byHash(keyHash: String): ApiKeyRow? = coreDbQuery {
        ApiKeys.selectAll()
            .where { ApiKeys.keyHash eq keyHash }
            .firstOrNull()
            ?.toApiKeyRow()
    }

    override suspend fun listForWorkspace(workspaceId: String): List<ApiKeyRow> = coreDbQuery {
        ApiKeys.selectAll()
            .where { ApiKeys.workspaceId eq workspaceId }
            .orderBy(ApiKeys.createdAt, SortOrder.DESC)
            .map { it.toApiKeyRow() }
    }

    override suspend fun rotate(
        workspaceId: String,
        oldKeyId: String,
        graceExpiresAt: Long,
        replacement: ApiKeyRow,
    ): Boolean = coreDbQuery {
        val updated = ApiKeys.update({
            (ApiKeys.id eq oldKeyId) and
                (ApiKeys.workspaceId eq workspaceId) and
                ApiKeys.revokedAt.isNull()
        }) {
            it[ApiKeys.graceExpiresAt] = graceExpiresAt
        }
        if (updated != 1) return@coreDbQuery false
        insertRow(replacement)
        true
    }

    override suspend fun revoke(workspaceId: String, keyId: String, now: Long): Boolean = coreDbQuery {
        ApiKeys.update({
            (ApiKeys.id eq keyId) and
                (ApiKeys.workspaceId eq workspaceId) and
                ApiKeys.revokedAt.isNull()
        }) {
            it[revokedAt] = now
        } == 1
    }

    override suspend fun revokeAllForCreator(ownerId: String, now: Long) {
        coreDbQuery {
            ApiKeys.update({
                (ApiKeys.createdByOwnerId eq ownerId) and ApiKeys.revokedAt.isNull()
            }) {
                it[revokedAt] = now
            }
        }
    }

    override suspend fun deleteAllForWorkspace(workspaceId: String) {
        coreDbQuery {
            ApiKeys.deleteWhere { ApiKeys.workspaceId eq workspaceId }
        }
    }

    override suspend fun touchLastUsed(keyId: String, now: Long) {
        coreDbQuery {
            ApiKeys.update({ ApiKeys.id eq keyId }) {
                it[lastUsedAt] = now
            }
        }
    }

    private fun insertRow(row: ApiKeyRow) {
        ApiKeys.insert {
            it[id] = row.id
            it[workspaceId] = row.workspaceId
            it[createdByOwnerId] = row.createdByOwnerId
            it[name] = row.name
            it[keyPrefix] = row.keyPrefix
            it[keyHash] = row.keyHash
            it[scopesCsv] = row.scopes.sorted().joinToString(",")
            it[expiresAt] = row.expiresAt
            it[graceExpiresAt] = row.graceExpiresAt
            it[revokedAt] = row.revokedAt
            it[lastUsedAt] = row.lastUsedAt
            it[createdAt] = row.createdAt
        }
    }

    private fun ResultRow.toApiKeyRow() = ApiKeyRow(
        id = this[ApiKeys.id],
        workspaceId = this[ApiKeys.workspaceId],
        createdByOwnerId = this[ApiKeys.createdByOwnerId],
        name = this[ApiKeys.name],
        keyPrefix = this[ApiKeys.keyPrefix],
        keyHash = this[ApiKeys.keyHash],
        scopes = this[ApiKeys.scopesCsv].split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
        expiresAt = this[ApiKeys.expiresAt],
        graceExpiresAt = this[ApiKeys.graceExpiresAt],
        revokedAt = this[ApiKeys.revokedAt],
        lastUsedAt = this[ApiKeys.lastUsedAt],
        createdAt = this[ApiKeys.createdAt],
    )
}
