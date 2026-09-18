package com.example.server.core

/**
 * One named, role-tagged portion of a prompt.
 *
 * A prompt is a list of these rather than one hand-concatenated string, so it
 * can be measured per part, kept byte-stable for the provider's prefix cache,
 * and truncated in a defined order. Lifted from prompt-poet's part model
 * (name / role / content / truncation priority).
 *
 * @param truncationPriority drop order when the budget is tight: the lowest
 *   value is trimmed first; within one value the later part is trimmed first, so
 *   a caller places old history before new. `null` means the part is pinned and
 *   is never trimmed by the budget.
 */
data class PromptPart(
    val name: String,
    val role: String,
    val content: String,
    val truncationPriority: Int? = null,
)

data class PromptMessage(val role: String, val content: String)

data class PromptRender(
    val messages: List<PromptMessage>,
    val tokensByPart: Map<String, Int>,
    val inputTokens: Int,
    val truncated: Boolean,
)

/** The pinned instructions alone do not fit the input budget. */
class PromptBudgetExceeded(
    val inputTokens: Int,
    val availableTokens: Int,
) : IllegalArgumentException(
    "prompt needs $inputTokens input tokens but only $availableTokens are available after reserving output",
)

/**
 * Conservative token estimate with no tokenizer dependency.
 *
 * Latin text averages about four characters per token; CJK is about one
 * character per token. Splitting the count and taking the higher per-script
 * figure means the estimate never under-counts a mixed-script prompt, which is
 * the safe direction for a budget. It is an estimate: the provider is the
 * authority, and this exists to reserve output and to attribute cost, not to
 * replace a tokenizer.
 */
object CharTokenEstimate {
    fun of(text: String): Int {
        if (text.isEmpty()) return 0
        var ascii = 0
        var nonAscii = 0
        for (ch in text) {
            if (ch.code < 128) ascii++ else nonAscii++
        }
        return (ascii + 3) / 4 + nonAscii
    }
}

/**
 * Assembles a prompt from [PromptPart]s under a token budget, reserving output
 * space for the model's answer.
 *
 * Two ideas, both taken from the researched OSS:
 *
 * - **Reserved output** (priompt's `<empty tokens={N}/>`): the budget the parts
 *   may consume is `contextTokens - reservedOutputTokens`, so the model always
 *   has room to answer instead of truncating itself.
 * - **Cache-aware truncation** (prompt-poet): when the prompt is too large, the
 *   parts are dropped in whole **fixed token steps**, not by the exact overflow.
 *   The cut point therefore stays where it was for several turns, so the prompt
 *   prefix stays byte-stable and the provider's prefix cache keeps hitting. The
 *   trade is that it sometimes drops more than strictly necessary.
 *
 * Parts of the same role that are contiguous render into one message, so
 * splitting a single message into parts does not change the wire shape.
 */
class PromptBudget(
    private val contextTokens: Int,
    private val reservedOutputTokens: Int,
    private val truncationStepTokens: Int = 512,
    private val estimate: (String) -> Int = CharTokenEstimate::of,
) {
    init {
        require(contextTokens > 0) { "contextTokens must be positive" }
        require(reservedOutputTokens >= 0 && reservedOutputTokens < contextTokens) {
            "reservedOutputTokens must be in 0 until contextTokens"
        }
        require(truncationStepTokens > 0) { "truncationStepTokens must be positive" }
    }

    val availableInputTokens: Int get() = contextTokens - reservedOutputTokens

    fun render(parts: List<PromptPart>): PromptRender {
        require(parts.map { it.name }.toSet().size == parts.size) { "part names must be unique" }
        val tokensByPart = parts.associate { it.name to estimate(it.content) }
        val total = tokensByPart.values.sum()

        val kept = if (total <= availableInputTokens) {
            parts
        } else {
            trim(parts, tokensByPart, total)
        }
        val keptTokens = kept.sumOf { tokensByPart[it.name] ?: 0 }

        val messages = mutableListOf<PromptMessage>()
        for (part in kept) {
            val last = messages.lastOrNull()
            if (last != null && last.role == part.role) {
                messages[messages.lastIndex] = last.copy(content = last.content + part.content)
            } else {
                messages += PromptMessage(part.role, part.content)
            }
        }
        return PromptRender(
            messages = messages,
            tokensByPart = tokensByPart.filterKeys { name -> kept.any { it.name == name } },
            inputTokens = keptTokens,
            truncated = kept.size != parts.size,
        )
    }

    private fun trim(
        parts: List<PromptPart>,
        tokensByPart: Map<String, Int>,
        total: Int,
    ): List<PromptPart> {
        val overflow = total - availableInputTokens
        // Round the cut up to a whole step so the same boundary serves several
        // turns (cache-aware truncation).
        val stepped = ((overflow + truncationStepTokens - 1) / truncationStepTokens) * truncationStepTokens
        val dropOrder = parts.withIndex()
            .filter { it.value.truncationPriority != null }
            .sortedWith(compareBy({ it.value.truncationPriority!! }, { -it.index }))

        val dropped = mutableSetOf<Int>()
        var removed = 0
        for ((index, part) in dropOrder) {
            if (removed >= stepped) break
            dropped += index
            removed += tokensByPart[part.name] ?: 0
        }
        val keptTokens = total - removed
        if (keptTokens > availableInputTokens) {
            throw PromptBudgetExceeded(inputTokens = keptTokens, availableTokens = availableInputTokens)
        }
        return parts.filterIndexed { index, _ -> index !in dropped }
    }
}
