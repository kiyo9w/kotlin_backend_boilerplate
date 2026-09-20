package com.example.server.core

import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

/**
 * The operation-registry convention: registration is first-writer-wins on
 * owner + operation id, a repeat of the same request attaches, and every
 * terminal transition is one-way from RUNNING.
 */
class OperationsTest {

    @Test
    fun firstBeginStartsRunning() = runBlocking<Unit> {
        val store = MemoryOperationStore()

        val begin = assertIs<OperationBegin.Started>(
            store.begin("owner", "op-1", "interpret", "hash-1", now = 100L),
        )

        assertEquals(OperationState.RUNNING, begin.record.state)
        assertEquals("hash-1", begin.record.payloadHash)
        assertEquals(100L, begin.record.createdAt)
        assertEquals(100L, begin.record.updatedAt)
        assertNull(begin.record.resultJson)
    }

    @Test
    fun theIdenticalRequestAttaches() = runBlocking<Unit> {
        val store = MemoryOperationStore()
        val started = assertIs<OperationBegin.Started>(
            store.begin("owner", "op-1", "interpret", "hash-1", now = 100L),
        )

        val attached = assertIs<OperationBegin.Attached>(
            store.begin("owner", "op-1", "interpret", "hash-1", now = 200L),
        )

        assertEquals(started.record, attached.record, "the original registration is returned, not a new one")
    }

    @Test
    fun theIdenticalRequestAttachesWhateverTheState() = runBlocking<Unit> {
        val store = MemoryOperationStore()
        store.begin("owner", "done-1", "interpret", "hash-1", now = 100L)
        store.complete("owner", "done-1", """{"answer":42}""", now = 200L)
        store.begin("owner", "failed-1", "interpret", "hash-1", now = 100L)
        store.fail("owner", "failed-1", "MODEL_TIMEOUT", "the model timed out", now = 200L)

        val completed = assertIs<OperationBegin.Attached>(
            store.begin("owner", "done-1", "interpret", "hash-1", now = 300L),
        )
        val failed = assertIs<OperationBegin.Attached>(
            store.begin("owner", "failed-1", "interpret", "hash-1", now = 300L),
        )

        assertEquals(OperationState.COMPLETE, completed.record.state)
        assertEquals(OperationState.FAILED, failed.record.state)
    }

    @Test
    fun aDifferentPayloadHashConflictsAndCarriesTheCurrentRecord() = runBlocking<Unit> {
        val store = MemoryOperationStore()
        val started = assertIs<OperationBegin.Started>(
            store.begin("owner", "op-1", "interpret", "hash-1", now = 100L),
        )

        val conflict = assertIs<OperationBegin.Conflict>(
            store.begin("owner", "op-1", "interpret", "hash-2", now = 200L),
        )

        assertEquals(started.record, conflict.current)
        assertEquals("hash-1", conflict.current.payloadHash)
    }

    @Test
    fun aDifferentKindConflicts() = runBlocking<Unit> {
        val store = MemoryOperationStore()
        store.begin("owner", "op-1", "interpret", "hash-1", now = 100L)

        val conflict = assertIs<OperationBegin.Conflict>(
            store.begin("owner", "op-1", "summarise", "hash-1", now = 200L),
        )

        assertEquals("interpret", conflict.current.kind)
    }

    @Test
    fun completeThenGetShowsCompleteWithTheResult() = runBlocking<Unit> {
        val store = MemoryOperationStore()
        store.begin("owner", "op-1", "interpret", "hash-1", now = 100L)

        assertTrue(store.complete("owner", "op-1", """{"answer":42}""", now = 200L))

        val record = store.get("owner", "op-1")!!
        assertEquals(OperationState.COMPLETE, record.state)
        assertEquals("""{"answer":42}""", record.resultJson)
        assertEquals(200L, record.updatedAt)
    }

    @Test
    fun aSecondCompleteReturnsFalseAndDoesNotOverwrite() = runBlocking<Unit> {
        val store = MemoryOperationStore()
        store.begin("owner", "op-1", "interpret", "hash-1", now = 100L)
        store.complete("owner", "op-1", """{"answer":42}""", now = 200L)

        assertFalse(store.complete("owner", "op-1", """{"answer":0}""", now = 300L))

        val record = store.get("owner", "op-1")!!
        assertEquals("""{"answer":42}""", record.resultJson)
        assertEquals(200L, record.updatedAt)
    }

    @Test
    fun failIsTerminalAndCarriesItsCode() = runBlocking<Unit> {
        val store = MemoryOperationStore()
        store.begin("owner", "op-1", "interpret", "hash-1", now = 100L)

        assertTrue(store.fail("owner", "op-1", "MODEL_TIMEOUT", "the model timed out", now = 200L))

        val record = store.get("owner", "op-1")!!
        assertEquals(OperationState.FAILED, record.state)
        assertEquals("MODEL_TIMEOUT", record.failureCode)
        assertEquals("the model timed out", record.failureMessage)
        assertFalse(store.fail("owner", "op-1", "OTHER", "again", now = 300L), "a failed operation stays failed")
        assertFalse(store.complete("owner", "op-1", """{"late":true}""", now = 300L), "a failure is never overwritten by success")
    }

    @Test
    fun cancelIsTerminalAndNeverSuccess() = runBlocking<Unit> {
        val store = MemoryOperationStore()
        store.begin("owner", "op-1", "interpret", "hash-1", now = 100L)

        assertTrue(store.cancel("owner", "op-1", now = 200L))

        val record = store.get("owner", "op-1")!!
        assertEquals(OperationState.CANCELLED, record.state)
        assertNull(record.resultJson, "cancellation is not a result")
        assertFalse(store.cancel("owner", "op-1", now = 300L), "a cancelled operation stays cancelled")
        assertFalse(store.complete("owner", "op-1", """{"late":true}""", now = 300L))
    }

    @Test
    fun transitionsOnAnUnknownOperationReturnFalse() = runBlocking<Unit> {
        val store = MemoryOperationStore()

        assertFalse(store.complete("owner", "ghost", """{"answer":42}""", now = 100L))
        assertFalse(store.fail("owner", "ghost", "MODEL_TIMEOUT", "the model timed out", now = 100L))
        assertFalse(store.cancel("owner", "ghost", now = 100L))
        assertNull(store.get("owner", "ghost"))
    }

    @Test
    fun operationsAreOwnerScoped() = runBlocking<Unit> {
        val store = MemoryOperationStore()
        store.begin("owner-a", "op-1", "interpret", "hash-1", now = 100L)

        assertNull(store.get("owner-b", "op-1"))
        assertFalse(store.complete("owner-b", "op-1", """{"answer":42}""", now = 200L))

        val otherOwner = assertIs<OperationBegin.Started>(
            store.begin("owner-b", "op-1", "interpret", "hash-1", now = 200L),
        )
        assertEquals(OperationState.RUNNING, otherOwner.record.state)
    }

    @Test
    fun twoConcurrentBeginsYieldExactlyOneStartedAndOneAttached() = runBlocking<Unit> {
        val store = MemoryOperationStore()

        val results = listOf(
            async(Dispatchers.Default) {
                store.begin("owner", "op-1", "interpret", "hash-1", now = 100L)
            },
            async(Dispatchers.Default) {
                store.begin("owner", "op-1", "interpret", "hash-1", now = 200L)
            },
        ).awaitAll()

        assertEquals(1, results.count { it is OperationBegin.Started }, "one registration wins")
        assertEquals(1, results.count { it is OperationBegin.Attached }, "the other attaches instead of starting a second attempt")
        assertEquals(OperationState.RUNNING, store.get("owner", "op-1")!!.state)
    }
}

/**
 * The SQL operation registry on H2 with the real Flyway migrations: the
 * primary-key registration gate and the RUNNING-predicated transitions are the
 * production path, so they are proven against a database, not only in memory.
 */
class SqlOperationStoreTest {

    private val pool = openCoreDataSource(
        jdbcUrl = "jdbc:h2:mem:ops_${UUID.randomUUID()};MODE=PostgreSQL;" +
            "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        user = "sa",
        password = "",
    )

    @AfterTest
    fun closePool() {
        (pool as? AutoCloseable)?.close()
    }

    @Test
    fun sqlRoundTripsBeginAttachConflictAndComplete() = runBlocking<Unit> {
        val store = SqlOperationStore()

        val started = assertIs<OperationBegin.Started>(
            store.begin("owner", "op-1", "interpret", "hash-1", now = 100L),
        )
        assertEquals(OperationState.RUNNING, started.record.state)

        val attached = assertIs<OperationBegin.Attached>(
            store.begin("owner", "op-1", "interpret", "hash-1", now = 200L),
        )
        assertEquals(started.record, attached.record)

        val conflict = assertIs<OperationBegin.Conflict>(
            store.begin("owner", "op-1", "interpret", "hash-2", now = 200L),
        )
        assertEquals("hash-1", conflict.current.payloadHash, "a different request is told what is registered instead")

        assertTrue(store.complete("owner", "op-1", """{"answer":42}""", now = 300L))
        val record = SqlOperationStore().get("owner", "op-1")!!
        assertEquals(OperationState.COMPLETE, record.state)
        assertEquals("""{"answer":42}""", record.resultJson)
        assertEquals(100L, record.createdAt)
        assertEquals(300L, record.updatedAt, "the completed row is durable across store instances")
    }

    @Test
    fun sqlTransitionsAreOneWay() = runBlocking<Unit> {
        val store = SqlOperationStore()
        store.begin("owner", "op-1", "interpret", "hash-1", now = 100L)
        store.complete("owner", "op-1", """{"answer":42}""", now = 200L)

        assertFalse(store.complete("owner", "op-1", """{"answer":0}""", now = 300L))
        assertFalse(store.fail("owner", "op-1", "MODEL_TIMEOUT", "the model timed out", now = 300L))
        assertFalse(store.cancel("owner", "op-1", now = 300L))

        val record = store.get("owner", "op-1")!!
        assertEquals(OperationState.COMPLETE, record.state)
        assertEquals("""{"answer":42}""", record.resultJson)
    }

    @Test
    fun sqlFailAndCancelAreTerminal() = runBlocking<Unit> {
        val store = SqlOperationStore()
        store.begin("owner", "fail-1", "interpret", "hash-1", now = 100L)
        store.begin("owner", "cancel-1", "interpret", "hash-1", now = 100L)

        assertTrue(store.fail("owner", "fail-1", "MODEL_TIMEOUT", "the model timed out", now = 200L))
        assertTrue(store.cancel("owner", "cancel-1", now = 200L))

        val failed = store.get("owner", "fail-1")!!
        assertEquals(OperationState.FAILED, failed.state)
        assertEquals("MODEL_TIMEOUT", failed.failureCode)
        assertEquals("the model timed out", failed.failureMessage)
        assertEquals(OperationState.CANCELLED, store.get("owner", "cancel-1")!!.state)
        assertFalse(store.complete("owner", "cancel-1", """{"late":true}""", now = 300L))
        assertFalse(store.fail("owner", "fail-1", "OTHER", "again", now = 300L))
    }

    @Test
    fun twoConcurrentSqlBeginsYieldExactlyOneStartedAndOneAttached() = runBlocking<Unit> {
        val store = SqlOperationStore()

        val results = listOf(
            async(Dispatchers.IO) {
                store.begin("owner", "op-1", "interpret", "hash-1", now = 100L)
            },
            async(Dispatchers.IO) {
                store.begin("owner", "op-1", "interpret", "hash-1", now = 200L)
            },
        ).awaitAll()

        assertEquals(1, results.count { it is OperationBegin.Started }, "the primary key lets one registration land")
        assertEquals(1, results.count { it is OperationBegin.Attached }, "the loser attaches to the winner instead of leaking the SQL error")
        assertEquals(OperationState.RUNNING, store.get("owner", "op-1")!!.state)
    }
}
