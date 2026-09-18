package com.example.server.core

/**
 * Generic batch quality gate. The pattern is "refuse the batch, do not ship
 * it" — lifted from the reference product's `CardQa` / `LanguageGate` (the reference ranking:
 * "refuse-the-batch quality gate").
 *
 * A product implements this with its own rules. The template ships the
 * contract; the product ships the gate.
 *
 * @param T the item type the gate judges (the reference product: `CardDto`)
 */
interface BatchGate<T> {
    /** Minimum usable items for the batch to ship. Below this, refuse the whole batch. */
    val minBatch: Int

    /** Return only the items that pass. Never substitute another item for a rejected one. */
    fun keep(items: List<T>): List<T>
}

/** The gate's ruling on one batch. [Refuse] never carries a shippable subset. */
sealed interface BatchDecision<out T> {
    /** These are the items to store, in order. */
    data class Ship<T>(val items: List<T>) : BatchDecision<T>

    /**
     * The batch (or its kept remainder) is below [BatchGate.minBatch]; the
     * caller takes its fallback path. [kept] rides along for the log line.
     */
    data class Refuse<T>(val kept: List<T>) : BatchDecision<T>
}

/**
 * Apply [BatchGate.keep] and then the batch rule: fewer than [BatchGate.minBatch]
 * kept items means refuse the whole batch, never store a partial one. An empty
 * input ships an empty list — there is nothing to gate, and the caller keeps its
 * own fallback ids.
 */
fun <T> BatchGate<T>.decide(items: List<T>): BatchDecision<T> {
    if (items.isEmpty()) return BatchDecision.Ship(emptyList())
    val kept = keep(items)
    return if (kept.size < minBatch) BatchDecision.Refuse(kept) else BatchDecision.Ship(kept)
}
