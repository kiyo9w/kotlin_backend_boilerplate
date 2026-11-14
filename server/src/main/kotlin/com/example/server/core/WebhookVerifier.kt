package com.example.server.core

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/**
 * A webhook that was verified, not merely received.
 *
 * [eventId] is the provider's own delivery identity where the headers or
 * payload carry one; a duplicate id is a replay and must not apply twice.
 */
data class VerifiedWebhook(
    val provider: String,
    val eventId: String,
    val rawBody: String,
)

/**
 * Webhook intake verifies before it trusts.
 *
 * The contract is deliberately one-way: `verify` returns a [VerifiedWebhook]
 * only when the signature is proven against the raw body and headers. `null`
 * means refused, and the route answers a refusal — it never decodes and applies
 * an unverified body. A verifier that cannot check (no secret configured) must
 * refuse, not pass: see [FailClosedWebhooks].
 *
 * Implementations receive the **raw** body, never a parsed object: a signature
 * covers the bytes the provider sent, and re-encoding a parsed body changes them.
 */
fun interface WebhookVerifier {
    suspend fun verify(rawBody: String, header: (String) -> String?): VerifiedWebhook?
}

/**
 * Refuses everything. The default wiring until a product configures a secret:
 * a webhook endpoint with no verifier must never apply an unverified payload.
 */
object FailClosedWebhooks : WebhookVerifier {
    override suspend fun verify(rawBody: String, header: (String) -> String?): VerifiedWebhook? = null
}

/**
 * Single-header HMAC-SHA256 over the raw body, compared in constant time.
 *
 * Covers providers with one shared-secret signature header (Meta's
 * `X-Hub-Signature-256`, GitHub's `X-Hub-Signature-256`, or custom internal
 * hooks). The signature is accepted as hex or base64, with an optional
 * `sha256=` prefix, because providers disagree about the encoding and none of
 * that disagreement is a product decision.
 */
class HmacWebhookVerifier(
    private val provider: String,
    private val secret: String,
    private val signatureHeader: String = DEFAULT_SIGNATURE_HEADER,
    /** How to read the provider's delivery id from the body; defaults to a digest of the body. */
    private val eventIdOf: (String) -> String? = { null },
) : WebhookVerifier {

    override suspend fun verify(rawBody: String, header: (String) -> String?): VerifiedWebhook? {
        if (secret.isBlank()) return null
        val presented = decode(lookupHeader(header, signatureHeader)) ?: return null
        val expected = hmacSha256(secret.toByteArray(Charsets.UTF_8), rawBody)
        if (!MessageDigest.isEqual(expected, presented)) return null
        val eventId = eventIdOf(rawBody)?.takeIf { it.isNotBlank() } ?: sha256Hex(rawBody)
        return VerifiedWebhook(provider = provider, eventId = eventId, rawBody = rawBody)
    }

    private fun decode(signature: String?): ByteArray? {
        val raw = signature?.trim()?.removePrefix("sha256=")?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { hexToBytes(raw) }.getOrNull()
            ?: runCatching { Base64.getDecoder().decode(raw) }.getOrNull()
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "odd-length hex" }
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    companion object {
        const val DEFAULT_SIGNATURE_HEADER = "X-Signature-256"
    }
}

/**
 * Standard Webhooks / Svix verifier (used by Polar and Resend).
 *
 * Verifies `HMAC-SHA256(base64Decode(secret), "$id.$timestamp.$rawBody")`
 * against space-delimited `v1,<base64>` signatures, enforces a timestamp replay
 * window around [clock], and requires all three headers (`id`, `timestamp`,
 * `signature`) to come from the same header family (`webhook-*` or `svix-*`).
 * Secrets may carry the standard `whsec_` prefix.
 */
class StandardWebhooksVerifier(
    private val provider: String,
    secret: String,
    private val toleranceSeconds: Long = DEFAULT_TOLERANCE_SECONDS,
    private val clock: () -> Long = System::currentTimeMillis,
) : WebhookVerifier {

    private val secretBytes: ByteArray? = decodeSecret(secret)

    override suspend fun verify(rawBody: String, header: (String) -> String?): VerifiedWebhook? {
        val key = secretBytes ?: return null
        if (toleranceSeconds < 0) return null
        val envelope = readEnvelope(header) ?: return null
        val timestampSeconds = envelope.timestamp.toLongOrNull() ?: return null
        if (envelope.timestamp != timestampSeconds.toString()) return null
        val nowSeconds = clock() / 1_000L
        if (abs(nowSeconds - timestampSeconds) > toleranceSeconds) return null

        val candidates = parseV1Signatures(envelope.signature)
        if (candidates.isEmpty()) return null
        val expected = hmacSha256(key, "${envelope.id}.${envelope.timestamp}.$rawBody")

        var matched = false
        for (candidate in candidates) {
            if (MessageDigest.isEqual(expected, candidate)) {
                matched = true
            }
        }
        if (!matched) return null
        return VerifiedWebhook(provider = provider, eventId = envelope.id, rawBody = rawBody)
    }

    private data class Envelope(val id: String, val timestamp: String, val signature: String)

    private fun readEnvelope(header: (String) -> String?): Envelope? {
        val stdId = lookupHeader(header, "webhook-id")
        val stdTs = lookupHeader(header, "webhook-timestamp")
        val stdSig = lookupHeader(header, "webhook-signature")
        val svixId = lookupHeader(header, "svix-id")
        val svixTs = lookupHeader(header, "svix-timestamp")
        val svixSig = lookupHeader(header, "svix-signature")

        val hasStd = stdId != null || stdTs != null || stdSig != null
        val hasSvix = svixId != null || svixTs != null || svixSig != null
        if (hasStd && hasSvix) return null

        val id = (if (hasStd) stdId else svixId)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val ts = (if (hasStd) stdTs else svixTs)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val sig = (if (hasStd) stdSig else svixSig)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return Envelope(id = id, timestamp = ts, signature = sig)
    }

    private fun parseV1Signatures(raw: String): List<ByteArray> =
        raw.split(WHITESPACE).mapNotNull { token ->
            if (!token.startsWith("v1,")) return@mapNotNull null
            val encoded = token.substring(3).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            runCatching { Base64.getDecoder().decode(encoded) }.getOrNull()?.takeIf { it.isNotEmpty() }
        }

    companion object {
        const val DEFAULT_TOLERANCE_SECONDS = 300L
        private val WHITESPACE = Regex("\\s+")

        private fun decodeSecret(raw: String): ByteArray? {
            val body = raw.trim().removePrefix("whsec_").takeIf { it.isNotEmpty() } ?: return null
            return runCatching { Base64.getDecoder().decode(body) }.getOrNull()?.takeIf { it.isNotEmpty() }
        }
    }
}

private fun lookupHeader(header: (String) -> String?, name: String): String? {
    header(name)?.let { return it }
    val lower = name.lowercase()
    if (lower != name) header(lower)?.let { return it }
    val title = lower.split('-').joinToString("-") { part ->
        part.replaceFirstChar { ch -> ch.titlecaseChar() }
    }
    if (title != name && title != lower) header(title)?.let { return it }
    val upper = name.uppercase()
    if (upper != name) header(upper)?.let { return it }
    return null
}

private fun hmacSha256(keyBytes: ByteArray, message: String): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(keyBytes, "HmacSHA256"))
    return mac.doFinal(message.toByteArray(Charsets.UTF_8))
}

private fun sha256Hex(body: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(body.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
