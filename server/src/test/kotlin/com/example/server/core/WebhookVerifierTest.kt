package com.example.server.core

import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

/**
 * Webhook intake verifies before it trusts.
 *
 * Covers both single-header HMAC hooks ([HmacWebhookVerifier]) and Standard
 * Webhooks / Svix ([StandardWebhooksVerifier], used by Polar and Resend).
 */
class WebhookVerifierTest {

    private val secret = "test-webhook-secret"
    private val body = """{"event":"paid","id":"evt_1"}"""

    private val rawStdKey = "0123456789abcdef0123456789abcdef".toByteArray(Charsets.UTF_8)
    private val whsecSecret = "whsec_${Base64.getEncoder().encodeToString(rawStdKey)}"

    private fun signHmac(raw: String, secret: String = this.secret): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun signStandard(id: String, timestamp: Long, raw: String, key: ByteArray = rawStdKey): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        val digest = mac.doFinal("$id.$timestamp.$raw".toByteArray(Charsets.UTF_8))
        return "v1,${Base64.getEncoder().encodeToString(digest)}"
    }

    private fun headersOf(vararg pairs: Pair<String, String>): (String) -> String? {
        val map = pairs.associate { (k, v) -> k.lowercase() to v }
        return { name -> map[name.lowercase()] }
    }

    @Test
    fun failClosedRefusesEverything(): Unit = runBlocking {
        assertNull(
            FailClosedWebhooks.verify(body, headersOf("X-Signature-256" to signHmac(body))),
            "the default must not trust a delivery",
        )
        assertNull(FailClosedWebhooks.verify(body) { null })
    }

    @Test
    fun aValidHexSignatureIsAccepted(): Unit = runBlocking {
        val verifier = HmacWebhookVerifier(provider = "example", secret = secret)

        val verified = assertNotNull(verifier.verify(body, headersOf("X-Signature-256" to signHmac(body))))

        assertEquals("example", verified.provider)
        assertEquals(body, verified.rawBody, "the raw bytes are carried, never a re-encoding")
        assertEquals(64, verified.eventId.length, "with no provider id, the body digest identifies the delivery")
    }

    @Test
    fun theSignatureEncodingIsNotAProductDecision(): Unit = runBlocking {
        val verifier = HmacWebhookVerifier(provider = "example", secret = secret)
        val raw = signHmac(body)
        val base64 = Base64.getEncoder().encodeToString(hexToBytes(raw))

        assertNotNull(verifier.verify(body, headersOf("X-Signature-256" to raw)), "hex is accepted")
        assertNotNull(verifier.verify(body, headersOf("X-Signature-256" to "sha256=$raw")), "sha256= prefix is accepted")
        assertNotNull(verifier.verify(body, headersOf("X-Signature-256" to base64)), "base64 is accepted")
    }

    @Test
    fun aWrongOrMissingSignatureIsRefused(): Unit = runBlocking {
        val verifier = HmacWebhookVerifier(provider = "example", secret = secret)

        assertNull(verifier.verify(body, headersOf("X-Signature-256" to signHmac(body, secret = "other"))))
        assertNull(verifier.verify(body, headersOf("X-Signature-256" to signHmac("""{"event":"other"}"""))))
        assertNull(verifier.verify(body) { null })
        assertNull(verifier.verify(body, headersOf("X-Signature-256" to "not-a-signature")))
        assertNull(verifier.verify(body, headersOf("X-Signature-256" to "")))
    }

    @Test
    fun anUnconfiguredSecretRefusesRatherThanPasses(): Unit = runBlocking {
        val hmac = HmacWebhookVerifier(provider = "example", secret = "")
        val std = StandardWebhooksVerifier(provider = "polar", secret = "")
        val malformed = StandardWebhooksVerifier(provider = "polar", secret = "whsec_not*base64!")

        assertNull(hmac.verify(body, headersOf("X-Signature-256" to "a-signature")))
        assertNull(std.verify(body, headersOf("webhook-id" to "msg_1", "webhook-timestamp" to "1", "webhook-signature" to "v1,a")))
        assertNull(malformed.verify(body, headersOf("webhook-id" to "msg_1", "webhook-timestamp" to "1", "webhook-signature" to "v1,a")))
    }

    @Test
    fun aProviderEventIdIsPreferredOverTheBodyDigest(): Unit = runBlocking {
        val verifier = HmacWebhookVerifier(
            provider = "example",
            secret = secret,
            eventIdOf = { raw -> Regex(""""id":"([^"]+)"""").find(raw)?.groupValues?.get(1) },
        )

        val verified = assertNotNull(verifier.verify(body, headersOf("X-Signature-256" to signHmac(body))))

        assertEquals("evt_1", verified.eventId, "the provider's own id is the replay identity")
    }

    @Test
    fun standardWebhooksAcceptsPolarAndResendSvixHeadersAndRotatedKeys(): Unit = runBlocking {
        val nowSeconds = 1_760_000_000L
        val verifier = StandardWebhooksVerifier(
            provider = "polar",
            secret = whsecSecret,
            clock = { nowSeconds * 1_000L },
        )
        val retiredSig = signStandard("msg_polar_1", nowSeconds, body, key = "retired-key-bytes-32-chars-long!".toByteArray())
        val activeSig = signStandard("msg_polar_1", nowSeconds, body)

        val polarVerified = assertNotNull(
            verifier.verify(
                body,
                headersOf(
                    "webhook-id" to "msg_polar_1",
                    "webhook-timestamp" to nowSeconds.toString(),
                    "webhook-signature" to "$retiredSig $activeSig",
                ),
            ),
        )
        assertEquals("polar", polarVerified.provider)
        assertEquals("msg_polar_1", polarVerified.eventId)
        assertEquals(body, polarVerified.rawBody)

        val resendVerifier = StandardWebhooksVerifier(
            provider = "resend",
            secret = whsecSecret,
            clock = { nowSeconds * 1_000L },
        )
        val svixSig = signStandard("msg_svix_9", nowSeconds - 120, body)
        val svixVerified = assertNotNull(
            resendVerifier.verify(
                body,
                headersOf(
                    "svix-id" to "msg_svix_9",
                    "svix-timestamp" to (nowSeconds - 120).toString(),
                    "svix-signature" to svixSig,
                ),
            ),
        )
        assertEquals("msg_svix_9", svixVerified.eventId)
    }

    @Test
    fun standardWebhooksRefusesReplayOutsideWindowMixedHeaderFamiliesAndTampering(): Unit = runBlocking {
        val nowSeconds = 1_760_000_000L
        val verifier = StandardWebhooksVerifier(
            provider = "polar",
            secret = whsecSecret,
            toleranceSeconds = 300L,
            clock = { nowSeconds * 1_000L },
        )

        val staleTs = nowSeconds - 301
        assertNull(
            verifier.verify(
                body,
                headersOf(
                    "webhook-id" to "msg_stale",
                    "webhook-timestamp" to staleTs.toString(),
                    "webhook-signature" to signStandard("msg_stale", staleTs, body),
                ),
            ),
            "deliveries older than toleranceSeconds must be refused",
        )

        val futureTs = nowSeconds + 301
        assertNull(
            verifier.verify(
                body,
                headersOf(
                    "webhook-id" to "msg_future",
                    "webhook-timestamp" to futureTs.toString(),
                    "webhook-signature" to signStandard("msg_future", futureTs, body),
                ),
            ),
            "deliveries beyond the future tolerance window must be refused",
        )

        assertNull(
            verifier.verify(
                body,
                headersOf(
                    "webhook-id" to "msg_mixed",
                    "webhook-timestamp" to nowSeconds.toString(),
                    "svix-signature" to signStandard("msg_mixed", nowSeconds, body),
                ),
            ),
            "mixing webhook-* and svix-* headers must be refused",
        )

        assertNull(
            verifier.verify(
                """{"event":"paid","id":"evt_tampered"}""",
                headersOf(
                    "webhook-id" to "msg_1",
                    "webhook-timestamp" to nowSeconds.toString(),
                    "webhook-signature" to signStandard("msg_1", nowSeconds, body),
                ),
            ),
        )
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
