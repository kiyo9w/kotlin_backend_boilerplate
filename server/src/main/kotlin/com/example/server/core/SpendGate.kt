package com.example.server.core

import com.example.server.ApiException
import io.ktor.http.HttpStatusCode
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap

/**
 * Generic spend and rate gate. Limits come from typed configuration; the gate
 * charges a subject (device id, user id, api key) per kind per period and
 * throws when the cap is crossed.
 *
 * Lifted from the reference product's [com.example.server.DailyUsageGate] shape
 * . The template ships
 * the contract plus [MemorySpendGate] and [SqlSpendGate]; a product configures
 * the kinds and caps.
 */
interface SpendGate {
    /**
     * Charge one unit of [kind] against [subjectId]. Throws
     * [com.example.server.ApiException] with code RATE_LIMITED when the
     * period cap is crossed. An unknown subject throws UNAUTHORIZED — fail
     * closed, never a free call.
     */
    suspend fun charge(subjectId: String, kind: String)
}

/**
 * A gate with no limits. The default for a product that has not set caps yet;
 * replace it with a real gate before charging for anything.
 */
object NoOpSpendGate : SpendGate {
    override suspend fun charge(subjectId: String, kind: String) = Unit
}

/** Per-kind caps. A missing kind or a cap <= 0 is uncapped. */
data class SpendCaps(val caps: Map<String, Int>) {
    fun capFor(kind: String): Int? = caps[kind]?.takeIf { it > 0 }

    companion object {
        fun of(vararg pairs: Pair<String, Int>) = SpendCaps(pairs.toMap())
    }
}

/** Period key derivation. Default: the UTC civil day, so the counter resets at midnight UTC. */
fun utcDayKey(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC).toLocalDate().toString()

/**
 * In-process counters: one per subject, period key, and kind. Dies with the
 * process; the local-only memory mode and tests use it.
 */
class MemorySpendGate(
    private val caps: SpendCaps,
    private val clock: () -> Long = System::currentTimeMillis,
    private val periodKey: (Long) -> String = ::utcDayKey,
) : SpendGate {

    private data class CounterKey(val subjectId: String, val periodKey: String, val kind: String)

    private val counters = ConcurrentHashMap<CounterKey, Int>()

    override suspend fun charge(subjectId: String, kind: String) {
        val cap = caps.capFor(kind) ?: return
        val key = CounterKey(subjectId, periodKey(clock()), kind)
        // compute is atomic per key: two parallel chargers at cap - 1 cannot
        // both win, and a refusal leaves the counter untouched.
        counters.compute(key) { _, used ->
            val count = used ?: 0
            if (count >= cap) throw rateLimited(kind, cap)
            count + 1
        }
    }
}

/**
 * Durable counters over the `usage_counters` table. A charge locks today's row
 * before deciding, so parallel chargers serialize at the cap. An unknown
 * subject is refused with UNAUTHORIZED — fail closed, never a free call.
 */
class SqlSpendGate(
    private val caps: SpendCaps,
    private val subjectExists: suspend (String) -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val periodKey: (Long) -> String = ::utcDayKey,
) : SpendGate {

    private val store = SqlSpendStore()

    override suspend fun charge(subjectId: String, kind: String) {
        val cap = caps.capFor(kind) ?: return
        if (!subjectExists(subjectId)) {
            throw ApiException(
                HttpStatusCode.Unauthorized,
                "UNAUTHORIZED",
                "unknown subject for kind $kind: spend refused",
            )
        }
        if (!store.tryCharge(subjectId, periodKey(clock()), kind, cap)) {
            throw rateLimited(kind, cap)
        }
    }
}

private fun rateLimited(kind: String, cap: Int) = ApiException(
    HttpStatusCode.TooManyRequests,
    "RATE_LIMITED",
    "cap $cap reached for kind $kind in this period",
)
