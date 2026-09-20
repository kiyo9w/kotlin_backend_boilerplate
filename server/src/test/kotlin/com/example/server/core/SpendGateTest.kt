package com.example.server.core

import com.example.server.ApiException
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * The spend gate contract: a cap counts up to the cap and refuses at it, an
 * absent or non-positive cap is uncapped, a new period resets the counter, and
 * an unknown subject is refused rather than served. The SQL path runs on H2
 * through the real Flyway migrations, like the other core stores.
 */
class SpendGateTest {

    private val pool = openCoreDataSource(
        jdbcUrl = "jdbc:h2:mem:spend_${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        user = "sa",
        password = "",
    )

    @AfterTest
    fun closePool() {
        (pool as? AutoCloseable)?.close()
    }

    @Test
    fun chargesCountUpToTheCapAndTheNextOneIsRefused() = runBlocking<Unit> {
        val gate = MemorySpendGate(SpendCaps.of("interpret" to 2))

        gate.charge("device-1", "interpret")
        gate.charge("device-1", "interpret")

        val refused = assertFailsWith<ApiException> { gate.charge("device-1", "interpret") }
        assertEquals(429, refused.status.value)
        assertEquals("RATE_LIMITED", refused.code)
        assertTrue("interpret" in refused.message, "the refusal names the kind: ${refused.message}")
    }

    @Test
    fun aRefusedChargeWritesNothingSoTheCountStaysAtTheCap() = runBlocking<Unit> {
        val gate = MemorySpendGate(SpendCaps.of("interpret" to 1))
        gate.charge("device-1", "interpret")

        repeat(3) {
            val refused = assertFailsWith<ApiException> { gate.charge("device-1", "interpret") }
            assertEquals("RATE_LIMITED", refused.code, "the counter must not creep past the cap")
        }
    }

    @Test
    fun aMissingOrNonPositiveCapIsUncapped() = runBlocking<Unit> {
        val gate = MemorySpendGate(SpendCaps.of("free" to 0, "other" to 1))

        repeat(100) { gate.charge("device-1", "missing") }
        repeat(100) { gate.charge("device-1", "free") }
    }

    @Test
    fun theCounterResetsOnANewPeriodKey() = runBlocking<Unit> {
        var now = Instant.parse("2026-09-18T12:00:00Z").toEpochMilli()
        val gate = MemorySpendGate(SpendCaps.of("interpret" to 1), clock = { now })

        gate.charge("device-1", "interpret")
        assertFailsWith<ApiException> { gate.charge("device-1", "interpret") }

        now += 24 * 60 * 60 * 1000
        gate.charge("device-1", "interpret")
    }

    @Test
    fun utcDayKeyTurnsAtMidnightUtc() {
        assertEquals("2026-09-18", utcDayKey(Instant.parse("2026-09-18T00:00:00Z").toEpochMilli()))
        assertEquals("2026-09-18", utcDayKey(Instant.parse("2026-09-18T23:59:59Z").toEpochMilli()))
        assertEquals("2026-09-19", utcDayKey(Instant.parse("2026-09-19T00:00:00Z").toEpochMilli()))
    }

    @Test
    fun concurrentChargesNeverExceedTheCap() = runBlocking<Unit> {
        val gate = MemorySpendGate(SpendCaps.of("interpret" to 5))

        val results = withContext(Dispatchers.Default) {
            (1..64).map { async { runCatching { gate.charge("device-1", "interpret") } } }.awaitAll()
        }

        assertEquals(5, results.count { it.isSuccess }, "exactly the cap's worth of charges may pass")
        assertEquals(
            59,
            results.count { it.exceptionOrNull() is ApiException },
            "every other charge is a typed refusal, never a silent pass",
        )
    }

    @Test
    fun sqlGateRefusesAnUnknownSubjectWithoutCounting() = runBlocking<Unit> {
        val fixedNow = Instant.parse("2026-09-18T09:00:00Z").toEpochMilli()
        val gate = SqlSpendGate(
            SpendCaps.of("interpret" to 3),
            subjectExists = { false },
            clock = { fixedNow },
        )

        val refused = assertFailsWith<ApiException> { gate.charge("ghost", "interpret") }
        assertEquals(401, refused.status.value)
        assertEquals("UNAUTHORIZED", refused.code)
        assertEquals(
            0,
            SqlSpendStore().count("ghost", utcDayKey(fixedNow), "interpret"),
            "a refused subject has no counter row",
        )
    }

    @Test
    fun sqlGateCountsDurablyOnH2AndRefusesAtTheCap() = runBlocking<Unit> {
        val fixedNow = Instant.parse("2026-09-18T09:00:00Z").toEpochMilli()
        val caps = SpendCaps.of("interpret" to 2)
        val gate = SqlSpendGate(caps, subjectExists = { true }, clock = { fixedNow })

        gate.charge("device-1", "interpret")
        gate.charge("device-1", "interpret")

        val refused = assertFailsWith<ApiException> { gate.charge("device-1", "interpret") }
        assertEquals(429, refused.status.value)
        assertEquals("RATE_LIMITED", refused.code)
        assertEquals(
            2,
            SqlSpendStore().count("device-1", utcDayKey(fixedNow), "interpret"),
            "a refused charge writes nothing; the stored count stops at the cap",
        )

        // A fresh gate over the same database sees the durable count.
        val reopened = SqlSpendGate(caps, subjectExists = { true }, clock = { fixedNow })
        assertFailsWith<ApiException> { reopened.charge("device-1", "interpret") }
    }
}
