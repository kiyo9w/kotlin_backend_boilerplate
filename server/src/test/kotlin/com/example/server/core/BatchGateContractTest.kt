package com.example.server.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The batch-gate convention: a gate either ships its kept items or refuses the
 * whole batch. It never ships a partial batch. A product's own gate implements
 * [BatchGate] and reuses the same rule.
 */
class BatchGateContractTest {

    private class KeepFirst(private val usable: Int, override val minBatch: Int) : BatchGate<Int> {
        override fun keep(items: List<Int>): List<Int> = items.take(usable)
    }

    @Test
    fun belowTheMinimumRefusesTheWholeBatchAndNamesWhatItKept() {
        val decision = KeepFirst(usable = 3, minBatch = 4).decide(List(5) { it })
        val refuse = assertIs<BatchDecision.Refuse<Int>>(decision)
        assertEquals(listOf(0, 1, 2), refuse.kept, "the kept items are reported, not shipped")
    }

    @Test
    fun atOrAboveTheMinimumShipsOnlyTheKeptItems() {
        val decision = KeepFirst(usable = 4, minBatch = 4).decide(List(7) { it })
        val ship = assertIs<BatchDecision.Ship<Int>>(decision)
        assertEquals(listOf(0, 1, 2, 3), ship.items, "kept items ship in order, unkept never padded back in")
    }

    @Test
    fun anEmptyBatchIsNotARefusal() {
        val decision = KeepFirst(usable = 0, minBatch = 4).decide(emptyList())
        val ship = assertIs<BatchDecision.Ship<Int>>(decision)
        assertTrue(ship.items.isEmpty(), "nothing to gate; the caller keeps its own fallback ids")
    }

    @Test
    fun aGateIsUsableWhereverTheContractIsExpected() {
        val gate: BatchGate<Int> = KeepFirst(usable = 1, minBatch = 1)
        assertEquals(1, gate.minBatch)
    }
}

