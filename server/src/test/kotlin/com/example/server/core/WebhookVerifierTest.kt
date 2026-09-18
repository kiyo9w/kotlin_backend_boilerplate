package com.example.server.core

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Webhook intake verifies before it trusts.
 *
 * The contract under test: a delivery is accepted only when its signature
 * proves against the raw bytes, an unconfigured endpoint refuses everything,
 * and the refusal is `null` rather than a decoded-but-unverified payload.
 */
class WebhookVerifierTest {

    private val secret = "test-webhook-secret"
    private val body = """{"event":"paid","id":"evt_1"}"""

    private fun sign(raw: String, secret: String = this.secret): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(raw.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun failClosedRefusesEverything() = runBlocking {
        assertNull(FailClosedWebhooks.verify(body, sign(body)), "the default must not trust a delivery")
        assertNull(FailClosedWebhooks.verify(body, null))
    }

    @Test
    fun aValidHexSignatureIsAccepted() = runBlocking {
        val verifier = HmacWebhookVerifier(provider = "example", secret = secret)

        val verified = assertNotNull(verifier.verify(body, sign(body)))

        assertEquals("example", verified.provider)
        assertEquals(body, verified.rawBody, "the raw bytes are carried, never a re-encoding")
        assertEquals(64, verified.eventId.length, "with no provider id, the body digest identifies the delivery")
    }

    @Test
    fun theSignatureEncodingIsNotAProductDecision() = runBlocking {
        val verifier = HmacWebhookVerifier(provider = "example", secret = secret)
        val raw = sign(body)
        val base64 = java.util.Base64.getEncoder().encodeToString(hexToBytes(raw))

        assertNotNull(verifier.verify(body, raw), "hex is accepted")
        assertNotNull(verifier.verify(body, "sha256=$raw"), "a sha256= prefix is accepted")
        assertNotNull(verifier.verify(body, base64), "base64 is accepted")
    }

    @Test
    fun aWrongOrMissingSignatureIsRefused() = runBlocking {
        val verifier = HmacWebhookVerifier(provider = "example", secret = secret)

        assertNull(verifier.verify(body, sign(body, secret = "some-other-secret")), "another secret is not proof")
        assertNull(verifier.verify(body, sign("""{"event":"other"}""")), "a signature for other bytes is not proof")
        assertNull(verifier.verify(body, null), "no signature is not proof")
        assertNull(verifier.verify(body, "not-a-signature"))
        assertNull(verifier.verify(body, ""))
    }

    @Test
    fun anUnconfiguredSecretRefusesRatherThanPasses() = runBlocking {
        val verifier = HmacWebhookVerifier(provider = "example", secret = "")
        // No secret means no verification, so no trust — whatever is presented.
        assertNull(verifier.verify(body, "a-signature"), "no secret means no verification, so no trust")
        assertNull(verifier.verify(body, null))
    }

    @Test
    fun aProviderEventIdIsPreferredOverTheBodyDigest() = runBlocking {
        val verifier = HmacWebhookVerifier(
            provider = "example",
            secret = secret,
            eventIdOf = { raw -> Regex(""""id":"([^"]+)"""").find(raw)?.groupValues?.get(1) },
        )

        val verified = assertNotNull(verifier.verify(body, sign(body)))

        assertEquals("evt_1", verified.eventId, "the provider's own id is the replay identity")
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
