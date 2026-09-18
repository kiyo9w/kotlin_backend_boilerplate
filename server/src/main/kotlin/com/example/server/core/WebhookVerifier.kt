package com.example.server.core

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A webhook that was verified, not merely received.
 *
 * [eventId] is the provider's own delivery identity where the payload carries
 * one; a duplicate id is a replay and must not apply twice.
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
 * only when the signature is proven against the raw body. `null` means refused,
 * and the route answers a refusal — it never decodes and applies an unverified
 * body. A verifier that cannot check (no secret configured) must refuse, not
 * pass: see [FailClosedWebhooks].
 *
 * Implementations receive the **raw** body, never a parsed object: a signature
 * covers the bytes the provider sent, and re-encoding a parsed body changes them.
 */
fun interface WebhookVerifier {
    suspend fun verify(rawBody: String, signature: String?): VerifiedWebhook?
}

/**
 * Refuses everything. The default wiring until a product configures a secret:
 * a webhook endpoint with no verifier must never apply an unverified payload.
 */
object FailClosedWebhooks : WebhookVerifier {
    override suspend fun verify(rawBody: String, signature: String?): VerifiedWebhook? = null
}

/**
 * HMAC-SHA256 over the raw body, compared in constant time.
 *
 * This is the shape every provider with a shared secret uses (Meta's
 * `X-Hub-Signature-256`, GitHub's `X-Hub-Signature-256`, SePay's API key).
 * The signature is accepted as hex or base64, with an optional `sha256=`
 * prefix, because providers disagree about the encoding and none of that
 * disagreement is a product decision.
 */
class HmacWebhookVerifier(
    private val provider: String,
    private val secret: String,
    /** How to read the provider's delivery id from the body; defaults to a digest of the body. */
    private val eventIdOf: (String) -> String? = { null },
) : WebhookVerifier {

    override suspend fun verify(rawBody: String, signature: String?): VerifiedWebhook? {
        if (secret.isBlank()) return null
        val presented = decode(signature) ?: return null
        val expected = mac(rawBody)
        if (!MessageDigest.isEqual(expected, presented)) return null
        val eventId = eventIdOf(rawBody)?.takeIf { it.isNotBlank() } ?: digestHex(rawBody)
        return VerifiedWebhook(provider = provider, eventId = eventId, rawBody = rawBody)
    }

    private fun mac(body: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(body.toByteArray(Charsets.UTF_8))
    }

    private fun decode(signature: String?): ByteArray? {
        val raw = signature?.trim()?.removePrefix("sha256=")?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { hexToBytes(raw) }.getOrNull()
            ?: runCatching { java.util.Base64.getDecoder().decode(raw) }.getOrNull()
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "odd-length hex" }
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private fun digestHex(body: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(body.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
