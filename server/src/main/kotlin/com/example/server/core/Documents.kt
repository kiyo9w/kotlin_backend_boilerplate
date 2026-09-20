package com.example.server.core

import com.example.server.ApiException
import io.ktor.http.HttpStatusCode
import java.util.concurrent.ConcurrentHashMap

/**
 * One stored document. [version] is a monotonic integer owned by the store;
 * [payload] is the product's JSON, opaque to the store.
 */
data class StoredDocument(
    val ownerId: String,
    val documentId: String,
    val schemaVersion: Int,
    val version: Long,
    val payload: String,
    val updatedAt: Long,
)

/** Result of a compare-and-set write. [Conflict] carries the current row (null when none exists). */
sealed interface DocumentPut {
    data class Created(val version: Long) : DocumentPut
    data class Updated(val version: Long) : DocumentPut
    data class Conflict(val current: StoredDocument?) : DocumentPut
}

/**
 * Owner-scoped versioned documents with compare-and-set semantics.
 *
 * `baseVersion == 0` means create-only; any existing row then conflicts.
 * `baseVersion == current.version` replaces and increments; anything else
 * conflicts and carries the current row. The decision is atomic against a
 * concurrent writer, so two writers holding the same base version cannot both
 * land — exactly one wins and the other is told what the winner committed.
 *
 * A payload over the implementation's cap is refused with
 * `ApiException(422, "DOCUMENT_TOO_LARGE")` before anything is written.
 */
interface DocumentStore {
    suspend fun get(ownerId: String, documentId: String): StoredDocument?

    /** Compare-and-set write. See the interface contract for the base-version rules. */
    suspend fun put(
        ownerId: String,
        documentId: String,
        schemaVersion: Int,
        payload: String,
        baseVersion: Long,
        now: Long,
    ): DocumentPut

    /** Owner-scoped and idempotent: deleting a document that is not there is a no-op. */
    suspend fun delete(ownerId: String, documentId: String)
}

/** 2 MiB. The cap counts bytes of UTF-8 payload, not characters. */
const val DEFAULT_MAX_DOCUMENT_BYTES: Int = 2 * 1024 * 1024

/**
 * In-memory [DocumentStore] for the explicit local-only mode and for tests.
 * The read-check-write runs inside one synchronized critical section, so the
 * compare-and-set holds under concurrency the same way the SQL store's
 * version-predicated UPDATE does. Keeps no state across restarts.
 */
class MemoryDocumentStore(
    private val maxBytes: Int = DEFAULT_MAX_DOCUMENT_BYTES,
) : DocumentStore {

    private val lock = Any()
    private val rows = ConcurrentHashMap<Pair<String, String>, StoredDocument>()

    override suspend fun get(ownerId: String, documentId: String): StoredDocument? =
        rows[ownerId to documentId]

    override suspend fun put(
        ownerId: String,
        documentId: String,
        schemaVersion: Int,
        payload: String,
        baseVersion: Long,
        now: Long,
    ): DocumentPut {
        requireDocumentPayloadFits(payload, maxBytes)
        synchronized(lock) {
            val key = ownerId to documentId
            val current = rows[key]
            if (baseVersion == 0L) {
                if (current != null) return DocumentPut.Conflict(current)
                rows[key] = StoredDocument(ownerId, documentId, schemaVersion, 1L, payload, now)
                return DocumentPut.Created(1L)
            }
            if (current == null || current.version != baseVersion) {
                return DocumentPut.Conflict(current)
            }
            val next = current.copy(
                schemaVersion = schemaVersion,
                version = current.version + 1,
                payload = payload,
                updatedAt = now,
            )
            rows[key] = next
            return DocumentPut.Updated(next.version)
        }
    }

    override suspend fun delete(ownerId: String, documentId: String) {
        rows.remove(ownerId to documentId)
    }
}

/**
 * The one size rule every [DocumentStore] enforces before writing. Callers see
 * a typed refusal, never a truncated document.
 */
internal fun requireDocumentPayloadFits(payload: String, maxBytes: Int) {
    val bytes = payload.toByteArray(Charsets.UTF_8).size
    if (bytes > maxBytes) {
        throw ApiException(
            HttpStatusCode.UnprocessableEntity,
            "DOCUMENT_TOO_LARGE",
            "document payload is $bytes bytes, over the $maxBytes-byte cap",
        )
    }
}
