package com.example.server.core

import com.example.server.ApiException
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import kotlin.math.ceil
import kotlin.math.floor

const val RATE_LIMIT_LIMIT_HEADER = "RateLimit-Limit"
const val RATE_LIMIT_REMAINING_HEADER = "RateLimit-Remaining"
const val RATE_LIMIT_RESET_HEADER = "RateLimit-Reset"
const val RETRY_AFTER_HEADER = "Retry-After"

/** Outcome of one rate-limit check, carrying standard IETF header values. */
data class RateLimitDecision(
    val allowed: Boolean,
    val limit: Long,
    val remaining: Long,
    val resetAfterSeconds: Long,
    val retryAfterSeconds: Long,
)

/** Weighted rate-limit contract supporting variable per-endpoint cost (`cost >= 1`). */
interface RateLimiter {
    fun check(
        key: String,
        cost: Long = 1L,
        now: Long = System.currentTimeMillis(),
    ): RateLimitDecision
}

/**
 * Thread-safe weighted token-bucket [RateLimiter] with bounded idle-key eviction.
 *
 * Enforces `tokens >= cost` before deducting so weighted requests (`cost = 10` for
 * exports or batch endpoints) can never overdraft a bucket that holds fewer than
 * `cost` remaining tokens.
 */
class TokenBucketRateLimiter(
    val capacity: Long,
    val refillTokensPerInterval: Long,
    val refillIntervalMs: Long,
    private val maxBuckets: Int = 10_000,
) : RateLimiter {

    private data class BucketState(var tokens: Double, var lastRefillMs: Long)

    private val lock = Any()
    private val buckets = object : LinkedHashMap<String, BucketState>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BucketState>?): Boolean =
            size > maxBuckets
    }

    init {
        require(capacity > 0L) { "capacity must be positive" }
        require(refillTokensPerInterval > 0L) { "refillTokensPerInterval must be positive" }
        require(refillIntervalMs > 0L) { "refillIntervalMs must be positive" }
        require(maxBuckets >= 16) { "maxBuckets must be >= 16" }
    }

    override fun check(key: String, cost: Long, now: Long): RateLimitDecision {
        require(key.isNotBlank()) { "rate-limit key must not be blank" }
        require(cost > 0L) { "cost must be positive" }

        val ratePerMs = refillTokensPerInterval.toDouble() / refillIntervalMs.toDouble()
        if (cost > capacity) {
            return RateLimitDecision(
                allowed = false,
                limit = capacity,
                remaining = 0L,
                resetAfterSeconds = secondsToRecover(capacity.toDouble(), ratePerMs),
                retryAfterSeconds = secondsToRecover(cost.toDouble(), ratePerMs),
            )
        }

        synchronized(lock) {
            val existing = buckets[key]
            val state = if (existing == null) {
                BucketState(capacity.toDouble(), now).also { buckets[key] = it }
            } else {
                val elapsedMs = (now - existing.lastRefillMs).coerceAtLeast(0L)
                if (elapsedMs > 0L) {
                    existing.tokens = (existing.tokens + elapsedMs * ratePerMs).coerceAtMost(capacity.toDouble())
                    existing.lastRefillMs = now
                }
                existing
            }

            return if (state.tokens + EPSILON >= cost.toDouble()) {
                state.tokens = (state.tokens - cost.toDouble()).coerceAtLeast(0.0)
                val remainingWhole = floor(state.tokens + EPSILON).toLong().coerceIn(0L, capacity)
                val deficitToFull = (capacity.toDouble() - state.tokens).coerceAtLeast(0.0)
                RateLimitDecision(
                    allowed = true,
                    limit = capacity,
                    remaining = remainingWhole,
                    resetAfterSeconds = if (deficitToFull <= EPSILON) 0L else secondsToRecover(deficitToFull, ratePerMs),
                    retryAfterSeconds = 0L,
                )
            } else {
                val remainingWhole = floor(state.tokens + EPSILON).toLong().coerceIn(0L, capacity)
                val deficitForCost = (cost.toDouble() - state.tokens).coerceAtLeast(0.0)
                val deficitToFull = (capacity.toDouble() - state.tokens).coerceAtLeast(0.0)
                RateLimitDecision(
                    allowed = false,
                    limit = capacity,
                    remaining = remainingWhole,
                    resetAfterSeconds = secondsToRecover(deficitToFull, ratePerMs),
                    retryAfterSeconds = secondsToRecover(deficitForCost, ratePerMs),
                )
            }
        }
    }

    private fun secondsToRecover(missingTokens: Double, ratePerMs: Double): Long {
        if (missingTokens <= 0.0) return 0L
        val msNeeded = missingTokens / ratePerMs
        return ceil(msNeeded / 1_000.0).toLong().coerceAtLeast(1L)
    }

    private companion object {
        const val EPSILON = 1e-9
    }
}

/** Attach standard `RateLimit-*` (and `Retry-After` on rejection) headers to the response. */
fun ApplicationCall.applyRateLimitHeaders(decision: RateLimitDecision) {
    response.headers.append(RATE_LIMIT_LIMIT_HEADER, decision.limit.toString(), safeOnly = false)
    response.headers.append(RATE_LIMIT_REMAINING_HEADER, decision.remaining.toString(), safeOnly = false)
    response.headers.append(RATE_LIMIT_RESET_HEADER, decision.resetAfterSeconds.toString(), safeOnly = false)
    if (!decision.allowed) {
        response.headers.append(RETRY_AFTER_HEADER, decision.retryAfterSeconds.toString(), safeOnly = false)
    }
}

/**
 * Evaluate [limiter] for [key] at [cost], write `RateLimit-*` headers, and throw
 * `429 RATE_LIMITED` (`ProblemDetail`) when the bucket is exhausted.
 */
fun ApplicationCall.enforceRateLimit(
    limiter: RateLimiter,
    key: String,
    cost: Long = 1L,
    now: Long = System.currentTimeMillis(),
): RateLimitDecision {
    val decision = limiter.check(key, cost, now)
    applyRateLimitHeaders(decision)
    if (!decision.allowed) {
        throw ApiException(
            HttpStatusCode.TooManyRequests,
            "RATE_LIMITED",
            "rate limit exceeded; retry in ${decision.retryAfterSeconds}s",
        )
    }
    return decision
}
