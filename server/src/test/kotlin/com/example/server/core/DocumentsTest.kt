package com.example.server.core

import com.example.server.ApiException
import io.ktor.http.HttpStatusCode
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertIs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

/**
 * The document-store convention: an owner-scoped versioned document whose only
 * write path is a compare-and-set against the base version. Two writers holding
 * the same base version can never both land.
 */
class DocumentsTest {

    @Test
    fun createWithBaseVersionZeroReturnsCreatedOne() = runBlocking<Unit> {
        val store = MemoryDocumentStore()

        val put = store.put("owner", "account", 1, """{"name":"ada"}""", baseVersion = 0L, now = 100L)

        assertEquals(DocumentPut.Created(1L), put)
        val stored = store.get("owner", "account")!!
        assertEquals(1L, stored.version)
        assertEquals(1, stored.schemaVersion)
        assertEquals("""{"name":"ada"}""", stored.payload)
        assertEquals(100L, stored.updatedAt)
    }

    @Test
    fun updateAtTheCurrentVersionIncrementsAndGetReflectsIt() = runBlocking<Unit> {
        val store = MemoryDocumentStore()
        store.put("owner", "account", 1, """{"name":"ada"}""", baseVersion = 0L, now = 100L)

        val put = store.put("owner", "account", 2, """{"name":"ada lovelace"}""", baseVersion = 1L, now = 200L)

        assertEquals(DocumentPut.Updated(2L), put)
        val stored = store.get("owner", "account")!!
        assertEquals(2L, stored.version)
        assertEquals(2, stored.schemaVersion)
        assertEquals("""{"name":"ada lovelace"}""", stored.payload)
        assertEquals(200L, stored.updatedAt)
    }

    @Test
    fun aStaleBaseVersionConflictsAndCarriesTheCurrentRow() = runBlocking<Unit> {
        val store = MemoryDocumentStore()
        store.put("owner", "account", 1, """{"v":1}""", baseVersion = 0L, now = 100L)
        store.put("owner", "account", 1, """{"v":2}""", baseVersion = 1L, now = 200L)

        val put = store.put("owner", "account", 1, """{"v":3}""", baseVersion = 1L, now = 300L)

        val conflict = assertIs<DocumentPut.Conflict>(put)
        assertEquals(2L, conflict.current!!.version, "the conflict carries the row the winner committed")
        assertEquals("""{"v":2}""", conflict.current.payload)
    }

    @Test
    fun createOnAnExistingDocumentConflicts() = runBlocking<Unit> {
        val store = MemoryDocumentStore()
        store.put("owner", "account", 1, """{"v":1}""", baseVersion = 0L, now = 100L)

        val put = store.put("owner", "account", 1, """{"v":9}""", baseVersion = 0L, now = 200L)

        val conflict = assertIs<DocumentPut.Conflict>(put)
        assertEquals(1L, conflict.current!!.version)
        assertEquals("""{"v":1}""", store.get("owner", "account")!!.payload, "a refused create writes nothing")
    }

    @Test
    fun updateOnAMissingDocumentConflictsWithoutARow() = runBlocking<Unit> {
        val store = MemoryDocumentStore()

        val conflict = assertIs<DocumentPut.Conflict>(
            store.put("owner", "ghost", 1, """{"v":1}""", baseVersion = 1L, now = 100L),
        )

        assertNull(conflict.current, "nothing exists to carry")
        assertNull(store.get("owner", "ghost"))
    }

    @Test
    fun aPayloadOverTheCapIsRefusedWithDocumentTooLarge() = runBlocking<Unit> {
        val store = MemoryDocumentStore(maxBytes = 8)

        val failure = assertFailsWith<ApiException> {
            store.put("owner", "account", 1, "123456789", baseVersion = 0L, now = 100L)
        }

        assertEquals(HttpStatusCode.UnprocessableEntity, failure.status)
        assertEquals("DOCUMENT_TOO_LARGE", failure.code)
        assertNull(store.get("owner", "account"), "a refused write stores nothing")
    }

    @Test
    fun deleteRemovesTheDocument() = runBlocking<Unit> {
        val store = MemoryDocumentStore()
        store.put("owner", "account", 1, """{"v":1}""", baseVersion = 0L, now = 100L)

        store.delete("owner", "account")

        assertNull(store.get("owner", "account"))
        assertEquals(
            DocumentPut.Created(1L),
            store.put("owner", "account", 1, """{"v":2}""", baseVersion = 0L, now = 200L),
            "the create gate reopens after a delete",
        )
    }

    @Test
    fun deleteIsIdempotent() = runBlocking<Unit> {
        val store = MemoryDocumentStore()
        store.put("owner", "account", 1, """{"v":1}""", baseVersion = 0L, now = 100L)

        store.delete("owner", "account")
        store.delete("owner", "account")

        assertNull(store.get("owner", "account"))
    }

    @Test
    fun documentsAreOwnerScoped() = runBlocking<Unit> {
        val store = MemoryDocumentStore()
        store.put("owner-a", "account", 1, """{"v":1}""", baseVersion = 0L, now = 100L)

        assertNull(store.get("owner-b", "account"))
        store.delete("owner-b", "account")
        assertEquals("""{"v":1}""", store.get("owner-a", "account")!!.payload, "another owner's delete cannot cross scopes")
    }

    @Test
    fun twoConcurrentCreatesYieldExactlyOneWinner() = runBlocking<Unit> {
        val store = MemoryDocumentStore()

        val results = listOf(
            async(Dispatchers.Default) {
                store.put("owner", "account", 1, """{"by":"a"}""", baseVersion = 0L, now = 100L)
            },
            async(Dispatchers.Default) {
                store.put("owner", "account", 1, """{"by":"b"}""", baseVersion = 0L, now = 200L)
            },
        ).awaitAll()

        assertEquals(1, results.count { it is DocumentPut.Created }, "one create wins")
        assertEquals(1, results.count { it is DocumentPut.Conflict }, "the other is told it conflicted")
        assertEquals(1L, store.get("owner", "account")!!.version)
    }
}

/**
 * The SQL document store on H2 with the real Flyway migrations: the
 * version-predicated UPDATE and the primary-key create gate are the production
 * path, so they are proven against a database, not only in memory.
 */
class SqlDocumentStoreTest {

    private val pool = openCoreDataSource(
        jdbcUrl = "jdbc:h2:mem:docs_${UUID.randomUUID()};MODE=PostgreSQL;" +
            "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        user = "sa",
        password = "",
    )

    @AfterTest
    fun closePool() {
        (pool as? AutoCloseable)?.close()
    }

    @Test
    fun sqlRoundTripsCreateUpdateAndConflict() = runBlocking<Unit> {
        val store = SqlDocumentStore()

        assertEquals(
            DocumentPut.Created(1L),
            store.put("owner", "account", 1, """{"v":1}""", baseVersion = 0L, now = 100L),
        )
        val created = SqlDocumentStore().get("owner", "account")!!
        assertEquals(1L, created.version)
        assertEquals(1, created.schemaVersion)
        assertEquals("""{"v":1}""", created.payload)
        assertEquals(100L, created.updatedAt)

        assertEquals(
            DocumentPut.Updated(2L),
            store.put("owner", "account", 2, """{"v":2}""", baseVersion = 1L, now = 200L),
        )
        val conflict = assertIs<DocumentPut.Conflict>(
            store.put("owner", "account", 3, """{"v":3}""", baseVersion = 1L, now = 300L),
        )
        assertEquals(2L, conflict.current!!.version)
        assertEquals("""{"v":2}""", conflict.current.payload)

        assertEquals(2L, SqlDocumentStore().get("owner", "account")!!.version, "the row is durable across store instances")
    }

    @Test
    fun sqlCreateOnAnExistingDocumentConflicts() = runBlocking<Unit> {
        val store = SqlDocumentStore()
        store.put("owner", "account", 1, """{"v":1}""", baseVersion = 0L, now = 100L)

        val conflict = assertIs<DocumentPut.Conflict>(
            store.put("owner", "account", 1, """{"v":9}""", baseVersion = 0L, now = 200L),
        )

        assertEquals(1L, conflict.current!!.version)
        assertEquals("""{"v":1}""", store.get("owner", "account")!!.payload)
    }

    @Test
    fun theSqlSizeCapRefusesBeforeWriting() = runBlocking<Unit> {
        val store = SqlDocumentStore(maxBytes = 8)

        val failure = assertFailsWith<ApiException> {
            store.put("owner", "account", 1, "123456789", baseVersion = 0L, now = 100L)
        }

        assertEquals(HttpStatusCode.UnprocessableEntity, failure.status)
        assertEquals("DOCUMENT_TOO_LARGE", failure.code)
        assertNull(SqlDocumentStore().get("owner", "account"))
    }

    @Test
    fun sqlDeleteIsIdempotent() = runBlocking<Unit> {
        val store = SqlDocumentStore()
        store.put("owner", "account", 1, """{"v":1}""", baseVersion = 0L, now = 100L)

        store.delete("owner", "account")
        store.delete("owner", "account")

        assertNull(store.get("owner", "account"))
        assertEquals(DocumentPut.Created(1L), store.put("owner", "account", 1, "{}", baseVersion = 0L, now = 200L))
    }

    @Test
    fun twoConcurrentSqlCreatesYieldExactlyOneWinner() = runBlocking<Unit> {
        val store = SqlDocumentStore()

        val results = listOf(
            async(Dispatchers.IO) {
                store.put("owner", "account", 1, """{"by":"a"}""", baseVersion = 0L, now = 100L)
            },
            async(Dispatchers.IO) {
                store.put("owner", "account", 1, """{"by":"b"}""", baseVersion = 0L, now = 200L)
            },
        ).awaitAll()

        assertEquals(1, results.count { it is DocumentPut.Created }, "the primary key lets one create land")
        assertEquals(1, results.count { it is DocumentPut.Conflict }, "the loser reads the winner instead of leaking the SQL error")
        assertEquals(1L, store.get("owner", "account")!!.version)
    }

    @Test
    fun twoConcurrentSqlUpdatesYieldExactlyOneWinner() = runBlocking<Unit> {
        val store = SqlDocumentStore()
        store.put("owner", "account", 1, """{"v":1}""", baseVersion = 0L, now = 100L)

        val results = listOf(
            async(Dispatchers.IO) {
                store.put("owner", "account", 1, """{"by":"a"}""", baseVersion = 1L, now = 200L)
            },
            async(Dispatchers.IO) {
                store.put("owner", "account", 1, """{"by":"b"}""", baseVersion = 1L, now = 300L)
            },
        ).awaitAll()

        assertEquals(1, results.count { it is DocumentPut.Updated }, "the version predicate lets one update land")
        val conflict = assertIs<DocumentPut.Conflict>(results.single { it is DocumentPut.Conflict })
        assertEquals(2L, conflict.current!!.version)
    }
}
