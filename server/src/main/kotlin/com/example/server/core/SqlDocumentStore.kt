package com.example.server.core

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Generic documents table, created by `V4__documents.sql`. One row per owner
 * and document. The composite primary key is what makes a create atomic and
 * [version] is the compare-and-set token.
 */
object Documents : Table("documents") {
    val ownerId = text("owner_id")
    val documentId = text("document_id")
    val schemaVersion = integer("schema_version")
    val version = long("version")
    val payload = text("payload")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(ownerId, documentId)
}

/**
 * SQL [DocumentStore].
 *
 * Updates are a single conditional `UPDATE ... WHERE version = ?`. The version
 * predicate is the compare-and-set: two writers holding the same base version
 * cannot both match a row, so exactly one changes it and the loser re-reads the
 * winner's row. A `SELECT ... FOR UPDATE` is deliberately not used — it cannot
 * lock a row that does not exist yet, and the conditional update is atomic on
 * both H2 (tests) and Postgres with no session affinity.
 *
 * Creates rely on the composite primary key instead: of two concurrent inserts
 * exactly one commits, and the loser's duplicate-key failure is caught and
 * answered with the winner's row rather than leaking the SQL error.
 */
class SqlDocumentStore(
    private val maxBytes: Int = DEFAULT_MAX_DOCUMENT_BYTES,
) : DocumentStore {

    override suspend fun get(ownerId: String, documentId: String): StoredDocument? = coreDbQuery {
        Documents.selectAll()
            .where { (Documents.ownerId eq ownerId) and (Documents.documentId eq documentId) }
            .firstOrNull()
            ?.toStoredDocument()
    }

    override suspend fun put(
        ownerId: String,
        documentId: String,
        schemaVersion: Int,
        payload: String,
        baseVersion: Long,
        now: Long,
    ): DocumentPut {
        requireDocumentPayloadFits(payload, maxBytes)
        return if (baseVersion == 0L) {
            createOrConflict(ownerId, documentId, schemaVersion, payload, now)
        } else {
            replaceOrConflict(ownerId, documentId, schemaVersion, payload, baseVersion, now)
        }
    }

    override suspend fun delete(ownerId: String, documentId: String) {
        coreDbQuery {
            Documents.deleteWhere {
                (Documents.ownerId eq ownerId) and (Documents.documentId eq documentId)
            }
        }
    }

    private suspend fun createOrConflict(
        ownerId: String,
        documentId: String,
        schemaVersion: Int,
        payload: String,
        now: Long,
    ): DocumentPut {
        get(ownerId, documentId)?.let { return DocumentPut.Conflict(it) }
        try {
            coreDbQuery {
                Documents.insert {
                    it[Documents.ownerId] = ownerId
                    it[Documents.documentId] = documentId
                    it[Documents.schemaVersion] = schemaVersion
                    it[Documents.version] = 1L
                    it[Documents.payload] = payload
                    it[Documents.updatedAt] = now
                }
            }
        } catch (e: ExposedSQLException) {
            // Two creates raced and the primary key let exactly one land. The
            // loser's transaction rolled back with the exception, so read the
            // winner in a fresh transaction. A null read means the failure was
            // not a duplicate-key race; surface it.
            return DocumentPut.Conflict(get(ownerId, documentId) ?: throw e)
        }
        return DocumentPut.Created(1L)
    }

    private suspend fun replaceOrConflict(
        ownerId: String,
        documentId: String,
        schemaVersion: Int,
        payload: String,
        baseVersion: Long,
        now: Long,
    ): DocumentPut = coreDbQuery {
        val updated = Documents.update({
            (Documents.ownerId eq ownerId) and
                (Documents.documentId eq documentId) and
                (Documents.version eq baseVersion)
        }) {
            it[Documents.schemaVersion] = schemaVersion
            it[Documents.version] = baseVersion + 1
            it[Documents.payload] = payload
            it[Documents.updatedAt] = now
        }
        if (updated == 1) {
            DocumentPut.Updated(baseVersion + 1)
        } else {
            DocumentPut.Conflict(
                Documents.selectAll()
                    .where { (Documents.ownerId eq ownerId) and (Documents.documentId eq documentId) }
                    .firstOrNull()
                    ?.toStoredDocument(),
            )
        }
    }

    private fun org.jetbrains.exposed.v1.core.ResultRow.toStoredDocument() = StoredDocument(
        ownerId = this[Documents.ownerId],
        documentId = this[Documents.documentId],
        schemaVersion = this[Documents.schemaVersion],
        version = this[Documents.version],
        payload = this[Documents.payload],
        updatedAt = this[Documents.updatedAt],
    )
}
