package com.example.server.core

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Product analytics event emitted from the backend (`purchase_completed`,
 * `subscription_renewed`, `signup_completed`).
 *
 * When [identified] is `false`, [PostHogAnalyticsSink] forces
 * `$process_person_profile = false` so anonymous traffic is billed at the
 * stateless rate instead of minting person profiles.
 */
data class AnalyticsEvent(
    val event: String,
    val distinctId: String,
    val identified: Boolean,
    val properties: Map<String, JsonElement> = emptyMap(),
    val timestampMs: Long = System.currentTimeMillis(),
) {
    init {
        require(event.isNotBlank()) { "event must not be blank" }
        require(distinctId.isNotBlank()) { "distinctId must not be blank" }
    }
}

/** Outbound product-analytics seam. */
fun interface AnalyticsSink {
    suspend fun capture(event: AnalyticsEvent): Boolean
}

/** Default sink for local mode or privacy-strict products (`Vowkey`) with analytics off. */
object NoOpAnalyticsSink : AnalyticsSink {
    override suspend fun capture(event: AnalyticsEvent): Boolean = true
}

/** In-memory [AnalyticsSink] for deterministic unit tests. */
class MemoryAnalyticsSink : AnalyticsSink {
    private val lock = Any()
    private val captured = ArrayList<AnalyticsEvent>()

    override suspend fun capture(event: AnalyticsEvent): Boolean {
        synchronized(lock) { captured.add(event) }
        return true
    }

    fun events(): List<AnalyticsEvent> = synchronized(lock) { captured.toList() }
}

/**
 * PostHog `/capture/` HTTP sink enforcing `$process_person_profile` discipline.
 */
class PostHogAnalyticsSink(
    private val httpClient: HttpClient,
    private val projectApiKey: String,
    private val host: String = DEFAULT_HOST,
) : AnalyticsSink {

    init {
        require(projectApiKey.isNotBlank()) { "PostHog projectApiKey must not be blank" }
        require(host.isNotBlank()) { "PostHog host must not be blank" }
    }

    override suspend fun capture(event: AnalyticsEvent): Boolean {
        val bodyJson = telemetryJson.encodeToString(
            buildJsonObject {
                put("api_key", projectApiKey)
                put("event", event.event)
                put("distinct_id", event.distinctId)
                put("timestamp", Instant.ofEpochMilli(event.timestampMs).toString())
                put(
                    "properties",
                    buildJsonObject {
                        event.properties.forEach { (k, v) -> put(k, v) }
                        put("\$process_person_profile", JsonPrimitive(event.identified))
                    },
                )
            },
        )
        val response = runCatching {
            httpClient.post("${host.trimEnd('/')}/capture/") {
                setBody(TextContent(bodyJson, ContentType.Application.Json))
            }
        }.getOrNull() ?: return false
        return response.status.isSuccess()
    }

    companion object {
        const val DEFAULT_HOST: String = "https://eu.i.posthog.com"
    }
}

/**
 * Per-call AI token, latency, and cost accounting entry (`V8__commercial_backbone.sql`).
 *
 * Carries zero prompt or completion columns by design so products can compute
 * per-owner gross margin and enforce spend guardrails without storing sensitive
 * user text or triggering AI-content retention liabilities.
 */
data class AiUsageRecord(
    val id: String = UUID.randomUUID().toString(),
    val ownerId: String?,
    val model: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val costMicros: Long,
    val latencyMs: Long,
    val outcome: String,
    val createdAt: Long = System.currentTimeMillis(),
) {
    init {
        require(id.isNotBlank()) { "id must not be blank" }
        require(model.isNotBlank()) { "model must not be blank" }
        require(inputTokens >= 0) { "inputTokens must be non-negative" }
        require(outputTokens >= 0) { "outputTokens must be non-negative" }
        require(costMicros >= 0L) { "costMicros must be non-negative" }
        require(latencyMs >= 0L) { "latencyMs must be non-negative" }
        require(outcome.isNotBlank()) { "outcome must not be blank" }
    }
}

/** Aggregated AI token and cost usage for an owner over a time window. */
data class AiUsageSummary(
    val calls: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val costMicros: Long,
)

/** Persistence seam for privacy-safe AI unit-economics telemetry. */
interface AiUsageStore {
    suspend fun record(entry: AiUsageRecord)

    suspend fun summarizeForOwner(ownerId: String, sinceEpochMs: Long = 0L): AiUsageSummary

    suspend fun deleteAllForOwner(ownerId: String)
}

/** In-memory [AiUsageStore] for local-only mode and fast unit tests. */
class MemoryAiUsageStore : AiUsageStore {

    private val lock = Any()
    private val rows = ArrayList<AiUsageRecord>()

    override suspend fun record(entry: AiUsageRecord) {
        synchronized(lock) { rows.add(entry) }
    }

    override suspend fun summarizeForOwner(ownerId: String, sinceEpochMs: Long): AiUsageSummary =
        synchronized(lock) {
            val matching = rows.filter { it.ownerId == ownerId && it.createdAt >= sinceEpochMs }
            AiUsageSummary(
                calls = matching.size,
                inputTokens = matching.sumOf { it.inputTokens.toLong() },
                outputTokens = matching.sumOf { it.outputTokens.toLong() },
                costMicros = matching.sumOf { it.costMicros },
            )
        }

    override suspend fun deleteAllForOwner(ownerId: String) {
        synchronized(lock) {
            rows.removeIf { it.ownerId == ownerId }
        }
    }
}

private val telemetryJson = Json { explicitNulls = false }
