package com.example.server.core

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Separates account/security mail (`mail.<domain>`) from promotional mail so a
 * campaign spam complaint cannot block 6-digit login codes or payment receipts.
 */
enum class MailStream {
    TRANSACTIONAL,
    MARKETING,
}

/**
 * Reason an email address was suppressed (`V8__commercial_backbone.sql`).
 *
 * [HARD_BOUNCE] and [SPAM_COMPLAINT] block every stream to protect sender
 * reputation (`< 0.3%` Gmail complaint ceiling, `< 0.5%` SES bounce ceiling).
 * [UNSUBSCRIBE] blocks only [MailStream.MARKETING] so sign-in codes and billing
 * notices continue to arrive.
 */
enum class EmailSuppressionReason(internal val severity: Int) {
    UNSUBSCRIBE(severity = 1),
    HARD_BOUNCE(severity = 2),
    SPAM_COMPLAINT(severity = 3),
    ;

    fun blocks(stream: MailStream): Boolean = when (this) {
        HARD_BOUNCE, SPAM_COMPLAINT -> true
        UNSUBSCRIBE -> stream == MailStream.MARKETING
    }
}

/** Persisted email suppression entry. */
data class EmailSuppressionRow(
    val email: String,
    val reason: EmailSuppressionReason,
    val sourceEventId: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * Persistence seam for bounce, complaint, and marketing-unsubscribe suppressions.
 *
 * Recording a lower-severity reason (such as a late [EmailSuppressionReason.UNSUBSCRIBE]
 * click) never downgrades an existing [EmailSuppressionReason.HARD_BOUNCE] or
 * [EmailSuppressionReason.SPAM_COMPLAINT].
 */
interface EmailSuppressionStore {
    suspend fun suppress(
        email: String,
        reason: EmailSuppressionReason,
        sourceEventId: String? = null,
        now: Long = System.currentTimeMillis(),
    )

    suspend fun find(email: String): EmailSuppressionRow?

    suspend fun remove(email: String)
}

/** In-memory [EmailSuppressionStore] for local-only mode and fast unit tests. */
class MemoryEmailSuppressionStore : EmailSuppressionStore {

    private val lock = Any()
    private val rows = HashMap<String, EmailSuppressionRow>()

    override suspend fun suppress(
        email: String,
        reason: EmailSuppressionReason,
        sourceEventId: String?,
        now: Long,
    ) {
        val key = normalizeEmailAddress(email)
        synchronized(lock) {
            val existing = rows[key]
            if (existing == null) {
                rows[key] = EmailSuppressionRow(
                    email = key,
                    reason = reason,
                    sourceEventId = sourceEventId,
                    createdAt = now,
                    updatedAt = now,
                )
            } else {
                val effectiveReason = if (existing.reason.severity > reason.severity) existing.reason else reason
                rows[key] = existing.copy(
                    reason = effectiveReason,
                    sourceEventId = sourceEventId ?: existing.sourceEventId,
                    updatedAt = now,
                )
            }
        }
    }

    override suspend fun find(email: String): EmailSuppressionRow? {
        val key = normalizeEmailAddress(email)
        return synchronized(lock) { rows[key] }
    }

    override suspend fun remove(email: String) {
        val key = normalizeEmailAddress(email)
        synchronized(lock) { rows.remove(key) }
    }
}

/**
 * Outbound email payload.
 *
 * [MailStream.MARKETING] messages must supply [oneClickUnsubscribeUrl] so every
 * promotional send carries `RFC 8058` one-click unsubscribe headers required by
 * Gmail and Yahoo bulk-sender rules.
 */
data class EmailMessage(
    val to: String,
    val from: String,
    val subject: String,
    val textBody: String,
    val htmlBody: String? = null,
    val stream: MailStream = MailStream.TRANSACTIONAL,
    val idempotencyKey: String,
    val oneClickUnsubscribeUrl: String? = null,
    val replyTo: String? = null,
) {
    init {
        require(to.isNotBlank()) { "to must not be blank" }
        require(from.isNotBlank()) { "from must not be blank" }
        require(subject.isNotBlank()) { "subject must not be blank" }
        require(textBody.isNotBlank()) { "textBody must not be blank" }
        require(idempotencyKey.isNotBlank()) { "idempotencyKey must not be blank" }
        if (stream == MailStream.MARKETING) {
            require(!oneClickUnsubscribeUrl.isNullOrBlank()) {
                "MARKETING mail requires oneClickUnsubscribeUrl for RFC 8058 List-Unsubscribe"
            }
        }
    }
}

/** Result of sending an [EmailMessage]. */
sealed interface MailSendResult {
    data class Sent(val providerMessageId: String?) : MailSendResult
    data class Suppressed(val reason: EmailSuppressionReason) : MailSendResult
}

/** Outbound mail seam so switching Resend, Postmark, or SES changes one adapter. */
fun interface Mailer {
    suspend fun send(message: EmailMessage): MailSendResult
}

/** Default guard before mail credentials are configured. */
object FailClosedMailer : Mailer {
    override suspend fun send(message: EmailMessage): MailSendResult {
        error("mailer is not configured")
    }
}

/** In-memory [Mailer] capturing outbound messages for local mode and tests. */
class MemoryMailer : Mailer {
    private val lock = Any()
    private val deliveries = ArrayList<EmailMessage>()

    override suspend fun send(message: EmailMessage): MailSendResult {
        synchronized(lock) { deliveries.add(message) }
        return MailSendResult.Sent(providerMessageId = "mem-${message.idempotencyKey}")
    }

    fun sentMessages(): List<EmailMessage> = synchronized(lock) { deliveries.toList() }
}

/**
 * Wraps any [Mailer] with pre-send [EmailSuppressionStore] enforcement so hard
 * bounces and spam complaints never hit the upstream MTA again, while marketing
 * unsubscribes still allow transactional codes and receipts through.
 */
class SuppressionGuardedMailer(
    private val delegate: Mailer,
    private val suppressions: EmailSuppressionStore,
) : Mailer {
    override suspend fun send(message: EmailMessage): MailSendResult {
        val existing = suppressions.find(message.to)
        if (existing != null && existing.reason.blocks(message.stream)) {
            return MailSendResult.Suppressed(existing.reason)
        }
        return delegate.send(message)
    }
}

/**
 * Resend HTTP API [Mailer] (`POST /emails`) with `Idempotency-Key` protection
 * against worker retries and automatic `RFC 8058` `List-Unsubscribe` headers.
 */
class ResendMailer(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
) : Mailer {

    init {
        require(apiKey.isNotBlank()) { "Resend apiKey must not be blank" }
        require(baseUrl.isNotBlank()) { "Resend baseUrl must not be blank" }
    }

    override suspend fun send(message: EmailMessage): MailSendResult {
        val bodyJson = mailJson.encodeToString(
            buildJsonObject {
                put("from", message.from)
                put("to", buildJsonArray { add(JsonPrimitive(normalizeEmailAddress(message.to))) })
                put("subject", message.subject)
                put("text", message.textBody)
                message.htmlBody?.let { put("html", it) }
                message.replyTo?.let { put("reply_to", it) }
                message.oneClickUnsubscribeUrl?.takeIf { it.isNotBlank() }?.let { url ->
                    put(
                        "headers",
                        buildJsonObject {
                            put("List-Unsubscribe", "<$url>")
                            put("List-Unsubscribe-Post", "List-Unsubscribe=One-Click")
                        },
                    )
                }
            },
        )
        val response = httpClient.post("${baseUrl.trimEnd('/')}/emails") {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            header("Idempotency-Key", message.idempotencyKey)
            setBody(TextContent(bodyJson, ContentType.Application.Json))
        }
        if (!response.status.isSuccess()) {
            error("Resend mail send failed with ${response.status.value}")
        }
        val parsedId = runCatching {
            (mailJson.parseToJsonElement(response.bodyAsText()).jsonObject["id"] as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
        }.getOrNull()
        return MailSendResult.Sent(providerMessageId = parsedId)
    }

    companion object {
        const val DEFAULT_BASE_URL: String = "https://api.resend.com"
    }
}

/**
 * Stateless `HMAC-SHA256` token signer for unauthenticated `RFC 8058` one-click
 * unsubscribe endpoints (`POST /v1/email/unsubscribe?token=...`).
 *
 * Mailbox providers invoke one-click unsubscribe directly without user cookies,
 * so the URL itself must carry a tamper-proof, expiring proof of the recipient address.
 */
class UnsubscribeTokenCodec(
    secret: String,
    private val defaultTtlSeconds: Long = DEFAULT_TTL_SECONDS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val keyBytes: ByteArray

    init {
        require(secret.isNotBlank()) { "unsubscribe secret must not be blank" }
        require(defaultTtlSeconds > 0L) { "defaultTtlSeconds must be positive" }
        keyBytes = secret.toByteArray(Charsets.UTF_8)
    }

    fun sign(email: String, ttlSeconds: Long = defaultTtlSeconds): String {
        require(ttlSeconds > 0L) { "ttlSeconds must be positive" }
        val normalized = normalizeEmailAddress(email)
        val expSeconds = (clock() / 1_000L) + ttlSeconds
        val payload = mailJson.encodeToString(
            buildJsonObject {
                put("email", normalized)
                put("exp", expSeconds)
            },
        )
        val payloadB64 = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.toByteArray(Charsets.UTF_8))
        val sigB64 = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(hmac(payloadB64))
        return "$payloadB64.$sigB64"
    }

    /** Return the normalized email address when signature and expiry hold, or `null` otherwise. */
    fun verify(token: String): String? {
        val parts = token.trim().split('.')
        if (parts.size != 2 || parts[0].isEmpty() || parts[1].isEmpty()) return null
        val presentedSig = runCatching { Base64.getUrlDecoder().decode(parts[1]) }.getOrNull() ?: return null
        val expectedSig = hmac(parts[0])
        if (!MessageDigest.isEqual(expectedSig, presentedSig)) return null

        val payloadBytes = runCatching { Base64.getUrlDecoder().decode(parts[0]) }.getOrNull() ?: return null
        val obj: JsonObject = runCatching {
            mailJson.parseToJsonElement(String(payloadBytes, Charsets.UTF_8)).jsonObject
        }.getOrNull() ?: return null

        val email = (obj["email"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val exp = (obj["exp"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: return null
        if ((clock() / 1_000L) >= exp) return null
        return normalizeEmailAddress(email)
    }

    private fun hmac(payloadSegment: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(keyBytes, "HmacSHA256"))
        return mac.doFinal(payloadSegment.toByteArray(Charsets.US_ASCII))
    }

    companion object {
        const val DEFAULT_TTL_SECONDS: Long = 180L * 86_400L
    }
}

private val mailJson = Json { ignoreUnknownKeys = true }
