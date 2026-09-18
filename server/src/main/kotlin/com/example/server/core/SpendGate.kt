package com.example.server.core

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
