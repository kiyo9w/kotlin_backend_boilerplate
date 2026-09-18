package com.example.server.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The prompt-context-budget contract: named parts, contiguous same-role parts
 * render as one message, pinned parts are never trimmed, trimmable parts drop
 * lowest-priority-first and in whole cache-preserving steps, and a prompt whose
 * pinned instructions do not fit fails closed instead of overflowing.
 */
class PromptBudgetTest {

    private fun budget(
        contextTokens: Int,
        reservedOutputTokens: Int = 0,
        truncationStepTokens: Int = 1,
    ) = PromptBudget(
        contextTokens = contextTokens,
        reservedOutputTokens = reservedOutputTokens,
        truncationStepTokens = truncationStepTokens,
        estimate = { it.length },
    )

    private fun part(name: String, role: String, tokens: Int, priority: Int? = null) =
        PromptPart(name = name, role = role, content = "x".repeat(tokens), truncationPriority = priority)

    @Test
    fun contiguousPartsOfTheSameRoleRenderIntoOneMessageInOrder() {
        val render = budget(contextTokens = 1_000).render(
            listOf(
                PromptPart("instructions", "system", "A"),
                PromptPart("rules", "system", "B"),
                PromptPart("seed", "user", "C"),
            ),
        )
        assertEquals(listOf(PromptMessage("system", "AB"), PromptMessage("user", "C")), render.messages)
        assertFalse(render.truncated)
        assertEquals(3, render.inputTokens)
    }

    @Test
    fun reservedOutputShrinksTheInputBudget() {
        assertEquals(900, budget(contextTokens = 1_000, reservedOutputTokens = 100).availableInputTokens)
    }

    @Test
    fun pinnedPartsAreNeverDropped() {
        // Available 85 vs 100 total: both trimmable parts must go, and the
        // pinned instructions still render.
        val render = budget(contextTokens = 85).render(
            listOf(
                part("system", "system", tokens = 80),
                part("old-history", "user", tokens = 10, priority = 1),
                part("newer-history", "user", tokens = 10, priority = 1),
            ),
        )
        assertTrue(render.truncated)
        assertEquals(80, render.inputTokens)
        assertEquals(setOf("system"), render.tokensByPart.keys)
    }

    @Test
    fun lowestPriorityDropsFirstAndTheLaterPartWithinAPriorityGoesFirst() {
        val render = budget(contextTokens = 95).render(
            listOf(
                part("pinned", "system", tokens = 80),
                part("old-1", "user", tokens = 10, priority = 1),
                part("keep-me", "user", tokens = 10, priority = 2),
                part("old-2", "user", tokens = 10, priority = 1),
            ),
        )
        assertEquals(90, render.inputTokens)
        assertTrue("pinned" in render.tokensByPart)
        assertTrue("keep-me" in render.tokensByPart, "a higher priority survives a lower one")
        assertFalse("old-1" in render.tokensByPart)
        assertFalse("old-2" in render.tokensByPart)
    }

    @Test
    fun theCutRoundsUpToAWholeStepSoTheBoundaryStaysPut() {
        // 10 tokens over budget, but the step is 50: the cache-aware rule drops
        // a whole step rather than shaving the exact overflow, so the same cut
        // point can serve several turns.
        val render = PromptBudget(
            contextTokens = 100,
            reservedOutputTokens = 0,
            truncationStepTokens = 50,
            estimate = { it.length },
        ).render(
            listOf(
                part("pinned", "system", tokens = 60),
                part("old-1", "user", tokens = 30, priority = 1),
                part("old-2", "user", tokens = 20, priority = 1),
            ),
        )
        assertTrue(render.truncated)
        assertEquals(60, render.inputTokens, "drops 50 tokens of history for a 10-token overflow, by design")
    }

    @Test
    fun pinnedInstructionsThatDoNotFitFailClosed() {
        val failure = assertFailsWith<PromptBudgetExceeded> {
            budget(contextTokens = 100).render(
                listOf(part("system", "system", tokens = 120)),
            )
        }
        assertEquals(120, failure.inputTokens)
        assertEquals(100, failure.availableTokens)
    }

    @Test
    fun theEstimatorIsConservativeForMixedScripts() {
        assertEquals(0, CharTokenEstimate.of(""))
        assertEquals(1, CharTokenEstimate.of("abcd"))
        assertEquals(2, CharTokenEstimate.of("漢字"), "a CJK character is about one token")
        assertEquals(2, CharTokenEstimate.of("abc漢"), "mixed text counts both scripts")
    }
}
